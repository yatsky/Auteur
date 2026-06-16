package com.auteur.web;

import com.auteur.domain.Series;
import com.auteur.domain.SeriesRepository;
import com.auteur.domain.Topic;
import com.auteur.domain.TopicRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Series 管理:CRUD + 反查该系列下的 topics。 */
@Slf4j
@RestController
@RequestMapping("/api/series")
@RequiredArgsConstructor
public class SeriesController {

    private final SeriesRepository seriesRepository;
    private final TopicRepository topicRepository;

    @GetMapping
    public List<SeriesDto> list() {
        List<Series> all = seriesRepository.findAll();
        if (all.isEmpty()) return List.of();
        // 一次 group by 拿到每个 series 的 topic count,避免 N+1
        List<Long> ids = all.stream().map(Series::getId).toList();
        Map<Long, Long> countMap = new HashMap<>();
        for (Object[] row : topicRepository.countBySeriesIds(ids)) {
            countMap.put((Long) row[0], (Long) row[1]);
        }
        return all.stream()
                .map(s -> SeriesDto.from(s, countMap.getOrDefault(s.getId(), 0L)))
                .toList();
    }

    @GetMapping("/{id}")
    public SeriesDto get(@PathVariable Long id) {
        Series s = seriesRepository.findById(id).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "系列 " + id + " 不存在"));
        long count = topicRepository.findBySeriesIdOrderByIdDesc(id).size();
        return SeriesDto.from(s, count);
    }

    /** 反查该系列下所有 topic。 */
    @GetMapping("/{id}/topics")
    public List<Topic> topics(@PathVariable Long id) {
        if (!seriesRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "系列 " + id + " 不存在");
        }
        return topicRepository.findBySeriesIdOrderByIdDesc(id);
    }

    @PostMapping
    @Transactional
    public SeriesDto create(@Valid @RequestBody UpsertRequest req) {
        // slug 必须唯一(DB unique 约束),提前查一下给个友好 409 错误
        if (seriesRepository.findBySlug(req.slug().trim()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "slug '" + req.slug() + "' 已被占用");
        }
        Series s = new Series();
        s.setName(req.name().trim());
        s.setSlug(req.slug().trim());
        s.setDescription(blankToNull(req.description()));
        s.setCoverUrl(blankToNull(req.coverUrl()));
        if (req.status() != null && !req.status().isBlank()) s.setStatus(req.status().trim());
        Series saved = seriesRepository.save(s);
        log.info("[Series] create id={} name={} slug={}", saved.getId(), saved.getName(), saved.getSlug());
        return SeriesDto.from(saved, 0L);
    }

    @PatchMapping("/{id}")
    @Transactional
    public SeriesDto update(@PathVariable Long id, @RequestBody UpsertRequest req) {
        Series s = seriesRepository.findById(id).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "系列 " + id + " 不存在"));
        if (req.name() != null && !req.name().isBlank()) s.setName(req.name().trim());
        if (req.slug() != null && !req.slug().isBlank()) {
            String newSlug = req.slug().trim();
            if (!newSlug.equals(s.getSlug())) {
                seriesRepository.findBySlug(newSlug).ifPresent(other -> {
                    if (!other.getId().equals(id)) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "slug '" + newSlug + "' 已被占用");
                    }
                });
                s.setSlug(newSlug);
            }
        }
        if (req.description() != null) s.setDescription(blankToNull(req.description()));
        if (req.coverUrl() != null) s.setCoverUrl(blankToNull(req.coverUrl()));
        if (req.status() != null && !req.status().isBlank()) s.setStatus(req.status().trim());
        s.setUpdatedAt(LocalDateTime.now());
        Series saved = seriesRepository.save(s);
        long count = topicRepository.findBySeriesIdOrderByIdDesc(id).size();
        return SeriesDto.from(saved, count);
    }

    /** 挂在该 series 下的 topic 不为零 → 409,要求先迁移。 */
    @DeleteMapping("/{id}")
    @Transactional
    public void delete(@PathVariable Long id) {
        Series s = seriesRepository.findById(id).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "系列 " + id + " 不存在"));
        long topicCount = topicRepository.findBySeriesIdOrderByIdDesc(id).size();
        if (topicCount > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "系列下还有 " + topicCount + " 条选题,请先把它们改到别的系列或清空 series_id");
        }
        seriesRepository.delete(s);
        log.info("[Series] delete id={} name={}", id, s.getName());
    }

    private static String blankToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    public record UpsertRequest(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Size(max = 120) String slug,
            @Size(max = 1000) String description,
            @Size(max = 500) String coverUrl,
            @Size(max = 20) String status
    ) {}

    public record SeriesDto(
            Long id,
            String name,
            String slug,
            String description,
            String coverUrl,
            String status,
            Long topicCount,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
        static SeriesDto from(Series s, long topicCount) {
            return new SeriesDto(
                    s.getId(), s.getName(), s.getSlug(), s.getDescription(), s.getCoverUrl(), s.getStatus(),
                    topicCount, s.getCreatedAt(), s.getUpdatedAt());
        }
    }
}
