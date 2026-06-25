package com.auteur.brainstorm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.auteur.common.text.TextUtils;
import com.auteur.domain.PipelineStage;
import com.auteur.domain.PublishedVideoRepository;
import com.auteur.domain.Topic;
import com.auteur.domain.TopicRepository;
import com.auteur.domain.TopicStatus;
import com.auteur.hotpool.HotFetchService;
import com.auteur.hotpool.HotItem;
import com.auteur.hotpool.HotItemQueryService;
import com.auteur.hotpool.HotItemRepository;
import com.auteur.insights.InsightDtos.BrainstormDataPack;
import com.auteur.insights.InsightService;
import com.auteur.llm.LlmCallSpec;
import com.auteur.llm.LlmClient;
import com.auteur.llm.LlmResult;
import com.auteur.llm.ModelRegistry;
import com.auteur.llm.PromptTemplateService;
import com.auteur.pipeline.PipelineRunService;
import com.auteur.video.DirectorNoteOptimizeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class BrainstormService {

    private final LlmClient llmClient;
    private final PromptTemplateService promptService;
    private final ModelRegistry modelRegistry;
    private final TopicRepository topicRepository;
    private final SeriesResolver seriesResolver;
    private final PipelineRunService runService;
    private final InsightService insightService;
    private final com.auteur.preset.PresetService presetService;
    private final DirectorNoteOptimizeService directorNoteOptimizeService;
    private final HotItemRepository hotItemRepository;
    private final HotItemQueryService hotItemQueryService;
    private final HotFetchService hotFetchService;
    private final PublishedVideoRepository publishedVideoRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Transactional
    public List<Topic> brainstorm(BrainstormRequest req) {
        com.auteur.domain.PipelineRun run = runService.start(
                PipelineStage.BRAINSTORM, null, null,
                Map.of("n", req.getN(),
                        "archive_hint", TextUtils.safe(req.getArchiveHint()),
                        "done_topics", TextUtils.safe(req.getDoneTopics()),
                        "preset_id", req.getPresetId() == null ? "" : req.getPresetId().toString()),
                "API");
        try {
            List<Topic> result = doBrainstorm(req);
            runService.markDone(run.getId(), result.size());
            return result;
        } catch (RuntimeException e) {
            runService.markFailed(run.getId(), e.toString());
            throw e;
        }
    }

    private List<Topic> doBrainstorm(BrainstormRequest req) {
        if (req.getPresetId() == null) {
            throw new IllegalStateException("brainstorm 必须传 presetId");
        }
        com.auteur.preset.Preset preset = presetService.get(req.getPresetId());
        if (preset.getBrainstormPromptYaml() == null || preset.getBrainstormPromptYaml().isBlank()) {
            throw new IllegalStateException(
                    "preset id=" + preset.getId() + " (" + preset.getName() + ") 未配置 brainstorm_prompt,不支持头脑风暴");
        }

        Map<String, Object> vars = new HashMap<>();
        vars.put("n", req.getN());
        vars.put("archive_hint", TextUtils.safe(req.getArchiveHint()));
        vars.put("done_topics", buildDoneTopics(preset.getId(), req.getDoneTopics()));

        boolean dataDriven = Boolean.TRUE.equals(req.getUseDataDriven());
        if (dataDriven) {
            int days = req.getWindowDays() == null ? 30 : Math.max(1, req.getWindowDays());
            try {
                BrainstormDataPack pack = insightService.buildBrainstormPack(req.getPlatform(), days, preset.getName());
                vars.put("weight_table", pack.weightTable());
                vars.put("top_features", pack.topFeatures());
                vars.put("bottom_features", pack.bottomFeatures());
                vars.put("prev_week_plan", pack.prevWeekPlan());
                log.info("[Brainstorm] data-driven on platform={} preset={} days={} weightChars={} topChars={} bottomChars={} prevPlanChars={}",
                        req.getPlatform(), preset.getName(), days,
                        pack.weightTable().length(), pack.topFeatures().length(), pack.bottomFeatures().length(),
                        pack.prevWeekPlan() == null ? 0 : pack.prevWeekPlan().length());
            } catch (RuntimeException e) {
                log.warn("[Brainstorm] data-driven prep failed, fall back to default: {}", e.toString());
                fillDataDrivenDefaults(vars);
            }
        } else {
            fillDataDrivenDefaults(vars);
        }

        // 热点池接入:withHotFetch=true 时按 preset 配置先抓一批,然后与显式 hotItemIds 合并
        vars.put("hot_items_context", buildHotItemsContext(preset, req));

        PromptTemplateService.Rendered tpl = promptService.renderInline(preset.getBrainstormPromptYaml(), vars);
        String operation = "brainstorm_" + preset.getName();
        // 模型优先级:req.model(用户单次覆盖) > preset 内 yaml.model > 全局默认(app_config)
        String model = req.getModel() != null && !req.getModel().isBlank()
                ? req.getModel()
                : modelRegistry.modelOrDefault(tpl.model(), "brainstorm");
        Double temperature = tpl.temperature() != null ? tpl.temperature() : 0.85;

        LlmCallSpec spec = LlmCallSpec.builder()
                .operation(operation)
                .relatedType("TOPIC")
                .model(model)
                .temperature(temperature)
                .build();
        LlmResult result = llmClient.chat(spec, tpl.system(), tpl.user());
        String raw = result.getContent();
        log.info("[Brainstorm] LLM raw chars={} inTok={} outTok={} ms={}",
                raw == null ? 0 : raw.length(),
                result.getInputTokens(), result.getOutputTokens(), result.getDurationMs());

        List<BrainstormCandidate> candidates = parseCandidates(raw);
        log.info("[Brainstorm] parsed candidates={}", candidates.size());

        List<Topic> persisted = new ArrayList<>(candidates.size());
        for (BrainstormCandidate c : candidates) {
            Topic t = new Topic();
            String trimmedTitle = TextUtils.truncate(c.getTitle(), 200);
            t.setTitle(trimmedTitle);
            t.setDynasty(TextUtils.truncate(c.getDynasty(), 40));
            t.setGenre(TextUtils.truncate(c.getGenre(), 40));
            t.setProtagonist(TextUtils.truncate(c.getProtagonist(), 120));
            t.setHookType(TextUtils.truncate(c.getHookType(), 40));
            t.setEmotion(TextUtils.truncate(c.getEmotion(), 40));
            t.setDurationMinutes(c.getDurationMinutes());
            t.setPotentialScore(c.getPotentialScore());
            t.setHistoricalReference(c.getHistoricalReference());
            t.setAiSuggestedSeries(TextUtils.truncate(c.getSuggestedSeries(), 120));
            t.setSeriesId(seriesResolver.resolveSeriesId(
                    c.getSuggestedSeries(), c.getDynasty(), c.getGenre()));
            t.setStatus(TopicStatus.DRAFT);
            t.setSource("AI_BRAINSTORM");
            t.setPresetId(preset.getId());
            t.setPresetVersionUsed(preset.getCurrentVersion());
            // 频道级常量(受众/时长/情绪等)从 schema.default 灌底,LLM 输出覆盖。
            // 跟 HotPromoteService 一致 —— 任何 Topic 创建路径都该保证 schema.default 至少不丢。
            com.fasterxml.jackson.databind.node.ObjectNode inputRoot = presetService.extractSchemaDefaults(preset);
            if (c.getPresetInput() != null && !c.getPresetInput().isEmpty()) {
                // LLM 输出整 map 覆盖到 root(key 命中 → 覆盖,新 key → 追加)
                c.getPresetInput().forEach((k, v) ->
                        inputRoot.set(k, objectMapper.valueToTree(v)));
                if ((t.getProtagonist() == null || t.getProtagonist().isBlank())
                        && c.getIdentityTag() != null) {
                    t.setProtagonist(TextUtils.truncate(c.getIdentityTag(), 120));
                }
            } else if (c.getIdentityTag() != null) {
                // 老路径(lifecopy):identity_tag / era / archetype / nodes 4 个硬编码字段
                inputRoot.set("identity_tag", objectMapper.valueToTree(c.getIdentityTag()));
                inputRoot.set("era", objectMapper.valueToTree(c.getEra()));
                inputRoot.set("archetype", objectMapper.valueToTree(c.getArchetype()));
                inputRoot.set("nodes", objectMapper.valueToTree(
                        c.getNodes() == null ? List.of() : c.getNodes()));
                if (t.getProtagonist() == null || t.getProtagonist().isBlank()) {
                    t.setProtagonist(TextUtils.truncate(c.getIdentityTag(), 120));
                }
            }
            // 即使两条 LLM 路径都没产出,只要 schema 有 default,也要落盘(频道常量不丢)
            if (inputRoot.size() > 0) {
                t.setPresetInputJson(inputRoot.toString());
            }
            t.setProjectName(pickProjectName(t.getProtagonist(), trimmedTitle));
            persisted.add(topicRepository.save(t));
        }
        // 自动回填 directorNote(并行调 LLM,失败不阻塞 brainstorm 整体)
        autoFillDirectorNotes(persisted);
        return persisted;
    }

    /**
     * brainstorm 后给每条 topic 自动生成一份 directorNote,免得用户每次还要手动点"AI 智能填充"。
     * 并行 LLM 调用(每条耗时 ~5-10s),JPA dirty-checking 在事务 commit 时把 directorNote 自动 flush。
     * 单条失败只 log,不影响其他 topic 与整体 brainstorm 返回。
     */
    private void autoFillDirectorNotes(List<Topic> topics) {
        if (topics.isEmpty()) return;
        long t0 = System.currentTimeMillis();
        java.util.concurrent.atomic.AtomicInteger ok = new java.util.concurrent.atomic.AtomicInteger();
        topics.parallelStream().forEach(t -> {
            try {
                DirectorNoteOptimizeService.OptimizeResponse resp =
                        directorNoteOptimizeService.optimizeForTopic(t, null);
                t.setDirectorNote(objectMapper.writeValueAsString(resp.note()));
                ok.incrementAndGet();
            } catch (Exception e) {
                log.warn("[Brainstorm] 自动 directorNote 失败 topicId={} title={}: {}",
                        t.getId(), t.getTitle(), e.toString());
            }
        });
        log.info("[Brainstorm] 自动 directorNote 完成 total={} success={} ms={}",
                topics.size(), ok.get(), System.currentTimeMillis() - t0);
    }

    /** project_name(VARCHAR 40)显示规则:protagonist 优先,否则截 title 前 10 字。两者都空返回 null。 */
    private static String pickProjectName(String protagonist, String title) {
        if (protagonist != null && !protagonist.isBlank()) {
            return TextUtils.truncate(protagonist.trim(), 40);
        }
        if (title != null && !title.isBlank()) {
            return title.substring(0, Math.min(title.length(), 10));
        }
        return null;
    }

    private List<BrainstormCandidate> parseCandidates(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("LLM returned empty content");
        }
        String json = TextUtils.stripCodeFence(raw).trim();
        int start = json.indexOf('[');
        int end = json.lastIndexOf(']');
        if (start < 0 || end < 0 || end <= start) {
            throw new IllegalStateException("LLM response is not a JSON array: " + TextUtils.preview(raw));
        }
        json = json.substring(start, end + 1);
        try {
            return objectMapper.readValue(json, new TypeReference<List<BrainstormCandidate>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse LLM JSON: " + TextUtils.preview(raw), e);
        }
    }

    /** 非数据驱动 / 数据驱动失败时给 yaml 占位符兜底，避免 {{...}} 透传到 LLM。 */
    private static void fillDataDrivenDefaults(Map<String, Object> vars) {
        vars.put("weight_table",
                "- 朝代权重：明 0.45 / 清 0.42 / 民国 0.40 / 唐 0.35 / 宋 0.30\n" +
                "- 题材权重：宫廷 0.48 / 案件 0.45 / 灵异 0.42 / 政治 0.32\n" +
                "- 钩子权重：反逻辑 0.50 / 数字冲击 0.46 / 时间地点反常 0.42 / 未解之谜 0.40 / 反差身份 0.38");
        vars.put("top_features", "（暂无历史数据，沿用静态权重经验值）");
        vars.put("bottom_features", "（暂无历史数据）");
        vars.put("prev_week_plan", "（暂无上周复盘的下周改进计划）");
    }

    /**
     * 拼热点条目 context — 供预设的 brainstorm_prompt_yaml 通过 {{hot_items_context}} 引用。
     *
     * withHotFetch=true 时先按预设 hot_source_config 实拉一批(写库),与显式 hotItemIds 合并去重。
     * 拉取失败会被 HotFetchService 内部吞掉,不影响 brainstorm 本身。
     *
     * 老预设不引用 {{hot_items_context}} 时,本变量被忽略(向后兼容)。
     */
    private String buildHotItemsContext(com.auteur.preset.Preset preset, BrainstormRequest req) {
        // 收集 entity,而不是 id → 二次 findAllById 是无谓的 round-trip(query() 已经返回 entity)。
        java.util.LinkedHashMap<Long, HotItem> selected = new java.util.LinkedHashMap<>();

        if (Boolean.TRUE.equals(req.getWithHotFetch())) {
            HotItemQueryService.HotItemFilter f = hotItemQueryService.filterFromPreset(preset);
            if (f.disabled) {
                log.info("[Brainstorm] preset={} 关闭了热点订阅,跳过 hot_items_context", preset.getName());
            } else {
                // 预设勾了源就只抓那几个;没勾源 = UI 承诺的「所有 enabled 源」,改调 fetchAll 兜底。
                try {
                    if (f.sourceIds != null && !f.sourceIds.isEmpty()) {
                        hotFetchService.fetchForPreset(preset.getId(), f.sourceIds);
                    } else {
                        hotFetchService.fetchAll();
                    }
                } catch (RuntimeException e) {
                    log.warn("[Brainstorm] 热点抓取失败,本次跳过: {}", e.getMessage());
                }
                if (f.limit == null) f.limit = 8; // 上下文别太长
                hotItemQueryService.query(f).forEach(it -> selected.put(it.getId(), it));
            }
        }
        // 显式 ids 是用户手动挑的,绕过 disabled,直接合并。
        if (req.getHotItemIds() != null && !req.getHotItemIds().isEmpty()) {
            // 避免重复 round-trip:只查不在 selected 里的 id。
            List<Long> missing = req.getHotItemIds().stream()
                    .filter(id -> !selected.containsKey(id))
                    .toList();
            if (!missing.isEmpty()) {
                hotItemRepository.findAllById(missing).forEach(it -> selected.put(it.getId(), it));
            }
        }

        if (selected.isEmpty()) return "";

        StringBuilder sb = new StringBuilder("以下是当前社会热点(用作素材参考):\n");
        for (HotItem it : selected.values()) {
            sb.append("- 【").append(it.getTitle()).append("】");
            if (it.getSummary() != null && !it.getSummary().isBlank()) {
                sb.append(" ").append(TextUtils.preview(it.getSummary(), 160));
            }
            sb.append('\n');
        }
        log.info("[Brainstorm] hot_items_context items={} chars={}", selected.size(), sb.length());
        return sb.toString();
    }

    /**
     * 已做过的题清单 — 喂给 LLM 的 {{done_topics}} 占位符。
     *
     * 数据来源:
     *   1) published_video 表(权威)— 按 preset_id 过滤,只看本预设近 100 条已发视频;
     *      跨预设(LifeCopy / 历史悬案号 等)的标题不会污染当前预设的 done_topics。
     *      LIMIT 100 由 Pageable 在 SQL 层完成,不会拉全表到 JVM。
     *   2) 前端传的 done_topics(可选,通常是「topic 列表前 30 条草稿」)— 补充未发但已存在的占位选题。
     *
     * 合并去重(LinkedHashSet 保序),空串/"无" 兜底。
     */
    private String buildDoneTopics(Long presetId, String fromRequest) {
        java.util.LinkedHashSet<String> all = new java.util.LinkedHashSet<>();

        if (presetId != null) {
            try {
                publishedVideoRepository.findRecentTitlesByPresetId(
                                presetId, org.springframework.data.domain.PageRequest.of(0, 100))
                        .stream()
                        .filter(t -> t != null && !t.isBlank())
                        .map(String::trim)
                        .forEach(all::add);
            } catch (RuntimeException e) {
                log.warn("[Brainstorm] 加载已发布视频去重失败,跳过: {}", e.getMessage());
            }
        }

        if (fromRequest != null && !fromRequest.isBlank() && !"无".equals(fromRequest.trim())) {
            for (String t : fromRequest.split(" / ")) {
                String trimmed = t.trim();
                if (!trimmed.isEmpty()) all.add(trimmed);
            }
        }

        if (all.isEmpty()) return "无";
        log.info("[Brainstorm] done_topics merged={} (published+request)", all.size());
        return String.join(" / ", all);
    }

}
