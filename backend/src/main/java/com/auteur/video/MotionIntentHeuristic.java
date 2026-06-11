package com.auteur.video;

import java.util.Random;

/**
 * Motion intent 启发式:把 storyboard 阶段 LLM 标记的 anchor_text(脚本字面锚定短语,
 * 6-15 字)用关键词字典映射到运镜情绪池,让画面节奏跟着剧情走而不是纯随机。
 *
 * 5 种 intent,优先级降序(同时命中多类时按此顺序选):
 *   CLIMAX     突然/惊/震/喊/斩/死 → 推近+横扫,聚焦戏剧张力
 *   REVEAL     原来/竟/终于/露    → 拉远+凝固,揭晓的画面留白
 *   CALM       静/默/凝/望/思/忆  → 静止/拉远,沉静凝视不被运动打断
 *   TRANSITION 然后/此后/转眼/十年 → 横向平移,时间/空间推进
 *   NEUTRAL    都没命中           → 全 5 种池随机
 *
 * 每个池都给 ≥ 2 个 motion,这样 buildDemoPlan 的"相邻不同"约束(P0 反 AI 检测的关键)
 * 总能在池内被满足;不需要为去重去掉语义,也不需要为语义放弃去重。
 *
 * 关键词均经测试在中文叙事 anchor 里高频出现且含义稳定,不做 stem/synonym(避免误命中)。
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

    /**
     * 根据 anchorText 选出 motion 候选池;空 / 都不命中时回到全池(neutral)。
     * 同时命中多类按 CLIMAX > REVEAL > CALM > TRANSITION 优先。
     */
    public static String[] poolFor(String anchorText) {
        if (anchorText == null || anchorText.isBlank()) return NEUTRAL_POOL;
        if (containsAny(anchorText, CLIMAX_KW)) return CLIMAX_POOL;
        if (containsAny(anchorText, REVEAL_KW)) return REVEAL_POOL;
        if (containsAny(anchorText, CALM_KW)) return CALM_POOL;
        if (containsAny(anchorText, TRANSITION_KW)) return TRANSITION_POOL;
        return NEUTRAL_POOL;
    }

    /**
     * 在 anchorText 对应的池里抽一个 motion,并避免与 prevMotion 相同。
     * 池只剩一种且与 prev 重合时容忍重复(语义优先于去重,极少触发因为所有池都 ≥ 2 元素)。
     */
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
