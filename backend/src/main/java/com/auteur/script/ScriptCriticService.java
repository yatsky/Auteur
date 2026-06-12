package com.auteur.script;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.auteur.common.text.TextUtils;
import com.auteur.domain.CriticLog;
import com.auteur.domain.CriticLogRepository;
import com.auteur.domain.Topic;
import com.auteur.llm.LlmCallSpec;
import com.auteur.llm.LlmClient;
import com.auteur.llm.LlmResult;
import com.auteur.llm.ModelRegistry;
import com.auteur.llm.PromptTemplateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 自审编辑 / Critic
 *
 * 编剧出稿后的自审循环。LLM 给的分数低于 preset.script_critic_threshold 时触发 REWRITE。
 * preset.script_critic_prompt_yaml 为空 → 该预设不需要 critic,直接 PASS。
 * 失败放行降级:LLM 异常或解析失败时返回 PASS,只 WARN 日志。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScriptCriticService {

    /** 默认通过分数线;preset.script_critic_threshold 缺失时回落此值。 */
    private static final int DEFAULT_PASS_THRESHOLD = 80;

    private final LlmClient llmClient;
    private final PromptTemplateService promptService;
    private final ModelRegistry modelRegistry;
    private final CriticLogRepository criticLogRepository;
    private final com.auteur.preset.TopicPresetResolver presetResolver;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 自审入口。返回的 decision = PASS / REWRITE。
     * scriptId 仅用于 critic_log 落库追溯,可空。
     */
    public ScriptCriticResult audit(ScriptDraft draft, Topic topic, Long scriptId) {
        ScriptCriticResult result = doAudit(draft, topic);
        writeLog(result, topic, scriptId);
        return result;
    }

    /** 老调用保持兼容 — 不写 critic_log。 */
    public ScriptCriticResult audit(ScriptDraft draft, Topic topic) {
        return doAudit(draft, topic);
    }

    private ScriptCriticResult doAudit(ScriptDraft draft, Topic topic) {
        com.auteur.preset.PresetContext ctx = presetResolver.forTopic(topic);
        String criticYaml = ctx.preset().getScriptCriticPromptYaml();
        if (criticYaml == null || criticYaml.isBlank()) {
            return passWithScore(100);
        }
        return runLlmCritic(draft, topic, ctx);
    }

    private ScriptCriticResult runLlmCritic(ScriptDraft draft, Topic topic,
                                            com.auteur.preset.PresetContext ctx) {
        try {
            Map<String, Object> params = new HashMap<>();
            String inputJson = topic.getPresetInputJson();
            if (inputJson != null && !inputJson.isBlank()) {
                JsonNode root = objectMapper.readTree(inputJson);
                if (root.isObject()) {
                    root.fields().forEachRemaining(e -> {
                        JsonNode v = e.getValue();
                        params.put(e.getKey(), v == null || v.isNull() ? "" : v.asText());
                    });
                }
            }
            params.put("script_draft_json", objectMapper.writeValueAsString(draft));

            PromptTemplateService.Rendered tpl = promptService.renderInline(
                    ctx.preset().getScriptCriticPromptYaml(), params);
            String operation = "script_critic_" + ctx.preset().getName();
            LlmCallSpec spec = LlmCallSpec.builder()
                    .operation(operation)
                    .relatedType("SCRIPT")
                    .model(modelRegistry.modelOrDefault(tpl.model(), "script_critic"))
                    .temperature(tpl.temperature() != null ? tpl.temperature() : 0.0)
                    .build();

            LlmResult r = llmClient.chat(spec, tpl.system(), tpl.user());
            ScriptCriticResult result = parseResult(r.getContent());
            int threshold = ctx.preset().getScriptCriticThreshold() != null
                    ? ctx.preset().getScriptCriticThreshold()
                    : DEFAULT_PASS_THRESHOLD;
            if (result != null && result.getScore() != null && result.getScore() < threshold
                    && !"REWRITE".equals(result.getDecision())) {
                result.setDecision("REWRITE");
            }
            log.info("[ScriptCritic] topicId={} score={} decision={} threshold={} issues={}",
                    topic.getId(), result.getScore(), result.getDecision(), threshold,
                    result.getIssues() == null ? 0 : result.getIssues().size());
            return result;
        } catch (RuntimeException e) {
            log.warn("[ScriptCritic] LLM critic failed for topicId={}, fall through PASS: {}",
                    topic.getId(), e.toString());
            return passWithScore(100);
        } catch (Exception e) {
            log.warn("[ScriptCritic] LLM critic threw checked exception for topicId={}, fall through PASS: {}",
                    topic.getId(), e.toString());
            return passWithScore(100);
        }
    }

    private ScriptCriticResult parseResult(String raw) {
        if (raw == null || raw.isBlank()) {
            log.warn("[ScriptCritic] LLM returned empty content, fall through PASS");
            return passWithScore(100);
        }
        String stripped = TextUtils.stripCodeFence(raw).trim();
        int s = stripped.indexOf('{');
        int e = stripped.lastIndexOf('}');
        if (s < 0 || e <= s) {
            log.warn("[ScriptCritic] response is not a JSON object, fall through PASS: {}", TextUtils.preview(raw));
            return passWithScore(100);
        }
        try {
            ScriptCriticResult result = objectMapper.readValue(stripped.substring(s, e + 1),
                    ScriptCriticResult.class);
            if (result.getDecision() == null || result.getDecision().isBlank()) {
                if (result.getScore() == null) {
                    result.setDecision("PASS");
                } else {
                    result.setDecision(result.getScore() >= DEFAULT_PASS_THRESHOLD ? "PASS" : "REWRITE");
                }
            }
            return result;
        } catch (Exception ex) {
            log.warn("[ScriptCritic] parse failed: {} | {}", ex.toString(), TextUtils.preview(raw));
            return passWithScore(100);
        }
    }

    private static ScriptCriticResult passWithScore(int score) {
        ScriptCriticResult r = new ScriptCriticResult();
        r.setScore(score);
        r.setDecision("PASS");
        return r;
    }

    /** 落库 critic_log。失败 swallow + warn。 */
    private void writeLog(ScriptCriticResult result, Topic topic, Long scriptId) {
        try {
            com.auteur.preset.PresetContext ctx = presetResolver.forTopic(topic);
            CriticLog row = new CriticLog();
            row.setRole("SCRIPT");
            row.setTopicId(topic.getId());
            row.setScriptId(scriptId);
            row.setContentType(ctx.preset().getName());
            row.setScore(result.getScore() == null ? 0 : result.getScore());
            row.setDecision(result.getDecision() == null ? "PASS" : result.getDecision());
            if (result.getIssues() != null && !result.getIssues().isEmpty()) {
                row.setIssuesJson(objectMapper.writeValueAsString(result.getIssues()));
            }
            row.setFeedback(result.getFeedbackForRewrite());
            criticLogRepository.save(row);
        } catch (Exception e) {
            log.warn("[ScriptCritic] critic_log 写入失败 topicId={}: {}", topic.getId(), e.toString());
        }
    }
}
