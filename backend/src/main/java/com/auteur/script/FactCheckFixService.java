package com.auteur.script;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.auteur.common.text.TextUtils;
import com.auteur.domain.FactCheckIssue;
import com.auteur.domain.FactCheckIssueRepository;
import com.auteur.domain.Script;
import com.auteur.domain.ScriptRepository;
import com.auteur.domain.ScriptSection;
import com.auteur.domain.ScriptSectionRepository;
import com.auteur.llm.LlmCallSpec;
import com.auteur.llm.LlmClient;
import com.auteur.llm.LlmResult;
import com.auteur.llm.ModelRegistry;
import com.auteur.llm.PromptTemplateService;
import com.auteur.web.NotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 事实核查的"采纳/忽略"闭环。
 *
 * 核心问题:LLM 写的 suggestion 是描述性散文,不是 ready-to-replace 的字符串。
 * 策略:再调一次便宜 LLM 把 suggestion 抽换成对 originalText 的精确替换,
 * 或 LLM 判定"原文成立、不用改" → 仅 dismiss。失败 → issue 不动。
 */
@Slf4j
@Service
public class FactCheckFixService {

    private final FactCheckIssueRepository issueRepository;
    private final ScriptRepository scriptRepository;
    private final ScriptSectionRepository sectionRepository;
    private final LlmClient llmClient;
    private final PromptTemplateService promptService;
    private final ModelRegistry modelRegistry;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public FactCheckFixService(FactCheckIssueRepository issueRepository,
                               ScriptRepository scriptRepository,
                               ScriptSectionRepository sectionRepository,
                               LlmClient llmClient,
                               PromptTemplateService promptService,
                               ModelRegistry modelRegistry) {
        this.issueRepository = issueRepository;
        this.scriptRepository = scriptRepository;
        this.sectionRepository = sectionRepository;
        this.llmClient = llmClient;
        this.promptService = promptService;
        this.modelRegistry = modelRegistry;
    }

    public record ApplyResult(boolean applied, String action, String rationale,
                              String before, String after, Long sectionId, String sectionCode) {}

    /** applied=true 真改了字 / applied=false 但 action=no_change 即 LLM 判定无需改。 */
    @Transactional
    public ApplyResult applyFix(Long issueId) {
        FactCheckIssue issue = issueRepository.findById(issueId)
                .orElseThrow(() -> new NotFoundException("FactCheckIssue not found: " + issueId));
        if (Boolean.TRUE.equals(issue.getResolved())) {
            throw new IllegalStateException("issue #" + issueId + " 已经被处理过了");
        }
        String originalText = issue.getOriginalText();
        if (originalText == null || originalText.isBlank()) {
            throw new IllegalStateException("issue #" + issueId + " 没有 originalText,无法定位");
        }

        Long scriptId = issue.getScriptId();
        Script script = scriptRepository.findById(scriptId)
                .orElseThrow(() -> new IllegalStateException("Script not found: " + scriptId));
        List<ScriptSection> sections = sectionRepository.findByScriptIdOrderBySectionCodeAsc(scriptId);

        ScriptSection target = findSectionContaining(sections, originalText);
        if (target == null) {
            throw new IllegalStateException(
                    "无法在脚本里定位原文(可能脚本已被改过) original=" + TextUtils.preview(originalText));
        }

        FixDecision decision = askLlm(originalText, target.getTextContent(),
                TextUtils.safe(issue.getSuggestion()), scriptId);
        log.info("[FactCheckFix] issue={} action={} rationale='{}'",
                issueId, decision.action, decision.rationale);

        if ("no_change".equalsIgnoreCase(decision.action)) {
            issue.setResolved(true);
            issueRepository.save(issue);
            return new ApplyResult(false, decision.action, decision.rationale,
                    originalText, originalText, target.getId(), target.getSectionCode());
        }

        if (!"replace".equalsIgnoreCase(decision.action)
                || decision.replacement == null || decision.replacement.isBlank()) {
            throw new IllegalStateException("LLM 决策异常: action=" + decision.action);
        }
        if (decision.replacement.equals(originalText)) {
            issue.setResolved(true);
            issueRepository.save(issue);
            return new ApplyResult(false, "no_change",
                    "LLM 给的替换串与原文相同,等同于不改", originalText, originalText,
                    target.getId(), target.getSectionCode());
        }

        String before = target.getTextContent();
        String after = before.replace(originalText, decision.replacement);
        if (after.equals(before)) {
            throw new IllegalStateException("string-replace 失败,原文未匹配");
        }
        target.setTextContent(after);
        sectionRepository.save(target);

        List<ScriptSection> reload = sectionRepository.findByScriptIdOrderBySectionCodeAsc(scriptId);
        script.setFullText(joinSectionFullText(reload));
        scriptRepository.save(script);

        issue.setResolved(true);
        issueRepository.save(issue);

        log.info("[FactCheckFix] issue={} script={} section={} applied: '{}' → '{}'",
                issueId, scriptId, target.getSectionCode(),
                TextUtils.preview(originalText), TextUtils.preview(decision.replacement));

        return new ApplyResult(true, "replace", decision.rationale,
                originalText, decision.replacement, target.getId(), target.getSectionCode());
    }

    @Transactional
    public FactCheckIssue dismiss(Long issueId) {
        FactCheckIssue issue = issueRepository.findById(issueId)
                .orElseThrow(() -> new NotFoundException("FactCheckIssue not found: " + issueId));
        issue.setResolved(true);
        return issueRepository.save(issue);
    }

    private FixDecision askLlm(String originalText, String sectionText,
                               String suggestion, Long scriptId) {
        PromptTemplateService.Rendered tpl = promptService.render("factcheck_apply", Map.of(
                "original_text", originalText,
                "section_text", sectionText == null ? "" : sectionText,
                "suggestion", suggestion
        ));
        LlmCallSpec spec = LlmCallSpec.builder()
                .operation("factcheck_apply")
                .relatedType("SCRIPT")
                .relatedId(scriptId)
                .model(modelRegistry.modelFor("factcheck_apply"))
                .temperature(tpl.temperature() != null ? tpl.temperature() : 0.0)
                .build();
        LlmResult r = llmClient.chat(spec, tpl.system(), tpl.user());
        String raw = r.getContent();
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("LLM 返回空");
        }
        String json = TextUtils.stripCodeFence(raw).trim();
        int s = json.indexOf('{');
        int e = json.lastIndexOf('}');
        if (s < 0 || e < 0 || e <= s) {
            throw new IllegalStateException("LLM 返回不是 JSON 对象: " + TextUtils.preview(raw));
        }
        try {
            return objectMapper.readValue(json.substring(s, e + 1), FixDecision.class);
        } catch (Exception ex) {
            throw new IllegalStateException("解析 LLM 决策失败: " + ex.getMessage()
                    + " raw=" + TextUtils.preview(raw));
        }
    }

    /** 原文已被改过 → 找不到 → 抛错让前端提示。 */
    private static ScriptSection findSectionContaining(List<ScriptSection> sections, String needle) {
        for (ScriptSection s : sections) {
            String t = s.getTextContent();
            if (t != null && t.contains(needle)) return s;
        }
        return null;
    }

    private static String joinSectionFullText(List<ScriptSection> sections) {
        StringBuilder sb = new StringBuilder();
        for (ScriptSection s : sections) {
            if (s.getTextContent() == null) continue;
            if (sb.length() > 0) sb.append("\n\n");
            sb.append("[").append(s.getSectionCode()).append("] ").append(s.getTextContent());
        }
        return sb.toString();
    }

    public static class FixDecision {
        public String action;
        public String replacement;
        public String rationale;
    }
}
