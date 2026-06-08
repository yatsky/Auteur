package com.auteur.storyboard;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * 摄影自审产物。当前只用硬规则(无 LLM),所以 score 只有 0/100 两种状态。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class StoryboardCriticResult {

    private Integer score;

    /** PASS / REWRITE。失败放行后由调用方降级。 */
    private String decision;

    /** 扣分点,展示用。 */
    private List<String> issues;

    /** 给"重写"用的反馈段落,会拼到原 user prompt 末尾。 */
    @JsonProperty("feedback_for_rewrite")
    private String feedbackForRewrite;
}
