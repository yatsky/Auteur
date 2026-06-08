package com.auteur.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * script ↔ bgm_track 的最终选定关系(单选,1:1)。
 * PK = script_id,upsert 语义。volume 默认 0.25,渲染时 ffmpeg 把 BGM 压到该音量再 ducking。
 */
@Entity
@Table(name = "script_bgm_choice")
@Getter
@Setter
public class ScriptBgmChoice {

    @Id
    @Column(name = "script_id")
    private Long scriptId;

    @Column(name = "bgm_track_id", nullable = false)
    private Long bgmTrackId;

    @Column(precision = 3, scale = 2, nullable = false)
    private BigDecimal volume = new BigDecimal("0.25");

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
