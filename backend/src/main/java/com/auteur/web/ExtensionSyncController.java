package com.auteur.web;

import com.auteur.published.PublishedVideoUpsertRequest;
import com.auteur.published.PublishedVideoUpsertService;
import com.auteur.published.PublishedVideoUpsertService.Result;
import com.auteur.published.PublishedVideoUpsertService.UpsertOutcome;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 浏览器插件回写已发布视频 KPI 的端点。
 * 鉴权:ExtensionTokenFilter 通过 X-Extension-Token 校验。
 * 语义:复用 PublishedVideoUpsertService.upsert 字段级 merge,逐行 try/catch 部分失败不影响其余行。
 */
@Slf4j
@RestController
@RequestMapping("/api/extension")
@RequiredArgsConstructor
public class ExtensionSyncController {

    private final PublishedVideoUpsertService upsertService;

    @PostMapping("/sync")
    public Map<String, Object> sync(@RequestBody List<PublishedVideoUpsertRequest> rows) {
        if (rows == null || rows.isEmpty()) {
            return Map.of("count", 0, "inserted", 0, "updated", 0, "errors", List.of());
        }
        int inserted = 0;
        int updated = 0;
        List<String> errors = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            try {
                UpsertOutcome out = upsertService.upsert(rows.get(i));
                if (out.result() == Result.INSERTED) inserted++; else updated++;
            } catch (Exception e) {
                errors.add("第 " + (i + 1) + " 行: " + e.getMessage());
            }
        }
        log.info("[ExtensionSync] received={} inserted={} updated={} errors={}",
                rows.size(), inserted, updated, errors.size());
        return Map.of(
                "count", inserted + updated,
                "inserted", inserted,
                "updated", updated,
                "errors", errors);
    }
}
