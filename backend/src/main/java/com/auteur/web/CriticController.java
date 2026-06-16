package com.auteur.web;

import com.auteur.domain.CriticLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 编剧/摄影自审看板 — 看 critic_log 在指定窗口内的分布。 */
@Slf4j
@RestController
@RequestMapping("/api/critic")
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CriticController {

    private final CriticLogRepository repo;

    @GetMapping("/stats")
    public Map<String, Object> stats(@RequestParam(defaultValue = "30") int days) {
        int window = Math.max(1, days);
        LocalDateTime since = LocalDateTime.now().minusDays(window);

        List<Map<String, Object>> summary = new ArrayList<>();
        for (Object[] row : repo.aggregateSince(since)) {
            Map<String, Object> rec = new HashMap<>();
            rec.put("role", row[0]);
            rec.put("decision", row[1]);
            rec.put("count", row[2] == null ? 0L : ((Number) row[2]).longValue());
            BigDecimal avg = row[3] == null ? BigDecimal.ZERO : new BigDecimal(row[3].toString());
            rec.put("avgScore", avg.setScale(1, RoundingMode.HALF_UP));
            summary.add(rec);
        }

        List<Map<String, Object>> topIssues = new ArrayList<>();
        for (Object[] row : repo.topIssuesSince(since)) {
            Map<String, Object> rec = new HashMap<>();
            rec.put("issues", row[0]);
            rec.put("count", row[1] == null ? 0L : ((Number) row[1]).longValue());
            topIssues.add(rec);
        }

        Map<String, Object> resp = new HashMap<>();
        resp.put("sinceDays", window);
        resp.put("summary", summary);
        resp.put("topIssues", topIssues);
        return resp;
    }
}
