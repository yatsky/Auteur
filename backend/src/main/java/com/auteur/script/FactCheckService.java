package com.auteur.script;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.auteur.common.text.TextUtils;
import com.auteur.domain.FactCheckIssue;
import com.auteur.domain.FactCheckIssueRepository;
import com.auteur.domain.PipelineRun;
import com.auteur.domain.PipelineStage;
import com.auteur.domain.Script;
import com.auteur.domain.ScriptRepository;
import com.auteur.domain.Topic;
import com.auteur.domain.TopicRepository;
import com.auteur.llm.LlmCallSpec;
import com.auteur.llm.LlmClient;
import com.auteur.llm.LlmResult;
import com.auteur.llm.PromptTemplateService;
import com.auteur.pipeline.PipelineRunService;
import com.auteur.web.NotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class FactCheckService {

    private final LlmClient llmClient;
    private final PromptTemplateService promptService;
    private final ScriptRepository scriptRepository;
    private final TopicRepository topicRepository;
    private final FactCheckIssueRepository issueRepository;
    private final PipelineRunService runService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public FactCheckService(LlmClient llmClient,
                            PromptTemplateService promptService,
                            ScriptRepository scriptRepository,
                            TopicRepository topicRepository,
                            FactCheckIssueRepository issueRepository,
                            PipelineRunService runService) {
        this.llmClient = llmClient;
        this.promptService = promptService;
        this.scriptRepository = scriptRepository;
        this.topicRepository = topicRepository;
        this.issueRepository = issueRepository;
        this.runService = runService;
    }

    /**
     * Pass 1:claude-opus-4-7 通读脚本找疑点;
     * Pass 2:xai.grok-4 对每条疑点联网核证,补 source_url + credibility。
     */
    @Transactional
    public List<FactCheckIssue> factCheck(Long scriptId) {
        PipelineRun run = runService.start(
                PipelineStage.FACTCHECK, null, scriptId,
                Map.of("scriptId", scriptId, "mode", "sync"), "API");
        try {
            List<FactCheckIssue> result = doFactCheck(scriptId, run.getId());
            runService.markDone(run.getId(), result.size());
            return result;
        } catch (RuntimeException e) {
            runService.markFailed(run.getId(), e.toString());
            throw e;
        }
    }

    /**
     * 异步事实核查:立即返回 runId,worker 在 pipelineExecutor 跑。
     * 适合 grok 联网核证不收敛延迟的场景。
     */
    public Long factCheckAsync(Long scriptId, String triggeredBy) {
        Map<String, Object> params = Map.of("scriptId", scriptId, "mode", "async");
        return runService.runAsync(PipelineStage.FACTCHECK, null, scriptId, params, triggeredBy, "FactCheck",
                runId -> doFactCheck(scriptId, runId).size());
    }

    /** runId 用来回写进度 (lastCompletedIndex / totalItems)。 */
    private List<FactCheckIssue> doFactCheck(Long scriptId, Long runId) {
        long t0 = System.currentTimeMillis();
        Script script = scriptRepository.findById(scriptId)
                .orElseThrow(() -> new NotFoundException("Script not found: " + scriptId));
        Topic topic = topicRepository.findById(script.getTopicId())
                .orElseThrow(() -> new IllegalStateException("Topic not found for script " + scriptId));
        log.info("[FactCheck] start scriptId={} runId={} topicId={} fullTextChars={}",
                scriptId, runId, topic.getId(),
                script.getFullText() == null ? 0 : script.getFullText().length());

        // 重跑前先清旧的未处理 issue —— 同一段事实会被 LLM 重复标。已 resolved 的保留作历史。
        int wiped = issueRepository.deleteUnresolvedByScriptId(scriptId);
        if (wiped > 0) {
            log.info("[FactCheck] scriptId={} cleared {} stale unresolved issues before re-run",
                    scriptId, wiped);
        }

        // Pass 1:通读找疑点
        PromptTemplateService.Rendered p1 = promptService.render("factcheck", Map.of(
                "title", TextUtils.safe(topic.getTitle()),
                "dynasty", TextUtils.safe(topic.getDynasty()),
                "genre", TextUtils.safe(topic.getGenre()),
                "historical_reference", TextUtils.safe(topic.getHistoricalReference()),
                "full_text", TextUtils.safe(script.getFullText())
        ));
        LlmCallSpec p1Spec = LlmCallSpec.builder()
                .operation("factcheck")
                .relatedType("SCRIPT")
                .relatedId(scriptId)
                .model(p1.model())
                .temperature(p1.temperature() != null ? p1.temperature() : 0.2)
                .build();
        LlmResult p1Result = llmClient.chat(p1Spec, p1.system(), p1.user());
        log.info("[FactCheck P1] scriptId={} chars={} ms={}",
                scriptId,
                p1Result.getContent() == null ? 0 : p1Result.getContent().length(),
                p1Result.getDurationMs());

        List<FactCheckIssueDraft> drafts = parseIssueArray(p1Result.getContent());
        log.info("[FactCheck P1] scriptId={} drafted={}", scriptId, drafts.size());

        if (drafts.isEmpty()) {
            log.info("[FactCheck] scriptId={} no issues drafted, totalMs={}",
                    scriptId, System.currentTimeMillis() - t0);
            return List.of();
        }

        // Pass 1 完了,把 Pass 2 总条数告诉 run —— UI 能拿来画进度条
        if (runId != null) runService.updateProgress(runId, 0, drafts.size());

        // Pass 2:grok 联网逐条核证
        long t2 = System.currentTimeMillis();
        List<FactCheckIssue> persisted = new ArrayList<>(drafts.size());
        int idx = 0;
        for (FactCheckIssueDraft d : drafts) {
            idx++;
            long ti = System.currentTimeMillis();
            log.info("[FactCheck P2] scriptId={} idx={}/{} line={} severity={} claim='{}' verifying...",
                    scriptId, idx, drafts.size(),
                    d.getLineNumber(), d.getSeverity(), TextUtils.preview(d.getOriginalText()));

            FactCheckIssue row = new FactCheckIssue();
            row.setScriptId(scriptId);
            row.setLineNumber(parseLine(d.getLineNumber()));
            row.setOriginalText(d.getOriginalText());
            row.setIssueType(TextUtils.truncate(d.getIssueType(), 40));
            row.setSuggestion(d.getSuggestion());
            row.setSeverity(TextUtils.truncate(d.getSeverity(), 20));

            FactCheckVerifyResult verify = verifyOne(topic, d, scriptId);
            if (verify != null) {
                row.setSourceUrl(TextUtils.truncate(verify.getSourceUrl(), 500));
                row.setCredibility(TextUtils.truncate(verify.getCredibility(), 2));
                if (verify.getVerdict() != null && !verify.getVerdict().isBlank()) {
                    String existing = row.getSuggestion() == null ? "" : row.getSuggestion() + " | ";
                    row.setSuggestion(existing + "[Grok] " + verify.getVerdict());
                }
                log.info("[FactCheck P2] scriptId={} idx={}/{} done ms={} credibility={} sourceUrl={} verdict='{}'",
                        scriptId, idx, drafts.size(), System.currentTimeMillis() - ti,
                        verify.getCredibility(),
                        verify.getSourceUrl() == null ? "-" : TextUtils.truncate(verify.getSourceUrl(), 80),
                        TextUtils.preview(verify.getVerdict()));
            } else {
                log.warn("[FactCheck P2] scriptId={} idx={}/{} done ms={} verify=null (skipped enrichment)",
                        scriptId, idx, drafts.size(), System.currentTimeMillis() - ti);
            }
            persisted.add(saveIssueInNewTx(row));
            if (runId != null) runService.updateProgress(runId, idx, drafts.size());
        }
        log.info("[FactCheck] scriptId={} persisted issues={} p2Ms={} totalMs={}",
                scriptId, persisted.size(),
                System.currentTimeMillis() - t2,
                System.currentTimeMillis() - t0);
        return persisted;
    }

    /** 异步路径不在 @Transactional 边界里,但每条 issue 写库要走自己的事务 */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public FactCheckIssue saveIssueInNewTx(FactCheckIssue row) {
        return issueRepository.save(row);
    }

    private FactCheckVerifyResult verifyOne(Topic topic,
                                            FactCheckIssueDraft draft, Long scriptId) {
        try {
            PromptTemplateService.Rendered p2 = promptService.render("factcheck_verify", Map.of(
                    "title", TextUtils.safe(topic.getTitle()),
                    "dynasty", TextUtils.safe(topic.getDynasty()),
                    "claim", TextUtils.safe(draft.getOriginalText())
            ));
            LlmCallSpec spec = LlmCallSpec.builder()
                    .operation("factcheck_verify")
                    .relatedType("SCRIPT")
                    .relatedId(scriptId)
                    .model(p2.model())
                    .temperature(p2.temperature() != null ? p2.temperature() : 0.0)
                    .build();
            LlmResult r = llmClient.chat(spec, p2.system(), p2.user());
            return parseVerify(r.getContent());
        } catch (Exception e) {
            log.warn("[FactCheck P2] verify failed for claim='{}', skip enrichment: {}",
                    TextUtils.preview(draft.getOriginalText()), e.toString());
            return null;
        }
    }

    private List<FactCheckIssueDraft> parseIssueArray(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        String json = TextUtils.stripCodeFence(raw).trim();
        int start = json.indexOf('[');
        int end = json.lastIndexOf(']');
        if (start < 0 || end < 0 || end <= start) {
            log.warn("[FactCheck P1] not a JSON array: {}", TextUtils.preview(raw));
            return List.of();
        }
        json = json.substring(start, end + 1);
        try {
            return objectMapper.readValue(json, new TypeReference<List<FactCheckIssueDraft>>() {});
        } catch (Exception e) {
            log.warn("[FactCheck P1] parse failed: {} | raw={}", e.toString(), TextUtils.preview(raw));
            return List.of();
        }
    }

    private FactCheckVerifyResult parseVerify(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String json = TextUtils.stripCodeFence(raw).trim();
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start < 0 || end < 0 || end <= start) return null;
        json = json.substring(start, end + 1);
        try {
            return objectMapper.readValue(json, FactCheckVerifyResult.class);
        } catch (Exception e) {
            log.warn("[FactCheck P2] parse failed: {} | raw={}", e.toString(), TextUtils.preview(raw));
            return null;
        }
    }

    /** prompt 让 LLM 写 A/B/C/D/E,存库时按 A=1, B=2, ... E=5 转 int */
    private static Integer parseLine(String s) {
        if (s == null || s.isBlank()) return null;
        char c = Character.toUpperCase(s.trim().charAt(0));
        if (c >= 'A' && c <= 'E') return (int) (c - 'A' + 1);
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
