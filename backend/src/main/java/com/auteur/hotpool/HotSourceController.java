package com.auteur.hotpool;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * 热点源 CRUD REST API。
 *
 * 用户在「系统设置 → 热点源」页编辑。
 */
@Slf4j
@RestController
@RequestMapping("/api/hot-sources")
@RequiredArgsConstructor
public class HotSourceController {

    private final HotSourceRepository sourceRepo;
    private final HotSourceRegistry registry;
    private final HotFetchService fetchService;

    @GetMapping
    public List<HotSource> list() {
        return sourceRepo.findAllByOrderByIdAsc();
    }

    @GetMapping("/{id}")
    public HotSource get(@PathVariable Long id) {
        return sourceRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "hot_source id=" + id));
    }

    /** 可用的适配器 id 列表 — 前端「添加源」下拉用。 */
    @GetMapping("/adapters")
    public List<String> adapters() {
        return registry.knownIds();
    }

    @PostMapping
    public HotSource create(@RequestBody HotSource draft) {
        draft.setId(null);
        draft.setLastFetchedAt(null);
        draft.setLastFetchCount(null);
        draft.setLastFetchError(null);
        return sourceRepo.save(draft);
    }

    @PutMapping("/{id}")
    public HotSource update(@PathVariable Long id, @RequestBody HotSource patch) {
        HotSource existing = sourceRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "hot_source id=" + id));
        if (patch.getName() != null) existing.setName(patch.getName());
        if (patch.getAdapter() != null) existing.setAdapter(patch.getAdapter());
        if (patch.getUrl() != null) existing.setUrl(patch.getUrl());
        if (patch.getConfigJson() != null) existing.setConfigJson(patch.getConfigJson());
        if (patch.getDefaultTagsJson() != null) existing.setDefaultTagsJson(patch.getDefaultTagsJson());
        existing.setEnabled(patch.isEnabled());
        return sourceRepo.save(existing);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        sourceRepo.deleteById(id);
    }

    /** 单源手动抓取入库。 */
    @PostMapping("/{id}/fetch")
    public HotFetchService.FetchResult fetch(@PathVariable Long id) {
        return fetchService.fetch(id);
    }
}
