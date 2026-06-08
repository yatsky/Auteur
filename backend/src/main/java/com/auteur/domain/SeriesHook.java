package com.auteur.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 追更钩子 —— 「本集 E 段埋的下集种子」实体。
 * 设计要点:
 *  - {@code fromTopicId} 不可空;{@code toTopicId} 在「兑现」时才填。
 *  - {@code strength} = STRONG 才会进未兑现列表(GET ?status=unresolved)。
 *  - {@code fromScriptId} 是观测冗余字段,便于反查到底是哪条 script 的 E 段抽出来的。
 */
@Entity
@Table(name = "series_hook")
@Getter
@Setter
public class SeriesHook {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "from_topic_id", nullable = false)
    private Long fromTopicId;

    @Column(name = "to_topic_id")
    private Long toTopicId;

    @Column(name = "hook_text", nullable = false, columnDefinition = "TEXT")
    private String hookText;

    @Column(name = "next_episode_hint", length = 200)
    private String nextEpisodeHint;

    /** STRONG / WEAK,默认 WEAK 防止回填漏字段污染列表。 */
    @Column(nullable = false, length = 8)
    private String strength = "WEAK";

    @Column(name = "suggested_title", length = 255)
    private String suggestedTitle;

    @Column(name = "suggested_dynasty", length = 40)
    private String suggestedDynasty;

    @Column(name = "from_script_id")
    private Long fromScriptId;

    /** 软删时间;NULL=未处理。banner 查询会排除非 NULL。 */
    @Column(name = "dismissed_at")
    private LocalDateTime dismissedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
