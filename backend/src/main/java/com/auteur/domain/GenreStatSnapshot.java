package com.auteur.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 体裁/垂类聚合统计快照。
 * 抖音「投稿作品.xlsx」按周期导入,作为 KpiDrift 体裁基准的数据源。
 * 单视频 metric 跟所属 (platform, vertical) 的均值比 σ —— 比"自比"baseline 多一个外部锚点。
 */
@Entity
@Table(name = "genre_stat_snapshot")
@Getter
@Setter
public class GenreStatSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    @Column(nullable = false, length = 40)
    private String platform;

    @Column(nullable = false, length = 60)
    private String genre;

    @Column(nullable = false, length = 60)
    private String vertical;

    @Column(name = "submission_count", nullable = false)
    private Integer submissionCount = 0;

    @Column(name = "avg_ctr_pct", precision = 5, scale = 2)
    private BigDecimal avgCtrPct;

    @Column(name = "avg_play_5s_pct", precision = 5, scale = 2)
    private BigDecimal avgPlay5sPct;

    @Column(name = "avg_drop_2s_pct", precision = 5, scale = 2)
    private BigDecimal avgDrop2sPct;

    @Column(name = "avg_play_seconds", precision = 8, scale = 2)
    private BigDecimal avgPlaySeconds;

    @Column(name = "median_views")
    private Long medianViews;

    @Column(name = "avg_likes", precision = 12, scale = 2)
    private BigDecimal avgLikes;

    @Column(name = "avg_comments", precision = 12, scale = 2)
    private BigDecimal avgComments;

    @Column(name = "avg_shares", precision = 12, scale = 2)
    private BigDecimal avgShares;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
