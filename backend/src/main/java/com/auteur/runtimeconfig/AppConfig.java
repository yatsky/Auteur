package com.auteur.runtimeconfig;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * UI 可编辑的运行时配置(KV 表)。
 *
 * 跟 application-local.yml 配合工作:RuntimeConfig 优先读本表,空时回落 yml 默认值。
 * 新用户的 yml 只需配 spring.datasource.*,其它全部在前端「系统设置」页面填。
 *
 * is_secret=true 的字段(api-key 等)REST 响应里会被遮掉,只保留是否已配置标志。
 */
@Entity
@Table(name = "app_config")
@Getter
@Setter
public class AppConfig {

    @Id
    @Column(name = "config_key", length = 128)
    private String configKey;

    /** 实际值。NULL/空 = 未配置,走 yml 兜底。 */
    @Column(name = "config_value", columnDefinition = "TEXT")
    @JsonIgnore  // 默认不直出原值;controller 自己决定 mask 还是直出
    private String configValue;

    @Column(name = "description", length = 512)
    private String description;

    @Column(name = "is_secret", nullable = false)
    private boolean secret;

    /** 分组,UI 用:llm / tos / voice / bgm / extension */
    @Column(name = "category", nullable = false, length = 64)
    private String category;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
