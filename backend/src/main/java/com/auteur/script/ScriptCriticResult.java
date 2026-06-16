package com.auteur.script;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * 编剧自审产物。Layer 1(硬规则)和 Layer 2(LLM)合并后的统一结果。
 * decision 决定 ScriptService 是否触发"带反馈重投"。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ScriptCriticResult {

    /** 0-100 整数。Layer 1 失败固定给 0;preset.script_critic_prompt_yaml 为空 → 直接 PASS 100。 */
    private Integer score;

    /** PASS / REWRITE。失败放行后由调用方降级。 */
    private String decision;

    private List<String> issues;

    /** 给"重写"用的反馈段落,会拼到原 user prompt 末尾。 */
    @JsonProperty("feedback_for_rewrite")
    private String feedbackForRewrite;
}
