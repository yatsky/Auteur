package com.auteur.llm;

import com.auteur.runtimeconfig.RuntimeConfig;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 流水线步骤模型注册表 — 每个 step 对应 app_config 里 auteur.model.&lt;step&gt; 一行,
 * 由前端「AI 模型」页面统一编辑,V8__model_config.sql 注入默认 seed。
 *
 * 项目内严禁在代码里硬编码模型 ID(prompts/*.yaml 的 model 字段、private static final
 * String MODEL = "..."),所有 LLM/图像/Agent 模型必须经此服务读取。
 *
 * 两个入口:
 *   modelFor(step)               — 必填,DB 缺值抛 IllegalStateException(给 BgmMoodTagger 这类
 *                                  纯系统级用,没有 preset 概念)。
 *   modelOrDefault(preset, step) — preset 已指定就尊重 preset(传入非空 string),空才回落到
 *                                  本注册表。给 brainstorm/script/storyboard/critic/image 这类
 *                                  preset 可覆盖的步骤用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelRegistry {

    public static final String KEY_PREFIX = "auteur.model.";

    /** 与 V8__model_config.sql 注册的 key 列表对齐;@PostConstruct 启动时自检用。 */
    private static final List<String> KNOWN_STEPS = List.of(
            "brainstorm", "script", "script_critic", "storyboard",
            "bgm_mood", "hook_extract",
            "factcheck", "factcheck_verify", "factcheck_apply",
            "video_attribution", "weekly_review",
            "shot_prompt_refine", "shot_prompt_desensitize", "image_audit",
            "image_primary", "image_fallback",
            "agent_default"
    );

    private final RuntimeConfig runtimeConfig;

    @PostConstruct
    public void selfCheck() {
        int missing = 0;
        for (String step : KNOWN_STEPS) {
            if (runtimeConfig.get(KEY_PREFIX + step).isBlank()) {
                log.warn("[ModelRegistry] step '{}' 在 app_config 表里没有有效值,对应流水线会抛 IllegalStateException;"
                        + "请在「AI 模型」页面填写或检查 V8 迁移是否成功执行", step);
                missing++;
            }
        }
        if (missing == 0) {
            log.info("[ModelRegistry] 启动自检通过: {} 个 step 全部已配置", KNOWN_STEPS.size());
        }
    }

    /**
     * 必填读取。step 没在 KNOWN_STEPS 里也允许(便于未来加新 step 时不必改本类),
     * 但 DB 里必须有值,否则抛异常引导用户去 UI 配置。
     */
    public String modelFor(String step) {
        String value = runtimeConfig.get(KEY_PREFIX + step);
        if (value.isBlank()) {
            throw new IllegalStateException(
                    "Model not configured for step '" + step + "'. "
                            + "请到前端「配置 → AI 模型」页面设置 " + KEY_PREFIX + step);
        }
        return value;
    }

    /** preset/caller 已经给了非空模型就直接用;空才回落到注册表。 */
    public String modelOrDefault(String preferred, String step) {
        if (preferred != null && !preferred.isBlank()) return preferred;
        return modelFor(step);
    }
}
