package com.auteur.llm;

/**
 * LLM 输出 JSON 偶尔会在字符串值内夹未转义的 ASCII 双引号(如 "...字迹"今日方少愈"隐约..."),
 * 这会让 Jackson 把字符串提前闭合。仅做最小修复,不参与 JSON 语义校验。
 *
 * 兜底规则:处于字符串内时遇到 '"',看下一个非空白字符:
 *  - 直接是 } ] : → 字段闭合
 *  - 是 , → 进一步看 ',' 之后:是 } ] / 是 " 后接 : / 是 " 后接 , } ] → 闭合;否则嵌入
 *  - 其它字符(中文/letter/数字)→ 嵌入引号,转义
 */
public final class JsonHealer {

    private JsonHealer() {}

    public static String fixUnescapedAsciiQuotes(String s) {
        int n = s.length();
        StringBuilder out = new StringBuilder(n + 16);
        boolean inStr = false;
        boolean escape = false;
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (escape) { out.append(c); escape = false; continue; }
            if (inStr) {
                if (c == '\\') { out.append(c); escape = true; continue; }
                if (c == '"') {
                    if (isStringClose(s, i, n)) {
                        out.append(c); inStr = false;
                    } else {
                        out.append("\\\"");
                    }
                    continue;
                }
                out.append(c);
                continue;
            }
            if (c == '"') { out.append(c); inStr = true; continue; }
            out.append(c);
        }
        return out.toString();
    }

    /**
     * 判断当前 i 位置的 '"' 是否真的是字符串闭合。
     * 返回 true → 闭合;false → 嵌入引号,应转义。
     */
    private static boolean isStringClose(String s, int i, int n) {
        // 找下一个非空白字符
        int j = i + 1;
        while (j < n && Character.isWhitespace(s.charAt(j))) j++;
        if (j >= n) return true;
        char next = s.charAt(j);

        // 明确的结构符:闭合
        if (next == '}' || next == ']' || next == ':') return true;

        // ',' 需要进一步前瞻确认是结构边界还是嵌入
        if (next == ',') {
            int k = j + 1;
            while (k < n && Character.isWhitespace(s.charAt(k))) k++;
            if (k >= n) return true;
            char afterComma = s.charAt(k);
            // ',' 后是 '}' / ']' → 罕见但合法(末尾 trailing 逗号),闭合
            if (afterComma == '}' || afterComma == ']') return true;
            // ',' 后是 '"' → 看 '"' 是字段名(后跟 ':')还是数组元素(后跟 , } ])
            if (afterComma == '"') {
                int m = k + 1;
                while (m < n && s.charAt(m) != '"') {
                    if (s.charAt(m) == '\\' && m + 1 < n) m++;
                    m++;
                }
                if (m >= n) return true;  // 找不到闭合,保守闭合
                int p = m + 1;
                while (p < n && Character.isWhitespace(s.charAt(p))) p++;
                if (p >= n) return true;
                char afterStr = s.charAt(p);
                // ',' "..." ':' → 下一个字段名,闭合
                // ',' "..." ',' / '}' / ']' → 数组下一个元素,闭合
                if (afterStr == ':' || afterStr == ',' || afterStr == '}' || afterStr == ']') {
                    return true;
                }
                // 否则不是结构边界,嵌入引号
                return false;
            }
            // ',' 后是中文 / 数字 / letter → 嵌入引号
            return false;
        }

        // 下一个字符是中文 / letter / 数字 → 嵌入引号
        return false;
    }
}

