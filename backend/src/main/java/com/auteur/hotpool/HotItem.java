package com.auteur.hotpool;

import com.auteur.preset.JsonRawStringDeserializer;
import com.fasterxml.jackson.annotation.JsonRawValue;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 热点条目 — brainstorm 阶段从这里选种子。
 *
 * status:
 *   new        — 刚抓到,待处理
 *   promoted   — 已转为 topic(promotedTopicId 记录目标)
 *   dismissed  — 用户手动忽略,不再出现在主列表
 *
 * (source_id, external_id) 唯一,重抓不会产生重复行。
 */
@Entity
@Table(name = "hot_item")
@Getter
@Setter
public class HotItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_id", nullable = false)
    private Long sourceId;

    @Column(name = "external_id", nullable = false, length = 200)
    private String externalId;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(length = 1000)
    private String url;

    @Column(name = "body_text", columnDefinition = "MEDIUMTEXT")
    private String bodyText;

    /**
     * 标签数组 — 入库时为 JSON 字符串(后端 HotFetchService 用 ObjectMapper.writeValueAsString 序列化)。
     * 故意 NOT 用 @JsonRawValue:前端在 HotItemCard / HotItemDetailDrawer 用 JSON.parse(tagsJson) 拆数组,
     * 后端必须吐字符串而不是嵌入式数组,否则 JSON.parse(["a","b"]) 抛错被 catch 静默,导致 tag chips 不渲染。
     */
    @Column(name = "tags_json", columnDefinition = "TEXT")
    private String tagsJson;

    @Column(nullable = false)
    private double popularity = 0.5;

    @Column(nullable = false, length = 10)
    private String locale = "zh";

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "fetched_at", nullable = false)
    private LocalDateTime fetchedAt;

    /** 原始响应 JSON — debug/迁移用,过期可置空。 */
    @JsonRawValue
    @JsonDeserialize(using = JsonRawStringDeserializer.class)
    @Column(name = "raw_payload_json", columnDefinition = "LONGTEXT")
    private String rawPayloadJson;

    @Column(nullable = false, length = 20)
    private String status = "new";

    @Column(name = "promoted_topic_id")
    private Long promotedTopicId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
