package com.auteur.insights;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** Insight 模块对外的 DTO 集合(全部 record)。 */
public final class InsightDtos {
    private InsightDtos() {}

    public record DimensionWeightReport(
            String platform,
            int days,
            int minSamples,
            BigDecimal globalAvgRetention,
            int totalSample,
            Map<String, List<DimensionValueWeight>> weights
    ) {}

    public record DimensionValueWeight(
            String value,
            BigDecimal avgRetention,
            BigDecimal avgEngagement,
            BigDecimal avgViews,
            int count,
            boolean credible,
            /** 平均点赞率(0~1 浮点;前端 ×100 显示) */
            BigDecimal avgLikeRate,
            /** 平均分享率(0~1) */
            BigDecimal avgShareRate,
            /** 每条视频平均涨粉数 */
            BigDecimal avgSubscribePerVideo,
            /** 平均封面点击率(0~100) */
            BigDecimal avgCoverCtr
    ) {}

    /** /api/insights/top-bottom 返回体。 */
    public record TopBottomReport(
            String platform,
            int days,
            int n,
            /** 完播率排序(主视角) */
            List<VideoFeature> top,
            List<VideoFeature> bottom,
            Map<String, String> topCommonality,
            Map<String, String> bottomCommonality,
            /** 点赞率视角 Top —— 不受播放量噪声影响的"互动力" */
            List<VideoFeature> topByLikeRate,
            /** 分享率视角 Top —— "传播力" */
            List<VideoFeature> topByShareRate,
            /** 涨粉视角 Top —— "带粉力",对选题反推最关键 */
            List<VideoFeature> topBySubscribe,
            /** 封面 CTR 视角 Top */
            List<VideoFeature> topByCoverCtr
    ) {}

    public record VideoFeature(
            Long id,
            String title,
            String projectName,
            String platform,
            LocalDateTime publishedAt,
            BigDecimal retentionPct,
            long views,
            long likesPlusComments,
            Map<String, String> dimensions,
            /** 点赞率(0~1) */
            BigDecimal likeRate,
            /** 分享率(0~1) */
            BigDecimal shareRate,
            /** 涨粉数 */
            Integer subscribeCount,
            /** 封面点击率(0~100) */
            BigDecimal coverCtr
    ) {}

    /** 喂给 BrainstormService 的整段 prompt 数据包,4 个字段直接对齐 yaml 占位符。 */
    public record BrainstormDataPack(
            String weightTable,
            String topFeatures,
            String bottomFeatures,
            /** 上一周复盘里"下周改进"的原文。无记录时为兜底中文提示,prompt 里直接渲染。 */
            String prevWeekPlan
    ) {}

    public record HookPerformancePack(
            String topHooks,
            String bottomHooks,
            int sample
    ) {}
}
