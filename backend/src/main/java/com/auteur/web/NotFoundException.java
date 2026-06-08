package com.auteur.web;

/**
 * 业务"找不到资源"语义 → 404。
 *
 * 替代之前各处 service 用 IllegalArgumentException("X not found: id") 表达 not-found 的做法
 * （那种写法被全局 handler 映射到 400，HTTP 语义错）。
 *
 * 真正的"参数非法"（如 hex 串长度奇数、weekCode 格式错）继续抛 IllegalArgumentException。
 */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
