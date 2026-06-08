package com.auteur.web;

import com.auteur.domain.GenreStatSnapshot;
import com.auteur.domain.GenreStatSnapshotRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 体裁/垂类聚合统计快照 CRUD + 批量导入。
 * 抖音「投稿作品.xlsx」按周期导入(period_start/period_end 由前端 dialog 提供),
 * KpiDrift 用最新快照算"体裁基准"。
 */
@Slf4j
@RestController
@RequestMapping("/api/genre-stats")
@RequiredArgsConstructor
public class GenreStatsController {

    private final GenreStatSnapshotRepository repo;

    @GetMapping
    public List<GenreStatDto> list() {
        return repo.findAllByOrderByPeriodEndDescPlatformAscGenreAsc().stream()
                .map(GenreStatDto::from).toList();
    }

    @PostMapping
    @Transactional
    public GenreStatDto create(@Valid @RequestBody UpsertRequest req) {
        repo.findByPeriodStartAndPeriodEndAndPlatformAndGenreAndVertical(
                req.periodStart(), req.periodEnd(), req.platform(), req.genre(), req.vertical())
            .ifPresent(existing -> {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "同(周期, 平台, 体裁, 垂类)已存在 #" + existing.getId());
            });
        GenreStatSnapshot s = applyUpsert(new GenreStatSnapshot(), req);
        GenreStatSnapshot saved = repo.save(s);
        log.info("[GenreStat] create id={} period={}~{} {}/{}/{}",
                saved.getId(), saved.getPeriodStart(), saved.getPeriodEnd(),
                saved.getPlatform(), saved.getGenre(), saved.getVertical());
        return GenreStatDto.from(saved);
    }

    /**
     * 批量导入。
     * 同(period_start, period_end, platform, genre, vertical)命中就字段级 merge,
     * 允许同一周期分多次导入(只补部分字段),也允许重导整周期覆盖空字段。
     */
    @PostMapping("/bulk")
    @Transactional
    public BulkResult bulk(@RequestBody List<UpsertRequest> rows) {
        if (rows == null || rows.isEmpty()) {
            return new BulkResult(0, 0, 0, List.of());
        }
        int inserted = 0;
        int updated = 0;
        int skipped = 0;
        List<String> errors = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            UpsertRequest r = rows.get(i);
            try {
                if (r.periodStart() == null || r.periodEnd() == null) {
                    errors.add("第 " + (i + 1) + " 行: periodStart / periodEnd 必填"); continue;
                }
                if (r.platform() == null || r.platform().isBlank()) {
                    errors.add("第 " + (i + 1) + " 行: platform 必填"); continue;
                }
                if (r.genre() == null || r.genre().isBlank()) {
                    errors.add("第 " + (i + 1) + " 行: genre / 体裁 必填"); continue;
                }
                if (r.vertical() == null || r.vertical().isBlank()) {
                    errors.add("第 " + (i + 1) + " 行: vertical / 垂类 必填"); continue;
                }
                Optional<GenreStatSnapshot> existing = repo
                        .findByPeriodStartAndPeriodEndAndPlatformAndGenreAndVertical(
                                r.periodStart(), r.periodEnd(),
                                r.platform(), r.genre(), r.vertical());
                if (existing.isPresent()) {
                    applyUpsertPartial(existing.get(), r);
                    updated++;
                } else {
                    repo.save(applyUpsert(new GenreStatSnapshot(), r));
                    inserted++;
                }
            } catch (Exception e) {
                errors.add("第 " + (i + 1) + " 行: " + e.getMessage());
            }
        }
        log.info("[GenreStat] bulk inserted={} updated={} skipped={} errors={}",
                inserted, updated, skipped, errors.size());
        return new BulkResult(inserted, updated, skipped, errors);
    }

    @DeleteMapping("/{id}")
    @Transactional
    public void delete(@PathVariable Long id) {
        if (!repo.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "快照 " + id + " 不存在");
        }
        repo.deleteById(id);
        log.info("[GenreStat] delete id={}", id);
    }

    private GenreStatSnapshot applyUpsert(GenreStatSnapshot s, UpsertRequest r) {
        s.setPeriodStart(r.periodStart());
        s.setPeriodEnd(r.periodEnd());
        s.setPlatform(r.platform().trim());
        s.setGenre(r.genre().trim());
        s.setVertical(r.vertical().trim());
        s.setSubmissionCount(r.submissionCount() != null ? r.submissionCount() : 0);
        s.setAvgCtrPct(r.avgCtrPct());
        s.setAvgPlay5sPct(r.avgPlay5sPct());
        s.setAvgDrop2sPct(r.avgDrop2sPct());
        s.setAvgPlaySeconds(r.avgPlaySeconds());
        s.setMedianViews(r.medianViews());
        s.setAvgLikes(r.avgLikes());
        s.setAvgComments(r.avgComments());
        s.setAvgShares(r.avgShares());
        s.setNotes(blankToNull(r.notes()));
        return s;
    }

    private void applyUpsertPartial(GenreStatSnapshot s, UpsertRequest r) {
        if (r.submissionCount() != null) s.setSubmissionCount(r.submissionCount());
        if (r.avgCtrPct() != null) s.setAvgCtrPct(r.avgCtrPct());
        if (r.avgPlay5sPct() != null) s.setAvgPlay5sPct(r.avgPlay5sPct());
        if (r.avgDrop2sPct() != null) s.setAvgDrop2sPct(r.avgDrop2sPct());
        if (r.avgPlaySeconds() != null) s.setAvgPlaySeconds(r.avgPlaySeconds());
        if (r.medianViews() != null) s.setMedianViews(r.medianViews());
        if (r.avgLikes() != null) s.setAvgLikes(r.avgLikes());
        if (r.avgComments() != null) s.setAvgComments(r.avgComments());
        if (r.avgShares() != null) s.setAvgShares(r.avgShares());
        if (r.notes() != null) s.setNotes(blankToNull(r.notes()));
    }

    private static String blankToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    public record UpsertRequest(
            @NotNull LocalDate periodStart,
            @NotNull LocalDate periodEnd,
            @NotBlank @Size(max = 40) String platform,
            @NotBlank @Size(max = 60) String genre,
            @NotBlank @Size(max = 60) String vertical,
            Integer submissionCount,
            BigDecimal avgCtrPct,
            BigDecimal avgPlay5sPct,
            BigDecimal avgDrop2sPct,
            BigDecimal avgPlaySeconds,
            Long medianViews,
            BigDecimal avgLikes,
            BigDecimal avgComments,
            BigDecimal avgShares,
            String notes
    ) {}

    public record BulkResult(int inserted, int updated, int skipped, List<String> errors) {}

    public record GenreStatDto(
            Long id,
            LocalDate periodStart,
            LocalDate periodEnd,
            String platform,
            String genre,
            String vertical,
            Integer submissionCount,
            BigDecimal avgCtrPct,
            BigDecimal avgPlay5sPct,
            BigDecimal avgDrop2sPct,
            BigDecimal avgPlaySeconds,
            Long medianViews,
            BigDecimal avgLikes,
            BigDecimal avgComments,
            BigDecimal avgShares,
            String notes
    ) {
        static GenreStatDto from(GenreStatSnapshot s) {
            return new GenreStatDto(
                    s.getId(), s.getPeriodStart(), s.getPeriodEnd(),
                    s.getPlatform(), s.getGenre(), s.getVertical(),
                    s.getSubmissionCount(),
                    s.getAvgCtrPct(), s.getAvgPlay5sPct(), s.getAvgDrop2sPct(),
                    s.getAvgPlaySeconds(), s.getMedianViews(),
                    s.getAvgLikes(), s.getAvgComments(), s.getAvgShares(),
                    s.getNotes());
        }
    }
}
