package com.auteur.common.text;

public final class TextUtils {

    private TextUtils() {}

    public static String stripCodeFence(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.startsWith("```")) {
            int firstNl = t.indexOf('\n');
            if (firstNl > 0) t = t.substring(firstNl + 1);
            if (t.endsWith("```")) t = t.substring(0, t.length() - 3);
        }
        return t;
    }

    public static String safe(String s) {
        return s == null ? "" : s;
    }

    public static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    public static String trimToMax(String s, int max) {
        if (s == null) return null;
        String x = s.trim();
        if (x.isEmpty()) return null;
        return x.length() <= max ? x : x.substring(0, max);
    }

    public static String preview(String s) {
        if (s == null) return "null";
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }

    /** preview 重载,自定义截断长度。结尾追加 "..."(已含在判断里:超过才追加)。 */
    public static String preview(String s, int max) {
        if (s == null) return "null";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
