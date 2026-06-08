package com.auteur.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Jamendo 曲库缓存。按 jamendo_id 去重(UK),local_path 是下载完成后的本地 mp3 路径,
 * 渲染时 ffmpeg 直接读 local_path,Jamendo audio_url 仅作下载源。
 */
@Entity
@Table(name = "bgm_track")
@Getter
@Setter
public class BgmTrack {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "jamendo_id", nullable = false, unique = true)
    private Long jamendoId;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "artist_name", length = 200)
    private String artistName;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @Column(name = "audio_url", nullable = false, length = 500)
    private String audioUrl;

    @Column(name = "preview_url", length = 500)
    private String previewUrl;

    @Column(name = "album_image_url", length = 500)
    private String albumImageUrl;

    @Column(name = "license_type", length = 40)
    private String licenseType;

    @Column(name = "mood_tag", length = 40)
    private String moodTag;

    @Column(name = "local_path", length = 500)
    private String localPath;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
