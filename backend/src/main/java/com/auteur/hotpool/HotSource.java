package com.auteur.hotpool;

import com.auteur.preset.JsonRawStringDeserializer;
import com.fasterxml.jackson.annotation.JsonRawValue;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 热点抓取源 — 一条 = 一个适配器实例。
 *
 * 默认 4 条由 HotSourceSeeder 启动时灌(空表才灌);用户在系统设置「热点源」页增删。
 * 项目无调度,所有拉取都是 HotFetchService 手动触发 — last_fetched_at 仅展示用。
 */
@Entity
@Table(name = "hot_source")
@Getter
@Setter
public class HotSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    /** "rss" / "http_json" — 对应一个 com.auteur.hotpool.adapter.HotSource 实现类的 id()。 */
    @Column(nullable = false, length = 32)
    private String adapter;

    @Column(nullable = false, length = 1000)
    private String url;

    /** 适配器私有配置 JSON — http_json 用来配 itemsPointer/titlePointer/urlPointer/publishedPointer。 */
    @JsonRawValue
    @JsonDeserialize(using = JsonRawStringDeserializer.class)
    @Column(name = "config_json", columnDefinition = "TEXT")
    private String configJson;

    /**
     * 该源默认打的 tag 数组 — JSON 字符串,前端 JSON.parse 拆。
     * 与 HotItem.tagsJson 同理,故意不加 @JsonRawValue。
     */
    @Column(name = "default_tags_json", columnDefinition = "TEXT")
    private String defaultTagsJson;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "last_fetched_at")
    private LocalDateTime lastFetchedAt;

    @Column(name = "last_fetch_count")
    private Integer lastFetchCount;

    @Column(name = "last_fetch_error", length = 500)
    private String lastFetchError;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
