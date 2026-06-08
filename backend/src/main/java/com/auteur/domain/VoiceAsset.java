package com.auteur.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "voice_asset")
@Getter
@Setter
public class VoiceAsset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "script_id", nullable = false)
    private Long scriptId;

    @Column(length = 60)
    private String model;

    @Column(name = "voice_label", length = 80)
    private String voiceLabel;

    @Column(precision = 3, scale = 1, nullable = false)
    private BigDecimal speed = BigDecimal.valueOf(1.0);

    @Column(nullable = false)
    private Integer pitch = 0;

    @Column(name = "subtitle_style", length = 20, nullable = false)
    private String subtitleStyle = "standard";

    @Column(name = "audio_url", length = 500)
    private String audioUrl;

    @Column(name = "subtitle_url", length = 500)
    private String subtitleUrl;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @Column(name = "cost_yuan", precision = 8, scale = 4)
    private BigDecimal costYuan;

    @Column(name = "is_final", nullable = false)
    private Boolean isFinal = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
