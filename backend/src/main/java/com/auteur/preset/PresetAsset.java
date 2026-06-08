package com.auteur.preset;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Preset 关联的二进制资源(V51 引入)。典型用例:锁脸预设的 P0.png 主角参考图。
 *
 * local_path 存相对启动 cwd 的路径,典型:
 *   ./storage/preset_assets/private/{preset.name}/P0.png
 *
 * 这个目录默认进 .gitignore,所以"私有预设的参考图"不会被 push 上去。
 */
@Entity
@Table(name = "preset_asset")
@Getter
@Setter
public class PresetAsset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "preset_id", nullable = false)
    private Long presetId;

    /** 'reference_image' | 'sound_effect' | ... */
    @Column(nullable = false, length = 32)
    private String kind;

    @Column(name = "local_path", nullable = false, length = 512)
    private String localPath;

    @CreationTimestamp
    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private LocalDateTime uploadedAt;
}
