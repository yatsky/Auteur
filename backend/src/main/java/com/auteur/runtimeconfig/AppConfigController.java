package com.auteur.runtimeconfig;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * GET  /api/config              列出所有配置项(secret 自动 mask)
 * PUT  /api/config              批量更新 { key1: value1, key2: value2 };value="" 表示清空走 yml 兜底
 *
 * 简单 CRUD,无鉴权 — 假定本地部署/反向代理层鉴权。
 */
@Slf4j
@RestController
@RequestMapping("/api/config")
@RequiredArgsConstructor
public class AppConfigController {

    private final RuntimeConfig runtimeConfig;

    @GetMapping
    public List<RuntimeConfig.ConfigView> list() {
        return runtimeConfig.listAll();
    }

    @PutMapping
    public Map<String, Object> updateBatch(@RequestBody Map<String, String> kv) {
        runtimeConfig.setAll(kv);
        log.info("[AppConfig] 更新 {} 个配置项 keys={}", kv.size(), kv.keySet());
        return Map.of("updated", kv.size(), "keys", kv.keySet());
    }
}
