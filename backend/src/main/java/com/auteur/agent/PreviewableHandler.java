package com.auteur.agent;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 增强版 ToolHandler:写入业务内容前给前端展示一份 diff(before/after)。
 *
 * 注:preview 应该是只读的(不动 DB)。execute 才是真写。
 */
public interface PreviewableHandler extends ToolHandler {

    /** 写工具默认 risk = WRITE,子类可改为 ACTION。 */
    @Override
    default Risk risk() { return Risk.WRITE; }

    /**
     * 计算 before/after。读 DB 看当前值,跟 args 里的新值比。
     * 抛异常 = 直接落库 ERROR,不进审批环节(让 LLM 自纠正)。
     */
    Preview preview(JsonNode args);

    /**
     * before/after 是字符串(对结构化对象,序列化成 yaml/json 文本再 diff)。
     */
    record Preview(String fieldName, String before, String after, String summary) {}
}
