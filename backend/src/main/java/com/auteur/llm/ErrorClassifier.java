package com.auteur.llm;

import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.net.SocketTimeoutException;

/**
 * 把上游 HTTP/IO 异常归类成 retry_rule.error_type 里的一档:
 * limit / timeout / sensitive / 4xx / network / json。
 */
public final class ErrorClassifier {

    private ErrorClassifier() {}

    public static String classify(Throwable t) {
        if (t == null) return "network";

        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof HttpClientErrorException ce) {
                int code = ce.getStatusCode().value();
                if (code == 429) return "limit";
                if (code == 408) return "timeout";
                // 上游内容安全审查 — 同一个 prompt 重试无意义,单独归类供调用方决定改写或跳过
                String body = ce.getResponseBodyAsString();
                if (body != null && containsSensitiveMarker(body)) return "sensitive";
                return "4xx";
            }
            // 5xx 通常瞬时,归 network 用更多重试
            if (cur instanceof HttpServerErrorException) {
                return "network";
            }
            if (cur instanceof SocketTimeoutException) return "timeout";
            if (cur instanceof ResourceAccessException) {
                Throwable cause = cur.getCause();
                if (cause instanceof SocketTimeoutException) return "timeout";
                return "network";
            }
            if (cur instanceof IOException) return "network";

            cur = cur.getCause();
        }

        String msg = t.getMessage();
        if (msg != null) {
            String lower = msg.toLowerCase();
            if (containsSensitiveMarker(msg)) return "sensitive";
            if (lower.contains("json") || lower.contains("parse") || lower.contains("unexpected token")) {
                return "json";
            }
            if (lower.contains("timeout")) return "timeout";
            if (lower.contains("rate limit") || lower.contains("429")) return "limit";
            if (lower.contains("empty choices") || lower.contains("empty data")
                    || lower.contains("blank url")) {
                return "json";
            }
        }
        return "network";
    }

    /** 上游内容安全审查 marker。 */
    private static boolean containsSensitiveMarker(String s) {
        if (s == null) return false;
        String lower = s.toLowerCase();
        return lower.contains("inputtextsensitivecontentdetected")
                || lower.contains("sensitive_content")
                || lower.contains("sensitive content")
                || lower.contains("safety_violations")
                || lower.contains("moderation_blocked")
                || lower.contains("inappropriate content")
                || lower.contains("敏感");
    }
}
