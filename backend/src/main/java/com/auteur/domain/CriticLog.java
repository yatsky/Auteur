package com.auteur.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * critic_log — 编剧/摄影自审结果(V49)。
 *
 * - role:SCRIPT / STORYBOARD,后续可扩 VOICE / COVER。
 * - score:0-100,Layer 1 硬规则失败固定 0,Layer 2 LLM 给的真实分。
 * - decision:PASS / REWRITE。REWRITE 后是否实际触发了重投,看 cost_log.operation = "*_rewrite"。
 *
 * 不挂 FK:critic_log 是审计日志,topic / script 删了我们也想保留历史记录。
 */
@Entity
@Table(name = "critic_log")
@Getter
@Setter
public class CriticLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String role;

    @Column(name = "topic_id")
    private Long topicId;

    @Column(name = "script_id")
    private Long scriptId;

    @Column(name = "content_type", length = 20)
    private String contentType;

    @Column(nullable = false)
    private Integer score;

    @Column(nullable = false, length = 16)
    private String decision;

    @Column(name = "issues_json", columnDefinition = "TEXT")
    private String issuesJson;

    @Column(columnDefinition = "TEXT")
    private String feedback;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
