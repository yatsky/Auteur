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
 * 周复盘 4 段文字落地。week_code 业务唯一,UI 通过 GET/PUT /api/reviews/weekly?week=YYYY-Wnn 读写。
 */
@Entity
@Table(name = "weekly_review")
@Getter
@Setter
public class WeeklyReview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** ISO-like week,例:2026-W21。前端展示和 UK 都按这个 */
    @Column(name = "week_code", nullable = false, length = 8)
    private String weekCode;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    @Column(name = "new_followers")
    private Integer newFollowers;

    @Column(name = "avg_completion", precision = 5, scale = 2)
    private BigDecimal avgCompletion;

    // 4 段文本。NULL 表示该段还没写。

    @Column(name = "highlights", columnDefinition = "TEXT")
    private String highlights;

    @Column(name = "lessons", columnDefinition = "TEXT")
    private String lessons;

    @Column(name = "experiments", columnDefinition = "TEXT")
    private String experiments;

    @Column(name = "next_week", columnDefinition = "TEXT")
    private String nextWeek;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
