package com.auteur.preset;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonRawValue;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 视频形态预设。
 *
 * 一行 preset 完整描述"一种视频形态的所有配方":
 *   - 输入字段(input_schema_json)
 *   - 各 stage 的 prompt(brainstorm/script/critic/storyboard/assistant_director/bgm_mood)
 *   - 美术配置(模型/锁脸/参考图/风格后缀/负面词)
 *   - 录音配置(可空 = 无旁白)
 *   - 合成配置(画幅/水印/BGM/hook 段/Remotion composition)
 *
 * JSON 列用 String 存,配 @JsonRawValue 让 REST 直接吐 JSON,无需 jackson 二次序列化。
 */
@Entity
@Table(name = "preset")
@Getter
@Setter
// 兜住 preset_version 历史快照 JSON 里残留的旧字段(visibility/ownerName,V18 已 drop),rollback 不破。
@JsonIgnoreProperties(ignoreUnknown = true)
public class Preset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 内部 key,如 'lifecopy' / 'freeform'。全局唯一,routing 用。 */
    @Column(nullable = false, unique = true, length = 64)
    private String name;

    @Column(name = "display_name", length = 128)
    private String displayName;

    @Column(columnDefinition = "TEXT")
    private String description;

    @JsonRawValue
    @JsonDeserialize(using = JsonRawStringDeserializer.class)
    @Column(name = "input_schema_json", columnDefinition = "TEXT")
    private String inputSchemaJson;

    /**
     * 默认导演笔记模板。HotPromoteService 在 promote 新建 topic 时把它 copy 到 topic.director_note。
     * NULL = 该 preset 不带默认模板,topic 的 director_note 保持 NULL(下游降级)。
     */
    @JsonRawValue
    @JsonDeserialize(using = JsonRawStringDeserializer.class)
    @Column(name = "default_director_note_json", columnDefinition = "JSON")
    private String defaultDirectorNoteJson;

    @Column(name = "brainstorm_prompt_yaml", columnDefinition = "TEXT")
    private String brainstormPromptYaml;

    @Column(name = "script_prompt_yaml", columnDefinition = "TEXT", nullable = false)
    private String scriptPromptYaml;

    @Column(name = "script_critic_prompt_yaml", columnDefinition = "TEXT")
    private String scriptCriticPromptYaml;

    @Column(name = "script_critic_threshold")
    private Integer scriptCriticThreshold;

    @Column(name = "storyboard_prompt_yaml", columnDefinition = "TEXT", nullable = false)
    private String storyboardPromptYaml;

    /** 'PRECISE_BY_CUE' | 'FREE'。PRECISE_BY_CUE 强制校验 anchor_cue_indices 严格连续覆盖。 */
    @Column(name = "storyboard_mode", nullable = false, length = 32)
    private String storyboardMode = "FREE";

    @Column(name = "assistant_director_prompt_yaml", columnDefinition = "TEXT")
    private String assistantDirectorPromptYaml;

    @Column(name = "bgm_mood_prompt_yaml", columnDefinition = "TEXT")
    private String bgmMoodPromptYaml;

    @JsonRawValue
    @JsonDeserialize(using = JsonRawStringDeserializer.class)
    @Column(name = "image_config_json", columnDefinition = "TEXT")
    private String imageConfigJson;

    @JsonRawValue
    @JsonDeserialize(using = JsonRawStringDeserializer.class)
    @Column(name = "voice_config_json", columnDefinition = "TEXT")
    private String voiceConfigJson;

    /**
     * 热点订阅配置 JSON — 前端「热点订阅」tab 落盘。
     * 结构: { enabled, sourceIds: number[], includeKeywords: string[], excludeKeywords: string[],
     *        maxAgeHours: int, minPopularity: double }
     * 为 null 或 enabled=false → 该预设 brainstorm 时不拉热点。
     */
    @JsonRawValue
    @JsonDeserialize(using = JsonRawStringDeserializer.class)
    @Column(name = "hot_source_config_json", columnDefinition = "TEXT")
    private String hotSourceConfigJson;

    @Column(name = "bgm_enabled", nullable = false)
    private boolean bgmEnabled;

    /**
     * true = BGM 锁定模式:用户不让在 BgmPicker 里选曲,后端按 preset 兜底逻辑出曲。
     * 仅在 bgm_enabled=true 时有意义;bgm_enabled=false 时整段无 BGM,本字段忽略。
     */
    @Column(name = "bgm_locked", nullable = false)
    private boolean bgmLocked;

    @Column(name = "composition_id", nullable = false, length = 64)
    private String compositionId;

    @Column(name = "format_width", nullable = false)
    private Integer formatWidth;

    @Column(name = "format_height", nullable = false)
    private Integer formatHeight;

    /** null = 不加水印。 */
    @Column(name = "watermark_text", length = 128)
    private String watermarkText;

    @Column(name = "hook_segment_enabled", nullable = false)
    private boolean hookSegmentEnabled;

    /** Hook 段翻书音效 URL,配合 hookSegmentEnabled。空 = 不放音效。 */
    @Column(name = "hook_page_flip_sound_url", length = 500)
    private String hookPageFlipSoundUrl;

    /** Storyboard critic 要求的极特写最少镜数;0 = 不检查。 */
    @Column(name = "min_extreme_closeup", nullable = false)
    private int minExtremeCloseup = 0;

    /**
     * 章节边界黑帧时长(秒)。Remotion StoryComposition 在 sectionCode 切换处插入此长度的黑帧;
     * 前后各 0.1s 渐变(包含在该值内)。与字幕 cue 重叠时自动回退白闪 FlashHint。
     * 默认 0.30 沿用旧常量;需要更明显黑过渡的预设调到 0.6-0.8。
     */
    @Column(name = "chapter_break_sec", nullable = false)
    private double chapterBreakSec = 0.30;

    @Column(name = "current_version", nullable = false)
    private Integer currentVersion = 1;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
