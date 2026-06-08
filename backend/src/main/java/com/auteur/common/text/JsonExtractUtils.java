package com.auteur.common.text;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class JsonExtractUtils {

    private JsonExtractUtils() {}

    /**
     * 在文本中找到第一个 '{' 起始的、大括号配平的 JSON 对象子串。
     * 走字符状态机,跳过字符串内的 '{' '}' 和转义。
     * 如果走完仍未配平(被 max_tokens 截断),退化到 lastIndexOf('}') 让 Jackson 给出真正的报错点。
     */
    public static String extractJsonObject(String s) {
        if (s == null) return null;
        int n = s.length();
        int start = -1;
        for (int i = 0; i < n; i++) {
            if (s.charAt(i) == '{') { start = i; break; }
        }
        if (start < 0) return null;
        int depth = 0;
        boolean inStr = false;
        boolean escape = false;
        for (int i = start; i < n; i++) {
            char c = s.charAt(i);
            if (escape) { escape = false; continue; }
            if (inStr) {
                if (c == '\\') { escape = true; continue; }
                if (c == '"') { inStr = false; }
                continue;
            }
            if (c == '"') { inStr = true; continue; }
            if (c == '{') { depth++; continue; }
            if (c == '}') {
                depth--;
                if (depth == 0) return s.substring(start, i + 1);
            }
        }
        int last = s.lastIndexOf('}');
        if (last > start) return s.substring(start, last + 1);
        return null;
    }

    /**
     * 在文本中找到第一个 '[' 起始的、括号配平的 JSON 数组子串。
     * 走字符状态机,跳过字符串内的方括号 / 转义字符。
     * 容忍上游被 max_tokens 截断:扫描时记录最近一次"顶层对象 '}' 闭合"位置,
     * 走到末尾仍未配平就回退到那里,补上 "]" 让 Jackson 至少解出 N-1 个对象。
     * 当发生回退时,以 logTag 作为日志前缀打印 warn,便于调用方追溯来源(e.g. "[摄影]")。
     */
    public static String extractJsonArray(String s, String logTag) {
        if (s == null) return null;
        int n = s.length();
        int start = -1;
        for (int i = 0; i < n; i++) {
            if (s.charAt(i) == '[') { start = i; break; }
        }
        if (start < 0) return null;

        int arrayDepth = 0;
        int braceDepth = 0;
        boolean inStr = false;
        boolean escape = false;
        int lastTopLevelObjectEnd = -1;

        for (int i = start; i < n; i++) {
            char c = s.charAt(i);
            if (escape) { escape = false; continue; }
            if (inStr) {
                if (c == '\\') { escape = true; continue; }
                if (c == '"') { inStr = false; }
                continue;
            }
            switch (c) {
                case '"' -> inStr = true;
                case '[' -> arrayDepth++;
                case '{' -> braceDepth++;
                case '}' -> {
                    braceDepth--;
                    if (arrayDepth == 1 && braceDepth == 0) {
                        lastTopLevelObjectEnd = i;
                    }
                }
                case ']' -> {
                    arrayDepth--;
                    if (arrayDepth == 0) return s.substring(start, i + 1);
                }
                default -> { /* skip */ }
            }
        }

        if (lastTopLevelObjectEnd > start) {
            log.warn("{} response truncated by upstream, salvaging up to last complete object (offset {}/{}, dropping partial trailing object)",
                    logTag == null ? "[json]" : logTag, lastTopLevelObjectEnd, n);
            return s.substring(start, lastTopLevelObjectEnd + 1) + "\n]";
        }
        return null;
    }
}
