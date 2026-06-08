package com.auteur.storyboard;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class StoryboardShotDraft {

    @JsonProperty("shot_index")
    private Integer shotIndex;

    @JsonProperty("section_code")
    private String sectionCode;

    @JsonProperty("time_range")
    private String timeRange;

    @JsonProperty("duration_seconds")
    private BigDecimal durationSeconds;

    @JsonProperty("shot_type")
    private String shotType;

    @JsonProperty("style_tag")
    private String styleTag;

    @JsonProperty("prompt_zh")
    private String promptZh;

    @JsonProperty("prompt_en")
    private String promptEn;

    @JsonProperty("negative_prompt")
    private String negativePrompt;

    /**
     * 字面锚定。LLM 必须从 script.full_text 摘一个 6-15 字的连续短语,
     * StoryboardService 会 validate 它是否在 fullText 里命中。
     */
    @JsonProperty("anchor_text")
    private String anchorText;

    /**
     * 该 shot 对应的 SRT cue 序号区间(1 起,inclusive)。
     * 所有 shot 的区间必须严格升序、相邻不重叠不缺失,合并起来覆盖 [1, cuesSize]。
     * PRECISE_BY_CUE 模式下强制要求,其它模式可空。
     */
    @JsonProperty("anchor_cue_indices")
    private java.util.List<Integer> anchorCueIndices;
}
