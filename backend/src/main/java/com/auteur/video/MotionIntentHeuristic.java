package com.auteur.video;

import java.util.Random;

/**
 * Motion intent 启发式:把 storyboard 阶段 LLM 标记的 anchor_text 用关键词字典映射到运镜情绪池。
 *
 * 5 种 intent,优先级降序:CLIMAX > REVEAL > CALM > TRANSITION > NEUTRAL。
 * 每个池都给 ≥ 2 个 motion,这样"相邻不同"约束总能在池内被满足。
 */
public final class MotionIntentHeuristic {

    private MotionIntentHeuristic() {}

    private static final String[] CLIMAX_POOL = {"in", "panRight"};
    private static final String[] REVEAL_POOL = {"out", "static"};
    private static final String[] CALM_POOL = {"static", "out"};
    private static final String[] TRANSITION_POOL = {"panLeft", "panRight"};
    private static final String[] NEUTRAL_POOL = {"in", "out", "panLeft", "panRight", "static"};

    private static final String[] CLIMAX_KW = {
            "突然", "忽然", "刹那", "瞬间", "顿时",
            "惊", "震", "怒", "喊", "喝", "嚎", "吼",
            "爆", "迸", "喷", "斩", "杀", "刺",
            "死", "亡", "崩", "塌", "毁", "骇", "恐"
    };
    private static final String[] REVEAL_KW = {
            "原来", "却", "竟", "不料", "谁知",
            "终于", "最终", "显", "露", "现",
            "揭", "悟", "明白"
    };
    private static final String[] CALM_KW = {
            "静", "默", "凝", "望", "思", "忆",
            "远", "寂", "独", "孤", "出神", "发呆", "沉吟"
    };
    private static final String[] TRANSITION_KW = {
            "然后", "接着", "此后", "随后", "于是", "之后",
            "转眼", "一晃", "不久", "过了", "几年", "十年", "数日", "月余"
    };

    /** 根据 anchorText 选出 motion 候选池;空 / 都不命中时回到全池。 */
    public static String[] poolFor(String anchorText) {
        if (anchorText == null || anchorText.isBlank()) return NEUTRAL_POOL;
        if (containsAny(anchorText, CLIMAX_KW)) return CLIMAX_POOL;
        if (containsAny(anchorText, REVEAL_KW)) return REVEAL_POOL;
        if (containsAny(anchorText, CALM_KW)) return CALM_POOL;
        if (containsAny(anchorText, TRANSITION_KW)) return TRANSITION_POOL;
        return NEUTRAL_POOL;
    }

    /** 在 anchorText 对应的池里抽一个 motion,并避免与 prevMotion 相同。 */
    public static String pickMotion(String anchorText, String prevMotion, Random rnd) {
        String[] pool = poolFor(anchorText);
        if (prevMotion == null) return pool[rnd.nextInt(pool.length)];
        int kept = 0;
        String[] filtered = new String[pool.length];
        for (String m : pool) {
            if (!m.equals(prevMotion)) filtered[kept++] = m;
        }
        if (kept == 0) return pool[rnd.nextInt(pool.length)];
        return filtered[rnd.nextInt(kept)];
    }

    private static boolean containsAny(String text, String[] keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) return true;
        }
        return false;
    }
}
