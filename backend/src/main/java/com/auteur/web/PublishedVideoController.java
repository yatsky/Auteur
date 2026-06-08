package com.auteur.web;

import com.auteur.domain.PublishedVideo;
import com.auteur.domain.PublishedVideoRepository;
import com.auteur.published.PublishedDedupeService;
import com.auteur.published.PublishedVideoUpsertRequest;
import com.auteur.published.PublishedVideoUpsertService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 已发布视频指标 CRUD + 批量 CSV 导入。
 * 数据复盘看板的数据源(手填 + 浏览器插件回写)。
 */
@Slf4j
@RestController
@RequestMapping("/api/published-videos")
@RequiredArgsConstructor
public class PublishedVideoController {

    private final PublishedVideoRepository repo;
    private final PublishedVideoUpsertService upsertService;
    private final PublishedDedupeService dedupeService;

    @GetMapping
    public List<PublishedVideoDto> list() {
        return repo.findAllByOrderByPublishedAtDesc().stream().map(PublishedVideoDto::from).toList();
    }

    /**
     * 一键去重 —— 按 (platform, title, publishedAt) 分组,保留 ID 最小那条并把另几条字段 merge 进来,删多余行。
     * dryRun=true(默认) 只列清单,不动数据;dryRun=false 真删。
     */
    @PostMapping("/dedupe")
    public PublishedDedupeService.DedupeResult dedupe(
            @RequestParam(defaultValue = "true") boolean dryRun) {
        return dedupeService.dedupe(dryRun);
    }

    @GetMapping("/{id}")
    public PublishedVideoDto get(@PathVariable Long id) {
        PublishedVideo v = repo.findById(id).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "已发布视频 " + id + " 不存在"));
        return PublishedVideoDto.from(v);
    }

    @PostMapping
    @Transactional
    public PublishedVideoDto create(@Valid @RequestBody PublishedVideoUpsertRequest req) {
        if (req.platformVideoId() != null && !req.platformVideoId().isBlank()) {
            repo.findByPlatformAndPlatformVideoId(req.platform(), req.platformVideoId())
                .ifPresent(existing -> {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "同平台 vid 已存在(" + existing.getPlatform() + "/" + existing.getPlatformVideoId() + ")");
                });
        }
        PublishedVideo v = upsertService.applyUpsert(new PublishedVideo(), req);
        PublishedVideo saved = repo.save(v);
        log.info("[PublishedVideo] create id={} platform={} title={}", saved.getId(), saved.getPlatform(), saved.getTitle());
        return PublishedVideoDto.from(saved);
    }

    /**
     * 批量导入(CSV / xlsx 解析后前端打包成 JSON 数组)。
     * Merge 语义全部走 PublishedVideoUpsertService.upsert,字段错的塞 errors 里继续(不整批 rollback)。
     */
    @PostMapping("/bulk")
    public BulkResult bulk(@RequestBody List<PublishedVideoUpsertRequest> rows) {
        if (rows == null || rows.isEmpty()) {
            return new BulkResult(0, 0, 0, List.of());
        }
        int inserted = 0;
        int updated = 0;
        int skipped = 0;
        List<String> errors = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            PublishedVideoUpsertRequest r = rows.get(i);
            try {
                PublishedVideoUpsertService.UpsertOutcome out = upsertService.upsert(r);
                if (out.result() == PublishedVideoUpsertService.Result.INSERTED) inserted++; else updated++;
            } catch (Exception e) {
                errors.add("第 " + (i + 1) + " 行: " + e.getMessage());
            }
        }
        log.info("[PublishedVideo] bulk inserted={} updated={} skipped={} errors={}",
                inserted, updated, skipped, errors.size());
        return new BulkResult(inserted, updated, skipped, errors);
    }

    @PatchMapping("/{id}")
    @Transactional
    public PublishedVideoDto update(@PathVariable Long id, @RequestBody PublishedVideoUpsertRequest req) {
        PublishedVideo v = repo.findById(id).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "已发布视频 " + id + " 不存在"));
        upsertService.applyUpsertPartial(v, req);
        return PublishedVideoDto.from(repo.save(v));
    }

    @DeleteMapping("/{id}")
    @Transactional
    public void delete(@PathVariable Long id) {
        if (!repo.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "已发布视频 " + id + " 不存在");
        }
        repo.deleteById(id);
        log.info("[PublishedVideo] delete id={}", id);
    }

    public record BulkResult(int inserted, int updated, int skipped, List<String> errors) {}

    public record PublishedVideoDto(
            Long id,
            Long scriptId,
            Long topicId,
            String title,
            String projectName,
            String platform,
            String platformVideoId,
            LocalDateTime publishedAt,
            Integer durationSeconds,
            Long views,
            Long likes,
            Long comments,
            Long shares,
            BigDecimal retentionPct,
            BigDecimal avgPlaySeconds,
            BigDecimal drop2sPct,
            BigDecimal play5sPct,
            BigDecimal avgPlayRatioPct,
            BigDecimal favoriteRatePct,
            BigDecimal dislikeRatePct,
            BigDecimal hookCtr,
            BigDecimal costYuan,
            String hookTemplate,
            String notes,
            String coverUrl,
            BigDecimal likeRate,
            BigDecimal shareRate,
            BigDecimal commentRate,
            Integer subscribeCount,
            Integer unsubscribeCount,
            BigDecimal coverCtr,
            Integer homepageVisitCount,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
        static PublishedVideoDto from(PublishedVideo v) {
            return new PublishedVideoDto(
                    v.getId(), v.getScriptId(), v.getTopicId(), v.getTitle(), v.getProjectName(),
                    v.getPlatform(), v.getPlatformVideoId(), v.getPublishedAt(), v.getDurationSeconds(),
                    v.getViews(), v.getLikes(), v.getComments(), v.getShares(),
                    v.getRetentionPct(),
                    v.getAvgPlaySeconds(), v.getDrop2sPct(), v.getPlay5sPct(),
                    v.getAvgPlayRatioPct(), v.getFavoriteRatePct(), v.getDislikeRatePct(),
                    v.getHookCtr(), v.getCostYuan(),
                    v.getHookTemplate(), v.getNotes(),
                    v.getCoverUrl(), v.getLikeRate(), v.getShareRate(), v.getCommentRate(),
                    v.getSubscribeCount(), v.getUnsubscribeCount(),
                    v.getCoverCtr(), v.getHomepageVisitCount(),
                    v.getCreatedAt(), v.getUpdatedAt());
        }
    }
}
