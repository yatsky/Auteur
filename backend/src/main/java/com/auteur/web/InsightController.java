package com.auteur.web;

import com.auteur.insights.InsightDtos.DimensionWeightReport;
import com.auteur.insights.InsightDtos.TopBottomReport;
import com.auteur.insights.InsightService;
import com.auteur.insights.VideoAttributionService;
import com.auteur.insights.WeeklyReviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数据复盘 → 反向选题优化的 4 个端点。
 *
 * 设计要点：
 * - 全部支持 platform 过滤（不传 = 全部混合）。
 * - dimension-weights 和 top-bottom 是只读分析；recompute-scores 和 check-exhaustion 是写操作（POST）。
 * - 维度权重 / 公式 / 阈值都在 InsightService 里集中维护，控制器只做参数透传。
 */
@Slf4j
@RestController
@RequestMapping("/api/insights")
@RequiredArgsConstructor
public class InsightController {

    private final InsightService insightService;
    private final WeeklyReviewService weeklyReviewService;
    private final VideoAttributionService videoAttributionService;

    /** 5 个维度每个值的平均完播 / 互动 / 样本数。 */
    @GetMapping("/dimension-weights")
    public DimensionWeightReport dimensionWeights(
            @RequestParam(required = false) String platform,
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(defaultValue = "5") int minSamples
    ) {
        return insightService.dimensionWeights(platform, days, minSamples);
    }

    /** Top N / Bottom N 视频 + 共性维度（爆款基因 / 失败模式）。 */
    @GetMapping("/top-bottom")
    public TopBottomReport topBottom(
            @RequestParam(required = false) String platform,
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(defaultValue = "5") int n
    ) {
        return insightService.topBottom(platform, days, n);
    }

    /** 用真实数据重算所有 DRAFT 选题的 potential_score。 */
    @PostMapping("/recompute-scores")
    public RecomputeResult recomputeScores(
            @RequestParam(required = false) String platform,
            @RequestParam(defaultValue = "30") int days
    ) {
        int updated = insightService.recomputePotentialScores(platform, days);
        return new RecomputeResult(updated);
    }

    /** 基于本周视频 + Top/Bottom + 活跃枯竭预警 → LLM 生成 4 段周复盘初稿。 */
    @PostMapping("/weekly-review")
    public WeeklyReviewService.WeeklyReviewResult weeklyReview(
            @RequestParam(required = false) String platform,
            @RequestParam(defaultValue = "7") int days
    ) {
        return weeklyReviewService.generate(platform, days);
    }

    /** 单视频归因 —— 给一条 published_video 算「为什么数据好/差」。结果不入库,前端弹一次性展示。 */
    @PostMapping("/video-attribution")
    public VideoAttributionService.VideoAttributionResult videoAttribution(
            @RequestParam("videoId") long videoId
    ) {
        return videoAttributionService.analyze(videoId);
    }

    public record RecomputeResult(int updated) {}
}
