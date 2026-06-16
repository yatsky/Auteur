package com.auteur.storyboard;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/** 摄影自审产物。当前只用硬规则(无 LLM),score 只有 0/100 两种状态。 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class StoryboardCriticResult {

    private Integer score;

    /** PASS / REWRITE。 */
    private String decision;

    private List<String> issues;

    @JsonProperty("feedback_for_rewrite")
    private String feedbackForRewrite;
}
