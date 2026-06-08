package com.auteur.voice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.auteur.common.FinalAssetMarker;
import com.auteur.domain.PipelineRun;
import com.auteur.domain.PipelineStage;
import com.auteur.domain.Script;
import com.auteur.domain.ScriptRepository;
import com.auteur.domain.ScriptSection;
import com.auteur.domain.ScriptSectionRepository;
import com.auteur.domain.Topic;
import com.auteur.domain.TopicRepository;
import com.auteur.domain.VoiceAsset;
import com.auteur.domain.VoiceAssetRepository;
import com.auteur.pipeline.PipelineRunService;
import com.auteur.web.NotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * 配音 / 字幕产物生成 worker。totalItems=1(整个 script 一次合成),只有 RUNNING / DONE / FAILED 三态。
 */
@Slf4j
@Service
public class VoiceGenService {

    private static final BigDecimal DEFAULT_SPEED = new BigDecimal("1.0");

    private final VoiceClient voiceClient;
    private final ScriptRepository scriptRepository;
    private final ScriptSectionRepository sectionRepository;
    private final VoiceAssetRepository voiceAssetRepository;
    private final PipelineRunService runService;
    private final Executor pipelineExecutor;
    private final TopicRepository topicRepository;
    private final VoiceCriticService voiceCriticService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final com.auteur.preset.TopicPresetResolver presetResolver;

    public VoiceGenService(VoiceClient voiceClient,
                           ScriptRepository scriptRepository,
                           ScriptSectionRepository sectionRepository,
                           VoiceAssetRepository voiceAssetRepository,
                           PipelineRunService runService,
                           @Qualifier("pipelineExecutor") Executor pipelineExecutor,
                           TopicRepository topicRepository,
                           VoiceCriticService voiceCriticService,
                           com.auteur.preset.TopicPresetResolver presetResolver) {
        this.voiceClient = voiceClient;
        this.scriptRepository = scriptRepository;
        this.sectionRepository = sectionRepository;
        this.voiceAssetRepository = voiceAssetRepository;
        this.runService = runService;
        this.pipelineExecutor = pipelineExecutor;
        this.topicRepository = topicRepository;
        this.voiceCriticService = voiceCriticService;
        this.presetResolver = presetResolver;
    }

    public record GenParams(String voiceModel, String voiceLabel,
                            BigDecimal speed, Integer pitch, String subtitleStyle, Boolean markFinal) {}

    /** 立即返回 runId,worker 在 pipelineExecutor 里跑;UI 通过 GET /api/runs/{runId} 轮询。 */
    public Long generateAsync(Long scriptId, GenParams p, String triggeredBy) {
        Map<String, Object> params = new HashMap<>();
        params.put("scriptId", scriptId);
        params.put("voiceModel", p.voiceModel());
        params.put("speed", p.speed());
        params.put("pitch", p.pitch());
        params.put("subtitleStyle", p.subtitleStyle());
        params.put("mode", "async");
        PipelineRun run = runService.start(PipelineStage.VOICE, null, scriptId, params,
                triggeredBy != null ? triggeredBy : "API");
        Long runId = run.getId();
        pipelineExecutor.execute(() -> runWorker(scriptId, p, runId));
        return runId;
    }

    private void runWorker(Long scriptId, GenParams p, Long runId) {
        try {
            runService.updateProgress(runId, 0, 1);
            Script script = scriptRepository.findById(scriptId)
                    .orElseThrow(() -> new NotFoundException("Script not found: " + scriptId));
            p = applyContentTypeDefaults(script, p);
            String fullText = resolveFullText(script);
            if (fullText == null || fullText.isBlank()) {
                runService.markFailed(runId, "script #" + scriptId + " 没有可合成文本(fullText 空且 sections 也空)");
                return;
            }
            String spokenText = sanitizeForTts(fullText);
            if (spokenText.isBlank()) {
                runService.markFailed(runId, "script #" + scriptId + " 清洗后无可念文本");
                return;
            }

            VoiceClient.Request req = new VoiceClient.Request(
                    scriptId, spokenText,
                    p.voiceModel(), p.voiceLabel(),
                    p.speed() != null ? p.speed() : BigDecimal.ONE,
                    p.pitch() != null ? p.pitch() : 0,
                    p.subtitleStyle() != null ? p.subtitleStyle() : "standard",
                    true);
            VoiceClient.Result r = voiceClient.synthesize(req);

            VoiceAsset asset = persistInNewTx(scriptId, p, r);
            try {
                Topic topic = topicRepository.findById(script.getTopicId()).orElse(null);
                voiceCriticService.auditAndLog(asset, script, topic);
            } catch (RuntimeException ex) {
                log.warn("[录音] critic 审核失败但不阻塞主流程 scriptId={}: {}", scriptId, ex.toString());
            }

            runService.markDone(runId, 1);
            log.info("[录音] runId={} scriptId={} done audio={}", runId, scriptId, r.audioUrl());
        } catch (RuntimeException e) {
            log.error("[录音] worker crashed runId={} scriptId={}: {}", runId, scriptId, e.toString(), e);
            runService.markFailed(runId, e.toString());
        }
    }

    /** 落库 + 选最终。markFinal=true 时清掉同 script 其它 voice_asset 的 is_final。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public VoiceAsset persistInNewTx(Long scriptId, GenParams p, VoiceClient.Result r) {
        VoiceAsset asset = new VoiceAsset();
        asset.setScriptId(scriptId);
        asset.setModel(r.model());
        asset.setVoiceLabel(p.voiceLabel());
        asset.setSpeed(p.speed() != null ? p.speed() : BigDecimal.ONE);
        asset.setPitch(p.pitch() != null ? p.pitch() : 0);
        asset.setSubtitleStyle(p.subtitleStyle() != null ? p.subtitleStyle() : "standard");
        asset.setAudioUrl(r.audioUrl());
        asset.setSubtitleUrl(r.subtitleUrl());
        asset.setDurationSeconds(r.durationSeconds());
        asset.setCostYuan(r.costYuan());
        boolean markFinal = Boolean.TRUE.equals(p.markFinal());
        asset.setIsFinal(markFinal);
        asset = voiceAssetRepository.save(asset);
        FinalAssetMarker.clearOthers(
                asset,
                voiceAssetRepository.findByScriptIdOrderByIdDesc(scriptId),
                VoiceAsset::getId,
                VoiceAsset::setIsFinal,
                voiceAssetRepository::save,
                markFinal);
        return asset;
    }

    /** Script.fullText 为空时,从 script_section 拼一份。 */
    private String resolveFullText(Script script) {
        if (script.getFullText() != null && !script.getFullText().isBlank()) {
            return script.getFullText();
        }
        List<ScriptSection> sections = sectionRepository.findByScriptIdOrderBySectionCodeAsc(script.getId());
        if (sections.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (ScriptSection s : sections) {
            if (s.getTextContent() != null && !s.getTextContent().isBlank()) {
                if (sb.length() > 0) sb.append("\n\n");
                sb.append(s.getTextContent());
            }
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    /**
     * 把 fullText 清洗成可念的纯口播文本,避免 TTS 把结构标签当字念出来。
     * 剥除:[A]/【A】 段落标签、行首圆圈数字 ①②、行首阿拉伯数字+点/顿号 "1. " "2、"。
     * 规则保守:只匹配行首方/中括号 + 短标识(≤8 字符,字母/数字/CJK)。
     */
    static String sanitizeForTts(String text) {
        if (text == null) return "";
        String stripped = text.replaceAll("(?m)^[ \\t]*[\\[【]\\s*[A-Za-z0-9一-龥①-⑳]{1,8}\\s*[\\]】][ \\t]*", "");
        stripped = stripped.replaceAll("(?m)^[ \\t]*[①-⑳][ \\t,。、:::,.\\-]*", "");
        stripped = stripped.replaceAll("(?m)^[ \\t]*\\d{1,2}[.、):、][ \\t]*", "");
        stripped = stripped.replaceAll("\\n{3,}", "\n\n");
        return stripped.trim();
    }

    /** 套用预设默认音色,仅在用户没显式传 voiceLabel 时生效。 */
    private GenParams applyContentTypeDefaults(Script script, GenParams p) {
        if (p.voiceLabel() != null && !p.voiceLabel().isBlank()) {
            return p;
        }
        com.auteur.preset.PresetContext ctx = presetResolver.forScriptId(script.getId());
        com.auteur.preset.VoiceConfig vc = ctx.voiceConfig();
        if (vc == null || vc.getVoiceId() == null || vc.getVoiceId().isBlank()) {
            throw new IllegalStateException(
                    "scriptId=" + script.getId() + " 所属 preset(" + ctx.preset().getName()
                            + ") 没有配置 voice_config.voice_id;无法生成配音。前端请显式选音色,或在预设里补默认。");
        }
        BigDecimal speed = p.speed() != null ? p.speed()
                : (vc.getSpeedRatio() != null ? BigDecimal.valueOf(vc.getSpeedRatio()) : DEFAULT_SPEED);
        log.info("[录音] scriptId={} preset={} 默认音色: {} speed={}",
                script.getId(), ctx.preset().getName(), vc.getVoiceId(), speed);
        return new GenParams(p.voiceModel(), vc.getVoiceId(), speed, p.pitch(), p.subtitleStyle(), p.markFinal());
    }
}
