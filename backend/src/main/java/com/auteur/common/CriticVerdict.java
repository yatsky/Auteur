package com.auteur.common;

/**
 * 自审 verdict 决断的纯函数。各 *CriticService Result 类型不同不便共用工厂,
 * 但"分数 vs 阈值 → PASS/REWRITE"的判断逻辑公共,集中在这里。
 */
public final class CriticVerdict {

    private CriticVerdict() {}

    public static String verdictFor(int score, int threshold) {
        return score >= threshold ? CriticConstants.VERDICT_PASS : CriticConstants.VERDICT_REWRITE;
    }
}
