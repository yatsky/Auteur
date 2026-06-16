package com.auteur.preset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * 把 topic.preset_input_json 平展成 prompt 变量,供 preset 的 prompt yaml 用 {{key}} 引用:
 *   - 顶层每个字符串/数字/布尔字段 → vars.put(key, value.asText())
 *   - 数组字段渲染成多行字符串(每行 "  - <element>")
 *   - 嵌套对象整体序列化塞进去
 *   - null 值 → 空串,避免 mustache 模板报错
 *
 * 解析失败 swallow + warn,不抛出。
 */
@Slf4j
public final class PresetInputInjector {

    private PresetInputInjector() {}

    public static void inject(ObjectMapper objectMapper,
                              Map<String, Object> vars,
                              String presetInputJson,
                              Long topicId,
                              String logTag) {
        if (presetInputJson == null || presetInputJson.isBlank()) return;
        try {
            JsonNode root = objectMapper.readTree(presetInputJson);
            if (!root.isObject()) {
                log.warn("{} preset_input_json 不是对象 topicId={}", logTag, topicId);
                return;
            }
            root.fields().forEachRemaining(entry -> {
                String key = entry.getKey();
                JsonNode v = entry.getValue();
                if (v == null || v.isNull()) {
                    vars.put(key, "");
                } else if (v.isTextual() || v.isNumber() || v.isBoolean()) {
                    vars.put(key, v.asText());
                } else if (v.isArray()) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < v.size(); i++) {
                        if (i > 0) sb.append('\n');
                        sb.append("  - ").append(v.get(i).toString());
                    }
                    vars.put(key, sb.toString());
                } else {
                    vars.put(key, v.toString());
                }
            });
        } catch (Exception e) {
            log.warn("{} preset_input_json 解析失败 topicId={}: {}", logTag, topicId, e.toString());
        }
    }
}
