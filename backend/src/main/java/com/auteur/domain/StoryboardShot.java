package com.auteur.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "storyboard_shot")
@Getter
@Setter
public class StoryboardShot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "script_id", nullable = false)
    private Long scriptId;

    @Column(name = "shot_index", nullable = false)
    private Integer shotIndex;

    /** 锚回 script_section.section_code,视频组装时用 SRT 时间码做精确对齐;null 时回落均匀缩放。 */
    @Column(name = "section_code", length = 8)
    private String sectionCode;

    @Column(name = "time_range", length = 20)
    private String timeRange;

    @Column(name = "duration_seconds", precision = 5, scale = 2)
    private BigDecimal durationSeconds;

    @Column(name = "prompt_zh", columnDefinition = "TEXT")
    private String promptZh;

    @Column(name = "prompt_en", columnDefinition = "TEXT")
    private String promptEn;

    @Column(name = "negative_prompt", columnDefinition = "TEXT")
    private String negativePrompt;

    @Column(name = "style_tag", length = 40)
    private String styleTag;

    @Column(name = "shot_type", length = 20)
    private String shotType;

    /**
     * LLM 在分镜时标记的脚本字面锚定短语(从 script.full_text 摘的连续 6-15 字)。
     * StoryboardService 校验它必须能在 fullText 里找到子串,找不到则 anchor_match=false。
     */
    @Column(name = "anchor_text", length = 120)
    private String anchorText;

    /** anchor_text 是否在 script.full_text 中命中。null/true = 未校验 / 命中。 */
    @Column(name = "anchor_match", nullable = false)
    private Boolean anchorMatch = Boolean.TRUE;

    /** 该 shot 对应的 SRT cue 起始序号(1 起,inclusive)。PRECISE_BY_CUE 时填,否则 NULL。 */
    @Column(name = "anchor_cue_start")
    private Integer anchorCueStart;

    /** 该 shot 对应的 SRT cue 结束序号(1 起,inclusive)。 */
    @Column(name = "anchor_cue_end")
    private Integer anchorCueEnd;

    @Column(length = 40)
    private String seed;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
