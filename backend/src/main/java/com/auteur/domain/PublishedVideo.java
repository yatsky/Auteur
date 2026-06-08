package com.auteur.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 已发布视频指标。手填(管理页 / CSV)或后续 OAuth 自动采集落进来。
 * 数据复盘看板的数据源;无 FK 故意松绑,允许 script/topic 删除后保留历史指标。
 */
@Entity
@Table(name = "published_video")
@Getter
@Setter
public class PublishedVideo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "script_id")
    private Long scriptId;

    @Column(name = "topic_id")
    private Long topicId;

    @Column(nullable = false, length = 300)
    private String title;

    @Column(name = "project_name", length = 40)
    private String projectName;

    @Column(nullable = false, length = 40)
    private String platform;

    @Column(name = "platform_video_id", length = 120)
    private String platformVideoId;

    @Column(name = "published_at", nullable = false)
    private LocalDateTime publishedAt;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @Column(nullable = false)
    private Long views = 0L;

    @Column(nullable = false)
    private Long likes = 0L;

    @Column(nullable = false)
    private Long comments = 0L;

    @Column(nullable = false)
    private Long shares = 0L;

    @Column(name = "retention_pct", precision = 5, scale = 2)
    private BigDecimal retentionPct;

    @Column(name = "avg_play_seconds", precision = 8, scale = 2)
    private BigDecimal avgPlaySeconds;

    @Column(name = "drop_2s_pct", precision = 5, scale = 2)
    private BigDecimal drop2sPct;

    @Column(name = "play_5s_pct", precision = 5, scale = 2)
    private BigDecimal play5sPct;

    @Column(name = "avg_play_ratio_pct", precision = 5, scale = 2)
    private BigDecimal avgPlayRatioPct;

    @Column(name = "favorite_rate_pct", precision = 5, scale = 2)
    private BigDecimal favoriteRatePct;

    @Column(name = "dislike_rate_pct", precision = 5, scale = 2)
    private BigDecimal dislikeRatePct;

    @Column(name = "hook_ctr", precision = 5, scale = 2)
    private BigDecimal hookCtr;

    @Column(name = "cover_url", length = 500)
    private String coverUrl;

    @Column(name = "like_rate", precision = 7, scale = 5)
    private BigDecimal likeRate;

    @Column(name = "share_rate", precision = 7, scale = 5)
    private BigDecimal shareRate;

    @Column(name = "comment_rate", precision = 7, scale = 5)
    private BigDecimal commentRate;

    @Column(name = "subscribe_count")
    private Integer subscribeCount;

    @Column(name = "unsubscribe_count")
    private Integer unsubscribeCount;

    @Column(name = "cover_ctr", precision = 5, scale = 2)
    private BigDecimal coverCtr;

    @Column(name = "homepage_visit_count")
    private Integer homepageVisitCount;

    @Column(name = "cost_yuan", precision = 10, scale = 4)
    private BigDecimal costYuan;

    @Column(name = "hook_template", length = 100)
    private String hookTemplate;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
