package com.auteur.preset;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Preset 修改快照(V51 引入)。每次 PresetService.saveAsNewVersion 写一行,
 * snapshot_json 是当时整个 preset 行的 JSON 序列化,可一键回滚。
 *
 * 不挂 ON DELETE CASCADE 的 Java 端联动 — 删 preset 时数据库 FK 会级联清快照(见 V51 migration)。
 */
@Entity
@Table(name = "preset_version")
@Getter
@Setter
public class PresetVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "preset_id", nullable = false)
    private Long presetId;

    @Column(nullable = false)
    private Integer version;

    @Column(name = "snapshot_json", columnDefinition = "TEXT", nullable = false)
    private String snapshotJson;

    @Column(length = 255)
    private String comment;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
