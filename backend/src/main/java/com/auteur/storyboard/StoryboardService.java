package com.auteur.storyboard;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.auteur.common.text.JsonExtractUtils;
import com.auteur.common.text.TextUtils;
import com.auteur.domain.PipelineRun;
import com.auteur.domain.Script;
import com.auteur.domain.ScriptRepository;
import com.auteur.domain.ScriptSection;
import com.auteur.domain.ScriptSectionRepository;
import com.auteur.domain.PipelineStage;
import com.auteur.domain.StoryboardShot;
import com.auteur.domain.StoryboardShotRepository;
import com.auteur.domain.Topic;
import com.auteur.domain.TopicRepository;
import com.auteur.domain.VoiceAsset;
import com.auteur.domain.VoiceAssetRepository;
import com.auteur.llm.LlmCallSpec;
import com.auteur.llm.LlmClient;
import com.auteur.llm.LlmResult;
import com.auteur.llm.JsonHealer;
import com.auteur.llm.PromptTemplateService;
import com.auteur.pipeline.PipelineRunService;
import com.auteur.script.ScriptAlignmentService;
import com.auteur.web.NotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 摄影指导 / Cinematographer */
@Slf4j
@Service
public class StoryboardService {

    /** 全局风格常量,在 service 层注入,避免让 LLM 重复输出。 */
    private static final String STYLE_TAG_DEFAULT = "古风工笔白描+暗调影视感+冷色+电影级构图";
    private static final String NEGATIVE_PROMPT_DEFAULT =
            "modern clothing, modern makeup, modern face, instagram face, sharp jawline, plastic skin, contemporary beauty, repetitive pattern, symmetric scars, watermark, text, logo, signature, subtitle, blurry, deformed hands, extra fingers, ugly face, low quality";

    private final LlmClient llmClient;
    private final PromptTemplateService promptService;
    private final ScriptRepository scriptRepository;
    private final ScriptSectionRepository sectionRepository;
    private final StoryboardShotRepository shotRepository;
    private final TopicRepository topicRepository;
    private final VoiceAssetRepository voiceAssetRepository;
    private final PipelineRunService runService;
    private final ScriptAlignmentService alignmentService;
    private final StoryboardCriticService storyboardCriticService;
    private final com.auteur.video.DirectorNoteService directorNoteService;
    private final com.auteur.preset.TopicPresetResolver presetResolver;
    private final com.auteur.llm.ModelRegistry modelRegistry;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public StoryboardService(LlmClient llmClient,
                             PromptTemplateService promptService,
                             ScriptRepository scriptRepository,
                             ScriptSectionRepository sectionRepository,
                             StoryboardShotRepository shotRepository,
                             TopicRepository topicRepository,
                             VoiceAssetRepository voiceAssetRepository,
                             PipelineRunService runService,
                             ScriptAlignmentService alignmentService,
                             StoryboardCriticService storyboardCriticService,
                             com.auteur.video.DirectorNoteService directorNoteService,
                             com.auteur.preset.TopicPresetResolver presetResolver,
                             com.auteur.llm.ModelRegistry modelRegistry) {
        this.llmClient = llmClient;
        this.promptService = promptService;
        this.scriptRepository = scriptRepository;
        this.sectionRepository = sectionRepository;
        this.shotRepository = shotRepository;
        this.topicRepository = topicRepository;
        this.voiceAssetRepository = voiceAssetRepository;
        this.runService = runService;
        this.alignmentService = alignmentService;
        this.storyboardCriticService = storyboardCriticService;
        this.directorNoteService = directorNoteService;
        this.presetResolver = presetResolver;
        this.modelRegistry = modelRegistry;
    }

    @Transactional
    public List<StoryboardShot> generate(Long scriptId) {
        return generate(scriptId, false);
    }

    /**
     * @param force true=即使已有 shot 也重新生成（先清空旧 shot，避免 uk_shot_script_index 冲突）
     */
    @Transactional
    public List<StoryboardShot> generate(Long scriptId, boolean force) {
        Map<String, Object> p = Map.of("scriptId", scriptId, "force", force, "mode", "sync");
        PipelineRun run = runService.start(
                PipelineStage.STORYBOARD, null, scriptId, p, "API");
        try {
            List<StoryboardShot> result = doGenerate(scriptId, force);
            runService.markDone(run.getId(), result.size());
            return result;
        } catch (RuntimeException e) {
            runService.markFailed(run.getId(), e.toString());
            throw e;
        }
    }

    public Long generateAsync(Long scriptId, boolean force, String triggeredBy) {
        Map<String, Object> p = Map.of("scriptId", scriptId, "force", force, "mode", "async");
        return runService.runAsync(PipelineStage.STORYBOARD, null, scriptId, p, triggeredBy, "Storyboard",
                runId -> doGenerateInNewTx(scriptId, force).size());
    }

    /** 异步路径要自己开事务(force=true 的 deleteByScriptId 必须在事务里)。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<StoryboardShot> doGenerateInNewTx(Long scriptId, boolean force) {
        return doGenerate(scriptId, force);
    }

    private List<StoryboardShot> doGenerate(Long scriptId, boolean force) {
        long t0 = System.currentTimeMillis();
        Script script = scriptRepository.findById(scriptId)
                .orElseThrow(() -> new NotFoundException("Script not found: " + scriptId));
        Topic topic = topicRepository.findById(script.getTopicId())
                .orElseThrow(() -> new IllegalStateException("Topic not found for script " + scriptId));
        List<ScriptSection> sections = sectionRepository.findByScriptIdOrderBySectionCodeAsc(scriptId);
        if (sections.isEmpty()) {
            throw new IllegalStateException("Script has no sections, cannot storyboard");
        }

        // 配音字幕是可选的:
        //   有 SRT → 按 preset.storyboardMode 决定是否精准对齐(PRECISE_BY_CUE 时强制 anchor)
        //   没 SRT → LLM 仅依据脚本自行判断分镜,运行时降级为 FREE 语义
        VoiceAsset voice = voiceAssetRepository.findFirstByScriptIdAndIsFinalTrueOrderByIdDesc(scriptId)
                .orElseGet(() -> {
                    List<VoiceAsset> all = voiceAssetRepository.findByScriptIdOrderByIdDesc(scriptId);
                    return all.isEmpty() ? null : all.get(0);
                });
        boolean hasSubtitle = voice != null
                && voice.getSubtitleUrl() != null
                && !voice.getSubtitleUrl().isBlank();

        if (shotRepository.countByScriptId(scriptId) > 0) {
            if (!force) {
                return shotRepository.findByScriptIdOrderByShotIndexAsc(scriptId);
            }
            // force=true:清空旧 shot。FK image_asset.shot_id ON DELETE CASCADE,旧图也一起删。
            shotRepository.deleteByScriptId(scriptId);
            shotRepository.flush();
            log.info("[摄影] force=true, deleted existing shots for scriptId={}", scriptId);
        }

        // useCueAnchoring 同时受 preset.storyboardMode 与 SRT 可用性控制:
        //   PRECISE_BY_CUE + 有 SRT → 加载 cues + 强制 anchor 校验
        //   PRECISE_BY_CUE + 无 SRT → 运行时降级 FREE,LLM 自由分镜
        //   FREE                    → LLM 自由分镜
        com.auteur.preset.PresetContext ctx = presetResolver.forTopic(topic);
        boolean preciseByCue = "PRECISE_BY_CUE".equals(ctx.preset().getStoryboardMode());
        boolean useCueAnchoring = preciseByCue && hasSubtitle;
        if (preciseByCue && !hasSubtitle) {
            log.info("[摄影] scriptId={} preset=PRECISE_BY_CUE 但未生成配音字幕,降级 FREE 让 LLM 自行判断分镜", scriptId);
        }

        // cue 锚定模式把 voice 的 SRT cues 喂给 LLM,让 LLM 拆 shot 时显式标 anchor_cue_indices。
        java.util.List<com.auteur.video.SrtParser.Cue> srtCues = useCueAnchoring
                ? loadSrtCuesAny(voice.getSubtitleUrl())
                : java.util.Collections.emptyList();

        Map<String, Object> vars = new HashMap<>();
        vars.put("title", TextUtils.safe(topic.getTitle()));
        vars.put("dynasty", TextUtils.safe(topic.getDynasty()));
        vars.put("genre", TextUtils.safe(topic.getGenre()));
        vars.put("duration_seconds", script.getDurationSeconds() == null ? 360 : script.getDurationSeconds());
        vars.put("sections_block", buildSectionsBlock(sections));
        // 总导演笔记:摄影/分镜师必须服从 visualStyle / narrativeArc / keyMoments
        vars.put("director_vision_block",
                topic.getDirectorNote() != null && !topic.getDirectorNote().isBlank()
                        ? topic.getDirectorNote()
                        : (useCueAnchoring
                                ? "(本片未配置导演笔记;按默认 vibe:漫画灰调 / 黄昏暖光 / 冷静克制 / 留白多)"
                                : "(本片未配置导演笔记;按默认 vibe:暗调冷色 / 浅景深 / 电影感低 key)"));
        // 剧组群聊汇总
        vars.put("creator_addenda_block", directorNoteService.buildBlock(topic.getId()));
        com.auteur.preset.PresetInputInjector.inject(objectMapper, vars, topic.getPresetInputJson(), topic.getId(), "[摄影]");
        if (useCueAnchoring) {
            vars.put("cues_block", buildCuesBlock(srtCues));
            vars.put("cues_total", srtCues.size());
        }

        PromptTemplateService.Rendered tpl = promptService.renderInline(
                ctx.preset().getStoryboardPromptYaml(), vars);
        LlmCallSpec spec = LlmCallSpec.builder()
                .operation("storyboard")
                .relatedType("SCRIPT")
                .relatedId(scriptId)
                .model(modelRegistry.modelOrDefault(tpl.model(), "storyboard"))
                .temperature(tpl.temperature() != null ? tpl.temperature() : 0.6)
                .maxTokens(tpl.maxTokens())
                .build();

        List<StoryboardShotDraft> drafts = runLlmWithRetry(
                scriptId, topic, spec, tpl, srtCues, useCueAnchoring);

        java.util.List<StoryboardShot> persisted = persistShots(
                scriptId, script, sections, drafts, ctx, useCueAnchoring);
        log.info("[摄影] scriptId={} persisted shots={} totalMs={}",
                scriptId, persisted.size(), System.currentTimeMillis() - t0);

        return postProcessAndAlign(scriptId, topic, persisted, useCueAnchoring);
    }

    /** 失败不阻塞;SRT 对齐失败时返回 persisted 原值。 */
    private java.util.List<StoryboardShot> postProcessAndAlign(Long scriptId,
                                                               Topic topic,
                                                               java.util.List<StoryboardShot> persisted,
                                                               boolean useCueAnchoring) {
        // 摄影 @ 群:告诉后续 stage 实际定下的镜头分布
        if (useCueAnchoring && !persisted.isEmpty()) {
            try {
                directorNoteService.append("STORYBOARD", topic.getId(),
                        buildStoryboardAddendum(persisted));
            } catch (RuntimeException ex) {
                log.warn("[摄影] addendum 写入失败但不阻塞 scriptId={}: {}", scriptId, ex.toString());
            }
        }

        // 持久化完立刻用 SRT 把 LLM 估算的 timeRange/durationSeconds 校准到真实音频。
        try {
            ScriptAlignmentService.AlignmentResult ar = alignmentService.align(scriptId);
            log.info("[摄影] post-align scriptId={} mode={} totalSec={} sec={} shots={} skipped={}",
                    scriptId, ar.mode(), ar.totalSeconds(), ar.sectionsUpdated(),
                    ar.shotsUpdated(), ar.shotsSkipped());
            return shotRepository.findByScriptIdOrderByShotIndexAsc(scriptId);
        } catch (RuntimeException e) {
            // 对齐失败不抹掉 shot —— 留下原始 LLM 估算时长好过整段失败
            log.warn("[摄影] post-align failed scriptId={}: {}", scriptId, e.toString());
            return persisted;
        }
    }

    /**
     * 把 LLM drafts 落到 storyboard_shot 表:
     *  1. sectionCode 位置纠正(LLM 用 A/B/C 时映射回实际 ①②③)
     *  2. anchor_text 字面校验(仅 PRECISE_BY_CUE 模式)
     *  3. styleTag / negativePrompt 三级兜底(draft → preset → 默认)
     */
    private java.util.List<StoryboardShot> persistShots(Long scriptId,
                                                        Script script,
                                                        List<ScriptSection> sections,
                                                        List<StoryboardShotDraft> drafts,
                                                        com.auteur.preset.PresetContext ctx,
                                                        boolean useCueAnchoring) {
        // LLM 可能用 A/B/C 而不是实际 section code(①②③)。按首次出现顺序建位置映射,
        // 确保 shot.sectionCode 与 script_section 一致。
        java.util.List<String> actualCodes = sections.stream()
                .map(ScriptSection::getSectionCode).collect(java.util.stream.Collectors.toList());
        java.util.LinkedHashMap<String, String> codeRemap = new java.util.LinkedHashMap<>();
        int sectionIdx = 0;
        for (StoryboardShotDraft d : drafts) {
            String llmCode = d.getSectionCode();
            if (llmCode != null && !llmCode.isBlank() && !codeRemap.containsKey(llmCode)) {
                if (sectionIdx < actualCodes.size()) {
                    codeRemap.put(llmCode, actualCodes.get(sectionIdx++));
                }
            }
        }
        boolean needsRemap = codeRemap.entrySet().stream()
                .anyMatch(e -> !e.getKey().equals(e.getValue()));
        if (needsRemap) {
            log.warn("[摄影] sectionCode 格式不匹配，按位置纠正: {}", codeRemap);
        }

        java.util.List<StoryboardShot> persisted = new java.util.ArrayList<>(drafts.size());
        int idx = 1;
        // anchor 校验:preset.storyboardMode == PRECISE_BY_CUE 时校验 anchor_text 字面锚定;FREE 模式跳过。
        // fullText 来源:script.full_text 优先,空则从 sections 拼。
        String fullTextForAnchor = (script.getFullText() != null && !script.getFullText().isBlank())
                ? script.getFullText()
                : sections.stream()
                        .map(ScriptSection::getTextContent)
                        .filter(t -> t != null && !t.isBlank())
                        .reduce((a, b) -> a + "\n\n" + b)
                        .orElse("");
        java.util.Set<Integer> anchorMissed = useCueAnchoring
                ? validateAnchors(drafts, fullTextForAnchor)
                : java.util.Collections.emptySet();
        if (useCueAnchoring && !anchorMissed.isEmpty()) {
            log.warn("[摄影] anchor 校验:{} 镜的 anchor_text 在 script.full_text 中未找到子串(或顺序违反),标记 anchor_match=false: shotIndex={}",
                    anchorMissed.size(), anchorMissed);
        }
        for (StoryboardShotDraft d : drafts) {
            String rawCode = d.getSectionCode();
            String resolvedCode = (needsRemap && rawCode != null) ? codeRemap.getOrDefault(rawCode, rawCode) : rawCode;
            StoryboardShot s = new StoryboardShot();
            s.setScriptId(scriptId);
            s.setShotIndex(d.getShotIndex() != null ? d.getShotIndex() : idx);
            s.setSectionCode(TextUtils.truncate(resolvedCode, 8));
            s.setTimeRange(TextUtils.truncate(d.getTimeRange(), 20));
            s.setDurationSeconds(d.getDurationSeconds());
            s.setPromptZh(d.getPromptZh());
            s.setPromptEn(d.getPromptEn());
            // styleTag / negativePrompt 三级兜底:LLM 给 → preset.image_config_json 给 → 代码内置默认
            String presetNeg = ctx.imageConfig() != null ? ctx.imageConfig().getNegativePrompt() : null;
            String presetStyle = ctx.imageConfig() != null ? ctx.imageConfig().getStyleTag() : null;
            s.setNegativePrompt(d.getNegativePrompt() != null && !d.getNegativePrompt().isBlank()
                    ? TextUtils.truncate(d.getNegativePrompt(), 500)
                    : (presetNeg != null && !presetNeg.isBlank() ? TextUtils.truncate(presetNeg, 500) : NEGATIVE_PROMPT_DEFAULT));
            s.setStyleTag(d.getStyleTag() != null && !d.getStyleTag().isBlank()
                    ? TextUtils.truncate(d.getStyleTag(), 40)
                    : (presetStyle != null && !presetStyle.isBlank() ? TextUtils.truncate(presetStyle, 40) : STYLE_TAG_DEFAULT));
            s.setShotType(TextUtils.truncate(d.getShotType(), 20));
            // anchor_text 持久化 + match 标记。非锚定模式时 anchorText=null,anchorMatch 保持 entity 默认 true。
            if (useCueAnchoring) {
                s.setAnchorText(TextUtils.truncate(d.getAnchorText(), 120));
                s.setAnchorMatch(!anchorMissed.contains(d.getShotIndex() != null ? d.getShotIndex() : idx));
                // 持久化 anchor_cue_indices [start, end]。校验失败时 indices=null 也照写,
                // 下游 ShotTimingResolver 会回落 PRECISE_BY_SECTION。
                java.util.List<Integer> ind = d.getAnchorCueIndices();
                if (ind != null && ind.size() >= 2) {
                    s.setAnchorCueStart(ind.get(0));
                    s.setAnchorCueEnd(ind.get(1));
                } else if (ind != null && ind.size() == 1) {
                    s.setAnchorCueStart(ind.get(0));
                    s.setAnchorCueEnd(ind.get(0));
                }
            }
            persisted.add(shotRepository.save(s));
            idx++;
        }
        return persisted;
    }

    /**
     * cue 锚定校验 + 1 次重试。PRECISE_BY_CUE 模式下,所有 shot 区间合并必须严格升序连续覆盖 [1, srtCues.size()]。
     * 重试仍失败 → log warn 放行(下游 ShotTimingResolver 会回落到 PRECISE_BY_SECTION 或 UNIFORM_SCALE)。
     */
    private List<StoryboardShotDraft> runLlmWithRetry(Long scriptId,
                                                      Topic topic,
                                                      LlmCallSpec spec,
                                                      PromptTemplateService.Rendered tpl,
                                                      java.util.List<com.auteur.video.SrtParser.Cue> srtCues,
                                                      boolean useCueAnchoring) {
        int cuesTotal = srtCues.size();
        LlmResult result;
        List<StoryboardShotDraft> drafts = null;
        String userPrompt = tpl.user();
        int maxAttempts = 2;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            result = llmClient.chat(spec, tpl.system(), userPrompt);
            log.info("[摄影] scriptId={} attempt={} chars={} ms={}",
                    scriptId, attempt,
                    result.getContent() == null ? 0 : result.getContent().length(),
                    result.getDurationMs());
            drafts = parseDrafts(result.getContent());
            if (drafts.isEmpty()) {
                throw new IllegalStateException("Storyboard returned no shots");
            }
            // 只对 cue anchoring 模式强制校验 cue 覆盖
            if (!useCueAnchoring || cuesTotal == 0) break;

            CueCoverageCheck check = checkCueCoverage(drafts, cuesTotal);
            // 在 cue 通过后再做画面质量自审(shot_type 多样性 / 极特写 / 重复 prompt 等);
            // 不通过也走同一次重试,合并反馈,避免双倍 LLM 成本。
            StoryboardCriticResult quality;
            if (check.ok()) {
                quality = storyboardCriticService.audit(drafts, topic, scriptId);
            } else {
                // cue 还没通过时,占位用 PASS 结果,避免拿 quality 误判。
                quality = new StoryboardCriticResult();
                quality.setScore(100);
                quality.setDecision("PASS");
            }
            boolean qualityOk = "PASS".equals(quality.getDecision());

            if (check.ok() && qualityOk) {
                log.info("[摄影] cue 覆盖 + 画面 quality OK attempt={} cues={} drafts={}",
                        attempt, cuesTotal, drafts.size());
                break;
            }
            if (attempt < maxAttempts) {
                StringBuilder fb = new StringBuilder();
                if (!check.ok()) {
                    fb.append("【cue 覆盖问题】").append(check.reason()).append('\n')
                      .append("anchor_cue_indices 必须严格满足:第 1 个 shot 的 [0]=1,末 shot 的 [1]=")
                      .append(cuesTotal).append(",相邻区间连续不重叠不缺失,start ≤ end。\n");
                }
                if (!qualityOk) {
                    fb.append("【画面质量问题】\n").append(quality.getFeedbackForRewrite());
                }
                log.warn("[摄影] audit 失败 attempt={} cueOk={} qualityOk={} → 重试一次",
                        attempt, check.ok(), qualityOk);
                userPrompt = tpl.user() + String.format(
                        "%n%n【上次输出反馈 — 必须修正,保留可用部分,不要全盘重写】%n%s%n"
                        + "如担心 token 超限,可精简每镜 prompt 到 80-130 字,优先把所有 cue 都覆盖完整。%n"
                        + "现在重新输出完整 JSON 数组,共约 %d 个 shot。",
                        fb, cuesTotal);
            } else {
                log.warn("[摄影] 重试 {} 次后仍未通过 cueOk={} qualityOk={}: cue={} | quality={}。"
                        + "放行降级,下游 ShotTimingResolver 会回落到 PRECISE_BY_SECTION 或 UNIFORM_SCALE。",
                        maxAttempts, check.ok(), qualityOk,
                        check.reason(), quality.getIssues());
            }
        }
        return drafts;
    }

    private static String buildSectionsBlock(List<ScriptSection> sections) {
        StringBuilder sb = new StringBuilder();
        for (ScriptSection s : sections) {
            sb.append("[").append(s.getSectionCode()).append("] ")
                    .append(s.getStartSeconds() == null ? 0 : s.getStartSeconds()).append("-")
                    .append(s.getEndSeconds() == null ? 0 : s.getEndSeconds()).append("s | ")
                    .append(s.getTitle() == null ? "" : s.getTitle()).append("\n");
            sb.append(s.getTextContent() == null ? "" : s.getTextContent()).append("\n\n");
        }
        return sb.toString();
    }

    /** 摄影 @ 群的内容:镜头总数 + shot_type Top 3 分布 + 极特写计数。 */
    private static String buildStoryboardAddendum(List<com.auteur.domain.StoryboardShot> shots) {
        java.util.Map<String, Integer> typeCount = new java.util.HashMap<>();
        int extremeCloseup = 0;
        for (com.auteur.domain.StoryboardShot s : shots) {
            String t = s.getShotType() == null ? "" : s.getShotType().trim();
            if (!t.isEmpty()) typeCount.merge(t, 1, Integer::sum);
            if (t.contains("极特写") || t.contains("特写")) extremeCloseup++;
        }
        String topTypes = typeCount.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(3)
                .map(e -> e.getKey() + " ×" + e.getValue())
                .collect(java.util.stream.Collectors.joining(", "));
        return String.format(
                "实际镜头数=%d;镜头类型 Top3=[%s];极特写=%d 镜。"
              + "下游(副导演/质检)请按此画面节奏安排剪辑,极特写镜留作金句段定格。",
                shots.size(), topTypes, extremeCloseup);
    }

    /** 把 SRT cues 渲染成 LLM 可读的 cues_block。格式:#N [start-end] cue_text(N 从 1 起)。 */
    private static String buildCuesBlock(java.util.List<com.auteur.video.SrtParser.Cue> cues) {
        if (cues == null || cues.isEmpty()) return "  (无 SRT cues - 不应该发生,storyboard 前置依赖 voice)";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cues.size(); i++) {
            com.auteur.video.SrtParser.Cue c = cues.get(i);
            sb.append(String.format("  #%d [%.2fs-%.2fs] %s%n",
                    i + 1, c.startMs() / 1000.0, c.endMs() / 1000.0, c.text()));
        }
        return sb.toString();
    }

    /** 加载 SRT cues。HTTP(S) 下载临时文件 / legacy 本地路径。失败安全降级到空列表。 */
    private java.util.List<com.auteur.video.SrtParser.Cue> loadSrtCuesAny(String subtitleUrl) {
        if (subtitleUrl == null || subtitleUrl.isBlank()) return java.util.Collections.emptyList();
        if (subtitleUrl.startsWith("http://") || subtitleUrl.startsWith("https://")) {
            java.nio.file.Path tmp = null;
            try {
                tmp = java.nio.file.Files.createTempFile("storyboard-srt-", ".srt");
                try (java.io.InputStream in = java.net.URI.create(subtitleUrl).toURL().openStream()) {
                    java.nio.file.Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                return com.auteur.video.SrtParser.parseFile(tmp);
            } catch (Exception e) {
                log.warn("[摄影] SRT 下载/解析失败 url={}: {}", subtitleUrl, e.toString());
                return java.util.Collections.emptyList();
            } finally {
                if (tmp != null) {
                    try { java.nio.file.Files.deleteIfExists(tmp); } catch (java.io.IOException ignored) {}
                }
            }
        }
        log.warn("[摄影] 不识别的 subtitleUrl 格式: {}", subtitleUrl);
        return java.util.Collections.emptyList();
    }

    private List<StoryboardShotDraft> parseDrafts(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        String stripped = TextUtils.stripCodeFence(raw).trim();
        String json = JsonExtractUtils.extractJsonArray(stripped, "[摄影]");
        if (json == null) {
            log.error("[摄影] response is not a JSON array. FULL raw=\n{}", raw);
            throw new IllegalStateException("Storyboard response is not a JSON array: " + TextUtils.preview(raw));
        }
        try {
            String fixed = JsonHealer.fixUnescapedAsciiQuotes(json);
            return objectMapper.readValue(fixed, new TypeReference<List<StoryboardShotDraft>>() {});
        } catch (Exception e) {
            log.error("[摄影] parse failed: {}. FULL raw=\n{}", e.toString(), raw);
            throw new IllegalStateException("Failed to parse storyboard JSON: " + TextUtils.preview(raw), e);
        }
    }

    /**
     * 字面锚定校验:对每个 draft 的 anchor_text 跑两个检查:
     *  1. 子串校验:normalize(anchor_text) 必须出现在 normalize(fullText) 中
     *  2. 顺序校验:相邻 shot 的 anchor 在 fullText 里的位置必须非降序
     * anchor_text 太短(< 4 字)直接判 false。
     */
    private static java.util.Set<Integer> validateAnchors(List<StoryboardShotDraft> drafts, String fullText) {
        java.util.Set<Integer> missed = new java.util.LinkedHashSet<>();
        if (fullText == null || fullText.isBlank()) return missed;
        String normText = normalizeForAnchor(fullText);
        int prevPos = -1;
        int idx = 0;
        for (StoryboardShotDraft d : drafts) {
            idx++;
            int shotIndex = d.getShotIndex() != null ? d.getShotIndex() : idx;
            String anchor = d.getAnchorText();
            if (anchor == null || anchor.length() < 4) {
                missed.add(shotIndex);
                continue;
            }
            String normAnchor = normalizeForAnchor(anchor);
            if (normAnchor.isEmpty()) {
                missed.add(shotIndex);
                continue;
            }
            int pos = normText.indexOf(normAnchor);
            if (pos < 0) {
                missed.add(shotIndex);
                continue;
            }
            if (pos < prevPos) {
                // 顺序违反:本镜 anchor 在脚本里的位置比上一镜靠前
                missed.add(shotIndex);
            } else {
                prevPos = pos;
            }
        }
        return missed;
    }

    /** 字面锚定校验前的归一化:去空白 + 统一中英文标点。 */
    private static String normalizeForAnchor(String s) {
        if (s == null) return "";
        return s
                .replaceAll("\\s+", "")
                .replace(',', ',').replace('。', '.').replace('?', '?').replace('!', '!')
                .replace(';', ';').replace(':', ':').replace('、', ',')
                .replace('「', '"').replace('」', '"').replace('"', '"').replace('"', '"')
                .replace('《', '<').replace('》', '>')
                .replace('(', '(').replace(')', ')');
    }

    private record CueCoverageCheck(boolean ok, String reason) {}

    /** 校验所有 draft 的 anchor_cue_indices 合并起来严格升序连续覆盖 [1, cuesTotal]。 */
    private static CueCoverageCheck checkCueCoverage(List<StoryboardShotDraft> drafts, int cuesTotal) {
        if (drafts == null || drafts.isEmpty()) return new CueCoverageCheck(false, "drafts 空");
        if (cuesTotal <= 0) return new CueCoverageCheck(true, "no cues to cover");
        int expectedNextStart = 1;
        for (int i = 0; i < drafts.size(); i++) {
            StoryboardShotDraft d = drafts.get(i);
            java.util.List<Integer> ind = d.getAnchorCueIndices();
            if (ind == null || ind.isEmpty()) {
                return new CueCoverageCheck(false,
                        String.format("shot_index=%d 缺 anchor_cue_indices 字段", i + 1));
            }
            int start, end;
            if (ind.size() == 1) {
                start = ind.get(0); end = ind.get(0);
            } else {
                start = ind.get(0); end = ind.get(1);
            }
            if (start < 1 || end > cuesTotal) {
                return new CueCoverageCheck(false,
                        String.format("shot_index=%d anchor_cue_indices=[%d,%d] 越界(应在 [1,%d])", i + 1, start, end, cuesTotal));
            }
            if (start > end) {
                return new CueCoverageCheck(false,
                        String.format("shot_index=%d anchor_cue_indices=[%d,%d] 倒序", i + 1, start, end));
            }
            if (start != expectedNextStart) {
                return new CueCoverageCheck(false,
                        String.format("shot_index=%d anchor_cue_indices[0]=%d,但应该 %d(连续覆盖,不允许跳跃或重叠)",
                                i + 1, start, expectedNextStart));
            }
            expectedNextStart = end + 1;
        }
        if (expectedNextStart - 1 != cuesTotal) {
            return new CueCoverageCheck(false,
                    String.format("末 shot 的 anchor_cue_indices[1]=%d,但应该 %d(必须覆盖到最后一条 cue)",
                            expectedNextStart - 1, cuesTotal));
        }
        return new CueCoverageCheck(true, "OK");
    }
}
