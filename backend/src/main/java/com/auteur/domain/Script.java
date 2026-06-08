package com.auteur.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "script")
@Getter
@Setter
public class Script {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "topic_id", nullable = false)
    private Long topicId;

    @Column(nullable = false)
    private Integer version = 1;

    @Column(name = "model_used", length = 60)
    private String modelUsed;

    @Column(name = "word_count")
    private Integer wordCount;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @Column(name = "full_text", columnDefinition = "MEDIUMTEXT")
    private String fullText;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ScriptStatus status = ScriptStatus.DRAFT;

    @Column(name = "review_score", precision = 5, scale = 2)
    private BigDecimal reviewScore;

    /**
     * 该 script 的主角基准照 asset id（V15 引入）。审图首张 PASS 时锁定，
     * 后续同 script 的非空镜镜头生图时作为 Seedream image-to-image reference。
     */
    @Column(name = "protagonist_ref_asset_id")
    private Long protagonistRefAssetId;

    /** V30: LLM 给本 script 打的 BGM mood,6 词表之一,缓存避免每次推荐都重打。 */
    @Column(name = "bgm_mood_tag", length = 40)
    private String bgmMoodTag;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
