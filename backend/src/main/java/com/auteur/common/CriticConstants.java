package com.auteur.common;

/**
 * 自审(critic)流程的共用常量。
 *
 * 编剧/摄影/事实核查等多个 *CriticService 都按相同的 verdict 字符串契约写入
 * critic_log.decision 与下游决策分支,集中放一处避免拼写漂移。
 */
public final class CriticConstants {

    private CriticConstants() {}

    /** 默认 PASS 阈值(score ≥ 该值视为通过)。preset 中可覆盖。 */
    public static final int DEFAULT_PASS_THRESHOLD = 80;

    /** 通过。 */
    public static final String VERDICT_PASS = "PASS";

    /** 需要重写。 */
    public static final String VERDICT_REWRITE = "REWRITE";
}
