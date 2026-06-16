package com.auteur.common;

/**
 * 自审(critic)流程的共用常量。多个 *CriticService 共用 verdict 字符串契约,集中放一处避免拼写漂移。
 */
public final class CriticConstants {

    private CriticConstants() {}

    /** 默认 PASS 阈值(score ≥ 该值视为通过)。preset 中可覆盖。 */
    public static final int DEFAULT_PASS_THRESHOLD = 80;

    public static final String VERDICT_PASS = "PASS";

    public static final String VERDICT_REWRITE = "REWRITE";
}
