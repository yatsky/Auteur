package com.auteur.insights;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.auteur.common.text.TextUtils;
import com.auteur.domain.PublishedVideo;
import com.auteur.domain.PublishedVideoRepository;
import com.auteur.llm.LlmCallSpec;
import com.auteur.llm.LlmClient;
import com.auteur.llm.LlmResult;
import com.auteur.llm.ModelRegistry;
import com.auteur.llm.PromptTemplateService;
import com.auteur.web.NotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * 单视频归因 —— 给一条 published_video 算「为什么数据好/差」的 LLM 定性分析。
 * 输出 JSON: {verdict, whatWorked, whatFailed, recommendations}。
 * 失败兜底:LLM 调用 / 解析失败时返回 fallback=true 的统一文案,前端不应自动落库。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoAttributionService {

    private static final int BASELINE_WINDOW_DAYS = 30;
    private static final DateTimeFormatter PUBLISHED_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final LlmClient llmClient;
    private final PromptTemplateService promptService;
    private final ModelRegistry modelRegistry;
    private final PublishedVideoRepository pvRepo;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 故意不加 @Transactional(readOnly = true):LlmClient 内部要往 cost_log 写审计行,
     * 一旦外层 tx 是 readOnly,JDBC 拒绝 INSERT,Spring 把 tx 标 rollback-only,
     * 方法返回时 commit 抛 UnexpectedRollbackException(LLM 已成功的结果会被异常顶掉)。
     */
    public VideoAttributionResult analyze(long videoId) {
        PublishedVideo v = pvRepo.findById(videoId)
                .orElseThrow(() -> new NotFoundException("视频不存在: " + videoId));
        Map<String, String> dims = fetchTopicDims(v.getTopicId());
        Baseline base = fetchBaseline(v.getPlatform());

        Map<String, Object> vars = new HashMap<>();
        vars.put("video_title", v.getTitle());
        vars.put("video_platform", v.getPlatform());
        vars.put("video_published_at", v.getPublishedAt() == null ? "—" : v.getPublishedAt().format(PUBLISHED_FMT));
        vars.put("video_duration_seconds", v.getDurationSeconds() == null ? "—" : v.getDurationSeconds().toString());
        vars.put("dim_dynasty", dims.getOrDefault("dynasty", "—"));
        vars.put("dim_genre", dims.getOrDefault("genre", "—"));
        vars.put("dim_hook_type", dims.getOrDefault("hookType", "—"));
        vars.put("dim_emotion", dims.getOrDefault("emotion", "—"));
        vars.put("dim_duration_minutes", dims.getOrDefault("durationMinutes", "—"));
        vars.put("hook_template", v.getHookTemplate() == null || v.getHookTemplate().isBlank() ? "—" : v.getHookTemplate());

        vars.put("kpi_views", String.format("%,d", v.getViews() == null ? 0L : v.getViews()));
        vars.put("kpi_retention_pct", fmtPct(v.getRetentionPct()));
        vars.put("kpi_hook_ctr", fmtPct(v.getHookCtr()));
        vars.put("kpi_drop_2s", fmtPct(v.getDrop2sPct()));
        vars.put("kpi_play_5s", fmtPct(v.getPlay5sPct()));
        vars.put("kpi_avg_play_ratio", fmtPct(v.getAvgPlayRatioPct()));
        vars.put("kpi_avg_play_seconds", v.getAvgPlaySeconds() == null ? "—" : v.getAvgPlaySeconds().toPlainString() + " s");
        vars.put("kpi_favorite_rate", fmtPct(v.getFavoriteRatePct()));
        vars.put("kpi_dislike_rate", fmtPct(v.getDislikeRatePct()));
        vars.put("kpi_cost_yuan", v.getCostYuan() == null ? "—" : "¥" + v.getCostYuan().toPlainString());
        // 三个 *Rate 在表里是 0~1 浮点,这里 ×100 转成"％";coverCtr 已经是 0~100;
        // subscribe / unsubscribe / homepage_visit 是计数。
        vars.put("kpi_like_rate", fmtRate01(v.getLikeRate()));
        vars.put("kpi_share_rate", fmtRate01(v.getShareRate()));
        vars.put("kpi_comment_rate", fmtRate01(v.getCommentRate()));
        vars.put("kpi_cover_ctr", fmtPct(v.getCoverCtr()));
        vars.put("kpi_subscribe_count", v.getSubscribeCount() == null ? "—" : String.valueOf(v.getSubscribeCount()));
        vars.put("kpi_unsubscribe_count", v.getUnsubscribeCount() == null ? "—" : String.valueOf(v.getUnsubscribeCount()));
        vars.put("kpi_homepage_visit_count", v.getHomepageVisitCount() == null ? "—" : String.valueOf(v.getHomepageVisitCount()));

        vars.put("base_sample_count", String.valueOf(base.sampleCount));
        vars.put("base_avg_retention", fmtPct(base.avgRetention));
        vars.put("base_avg_hook_ctr", fmtPct(base.avgHookCtr));

        PromptTemplateService.Rendered tpl = promptService.render("video_attribution", vars);
        Double temperature = tpl.temperature() != null ? tpl.temperature() : 0.5;

        LlmCallSpec spec = LlmCallSpec.builder()
                .operation("video_attribution")
                .relatedType("PUBLISHED_VIDEO")
                .relatedId(videoId)
                .model(modelRegistry.modelFor("video_attribution"))
                .temperature(temperature)
                .maxTokens(tpl.maxTokens())
                .build();

        LlmResult result;
        try {
            result = llmClient.chat(spec, tpl.system(), tpl.user());
        } catch (RuntimeException e) {
            log.warn("[VideoAttr] LLM call failed for videoId={}: {}", videoId, e.toString());
            return fallback("AI 调用失败:" + e.getMessage());
        }
        String raw = result.getContent();
        log.info("[VideoAttr] videoId={} LLM raw chars={} inTok={} outTok={} ms={}",
                videoId, raw == null ? 0 : raw.length(),
                result.getInputTokens(), result.getOutputTokens(), result.getDurationMs());

        return parseResult(raw);
    }

    /** 拿 topic 的 5 个维度。topicId null / topic 不存在 → 返回空 map。 */
    private Map<String, String> fetchTopicDims(Long topicId) {
        Map<String, String> out = new HashMap<>();
        if (topicId == null) return out;
        String sql = "SELECT dynasty, genre, hook_type, emotion, duration_minutes FROM topic WHERE id = ?";
        jdbc.query(sql, new Object[]{topicId}, rs -> {
            putIfPresent(out, "dynasty", rs.getString("dynasty"));
            putIfPresent(out, "genre", rs.getString("genre"));
            putIfPresent(out, "hookType", rs.getString("hook_type"));
            putIfPresent(out, "emotion", rs.getString("emotion"));
            int dm = rs.getInt("duration_minutes");
            if (!rs.wasNull()) out.put("durationMinutes", dm + " min");
        });
        return out;
    }

    /** 30 天 same-platform 基线。无样本时 sampleCount=0,avg 为 null。 */
    private Baseline fetchBaseline(String platform) {
        LocalDateTime since = LocalDateTime.now().minusDays(BASELINE_WINDOW_DAYS);
        String sql = "SELECT AVG(retention_pct) ar, AVG(hook_ctr) ah, COUNT(*) cnt " +
                "FROM published_video " +
                "WHERE published_at >= ? AND platform = ?";
        return jdbc.query(sql, rs -> {
            if (!rs.next()) return new Baseline(0, null, null);
            BigDecimal ar = rs.getBigDecimal("ar");
            BigDecimal ah = rs.getBigDecimal("ah");
            int cnt = rs.getInt("cnt");
            return new Baseline(
                    cnt,
                    ar == null ? null : ar.setScale(2, RoundingMode.HALF_UP),
                    ah == null ? null : ah.setScale(2, RoundingMode.HALF_UP)
            );
        }, since, platform);
    }

    private VideoAttributionResult parseResult(String raw) {
        if (raw == null || raw.isBlank()) {
            log.warn("[VideoAttr] LLM returned empty content");
            return fallback("AI 返回为空");
        }
        String json = TextUtils.stripCodeFence(raw).trim();
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start < 0 || end < 0 || end <= start) {
            log.warn("[VideoAttr] LLM response is not a JSON object: {}", TextUtils.preview(raw));
            return fallback("AI 返回非 JSON");
        }
        json = json.substring(start, end + 1);
        try {
            JsonNode node = objectMapper.readTree(json);
            String verdict = readField(node, "verdict");
            String worked = readField(node, "whatWorked");
            String failed = readField(node, "whatFailed");
            String recs = readField(node, "recommendations");
            if (verdict == null || worked == null || failed == null || recs == null) {
                log.warn("[VideoAttr] missing fields in LLM JSON: {}", TextUtils.preview(raw));
                return fallback("AI 返回字段不全");
            }
            return new VideoAttributionResult(verdict, worked, failed, recs, false);
        } catch (Exception ex) {
            log.warn("[VideoAttr] JSON parse failed: {}", TextUtils.preview(raw), ex);
            return fallback("AI JSON 解析失败");
        }
    }

    private static void putIfPresent(Map<String, String> out, String key, String value) {
        if (value != null && !value.isBlank()) out.put(key, value);
    }

    private static String fmtPct(BigDecimal v) {
        if (v == null) return "—";
        return v.toPlainString() + "%";
    }

    /** 0~1 的浮点率 → 0~100% 字符串,保留 2 位 */
    private static String fmtRate01(BigDecimal v) {
        if (v == null) return "—";
        return v.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    private static String readField(JsonNode node, String key) {
        JsonNode v = node.get(key);
        if (v == null || v.isNull()) return null;
        String text = v.isTextual() ? v.asText() : v.toString();
        return text == null || text.isBlank() ? null : text;
    }

    private static VideoAttributionResult fallback(String reason) {
        String msg = "AI 解析失败(" + reason + "),原始输出已记录到日志,请手动复盘。";
        return new VideoAttributionResult(msg, msg, msg, msg, true);
    }

    public record VideoAttributionResult(
            String verdict,
            String whatWorked,
            String whatFailed,
            String recommendations,
            /** true = 兜底文案(LLM 失败 / 解析失败),前端不应做自动持久化或长期展示 */
            boolean fallback
    ) {}

    private record Baseline(int sampleCount, BigDecimal avgRetention, BigDecimal avgHookCtr) {}
}
