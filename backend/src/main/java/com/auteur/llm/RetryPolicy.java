package com.auteur.llm;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 写死的重试策略，按 ErrorClassifier 的 errorType 分档。
 *
 * 历史上由 retry_rule 表驱动，做减法之后改硬编码：
 *   limit / timeout / network → 指数退避，最多 3 次
 *   json                       → 线性，最多 2 次
 *   sensitive / 4xx            → 不重试（同 prompt/同请求重试无意义）
 */
public final class RetryPolicy {

    private RetryPolicy() {}

    public record Decision(boolean retry, long sleepMs) {}

    public static Decision decide(String errorType, int attempt) {
        return switch (errorType == null ? "network" : errorType) {
            case "limit"    -> exponential(attempt, 3, 10_000L, 60_000L);
            case "timeout"  -> exponential(attempt, 1, 2_000L, 5_000L);  // gpt-image-2 慢，最多 1 次重试
            case "network"  -> exponential(attempt, 3, 2_000L, 30_000L);
            case "json"     -> linear(attempt, 2, 1_000L, 5_000L);
            case "sensitive", "4xx" -> new Decision(false, 0L);
            default         -> exponential(attempt, 3, 2_000L, 30_000L);
        };
    }

    private static Decision exponential(int attempt, int maxRetries, long baseMs, long capMs) {
        if (attempt > maxRetries) return new Decision(false, 0L);
        long raw = baseMs * (1L << Math.min(attempt - 1, 16));
        if (raw > capMs) raw = capMs;
        return new Decision(true, jitter(raw, 20));
    }

    private static Decision linear(int attempt, int maxRetries, long baseMs, long capMs) {
        if (attempt > maxRetries) return new Decision(false, 0L);
        long raw = baseMs * Math.max(1, attempt);
        if (raw > capMs) raw = capMs;
        return new Decision(true, jitter(raw, 20));
    }

    private static long jitter(long ms, int pct) {
        if (pct <= 0 || ms <= 0) return ms;
        double factor = 1.0 + (ThreadLocalRandom.current().nextDouble() * 2 - 1) * (pct / 100.0);
        return Math.max(0L, (long) (ms * factor));
    }
}
