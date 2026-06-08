package com.auteur.script;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.auteur.common.text.JsonExtractUtils;
import com.auteur.common.text.TextUtils;
import com.auteur.domain.Script;
import com.auteur.domain.ScriptRepository;
import com.auteur.domain.ScriptSection;
import com.auteur.domain.ScriptSectionRepository;
import com.auteur.domain.SeriesHook;
import com.auteur.domain.SeriesHookRepository;
import com.auteur.llm.JsonHealer;
import com.auteur.llm.LlmCallSpec;
import com.auteur.llm.LlmClient;
import com.auteur.llm.LlmResult;
import com.auteur.llm.PromptTemplateService;
import com.auteur.web.NotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 钩子抽取服务 —— 读 script E 段全文,LLM 判 STRONG/WEAK,落 series_hook.
 *
 * 两种入口:
 *  - {@link #extractAsync(Long)} 给 ScriptService 钩在 doGenerate 末尾用,失败只 log 不抛
 *  - {@link #extract(Long)} 给 POST /api/scripts/{id}/extract-hook 用,失败抛异常,返回新行
 *
 * Phase 1 不做去重 —— 反复跑会插多行,prompt 调试期反而方便对比.
 * Phase 2 走 fulfill 时再做"同 from_topic_id 多 hook 合并"逻辑.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HookExtractor {

    private static final String MODEL = "DeepSeek-V3.2";
    private static final Double TEMPERATURE = 0.2;

    private final LlmClient llmClient;
    private final PromptTemplateService promptService;
    private final ScriptRepository scriptRepo;
    private final ScriptSectionRepository sectionRepo;
    private final SeriesHookRepository hookRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 异步入口:任何异常吞掉只 log。主流程不能被钩子抽取拖死。 */
    public void extractAsync(Long scriptId) {
        try {
            extract(scriptId);
        } catch (Exception e) {
            log.warn("[Hook] async extract failed scriptId={}: {}", scriptId, e.toString());
        }
    }

    /** 测试触发用,失败抛异常。返回新落库的 SeriesHook。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SeriesHook extract(Long scriptId) {
        long t0 = System.currentTimeMillis();
        Script script = scriptRepo.findById(scriptId)
                .orElseThrow(() -> new NotFoundException("Script not found: " + scriptId));

        List<ScriptSection> sections = sectionRepo.findByScriptIdOrderBySectionCodeAsc(scriptId);
        if (sections.isEmpty()) {
            throw new IllegalStateException("Script " + scriptId + " has no sections");
        }
        ScriptSection eSection = sections.stream()
                .filter(x -> x.getSectionCode() != null
                        && x.getSectionCode().toUpperCase().startsWith("E"))
                .findFirst()
                .orElseGet(() -> sections.get(sections.size() - 1));

        String eText = eSection.getTextContent() == null ? "" : eSection.getTextContent().trim();
        if (eText.isEmpty()) {
            throw new IllegalStateException(
                    "Script " + scriptId + " E section text is empty (sectionCode="
                            + eSection.getSectionCode() + ")");
        }

        Map<String, Object> params = new HashMap<>();
        params.put("e_section_text", eText);
        PromptTemplateService.Rendered tpl = promptService.render("hook-extract", params);

        LlmCallSpec spec = LlmCallSpec.builder()
                .operation("hook-extract")
                .relatedType("SCRIPT")
                .relatedId(scriptId)
                .model(tpl.model() != null ? tpl.model() : MODEL)
                .temperature(tpl.temperature() != null ? tpl.temperature() : TEMPERATURE)
                .build();
        LlmResult result = llmClient.chat(spec, tpl.system(), tpl.user());

        Parsed parsed = parse(result.getContent());

        SeriesHook hook = new SeriesHook();
        hook.setFromTopicId(script.getTopicId());
        hook.setFromScriptId(scriptId);
        hook.setHookText(eText);
        hook.setNextEpisodeHint(TextUtils.trimToMax(parsed.hint, 200));
        hook.setStrength("STRONG".equalsIgnoreCase(parsed.strength) ? "STRONG" : "WEAK");
        hook.setSuggestedTitle(TextUtils.trimToMax(parsed.suggestedTitle, 255));
        hook.setSuggestedDynasty(TextUtils.trimToMax(parsed.suggestedDynasty, 40));
        hook = hookRepo.save(hook);

        log.info("[Hook] scriptId={} topicId={} strength={} hint='{}' title='{}' dynasty='{}' ms={}",
                scriptId, script.getTopicId(), hook.getStrength(),
                hook.getNextEpisodeHint(), hook.getSuggestedTitle(), hook.getSuggestedDynasty(),
                System.currentTimeMillis() - t0);
        return hook;
    }

    private record Parsed(String strength, String hint, String suggestedTitle, String suggestedDynasty) {}

    private Parsed parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("LLM returned empty content");
        }
        String stripped = TextUtils.stripCodeFence(raw).trim();
        String json = JsonExtractUtils.extractJsonObject(stripped);
        if (json == null) {
            log.warn("[Hook] response is not a JSON object. raw={}", TextUtils.preview(raw));
            throw new IllegalStateException("LLM response is not a JSON object: " + TextUtils.preview(raw));
        }
        String fixed = JsonHealer.fixUnescapedAsciiQuotes(json);
        try {
            JsonNode n = objectMapper.readTree(fixed);
            return new Parsed(
                    text(n, "strength"),
                    text(n, "hint"),
                    text(n, "suggestedTitle"),
                    text(n, "suggestedDynasty")
            );
        } catch (Exception e) {
            log.warn("[Hook] parse failed: {}. raw={}", e.toString(), TextUtils.preview(raw));
            throw new IllegalStateException("Failed to parse LLM JSON: " + TextUtils.preview(raw), e);
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText();
        return (s == null || s.isBlank() || "null".equalsIgnoreCase(s)) ? null : s.trim();
    }
}
