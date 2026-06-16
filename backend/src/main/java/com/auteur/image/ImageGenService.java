package com.auteur.image;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.auteur.domain.ImageAsset;
import com.auteur.domain.ImageAssetRepository;
import com.auteur.domain.PipelineRun;
import com.auteur.domain.PipelineStage;
import com.auteur.domain.Script;
import com.auteur.domain.ScriptRepository;
import com.auteur.domain.StoryboardShot;
import com.auteur.domain.StoryboardShotRepository;
import com.auteur.domain.Topic;
import com.auteur.llm.ImageClient;
import com.auteur.llm.LlmCallSpec;
import com.auteur.llm.ModelRegistry;
import com.auteur.llm.SensitiveContentException;
import com.auteur.pipeline.PipelineRunService;
import com.auteur.web.NotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 美术指导 / Art Director
 *
 * 审核失败走 ShotPromptRefineService 重写;sensitive 走自动脱敏。
 */
@Slf4j
@Service
public class ImageGenService {

    /** 两个模型都支持的 9:16 portrait 尺寸。 */
    private static final String IMAGE_SIZE = "1024x1792";
    /** 串行限速:RPM=20,3.5s ≈ 17 RPM,留余量。 */
    private static final long THROTTLE_MS = 3500L;

    /** gpt-image-2 全局风格后缀;negative 词融入正向描述(GPT image 不支持独立 negative)。 */
    private static final String STYLE_SUFFIX =
            "Photorealistic cinematic photography. "
            + "Authentic historical period-accurate costumes and architecture. "
            + "Natural ambient lighting, shallow depth of field with soft bokeh background. "
            + "Non-idealized facial features: visible skin texture, natural pores, "
            + "subtle asymmetry, realistic imperfections, no airbrushing. "
            + "Documentary-style candid composition, handheld camera feel. "
            + "No studio lighting, no CGI, no illustration, no anime, "
            + "no modern elements, no perfect symmetry, no beauty filter, no watermarks.";

    /** 不带人物的镜头不需要 reference,避免参考图把空镜也染上人脸。 */
    private static final String EMPTY_SHOT_TYPE = "空镜";

    public static final String REVIEW_DECISION_SENSITIVE_BLOCKED = "SENSITIVE_BLOCKED";
    public static final String AUTO_DESENSITIZED_TAG = "auto-desensitized";

    private static final ObjectMapper DIRECTOR_NOTE_MAPPER = new ObjectMapper();

    private final ImageClient imageClient;
    private final StoryboardShotRepository shotRepository;
    private final ImageAssetRepository assetRepository;
    private final ScriptRepository scriptRepository;
    private final PipelineRunService runService;
    private final Executor pipelineExecutor;
    private final Executor imageWorkExecutor;
    private final ShotPromptRefineService refineService;
    private final com.auteur.domain.TopicRepository topicRepository;
    private final com.auteur.preset.TopicPresetResolver presetResolver;
    private final ModelRegistry modelRegistry;

    public ImageGenService(ImageClient imageClient,
                           StoryboardShotRepository shotRepository,
                           ImageAssetRepository assetRepository,
                           ScriptRepository scriptRepository,
                           PipelineRunService runService,
                           @Qualifier("pipelineExecutor") Executor pipelineExecutor,
                           @Qualifier("imageWorkExecutor") Executor imageWorkExecutor,
                           ShotPromptRefineService refineService,
                           com.auteur.domain.TopicRepository topicRepository,
                           com.auteur.preset.TopicPresetResolver presetResolver,
                           ModelRegistry modelRegistry) {
        this.imageClient = imageClient;
        this.shotRepository = shotRepository;
        this.assetRepository = assetRepository;
        this.scriptRepository = scriptRepository;
        this.runService = runService;
        this.pipelineExecutor = pipelineExecutor;
        this.imageWorkExecutor = imageWorkExecutor;
        this.refineService = refineService;
        this.topicRepository = topicRepository;
        this.presetResolver = presetResolver;
        this.modelRegistry = modelRegistry;
    }

    public List<ImageAsset> generateForScript(Long scriptId, Integer limit) {
        PipelineRun run = runService.start(
                PipelineStage.IMAGEGEN, null, scriptId,
                Map.of("scriptId", scriptId, "limit", limit == null ? 0 : limit, "force", false, "mode", "sync"),
                "API");
        try {
            BatchResult r = processShots(scriptId, false, limit, 0, run.getId(), false);
            runService.markDone(run.getId(), r.created().size());
            return r.created();
        } catch (RuntimeException e) {
            runService.markFailed(run.getId(), e.toString());
            throw e;
        }
    }

    /**
     * @param force true=先删每个 shot 已有的 asset 再重新生图(destructive)
     * @param limit 仅前 N 镜,null 或 ≤0 不限
     */
    public Long generateForScriptAsync(Long scriptId, boolean force, Integer limit, String triggeredBy) {
        Map<String, Object> params = Map.of(
                "scriptId", scriptId,
                "force", force,
                "limit", limit == null ? 0 : limit,
                "mode", "async");
        PipelineRun run = runService.start(PipelineStage.IMAGEGEN, null, scriptId, params,
                triggeredBy != null ? triggeredBy : "API");
        Long runId = run.getId();
        pipelineExecutor.execute(() -> runWorker(scriptId, force, limit, 0, runId));
        return runId;
    }

    /** Resume:从 last_completed_index 继续。 */
    public void resumeAsync(Long runId, Long scriptId, boolean force, Integer limit, int startIndex) {
        pipelineExecutor.execute(() -> runWorker(scriptId, force, limit, startIndex, runId));
    }

    /**
     * 单镜重生:为某个 shot 强制重生一张图。若上一张 asset 有 reviewIssues,会用 ShotPromptRefineService
     * 调一次便宜模型把 issues 翻译成正向 prompt 增补,再用修订版 prompt 跑生图。
     */
    public Long regenerateForShotAsync(Long shotId, String triggeredBy) {
        StoryboardShot shot = shotRepository.findById(shotId)
                .orElseThrow(() -> new NotFoundException("Shot not found: " + shotId));
        Long scriptId = shot.getScriptId();
        Map<String, Object> params = Map.of(
                "scriptId", scriptId,
                "shotId", shotId,
                "shotIndex", shot.getShotIndex(),
                "mode", "async",
                "scope", "single-shot");
        PipelineRun run = runService.start(PipelineStage.IMAGEGEN, null, scriptId, params,
                triggeredBy != null ? triggeredBy : "API");
        Long runId = run.getId();
        pipelineExecutor.execute(() -> runRegenerateWorker(runId, shotId, shot));
        return runId;
    }

    private void runRegenerateWorker(Long runId, Long shotId, StoryboardShot shot) {
        try {
            runService.updateProgress(runId, 0, 1);

            RefineDecision decision = resolveRefinedShot(shot);
            StoryboardShot effectiveShot = decision.effectiveShot();
            log.info("[美术] runId={} shotId={} regen path={} issues={}",
                    runId, shotId, decision.path(),
                    decision.reviewIssues() == null ? "none"
                            : (decision.reviewIssues().length() > 120
                                    ? decision.reviewIssues().substring(0, 120) + "..."
                                    : decision.reviewIssues()));

            // 2. 删旧 asset
            if (assetRepository.countByShotId(shotId) > 0) {
                assetRepository.deleteByShotId(shotId);
                log.info("[美术] runId={} shotId={} deleted existing assets before regen", runId, shotId);
            }

            generateOne(effectiveShot);
            runService.markDone(runId, 1);
            log.info("[美术] runId={} shotId={} single-shot regen done (path={})",
                    runId, shotId, decision.path());
        } catch (RuntimeException e) {
            log.error("[美术] single-shot regen worker crashed runId={} shotId={}: {}",
                    runId, shotId, e.toString(), e);
            runService.markFailed(runId, e.toString());
        }
    }

    /**
     * SENSITIVE_BLOCKED placeholder 和 auto-desensitized 的 reviewIssues 是
     * sensitive 路径自己的 tag,不应该当审图扣分点喂给 refine。
     */
    private RefineDecision resolveRefinedShot(StoryboardShot shot) {
        ImageAsset prev = assetRepository.findFirstByShotIdOrderByIdDesc(shot.getId()).orElse(null);
        String reviewIssues = prev != null ? prev.getReviewIssues() : null;
        String prevDecision = prev != null ? prev.getReviewDecision() : null;
        boolean isSensitiveTag = REVIEW_DECISION_SENSITIVE_BLOCKED.equals(prevDecision)
                || AUTO_DESENSITIZED_TAG.equals(reviewIssues);
        StoryboardShot effectiveShot = shot;
        String path = "original";
        if (reviewIssues != null && !reviewIssues.isBlank() && !isSensitiveTag) {
            ShotPromptRefineService.RefinedPrompt refined = refineService.refine(shot, reviewIssues);
            if (refined != null) {
                effectiveShot = withOverriddenPrompt(shot, refined);
                path = "refined";
            } else {
                path = "refine-failed-fallback";
            }
        }
        return new RefineDecision(effectiveShot, path, reviewIssues);
    }

    private record RefineDecision(StoryboardShot effectiveShot, String path, String reviewIssues) {}

    /** 构造游离副本:下游生图用修订版 prompt,但 shot 表 ground truth 不被污染。 */
    private static StoryboardShot withOverriddenPrompt(StoryboardShot src,
                                                       ShotPromptRefineService.RefinedPrompt refined) {
        StoryboardShot copy = new StoryboardShot();
        copy.setId(src.getId());
        copy.setScriptId(src.getScriptId());
        copy.setShotIndex(src.getShotIndex());
        copy.setTimeRange(src.getTimeRange());
        copy.setDurationSeconds(src.getDurationSeconds());
        copy.setShotType(src.getShotType());
        copy.setStyleTag(src.getStyleTag());
        copy.setSeed(src.getSeed());
        copy.setCreatedAt(src.getCreatedAt());
        copy.setPromptZh(refined.promptZh() != null ? refined.promptZh() : src.getPromptZh());
        copy.setPromptEn(refined.promptEn() != null ? refined.promptEn() : src.getPromptEn());
        copy.setNegativePrompt(refined.negativePrompt() != null ? refined.negativePrompt() : src.getNegativePrompt());
        return copy;
    }

    /** 异步路径不在 @Transactional 边界,单张落库要走自己的事务。 */
    private void runWorker(Long scriptId, boolean force, Integer limit, int startIndex, Long runId) {
        try {
            BatchResult r = processShots(scriptId, force, limit, startIndex, runId, true);
            if (r.completedNormally()) {
                runService.markDone(runId, r.total());
            }
        } catch (RuntimeException e) {
            log.error("[美术] worker crashed runId={}: {}", runId, e.toString(), e);
            runService.markFailed(runId, e.toString());
        }
    }

    /**
     * checkPause=true(异步):每镜先看 pauseRequested/cancel,命中则 markPaused 后早退。
     * checkPause=false(同步):HTTP 单连接没法暂停,跑完为止。
     */
    private BatchResult processShots(Long scriptId, boolean force, Integer limit,
                                     int startIndex, Long runId, boolean checkPause) {
        List<StoryboardShot> shots = shotRepository.findByScriptIdOrderByShotIndexAsc(scriptId);
        if (shots.isEmpty()) {
            String msg = "No storyboard shots for scriptId=" + scriptId;
            if (checkPause) {
                runService.markFailed(runId, msg);
                return new BatchResult(List.of(), 0, false);
            }
            throw new IllegalStateException(msg);
        }
        int total = shots.size();
        int budget = (limit == null || limit <= 0) ? Integer.MAX_VALUE : limit;
        runService.updateProgress(runId, startIndex, total);

        List<ImageAsset> created = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger completedCount = new AtomicInteger(startIndex);
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        boolean firstSubmit = true;
        int submitted = 0;

        for (int i = startIndex; i < total; i++) {
            if (checkPause && runService.shouldPause(runId)) {
                handlePauseOrCancel(runId, i, futures);
                return new BatchResult(created, total, false);
            }
            if (submitted >= budget) break;

            StoryboardShot shot = shots.get(i);
            if (shouldSkipShot(shot, force, completedCount, total, runId)) {
                continue;
            }

            // 主循环限速:保证提交速率 ≤ RPM=20(每 3.5s 一个),各镜并发执行
            if (!firstSubmit) {
                try {
                    Thread.sleep(THROTTLE_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    awaitAll(futures);
                    if (checkPause) runService.markFailed(runId, "interrupted at index=" + i);
                    return new BatchResult(created, total, false);
                }
            }
            firstSubmit = false;
            submitted++;

            futures.add(submitGenerateOne(shot, runId, created, completedCount, total));
        }

        awaitAll(futures);
        return new BatchResult(created, total, true);
    }

    private void handlePauseOrCancel(Long runId, int i, List<CompletableFuture<Void>> futures) {
        // 等正在跑的镜头收尾再 pause/cancel
        awaitAll(futures);
        if (runService.isCancelled(runId)) {
            log.info("[美术] runId={} cancelled at index={}", runId, i);
        } else {
            runService.markPaused(runId, i);
            log.info("[美术] runId={} paused at index={}", runId, i);
        }
    }

    /** force=true 路径:有旧 asset 则先删,再返回 false 让外层继续生图。 */
    private boolean shouldSkipShot(StoryboardShot shot, boolean force,
                                   AtomicInteger completedCount, int total, Long runId) {
        long shotId = shot.getId();
        if (force) {
            if (assetRepository.countByShotId(shotId) > 0) {
                assetRepository.deleteByShotId(shotId);
            }
            return false;
        }
        if (assetRepository.countByShotId(shotId) > 0) {
            completedCount.incrementAndGet();
            runService.updateProgress(runId, completedCount.get(), total);
            return true;
        }
        return false;
    }

    private CompletableFuture<Void> submitGenerateOne(StoryboardShot shot, Long runId,
                                                      List<ImageAsset> created,
                                                      AtomicInteger completedCount, int total) {
        return CompletableFuture.runAsync(() -> {
            try {
                ImageAsset asset = generateOne(shot);
                if (asset != null) created.add(asset);
            } catch (Exception e) {
                log.warn("[美术] runId={} shot {} failed: {}", runId, shot.getId(), e.toString());
            }
            runService.updateProgress(runId, completedCount.incrementAndGet(), total);
        }, imageWorkExecutor);
    }

    private void awaitAll(List<CompletableFuture<Void>> futures) {
        if (!futures.isEmpty()) {
            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            } catch (Exception e) {
                log.warn("[美术] awaitAll 异常: {}", e.toString());
            }
        }
    }

    private record BatchResult(List<ImageAsset> created, int total, boolean completedNormally) {}

    /** 为单个镜头生图(不审)。 */
    public ImageAsset generateOne(StoryboardShot shot) {
        String fullPrompt = buildPrompt(shot);
        com.auteur.preset.ImageConfig cfg = effectiveImageConfig(shot);
        String primaryModel = pickPrimaryModel(cfg);
        return generateWithFallback(shot, fullPrompt, primaryModel, true);
    }

    private ImageAsset generateWithFallback(StoryboardShot shot, String prompt,
                                             String model, boolean allowFallback) {
        // cfg.imageSize 决定尺寸;cfg.referenceImagePath 决定是否传 ref(都为空 → 走 default 兜底)
        com.auteur.preset.ImageConfig cfg = effectiveImageConfig(shot);
        String size = pickSize(cfg);
        String refDataUrl = pickRefDataUrl(cfg, model);
        LlmCallSpec spec = LlmCallSpec.builder()
                .operation("image_gen")
                .relatedType("SHOT")
                .relatedId(shot.getId())
                .scriptId(shot.getScriptId())
                .model(model)
                .build();
        try {
            ImageClient.Result r = imageClient.generate(spec, prompt, size, refDataUrl);
            return persistAsset(shot, r, null);
        } catch (SensitiveContentException sensitive) {
            return handleSensitive(shot, spec, sensitive);
        } catch (RuntimeException e) {
            String fallback = modelRegistry.modelFor("image_fallback");
            if (allowFallback && !fallback.equals(model)) {
                log.warn("[美术] shotId={} {} failed ({}), falling back to {}",
                        shot.getId(), model, e.getClass().getSimpleName(), fallback);
                return generateWithFallback(shot, prompt, fallback, false);
            }
            throw e;
        }
    }

    /**
     * 决定本次调用传给 image-to-image 的 reference。
     * fallback model(qwen 系列)是 text-to-image 模型,不传 ref。
     */
    private String pickRefDataUrl(com.auteur.preset.ImageConfig cfg, String model) {
        if (modelRegistry.modelFor("image_fallback").equals(model)) return null;
        if (cfg == null || cfg.getReferenceImagePath() == null || cfg.getReferenceImagePath().isBlank()) {
            return null;
        }
        return resolveReferenceImage(cfg.getReferenceImagePath());
    }

    private static String resolveReferenceImage(String path) {
        if (path.startsWith("http://") || path.startsWith("https://")) {
            return path;
        }
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path));
            String base64 = java.util.Base64.getEncoder().encodeToString(bytes);
            String mime = path.toLowerCase().endsWith(".png") ? "image/png" : "image/jpeg";
            return "data:" + mime + ";base64," + base64;
        } catch (Exception e) {
            log.warn("[美术] 加载参考图失败 path={}: {}", path, e.toString());
            return null;
        }
    }

    private String pickPrimaryModel(com.auteur.preset.ImageConfig cfg) {
        return modelRegistry.modelOrDefault(cfg == null ? null : cfg.getModel(), "image_primary");
    }

    private String pickSize(com.auteur.preset.ImageConfig cfg) {
        if (cfg != null && cfg.getImageSize() != null && !cfg.getImageSize().isBlank()) {
            return cfg.getImageSize();
        }
        return IMAGE_SIZE;
    }

    /**
     * 第一次生图被上游内容审查拦截后的兜底路径：
     *  1. 调便宜 LLM 把 prompt 中性化（desensitize）
     *  2. 用脱敏 prompt 再请求一次
     *  3. 二次成功：把脱敏 prompt 写回 storyboard_shot（下次重生不再被拦），ImageAsset.reviewIssues 标 auto-desensitized
     *  4. 任一步失败：写一个 SENSITIVE_BLOCKED placeholder asset，前端识别后引导用户手动改 prompt
     *
     * 不抛异常：worker 进度需要正常推进，UI 通过 placeholder 看到状态。
     */
    private ImageAsset handleSensitive(StoryboardShot shot, LlmCallSpec spec, SensitiveContentException origin) {
        String auditMsg = origin.getCause() != null ? origin.getCause().getMessage() : origin.getMessage();
        log.warn("[美术] shotId={} blocked by content audit, attempting auto-desensitize. msg={}",
                shot.getId(), truncate(auditMsg, 200));

        ShotPromptRefineService.RefinedPrompt refined = refineService.desensitize(shot, auditMsg);
        if (refined == null) {
            log.warn("[美术] shotId={} desensitize failed, persisting SENSITIVE_BLOCKED placeholder",
                    shot.getId());
            return persistBlockedPlaceholder(shot, spec.getModel(),
                    "auto-desensitize failed: " + truncate(auditMsg, 240));
        }

        StoryboardShot effective = withOverriddenPrompt(shot, refined);
        // 用 effective ImageConfig 决定 size + ref(preset 命中时自带 cfg 字段)
        com.auteur.preset.ImageConfig cfg = effectiveImageConfig(shot);
        String size = pickSize(cfg);
        String refDataUrl = pickRefDataUrl(cfg, spec.getModel());
        try {
            ImageClient.Result r = imageClient.generate(spec, buildPrompt(effective), size, refDataUrl);
            applyDesensitizedToShot(shot.getId(), refined);
            log.info("[美术] shotId={} auto-desensitized and regenerated successfully",
                    shot.getId());
            return persistAsset(effective, r, AUTO_DESENSITIZED_TAG);
        } catch (SensitiveContentException second) {
            String msg = second.getCause() != null ? second.getCause().getMessage() : second.getMessage();
            log.warn("[美术] shotId={} second attempt still sensitive after desensitize: {}",
                    shot.getId(), truncate(msg, 200));
            return persistBlockedPlaceholder(shot, spec.getModel(),
                    "still sensitive after auto-desensitize: " + truncate(msg, 240));
        }
    }

    /** desensitize 成功后写回 storyboard_shot，让下次重生 / UI 都拿到中性化版本。 */
    private void applyDesensitizedToShot(Long shotId, ShotPromptRefineService.RefinedPrompt refined) {
        if (shotId == null || refined == null) return;
        shotRepository.findById(shotId).ifPresent(persisted -> {
            if (refined.promptZh() != null) persisted.setPromptZh(refined.promptZh());
            if (refined.promptEn() != null) persisted.setPromptEn(refined.promptEn());
            if (refined.negativePrompt() != null) persisted.setNegativePrompt(refined.negativePrompt());
            shotRepository.save(persisted);
        });
    }

    private ImageAsset persistAsset(StoryboardShot shot, ImageClient.Result r, String reviewIssuesTag) {
        ImageAsset asset = new ImageAsset();
        asset.setShotId(shot.getId());
        asset.setModel(r.model());
        asset.setFileUrl(r.url());
        int[] wh = parseSize(r.size());
        asset.setWidth(wh[0]);
        asset.setHeight(wh[1]);
        asset.setIsFinal(false);
        asset.setUsedProtagonistRef(false);
        if (reviewIssuesTag != null) asset.setReviewIssues(reviewIssuesTag);
        return assetRepository.save(asset);
    }

    /**
     * 内容审查拦截 + 自动脱敏失败时的占位:fileUrl 留 null(前端识别后显示拦截卡片),
     * reviewDecision = SENSITIVE_BLOCKED 让 UI 区分于普通"未生图"状态。
     */
    private ImageAsset persistBlockedPlaceholder(StoryboardShot shot, String model, String reason) {
        ImageAsset asset = new ImageAsset();
        asset.setShotId(shot.getId());
        asset.setModel(model != null ? model : modelRegistry.modelFor("image_primary"));
        asset.setFileUrl(null);
        asset.setIsFinal(false);
        asset.setUsedProtagonistRef(false);
        asset.setReviewDecision(REVIEW_DECISION_SENSITIVE_BLOCKED);
        asset.setReviewIssues(reason);
        return assetRepository.save(asset);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    private String buildPrompt(StoryboardShot shot) {
        DirectorVisual vs = resolveDirectorVisual(shot);

        // gpt-image-2 / Doubao 都吃英文;优先 promptEn,无英文时回退 promptZh
        String base = (shot.getPromptEn() != null && !shot.getPromptEn().isBlank())
                ? shot.getPromptEn() : (shot.getPromptZh() != null ? shot.getPromptZh() : "");

        // identity-lock(锁主角)+ 风格后缀,从 effective ImageConfig 读
        //  - cfg.identityLockText 不空 → prepend
        //  - cfg.styleSuffix 不空 → append
        com.auteur.preset.ImageConfig cfg = effectiveImageConfig(shot);
        StringBuilder sb = new StringBuilder();
        if (cfg.getIdentityLockText() != null && !cfg.getIdentityLockText().isBlank()) {
            sb.append(cfg.getIdentityLockText()).append(" Scene: ").append(base);
        } else {
            sb.append(base);
        }
        if (shot.getStyleTag() != null && !shot.getStyleTag().isBlank()) {
            sb.append(". Style reference: ").append(shot.getStyleTag());
        }
        if (vs != null && !vs.positive.isEmpty()) {
            sb.append(". Visual direction: ").append(vs.positive);
        }
        if (vs != null && !vs.avoidWords.isEmpty()) {
            sb.append(". Avoid: ").append(String.join(", ", vs.avoidWords)).append(".");
        }
        if (cfg.getStyleSuffix() != null && !cfg.getStyleSuffix().isBlank()) {
            sb.append(" ").append(cfg.getStyleSuffix());
        }
        return sb.toString();
    }

    /** 从 shot → script → topic 反查导演笔记 visualStyle。 */
    private DirectorVisual resolveDirectorVisual(StoryboardShot shot) {
        if (shot.getScriptId() == null) return null;
        Script s = scriptRepository.findById(shot.getScriptId()).orElse(null);
        if (s == null || s.getTopicId() == null) return null;
        Topic t = topicRepository.findById(s.getTopicId()).orElse(null);
        if (t == null || t.getDirectorNote() == null || t.getDirectorNote().isBlank()) return null;
        try {
            JsonNode root = DIRECTOR_NOTE_MAPPER.readTree(t.getDirectorNote());
            JsonNode visual = root.path("visualStyle");
            if (visual.isMissingNode()) return null;
            StringBuilder pos = new StringBuilder();
            appendIfPresent(pos, visual, "palette");
            appendIfPresent(pos, visual, "lighting");
            appendIfPresent(pos, visual, "depthOfField");
            List<String> avoid = new ArrayList<>();
            JsonNode aw = visual.path("avoidWords");
            if (aw.isArray()) {
                for (JsonNode n : aw) {
                    String w = n.asText("").trim();
                    if (!w.isEmpty()) avoid.add(w);
                }
            }
            if (pos.length() == 0 && avoid.isEmpty()) return null;
            return new DirectorVisual(pos.toString(), avoid);
        } catch (Exception e) {
            log.warn("[美术] director_note JSON 解析失败,跳过 visualStyle 注入: {}", e.toString());
            return null;
        }
    }

    private static void appendIfPresent(StringBuilder sb, JsonNode parent, String field) {
        String v = parent.path(field).asText("").trim();
        if (v.isEmpty()) return;
        if (sb.length() > 0) sb.append(", ");
        sb.append(v);
    }

    /** preset.image_config 字段可能为 null(代表该预设没配置图像层)。 */
    private com.auteur.preset.ImageConfig effectiveImageConfig(StoryboardShot shot) {
        Topic topic = resolveTopic(shot);
        if (topic == null) return new com.auteur.preset.ImageConfig();
        com.auteur.preset.PresetContext ctx = presetResolver.forTopic(topic);
        return ctx.imageConfig() != null ? ctx.imageConfig() : new com.auteur.preset.ImageConfig();
    }

    private Topic resolveTopic(StoryboardShot shot) {
        if (shot == null || shot.getScriptId() == null) return null;
        Script s = scriptRepository.findById(shot.getScriptId()).orElse(null);
        if (s == null || s.getTopicId() == null) return null;
        return topicRepository.findById(s.getTopicId()).orElse(null);
    }

    private record DirectorVisual(String positive, List<String> avoidWords) {}

    private static int[] parseSize(String size) {
        try {
            String[] parts = size.split("x");
            return new int[] { Integer.parseInt(parts[0]), Integer.parseInt(parts[1]) };
        } catch (Exception e) {
            return new int[] { 1024, 1792 };
        }
    }
}
