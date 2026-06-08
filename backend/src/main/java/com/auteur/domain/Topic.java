package com.auteur.domain;

import com.fasterxml.jackson.annotation.JsonRawValue;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "topic")
@Getter
@Setter
public class Topic {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String title;

    /** 项目名(显示用,默认 LEFT(title,10),可手改)。 */
    @Column(name = "project_name", length = 40)
    private String projectName;

    @Column(length = 40)
    private String dynasty;

    @Column(length = 40)
    private String genre;

    @Column(length = 120)
    private String protagonist;

    @Column(name = "hook_type", length = 40)
    private String hookType;

    @Column(length = 40)
    private String emotion;

    @Column(name = "duration_minutes")
    private Integer durationMinutes;

    @Column(name = "potential_score", precision = 5, scale = 2)
    private BigDecimal potentialScore;

    @Column(name = "historical_reference", columnDefinition = "TEXT")
    private String historicalReference;

    @Column(name = "series_id")
    private Long seriesId;

    @Column(name = "ai_suggested_series", length = 120)
    private String aiSuggestedSeries;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TopicStatus status = TopicStatus.DRAFT;

    @Column(nullable = false, length = 40)
    private String source = "AI_BRAINSTORM";

    /**
     * Fulfill 时记录来自哪条 SeriesHook;NULL = 非钩子来源。
     * 不级联:hook 软删 / 重建仍允许,topic 自己保留来源指针(查询时容忍 hook 不存在)。
     */
    @Column(name = "source_hook_id")
    private Long sourceHookId;

    @Column(columnDefinition = "TEXT")
    private String notes;

    /**
     * 总导演笔记(JSON,可空)。下游消费者拼 prompt 时塞进去当全局 vision。
     * NULL = 未配置 → 下游降级到默认风格词。
     */
    @JsonRawValue
    @Column(name = "director_note", columnDefinition = "JSON")
    private String directorNote;

    /** 每条 topic 关联一个 preset。null = 数据异常。 */
    @Column(name = "preset_id", nullable = false)
    private Long presetId;

    /** 用户填的字段,与 preset.input_schema_json 对应(JSON Schema 校验)。 */
    @JsonRawValue
    @Column(name = "preset_input_json", columnDefinition = "JSON")
    private String presetInputJson;

    /** 跑这条 topic 流水线时使用的 preset 版本号,用于历史复现。 */
    @Column(name = "preset_version_used")
    private Integer presetVersionUsed;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 列表里给前端反向显示用:这条 topic 已生成过的最新脚本 id。无脚本则 null。 */
    @Transient
    private Long latestScriptId;

    /** 详情页反向 enrich:fulfill 来源 hook 摘要。列表不 enrich,过载。 */
    @Transient
    private SeriesHook sourceHook;
}
