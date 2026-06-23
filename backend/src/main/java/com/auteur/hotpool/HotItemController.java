package com.auteur.hotpool;

import com.auteur.domain.Topic;
import com.auteur.hotpool.adapter.HotItemDraft;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * 热点条目 REST API。
 *
 * 端点设计:
 *   GET    /api/hot-items                列表(query 参数全部可选 — 与 HotItemQueryService.HotItemFilter 对应)
 *   GET    /api/hot-items/{id}           详情
 *   POST   /api/hot-items/{id}/promote   转 topic
 *   POST   /api/hot-items/{id}/dismiss   忽略
 *   POST   /api/hot/fetch                手动抓取(body 可空 = 抓所有 enabled 源;sourceIds 传子集)
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class HotItemController {

    private final HotItemRepository itemRepo;
    private final HotItemQueryService queryService;
    private final HotFetchService fetchService;
    private final HotPromoteService promoteService;

    @GetMapping("/api/hot-items")
    public List<HotItem> list(
            @RequestParam(required = false, defaultValue = "new") String status,
            @RequestParam(required = false) List<Long> sourceIds,
            @RequestParam(required = false) List<String> includeKeywords,
            @RequestParam(required = false) List<String> excludeKeywords,
            @RequestParam(required = false) List<String> includeTags,
            @RequestParam(required = false) Integer maxAgeHours,
            @RequestParam(required = false) Double minPopularity,
            @RequestParam(required = false, defaultValue = "200") Integer limit
    ) {
        HotItemQueryService.HotItemFilter f = new HotItemQueryService.HotItemFilter();
        f.status = "all".equals(status) ? null : status;
        f.sourceIds = sourceIds;
        f.includeKeywords = includeKeywords;
        f.excludeKeywords = excludeKeywords;
        f.includeTags = includeTags;
        f.maxAgeHours = maxAgeHours;
        f.minPopularity = minPopularity;
        f.limit = limit;
        return queryService.query(f);
    }

    @GetMapping("/api/hot-items/{id}")
    public HotItem get(@PathVariable Long id) {
        return itemRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "hot_item id=" + id));
    }

    public record PromoteRequest(Long presetId) {
    }

    @PostMapping("/api/hot-items/{id}/promote")
    public Topic promote(@PathVariable Long id, @RequestBody PromoteRequest req) {
        return promoteService.promote(id, req.presetId());
    }

    @PostMapping("/api/hot-items/{id}/dismiss")
    public Map<String, Object> dismiss(@PathVariable Long id) {
        promoteService.dismiss(id);
        return Map.of("id", id, "status", "dismissed");
    }

    public record FetchRequest(List<Long> sourceIds) {
    }

    @PostMapping("/api/hot/fetch")
    public List<HotFetchService.FetchResult> fetch(@RequestBody(required = false) FetchRequest req) {
        if (req == null || req.sourceIds() == null || req.sourceIds().isEmpty()) {
            return fetchService.fetchAll();
        }
        return req.sourceIds().stream().map(fetchService::fetch).toList();
    }

    /** 测试抓取 — 传单条 HotSource(不入库,只返回前 5 条 preview)。添加源时校验用。 */
    @PostMapping("/api/hot/test-fetch")
    public List<HotItemDraft> testFetch(@RequestBody HotSource source) {
        return fetchService.testFetch(source);
    }
}
