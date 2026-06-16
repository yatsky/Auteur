package com.auteur.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "video_asset")
@Getter
@Setter
public class VideoAsset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "script_id", nullable = false)
    private Long scriptId;

    @Column(name = "voice_asset_id")
    private Long voiceAssetId;

    @Column(name = "video_url", length = 500)
    private String videoUrl;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @Column
    private Integer width;

    @Column
    private Integer height;

    @Column(length = 20)
    private String format;

    @Column(name = "shot_count")
    private Integer shotCount;

    @Column(name = "cost_yuan", precision = 8, scale = 4)
    private BigDecimal costYuan;

    @Column(name = "is_final", nullable = false)
    private Boolean isFinal = false;

    /** 渲染时采用的 bgm_track,NULL = 无 BGM。不建 FK 让历史视频在 bgm_track 被清后仍存。 */
    @Column(name = "bgm_track_id")
    private Long bgmTrackId;

    @Column(name = "bgm_volume", precision = 3, scale = 2)
    private BigDecimal bgmVolume;

    @Column(name = "timing_strategy", length = 30)
    private String timingStrategy;

    @Column(name = "timing_note", length = 500)
    private String timingNote;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
