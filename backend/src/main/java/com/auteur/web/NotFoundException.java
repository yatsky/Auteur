package com.auteur.web;

/** 业务"找不到资源"语义 → 404。真正的"参数非法"继续抛 IllegalArgumentException。 */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
