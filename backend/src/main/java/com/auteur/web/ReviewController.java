package com.auteur.web;

import com.auteur.insights.WeeklyReviewService;
import com.auteur.insights.WeeklyReviewService.WeeklyReviewSavePayload;
import com.auteur.insights.WeeklyReviewService.WeeklyReviewView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 周复盘 4 段文字的读写。LLM 生成路径在 InsightController.weeklyReview,本控制器只负责持久化。
 */
@Slf4j
@RestController
@RequestMapping("/api/reviews/weekly")
@RequiredArgsConstructor
public class ReviewController {

    private final WeeklyReviewService weeklyReviewService;

    @GetMapping
    public WeeklyReviewView get(@RequestParam("week") String week) {
        return weeklyReviewService.getByWeek(week);
    }

    @PutMapping
    public WeeklyReviewView save(
            @RequestParam("week") String week,
            @RequestBody WeeklyReviewSavePayload payload
    ) {
        return weeklyReviewService.upsertByWeek(week, payload);
    }
}
