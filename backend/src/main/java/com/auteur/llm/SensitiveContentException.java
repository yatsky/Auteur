package com.auteur.llm;

/**
 * 上游模型把 prompt 判定为含敏感内容（如豆包 InputTextSensitiveContentDetected）。
 *
 * 与普通 4xx / 5xx 区分开：同一个 prompt 重试 / 切 fallback 模型都救不了，
 * 调用方要么改写 prompt 再试，要么把状态丢给用户人工处理。
 *
 * cause 持有原始 HttpClientErrorException，便于上层取 raw body 写日志。
 */
public class SensitiveContentException extends RuntimeException {
    public SensitiveContentException(String message, Throwable cause) {
        super(message, cause);
    }
}
