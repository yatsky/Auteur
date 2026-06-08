package com.auteur.image;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.auteur.common.text.TextUtils;
import com.auteur.domain.StoryboardShot;
import com.auteur.llm.LlmCallSpec;
import com.auteur.llm.LlmClient;
import com.auteur.llm.LlmResult;
import com.auteur.llm.PromptTemplateService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 单镜重生前，把上一轮审图反馈融进 prompt 输出修订版。
 * 调用方 (ImageGenService.regenerateForShotAsync) 在拿到 reviewIssues 非空时调用一次。
 *
 * 失败语义：parse / LLM 任何异常都返回 null，调用方就 fall back 到原 prompt。
 * 不抛异常，重生主流程不该被一次提示词改写失败阻断。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShotPromptRefineService {

    private final LlmClient llmClient;
    private final PromptTemplateService promptService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 修订结果。任何字段为空字符串当作模型未返回，调用方按需保留原值。 */
    public record RefinedPrompt(String promptZh, String promptEn, String negativePrompt) {}

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RefineRaw {
        private String promptZh;
        private String promptEn;
        private String negativePrompt;
    }

    /**
     * @param shot 原 shot（取 promptZh/En/negativePrompt/styleTag/shotType 当输入）
     * @param reviewIssues 上一轮审图扣分点，已经是 "issue1 | issue2 | issue3" 形式
     * @return 修订版；任何失败返回 null
     */
    public RefinedPrompt refine(StoryboardShot shot, String reviewIssues) {
        if (shot == null || reviewIssues == null || reviewIssues.isBlank()) return null;
        try {
            PromptTemplateService.Rendered tpl = promptService.render("shot_prompt_refine", Map.of(
                    "prompt_zh", TextUtils.safe(shot.getPromptZh()),
                    "prompt_en", TextUtils.safe(shot.getPromptEn()),
                    "negative_prompt", TextUtils.safe(shot.getNegativePrompt()),
                    "style_tag", TextUtils.safe(shot.getStyleTag()),
                    "shot_type", TextUtils.safe(shot.getShotType()),
                    "review_issues", reviewIssues
            ));
            LlmCallSpec spec = LlmCallSpec.builder()
                    .operation("shot_prompt_refine")
                    .relatedType("SHOT")
                    .relatedId(shot.getId())
                    .model(tpl.model())
                    .temperature(tpl.temperature() != null ? tpl.temperature() : 0.3)
                    .build();
            LlmResult r = llmClient.chat(spec, tpl.system(), tpl.user());
            return parse(r.getContent());
        } catch (Exception e) {
            log.warn("[ShotPromptRefine] shotId={} refine failed: {}", shot.getId(), e.toString());
            return null;
        }
    }

    /**
     * 上游图像模型把 prompt 判定为含敏感内容时的自动改写路径。
     * 用 shot_prompt_desensitize 模板让 LLM 把可能的敏感词改成中性等价物，
     * 保留构图骨架。失败返回 null，调用方按原 prompt 状态向用户报告。
     *
     * @param shot 触发审查的 shot
     * @param auditMessage 上游 4xx 错误体或本地概要，喂给 LLM 提供敏感词线索
     */
    public RefinedPrompt desensitize(StoryboardShot shot, String auditMessage) {
        if (shot == null) return null;
        try {
            PromptTemplateService.Rendered tpl = promptService.render("shot_prompt_desensitize", Map.of(
                    "prompt_zh", TextUtils.safe(shot.getPromptZh()),
                    "prompt_en", TextUtils.safe(shot.getPromptEn()),
                    "negative_prompt", TextUtils.safe(shot.getNegativePrompt()),
                    "style_tag", TextUtils.safe(shot.getStyleTag()),
                    "shot_type", TextUtils.safe(shot.getShotType()),
                    "audit_message", TextUtils.safe(auditMessage)
            ));
            LlmCallSpec spec = LlmCallSpec.builder()
                    .operation("shot_prompt_desensitize")
                    .relatedType("SHOT")
                    .relatedId(shot.getId())
                    .model(tpl.model())
                    .temperature(tpl.temperature() != null ? tpl.temperature() : 0.2)
                    .build();
            LlmResult r = llmClient.chat(spec, tpl.system(), tpl.user());
            return parse(r.getContent());
        } catch (Exception e) {
            log.warn("[ShotPromptDesensitize] shotId={} desensitize failed: {}",
                    shot.getId(), e.toString());
            return null;
        }
    }

    private RefinedPrompt parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String json = TextUtils.stripCodeFence(raw).trim();
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start < 0 || end < 0 || end <= start) return null;
        json = json.substring(start, end + 1);
        try {
            RefineRaw rr = objectMapper.readValue(json, RefineRaw.class);
            return new RefinedPrompt(
                    blankToNull(rr.getPromptZh()),
                    blankToNull(rr.getPromptEn()),
                    blankToNull(rr.getNegativePrompt())
            );
        } catch (Exception e) {
            log.warn("[ShotPromptRefine] parse failed: {} | raw={}", e.toString(),
                    raw.length() > 200 ? raw.substring(0, 200) + "..." : raw);
            return null;
        }
    }

    private static String blankToNull(String s) { return (s == null || s.isBlank()) ? null : s; }
}
