package com.auteur.hotpool;

import com.auteur.hotpool.adapter.HotSourceAdapter;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 启动扫描所有 @Component HotSourceAdapter,按 id() 注册。
 *
 * 新增适配器 → 写一个 @Component 实现 HotSourceAdapter 即可,无需改这里。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HotSourceRegistry {

    private final List<HotSourceAdapter> adapters;
    private final Map<String, HotSourceAdapter> byId = new HashMap<>();

    @PostConstruct
    public void init() {
        for (HotSourceAdapter a : adapters) {
            if (byId.put(a.id(), a) != null) {
                log.warn("[hotpool] 适配器 id 重复: {}", a.id());
            }
            log.info("[hotpool] 适配器注册: {} -> {}", a.id(), a.getClass().getSimpleName());
        }
    }

    public Optional<HotSourceAdapter> find(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    public List<String> knownIds() {
        return List.copyOf(byId.keySet());
    }
}
