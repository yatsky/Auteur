package com.auteur.pipeline;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.auteur.brainstorm.BrainstormRequest;
import com.auteur.brainstorm.BrainstormService;
import com.auteur.cover.CoverGenerationService;
import com.auteur.domain.PipelineRun;
import com.auteur.domain.PipelineRunRepository;
import com.auteur.domain.PipelineRunStatus;
import com.auteur.domain.PipelineStage;
import com.auteur.image.ImageAuditService;
import com.auteur.image.ImageGenService;
import com.auteur.script.FactCheckService;
import com.auteur.script.ScriptService;
import com.auteur.storyboard.StoryboardService;
import com.auteur.video.VideoAssemblyService;
import com.auteur.voice.VoiceGenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * 把 stage 派发到对应 service。rerun 和 resume 都从这里走。
 *
 * 设计动机：避免 PipelineRunController 直接耦合 6 个 service，所有"按 stage 选 service"的逻辑集中在这。
 * 派发参数：尽量用 run.topicId / scriptId 当主键；只有 brainstorm 需要从 params_json 反序列化原参数。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PipelineRunDispatcher {

    private final BrainstormService brainstormService;
    private final ScriptService scriptService;
    private final FactCheckService factCheckService;
    private final StoryboardService storyboardService;
    private final ImageGenService imageGenService;
    private final ImageAuditService imageAuditService;
    private final VoiceGenService voiceGenService;
    private final VideoAssemblyService videoAssemblyService;
    private final CoverGenerationService coverGenerationService;
    private final PipelineRunRepository runRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Rerun：按原 stage 重新触发。force 默认 true（用户既然主动 rerun 一般就想覆盖）。
     * 短任务（brainstorm/script/storyboard/factcheck）同步跑，长任务（imagegen/imageaudit）异步。
     *
     * 同步 stage 内部各自 start/markDone 一行新 run；rerun 完成后查最近一条同 (stage, scriptId/topicId)
     * 的 run id 返回给前端，让前端点进新 run 看结果，而不是回到 src 这条历史行。
     * brainstorm 没有 topicId 锚点，按 stage 全表最近一行兜底。
     */
    public Long rerun(PipelineRun src) {
        Map<String, Object> params = parseParams(src.getParamsJson());
        return switch (src.getStage()) {
            case BRAINSTORM -> {
                BrainstormRequest req = new BrainstormRequest();
                req.setN(((Number) params.getOrDefault("n", 5)).intValue());
                req.setArchiveHint((String) params.getOrDefault("archive_hint", ""));
                req.setDoneTopics((String) params.getOrDefault("done_topics", ""));
                // rerun 沿用原 preset
                Object pid = params.get("preset_id");
                if (pid instanceof String s && !s.isBlank()) {
                    try {
                        req.setPresetId(Long.valueOf(s));
                    } catch (NumberFormatException ignore) { /* 旧 run 没 preset_id 字段则跳过 */ }
                } else if (pid instanceof Number n) {
                    req.setPresetId(n.longValue());
                }
                brainstormService.brainstorm(req);
                yield runRepo.findFirstByStageOrderByIdDesc(PipelineStage.BRAINSTORM)
                        .map(PipelineRun::getId).orElse(src.getId());
            }
            case SCRIPT -> {
                scriptService.generate(src.getTopicId());
                yield runRepo.findFirstByTopicIdAndStageOrderByIdDesc(src.getTopicId(), PipelineStage.SCRIPT)
                        .map(PipelineRun::getId).orElse(src.getId());
            }
            case FACTCHECK -> {
                factCheckService.factCheck(src.getScriptId());
                yield runRepo.findFirstByScriptIdAndStageOrderByIdDesc(src.getScriptId(), PipelineStage.FACTCHECK)
                        .map(PipelineRun::getId).orElse(src.getId());
            }
            case STORYBOARD -> {
                storyboardService.generate(src.getScriptId(), true);
                yield runRepo.findFirstByScriptIdAndStageOrderByIdDesc(src.getScriptId(), PipelineStage.STORYBOARD)
                        .map(PipelineRun::getId).orElse(src.getId());
            }
            case IMAGEGEN -> {
                Integer limit = params.get("limit") instanceof Number n && n.intValue() > 0
                        ? n.intValue() : null;
                yield imageGenService.generateForScriptAsync(src.getScriptId(), true, limit, "RERUN");
            }
            case IMAGEAUDIT -> imageAuditService.auditScriptAsync(src.getScriptId(), "RERUN");
            case VOICE -> {
                VoiceGenService.GenParams p = new VoiceGenService.GenParams(
                        (String) params.get("voiceModel"),
                        (String) params.get("voiceLabel"),
                        params.get("speed") instanceof Number n ? BigDecimal.valueOf(n.doubleValue()) : null,
                        params.get("pitch") instanceof Number n ? n.intValue() : null,
                        (String) params.get("subtitleStyle"),
                        params.get("markFinal") == null ? Boolean.TRUE : (Boolean) params.get("markFinal"));
                yield voiceGenService.generateAsync(src.getScriptId(), p, "RERUN");
            }
            case VIDEO -> {
                Long voiceAssetId = params.get("voiceAssetId") instanceof Number n ? n.longValue() : null;
                Integer width = params.get("width") instanceof Number n ? n.intValue() : null;
                Integer height = params.get("height") instanceof Number n ? n.intValue() : null;
                VideoAssemblyService.RenderParams p = new VideoAssemblyService.RenderParams(
                        voiceAssetId, (String) params.get("format"), width, height,
                        params.get("markFinal") == null ? Boolean.TRUE : (Boolean) params.get("markFinal"));
                yield videoAssemblyService.renderAsync(src.getScriptId(), p, "RERUN");
            }
            case COVER -> {
                CoverGenerationService.GenerateParams p = new CoverGenerationService.GenerateParams(
                        (String) params.get("templateId"),
                        (String) params.get("titleText"),
                        (String) params.get("heroImageUrl"));
                yield coverGenerationService.generateAsync(src.getScriptId(), p, "RERUN");
            }
        };
    }

    /**
     * Resume：仅 IMAGEGEN/IMAGEAUDIT 支持（其他短 stage 一次性完成，没有"未完成的中间状态"）。
     * 调用方先确认 run.status==PAUSED，再走这里；这里不再校验 status。
     */
    public void resume(PipelineRun run, int startIndex) {
        if (run.getStage() == PipelineStage.IMAGEGEN) {
            Map<String, Object> params = parseParams(run.getParamsJson());
            boolean force = Boolean.TRUE.equals(params.get("force"));
            Integer limit = params.get("limit") instanceof Number n && n.intValue() > 0
                    ? n.intValue() : null;
            imageGenService.resumeAsync(run.getId(), run.getScriptId(), force, limit, startIndex);
        } else if (run.getStage() == PipelineStage.IMAGEAUDIT) {
            imageAuditService.resumeAsync(run.getId(), run.getScriptId(), startIndex);
        } else {
            throw new IllegalStateException("Stage " + run.getStage() + " does not support resume");
        }
    }

    public boolean isResumable(PipelineStage stage) {
        return stage == PipelineStage.IMAGEGEN
                || stage == PipelineStage.IMAGEAUDIT;
    }

    private Map<String, Object> parseParams(String json) {
        if (json == null || json.isBlank()) return new HashMap<>();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("[PipelineRunDispatcher] parse params_json failed: {}", e.toString());
            return new HashMap<>();
        }
    }

    public boolean isPausable(PipelineRunStatus status) {
        return status == PipelineRunStatus.RUNNING;
    }
}
