package com.auteur.insights;

import com.auteur.domain.Topic;
import com.auteur.domain.TopicRepository;
import com.auteur.domain.TopicStatus;
import com.auteur.domain.WeeklyReview;
import com.auteur.domain.WeeklyReviewRepository;
import com.auteur.insights.InsightDtos.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据复盘 → 反向选题优化的核心服务。
 *
 * 现算不预聚合:published_video 量级在几千条以内,每次 group by 几十毫秒就够。
 * 维度归一用 TRIM(LOWER(...)) 在 SQL 里做桶。
 *
 * 主指标:retention_pct(完播率,已经是百分比 0-100)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InsightService {

    public static final List<DimensionDef> DIMENSIONS = List.of(
            new DimensionDef("dynasty",         "朝代",   0.20, "t.dynasty"),
            new DimensionDef("genre",           "题材",   0.30, "t.genre"),
            new DimensionDef("hookType",        "钩子",   0.25, "t.hook_type"),
            new DimensionDef("emotion",         "情绪",   0.15, "t.emotion"),
            new DimensionDef("durationMinutes", "时长",   0.10, "t.duration_minutes")
    );

    private static final int DEFAULT_MIN_SAMPLES = 5;
    /** Top/Bottom commonality 阈值：N 条里至少 60% 出现才算共性 */
    private static final double COMMONALITY_RATIO = 0.6;

    private final JdbcTemplate jdbc;
    private final TopicRepository topicRepository;
    private final WeeklyReviewRepository weeklyReviewRepository;

    @Transactional(readOnly = true)
    public DimensionWeightReport dimensionWeights(String platform, int days, int minSamples) {
        return dimensionWeights(platform, days, minSamples, null);
    }

    @Transactional(readOnly = true)
    public DimensionWeightReport dimensionWeights(String platform, int days, int minSamples, String contentType) {
        LocalDateTime since = LocalDateTime.now().minusDays(Math.max(1, days));
        int min = minSamples > 0 ? minSamples : DEFAULT_MIN_SAMPLES;
        Map<String, List<DimensionValueWeight>> out = new LinkedHashMap<>();
        BigDecimal globalAvg = globalAvgRetention(platform, since, contentType);
        int totalSample = totalRows(platform, since, contentType);

        for (DimensionDef d : DIMENSIONS) {
            out.put(d.key(), aggregateDimension(d, platform, since, min, contentType));
        }
        return new DimensionWeightReport(
                platform, days, min,
                globalAvg, totalSample,
                out
        );
    }

    private List<DimensionValueWeight> aggregateDimension(
            DimensionDef d, String platform, LocalDateTime since, int minSamples, String contentType) {
        boolean isNumeric = "durationMinutes".equals(d.key());
        String groupKey = isNumeric ? d.column() : "TRIM(LOWER(" + d.column() + "))";
        StringBuilder sb = new StringBuilder()
                .append("SELECT ").append(groupKey).append(" AS norm_value, ")
                .append("MAX(").append(d.column()).append(") AS display_value, ")
                .append("AVG(pv.retention_pct) AS avg_retention, ")
                .append("AVG((pv.likes + pv.comments) * 100.0 / NULLIF(pv.views, 0)) AS avg_engagement, ")
                .append("AVG(pv.views) AS avg_views, ")
                .append("AVG(pv.like_rate) AS avg_like_rate, ")
                .append("AVG(pv.share_rate) AS avg_share_rate, ")
                .append("AVG(pv.subscribe_count) AS avg_subscribe, ")
                .append("AVG(pv.cover_ctr) AS avg_cover_ctr, ")
                .append("COUNT(*) AS cnt ")
                .append("FROM published_video pv ")
                .append("INNER JOIN topic t ON t.id = pv.topic_id ")
                .append("WHERE pv.published_at >= ? ")
                .append("  AND pv.retention_pct IS NOT NULL ")
                .append("  AND ").append(d.column()).append(" IS NOT NULL ");
        if (!isNumeric) {
            sb.append("  AND TRIM(").append(d.column()).append(") <> '' ");
        }
        if (platform != null && !platform.isBlank()) {
            sb.append("  AND pv.platform = ? ");
        }
        if (contentType != null && !contentType.isBlank()) {
            sb.append("  AND t.preset_id = (SELECT id FROM preset WHERE name = ?) ");
        }
        sb.append("GROUP BY ").append(groupKey).append(" ")
          .append("ORDER BY avg_retention DESC");

        List<DimensionValueWeight> rows = new ArrayList<>();
        Object[] args = buildArgs(since, platform, contentType);
        jdbc.query(sb.toString(), args, rs -> {
            String display = rs.getString("display_value");
            BigDecimal avgR = nullSafe(rs.getBigDecimal("avg_retention"));
            BigDecimal avgE = nullSafe(rs.getBigDecimal("avg_engagement"));
            BigDecimal avgV = nullSafe(rs.getBigDecimal("avg_views"));
            BigDecimal avgLR = nullSafe(rs.getBigDecimal("avg_like_rate"));
            BigDecimal avgSR = nullSafe(rs.getBigDecimal("avg_share_rate"));
            BigDecimal avgSub = nullSafe(rs.getBigDecimal("avg_subscribe"));
            BigDecimal avgCC = nullSafe(rs.getBigDecimal("avg_cover_ctr"));
            int cnt = rs.getInt("cnt");
            rows.add(new DimensionValueWeight(
                    display,
                    avgR == null ? null : avgR.setScale(2, RoundingMode.HALF_UP),
                    avgE == null ? null : avgE.setScale(2, RoundingMode.HALF_UP),
                    avgV == null ? null : avgV.setScale(0, RoundingMode.HALF_UP),
                    cnt,
                    cnt >= minSamples,
                    // like_rate / share_rate 在表里是 0~1 浮点,保留 5 位小数让前端 ×100 后还有显著有效位
                    avgLR == null ? null : avgLR.setScale(5, RoundingMode.HALF_UP),
                    avgSR == null ? null : avgSR.setScale(5, RoundingMode.HALF_UP),
                    avgSub == null ? null : avgSub.setScale(1, RoundingMode.HALF_UP),
                    avgCC == null ? null : avgCC.setScale(2, RoundingMode.HALF_UP)
            ));
        });
        return rows;
    }

    private BigDecimal globalAvgRetention(String platform, LocalDateTime since, String contentType) {
        StringBuilder sb = new StringBuilder("SELECT AVG(pv.retention_pct) FROM published_video pv ");
        if (contentType != null && !contentType.isBlank()) {
            sb.append("INNER JOIN topic t ON t.id = pv.topic_id ");
        }
        sb.append("WHERE pv.published_at >= ? AND pv.retention_pct IS NOT NULL ");
        if (platform != null && !platform.isBlank()) sb.append("AND pv.platform = ? ");
        if (contentType != null && !contentType.isBlank()) sb.append("AND t.preset_id = (SELECT id FROM preset WHERE name = ?) ");
        BigDecimal v = jdbc.queryForObject(sb.toString(), BigDecimal.class, buildArgs(since, platform, contentType));
        return v == null ? null : v.setScale(2, RoundingMode.HALF_UP);
    }

    private int totalRows(String platform, LocalDateTime since, String contentType) {
        StringBuilder sb = new StringBuilder("SELECT COUNT(*) FROM published_video pv ");
        if (contentType != null && !contentType.isBlank()) {
            sb.append("INNER JOIN topic t ON t.id = pv.topic_id ");
        }
        sb.append("WHERE pv.published_at >= ? ");
        if (platform != null && !platform.isBlank()) sb.append("AND pv.platform = ? ");
        if (contentType != null && !contentType.isBlank()) sb.append("AND t.preset_id = (SELECT id FROM preset WHERE name = ?) ");
        Integer n = jdbc.queryForObject(sb.toString(), Integer.class, buildArgs(since, platform, contentType));
        return n == null ? 0 : n;
    }

    /** 构造 SQL 参数数组,since 必有,platform / contentType 各占一位(都可空)。 */
    private static Object[] buildArgs(LocalDateTime since, String platform, String contentType) {
        boolean hasPlatform = platform != null && !platform.isBlank();
        boolean hasCt = contentType != null && !contentType.isBlank();
        List<Object> args = new ArrayList<>(3);
        args.add(since);
        if (hasPlatform) args.add(platform.trim());
        if (hasCt) args.add(contentType.trim());
        return args.toArray();
    }

    @Transactional(readOnly = true)
    public TopBottomReport topBottom(String platform, int days, int n) {
        return topBottom(platform, days, n, null);
    }

    @Transactional(readOnly = true)
    public TopBottomReport topBottom(String platform, int days, int n, String contentType) {
        int topN = Math.max(1, Math.min(20, n));
        LocalDateTime since = LocalDateTime.now().minusDays(Math.max(1, days));
        List<VideoFeature> top    = fetchVideosOrdered(platform, since, topN, SortMetric.RETENTION, true,  contentType);
        List<VideoFeature> bottom = fetchVideosOrdered(platform, since, topN, SortMetric.RETENTION, false, contentType);
        Map<String, String> topCommon    = commonalityOf(top);
        Map<String, String> bottomCommon = commonalityOf(bottom);
        List<VideoFeature> topByLike      = fetchVideosOrdered(platform, since, topN, SortMetric.LIKE_RATE, true,  contentType);
        List<VideoFeature> topByShare     = fetchVideosOrdered(platform, since, topN, SortMetric.SHARE_RATE, true, contentType);
        List<VideoFeature> topBySubscribe = fetchVideosOrdered(platform, since, topN, SortMetric.SUBSCRIBE, true,  contentType);
        List<VideoFeature> topByCoverCtr  = fetchVideosOrdered(platform, since, topN, SortMetric.COVER_CTR, true,  contentType);
        return new TopBottomReport(
                platform, days, topN, top, bottom, topCommon, bottomCommon,
                topByLike, topByShare, topBySubscribe, topByCoverCtr);
    }

    /** Top/Bottom 排序维度。每个维度单独的 NOT NULL 过滤,避免空值"沉底"污染 Bottom。 */
    private enum SortMetric {
        RETENTION("pv.retention_pct"),
        LIKE_RATE("pv.like_rate"),
        SHARE_RATE("pv.share_rate"),
        SUBSCRIBE("pv.subscribe_count"),
        COVER_CTR("pv.cover_ctr");
        final String column;
        SortMetric(String column) { this.column = column; }
    }

    private List<VideoFeature> fetchVideosOrdered(
            String platform, LocalDateTime since, int n, SortMetric metric, boolean topDesc, String contentType) {
        StringBuilder sb = new StringBuilder()
                .append("SELECT pv.id, pv.title, pv.project_name, pv.platform, pv.published_at, ")
                .append("       pv.retention_pct, pv.views, pv.likes, pv.comments, ")
                .append("       pv.like_rate, pv.share_rate, pv.subscribe_count, pv.cover_ctr, ")
                .append("       t.dynasty, t.genre, t.hook_type, t.emotion, t.duration_minutes ")
                .append("FROM published_video pv ")
                .append("LEFT JOIN topic t ON t.id = pv.topic_id ")
                .append("WHERE pv.published_at >= ? ")
                .append("  AND ").append(metric.column).append(" IS NOT NULL ");
        if (platform != null && !platform.isBlank()) sb.append("AND pv.platform = ? ");
        if (contentType != null && !contentType.isBlank()) sb.append("AND t.preset_id = (SELECT id FROM preset WHERE name = ?) ");
        sb.append("ORDER BY ").append(metric.column).append(' ').append(topDesc ? "DESC" : "ASC")
          .append(" LIMIT ").append(n);

        Object[] args = buildArgs(since, platform, contentType);
        List<VideoFeature> out = new ArrayList<>();
        jdbc.query(sb.toString(), args, rs -> {
            Map<String, String> dims = new LinkedHashMap<>();
            putIfNonBlank(dims, "dynasty",         rs.getString("dynasty"));
            putIfNonBlank(dims, "genre",           rs.getString("genre"));
            putIfNonBlank(dims, "hookType",        rs.getString("hook_type"));
            putIfNonBlank(dims, "emotion",         rs.getString("emotion"));
            int dm = rs.getInt("duration_minutes");
            if (!rs.wasNull()) dims.put("durationMinutes", String.valueOf(dm));
            BigDecimal r = rs.getBigDecimal("retention_pct");
            BigDecimal lr = rs.getBigDecimal("like_rate");
            BigDecimal sr = rs.getBigDecimal("share_rate");
            int sub = rs.getInt("subscribe_count");
            boolean subWasNull = rs.wasNull();
            BigDecimal cc = rs.getBigDecimal("cover_ctr");
            out.add(new VideoFeature(
                    rs.getLong("id"),
                    rs.getString("title"),
                    rs.getString("project_name"),
                    rs.getString("platform"),
                    rs.getTimestamp("published_at").toLocalDateTime(),
                    r == null ? null : r.setScale(2, RoundingMode.HALF_UP),
                    rs.getLong("views"),
                    rs.getLong("likes") + rs.getLong("comments"),
                    dims,
                    lr == null ? null : lr.setScale(5, RoundingMode.HALF_UP),
                    sr == null ? null : sr.setScale(5, RoundingMode.HALF_UP),
                    subWasNull ? null : sub,
                    cc == null ? null : cc.setScale(2, RoundingMode.HALF_UP)
            ));
        });
        return out;
    }

    /** N 条视频里至少 ceil(N*0.6) 条出现的 (dim, value) 算共性。每个维度最多一个共性值。 */
    private static Map<String, String> commonalityOf(List<VideoFeature> rows) {
        if (rows.isEmpty()) return Map.of();
        int threshold = (int) Math.ceil(rows.size() * COMMONALITY_RATIO);
        Map<String, Map<String, Integer>> count = new HashMap<>();
        for (VideoFeature v : rows) {
            for (var e : v.dimensions().entrySet()) {
                String norm = e.getValue().trim().toLowerCase();
                if (norm.isBlank()) continue;
                count.computeIfAbsent(e.getKey(), k -> new HashMap<>())
                     .merge(norm, 1, Integer::sum);
            }
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (DimensionDef d : DIMENSIONS) {
            Map<String, Integer> bucket = count.get(d.key());
            if (bucket == null) continue;
            String winner = null;
            int winnerCnt = 0;
            for (var e : bucket.entrySet()) {
                if (e.getValue() >= threshold && e.getValue() > winnerCnt) {
                    winner = e.getKey();
                    winnerCnt = e.getValue();
                }
            }
            if (winner != null) {
                String display = winner;
                for (VideoFeature v : rows) {
                    String raw = v.dimensions().get(d.key());
                    if (raw != null && raw.trim().equalsIgnoreCase(winner)) {
                        display = raw.trim();
                        break;
                    }
                }
                out.put(d.key(), display);
            }
        }
        return out;
    }

    /**
     * 按真实 published_video 数据重算 DRAFT 状态选题的 potential_score。
     * 公式:score = Σ weight(dim_i, value_i) × coef_i
     * 单维度无可信样本时回退到全局平均;topic 的某维度为空时该项贡献全局平均×coef。
     */
    @Transactional
    public int recomputePotentialScores(String platform, int days) {
        DimensionWeightReport report = dimensionWeights(platform, days, DEFAULT_MIN_SAMPLES);
        BigDecimal fallback = report.globalAvgRetention() == null
                ? BigDecimal.valueOf(40)  // 冷启动兜底
                : report.globalAvgRetention();
        Map<String, Map<String, BigDecimal>> weightLookup = new HashMap<>();
        for (DimensionDef d : DIMENSIONS) {
            Map<String, BigDecimal> m = new HashMap<>();
            for (DimensionValueWeight v : report.weights().get(d.key())) {
                if (v.credible() && v.avgRetention() != null) {
                    m.put(v.value().trim().toLowerCase(), v.avgRetention());
                }
            }
            weightLookup.put(d.key(), m);
        }

        List<Topic> drafts = topicRepository.findAll().stream()
                .filter(t -> t.getStatus() == TopicStatus.DRAFT)
                .toList();
        int updated = 0;
        for (Topic t : drafts) {
            BigDecimal score = BigDecimal.ZERO;
            for (DimensionDef d : DIMENSIONS) {
                String value = readDimension(t, d.key());
                BigDecimal w = (value == null || value.isBlank())
                        ? fallback
                        : weightLookup.get(d.key()).getOrDefault(value.trim().toLowerCase(), fallback);
                score = score.add(w.multiply(BigDecimal.valueOf(d.coefficient())));
            }
            score = score.setScale(2, RoundingMode.HALF_UP);
            if (t.getPotentialScore() == null || t.getPotentialScore().compareTo(score) != 0) {
                t.setPotentialScore(score);
                updated++;
            }
        }
        log.info("[Insight] recompute potential_score updated={} (drafts={}) platform={} days={}",
                updated, drafts.size(), platform, days);
        return updated;
    }

    private static String readDimension(Topic t, String key) {
        return switch (key) {
            case "dynasty"         -> t.getDynasty();
            case "genre"           -> t.getGenre();
            case "hookType"        -> t.getHookType();
            case "emotion"         -> t.getEmotion();
            case "durationMinutes" -> t.getDurationMinutes() == null ? null : String.valueOf(t.getDurationMinutes());
            default -> null;
        };
    }

    /**
     * 把维度权重 + Top/Bottom 渲染成 prompt 友好的中文文本。
     * BrainstormService.useDataDriven=true 时一次性塞进 yaml 占位符。
     */
    @Transactional(readOnly = true)
    public BrainstormDataPack buildBrainstormPack(String platform, int days) {
        return buildBrainstormPack(platform, days, null);
    }

    @Transactional(readOnly = true)
    public BrainstormDataPack buildBrainstormPack(String platform, int days, String contentType) {
        DimensionWeightReport w = dimensionWeights(platform, days, DEFAULT_MIN_SAMPLES, contentType);
        TopBottomReport tb = topBottom(platform, days, 5, contentType);

        StringBuilder weight = new StringBuilder();
        for (DimensionDef d : DIMENSIONS) {
            List<DimensionValueWeight> vals = w.weights().get(d.key());
            weight.append("- ").append(d.label()).append("（系数 ").append(d.coefficient()).append("）：");
            if (vals.isEmpty()) {
                weight.append("无样本");
            } else {
                StringBuilder line = new StringBuilder();
                int shown = 0;
                for (DimensionValueWeight v : vals) {
                    if (shown >= 6) break;
                    if (line.length() > 0) line.append(" / ");
                    line.append(v.value()).append(" ").append(v.avgRetention());
                    if (!v.credible()) line.append("?"); // 低样本标记
                    shown++;
                }
                weight.append(line);
            }
            weight.append('\n');
        }

        String topFeats    = formatVideoFeatures(tb.top(), tb.topCommonality(), "完播 Top");
        String bottomFeats = formatVideoFeatures(tb.bottom(), tb.bottomCommonality(), "完播 Bottom");
        String prevPlan = fetchLatestNextWeekPlan();
        return new BrainstormDataPack(weight.toString().trim(), topFeats, bottomFeats, prevPlan);
    }

    /**
     * 找最近一次 weekly_review 的 nextWeek 文本。
     * 不强求"上一周",运营可能跳周;只要历史上有人写过"下周改进",就拿最新一条用。
     */
    private String fetchLatestNextWeekPlan() {
        return weeklyReviewRepository.findAll(
                        org.springframework.data.domain.Sort.by(
                                org.springframework.data.domain.Sort.Direction.DESC, "periodEnd"))
                .stream()
                .map(WeeklyReview::getNextWeek)
                .filter(s -> s != null && !s.isBlank())
                .findFirst()
                .orElse("（暂无历史复盘的下周改进计划）");
    }

    /**
     * 近 days 天 published_video 按 hook_template 聚合,top 5 / bottom 5 钩子的平均完播 + 5s 完播。
     * platform 传 null = 跨平台聚合。minSamples 默认 2 —— 钩子模板基数小。
     */
    @Transactional(readOnly = true)
    public HookPerformancePack buildHookPerformancePack(String platform, int days) {
        int window = Math.max(1, days);
        LocalDateTime since = LocalDateTime.now().minusDays(window);
        int minSamples = 2;

        StringBuilder sb = new StringBuilder()
                .append("SELECT hook_template, ")
                .append("       AVG(retention_pct) AS avg_ret, ")
                .append("       AVG(play_5s_pct)   AS avg_p5, ")
                .append("       AVG(drop_2s_pct)   AS avg_d2, ")
                .append("       COUNT(*)           AS cnt ")
                .append("FROM published_video ")
                .append("WHERE published_at >= ? ")
                .append("  AND hook_template IS NOT NULL AND hook_template <> '' ");
        if (platform != null && !platform.isBlank()) sb.append("AND platform = ? ");
        sb.append("GROUP BY hook_template HAVING COUNT(*) >= ").append(minSamples);

        Object[] args = (platform == null || platform.isBlank())
                ? new Object[]{since}
                : new Object[]{since, platform.trim()};

        List<HookRow> rows = new ArrayList<>();
        jdbc.query(sb.toString(), args, rs -> {
            BigDecimal ret = rs.getBigDecimal("avg_ret");
            BigDecimal p5  = rs.getBigDecimal("avg_p5");
            BigDecimal d2  = rs.getBigDecimal("avg_d2");
            rows.add(new HookRow(
                    rs.getString("hook_template"),
                    ret == null ? null : ret.setScale(2, RoundingMode.HALF_UP),
                    p5  == null ? null : p5.setScale(2, RoundingMode.HALF_UP),
                    d2  == null ? null : d2.setScale(2, RoundingMode.HALF_UP),
                    rs.getInt("cnt")
            ));
        });

        if (rows.isEmpty()) {
            return new HookPerformancePack(
                    "（近 " + window + " 天无足够钩子模板样本)",
                    "（同上)",
                    0
            );
        }

        // null 视为最差,排到 bottom
        rows.sort(Comparator.comparing(
                (HookRow r) -> r.avgRet == null ? BigDecimal.valueOf(-1) : r.avgRet
        ).reversed());

        List<HookRow> topRows = rows.subList(0, Math.min(5, rows.size()));
        List<HookRow> bottomRows = rows.size() <= 5
                ? List.of()
                : rows.subList(Math.max(0, rows.size() - 5), rows.size());

        return new HookPerformancePack(
                formatHookRows(topRows),
                bottomRows.isEmpty() ? "（样本数 <= 5,未单独列出 bottom)" : formatHookRows(bottomRows),
                rows.size()
        );
    }

    private static String formatHookRows(List<HookRow> rows) {
        if (rows.isEmpty()) return "（无）";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rows.size(); i++) {
            HookRow r = rows.get(i);
            sb.append(i + 1).append(". ").append(r.template);
            sb.append(" 完播 ").append(r.avgRet == null ? "—" : r.avgRet.toPlainString() + "%");
            if (r.avgP5 != null) sb.append(" / 5s 完播 ").append(r.avgP5.toPlainString()).append("%");
            if (r.avgD2 != null) sb.append(" / 2s 跳出 ").append(r.avgD2.toPlainString()).append("%");
            sb.append(" (n=").append(r.count).append(")\n");
        }
        return sb.toString().trim();
    }

    private record HookRow(String template, BigDecimal avgRet, BigDecimal avgP5, BigDecimal avgD2, int count) {}

    private static String formatVideoFeatures(
            List<VideoFeature> rows, Map<String, String> commonality, String label) {
        if (rows.isEmpty()) return label + "：暂无数据";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rows.size(); i++) {
            VideoFeature v = rows.get(i);
            sb.append(i + 1).append(". ").append(v.title())
              .append("（完播 ").append(v.retentionPct()).append("%, ");
            sb.append(v.dimensions().getOrDefault("dynasty", "?"))
              .append(" / ").append(v.dimensions().getOrDefault("genre", "?"))
              .append(" / ").append(v.dimensions().getOrDefault("hookType", "?"))
              .append("）\n");
        }
        if (!commonality.isEmpty()) {
            sb.append("共性：");
            commonality.forEach((k, val) -> sb.append(k).append("=").append(val).append("  "));
        }
        return sb.toString().trim();
    }

    private static BigDecimal nullSafe(BigDecimal x) { return x; }

    private static void putIfNonBlank(Map<String, String> m, String k, String v) {
        if (v != null && !v.isBlank()) m.put(k, v);
    }

    public record DimensionDef(String key, String label, double coefficient, String column) {}
}
