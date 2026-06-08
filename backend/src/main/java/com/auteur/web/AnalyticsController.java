package com.auteur.web;

import com.auteur.domain.PublishedVideo;
import com.auteur.domain.PublishedVideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/analytics")
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AnalyticsController {

    private final PublishedVideoRepository repo;

    @GetMapping("/compare")
    public List<VideoCompareDto> compare() {
        return repo.findAllByOrderByPublishedAtDesc().stream()
                .map(VideoCompareDto::from)
                .toList();
    }

    @GetMapping("/daily-trend")
    public List<DailyTrendPoint> dailyTrend() {
        LocalDateTime since = LocalDate.now().minusDays(30).atStartOfDay();
        List<PublishedVideo> videos = repo.findByPublishedAtAfterOrderByPublishedAtDesc(since);
        Map<LocalDate, long[]> byDay = new HashMap<>();
        for (PublishedVideo v : videos) {
            LocalDate d = v.getPublishedAt().toLocalDate();
            long[] agg = byDay.computeIfAbsent(d, k -> new long[3]);
            agg[0] += v.getViews();
            agg[1] += v.getLikes() + v.getComments();
            agg[2] += 1;
        }
        List<DailyTrendPoint> out = new ArrayList<>();
        byDay.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> {
                    long views = e.getValue()[0];
                    long lc = e.getValue()[1];
                    double engagement = views > 0 ? (lc * 100.0) / views : 0.0;
                    out.add(new DailyTrendPoint(
                            e.getKey().toString(), views,
                            BigDecimal.valueOf(engagement).setScale(2, RoundingMode.HALF_UP)));
                });
        return out;
    }

    public record VideoCompareDto(
            Long id, Long scriptId, Long topicId,
            String title, String projectName, String platform,
            LocalDateTime publishedAt, Integer durationSeconds,
            Long views, Long likes, Long comments, Long shares,
            BigDecimal retentionPct, BigDecimal hookCtr, BigDecimal costYuan,
            BigDecimal avgPlaySeconds, BigDecimal drop2sPct, BigDecimal play5sPct,
            BigDecimal avgPlayRatioPct, BigDecimal favoriteRatePct, BigDecimal dislikeRatePct,
            String hookTemplate, BigDecimal engagementPct
    ) {
        static VideoCompareDto from(PublishedVideo v) {
            BigDecimal eng = v.getViews() != null && v.getViews() > 0
                    ? BigDecimal.valueOf((v.getLikes() + v.getComments()) * 100.0 / v.getViews())
                            .setScale(2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            return new VideoCompareDto(
                    v.getId(), v.getScriptId(), v.getTopicId(),
                    v.getTitle(), v.getProjectName(), v.getPlatform(),
                    v.getPublishedAt(), v.getDurationSeconds(),
                    v.getViews(), v.getLikes(), v.getComments(), v.getShares(),
                    v.getRetentionPct(), v.getHookCtr(), v.getCostYuan(),
                    v.getAvgPlaySeconds(), v.getDrop2sPct(), v.getPlay5sPct(),
                    v.getAvgPlayRatioPct(), v.getFavoriteRatePct(), v.getDislikeRatePct(),
                    v.getHookTemplate(), eng);
        }
    }

    public record DailyTrendPoint(String date, long views, BigDecimal engagementPct) {}
}
