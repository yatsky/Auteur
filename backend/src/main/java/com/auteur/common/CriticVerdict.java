package com.auteur.common;

/**
 * 自审 verdict 决断的纯函数。
 *
 * 各 *CriticService 的 Result 类型不同(ScriptCriticResult / StoryboardCriticResult …),
 * 不便共用 Result 工厂;但"分数 vs 阈值 → PASS/REWRITE"的判断逻辑是公共的,集中在这里。
 */
public final class CriticVerdict {

    private CriticVerdict() {}

    /**
     * 按分数和阈值决定 verdict 字符串。
     *
     * @param score     LLM 给出的分(0..100)
     * @param threshold preset 配置或默认阈值
     * @return {@link CriticConstants#VERDICT_PASS} 或 {@link CriticConstants#VERDICT_REWRITE}
     */
    public static String verdictFor(int score, int threshold) {
        return score >= threshold ? CriticConstants.VERDICT_PASS : CriticConstants.VERDICT_REWRITE;
    }
}
