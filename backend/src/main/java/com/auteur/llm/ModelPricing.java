package com.auteur.llm;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 模型成本换算(best-effort)。
 *
 * 单位:
 *   - chat / vision: 元 / 百万 token(in / out 分别计价)
 *   - image: 元 / 张
 *
 * 经过 LLM relay/网关时实际结算价可能不同。匹配规则:模型名 toLowerCase 后按
 * LinkedHashMap 插入顺序 startsWith 查找,**长前缀必须放前面**。
 * 找不到匹配返回 null,调用方应让 cost_yuan 留 NULL。
 */
public final class ModelPricing {

    private ModelPricing() {}

    private record ChatPrice(BigDecimal in, BigDecimal out) {}

    /** chat 模型单价:元 / 百万 token。顺序敏感,长前缀靠前。 */
    private static final Map<String, ChatPrice> CHAT_PRICES = new LinkedHashMap<>();
    static {
        CHAT_PRICES.put("claude-opus-4-7",   new ChatPrice(bd("108"),  bd("540")));
        CHAT_PRICES.put("claude-opus",       new ChatPrice(bd("108"),  bd("540")));
        CHAT_PRICES.put("claude-sonnet-4-6", new ChatPrice(bd("21.6"), bd("108")));
        CHAT_PRICES.put("claude-sonnet",     new ChatPrice(bd("21.6"), bd("108")));
        CHAT_PRICES.put("claude-haiku",      new ChatPrice(bd("7.2"),  bd("36")));
        CHAT_PRICES.put("deepseek-v3",       new ChatPrice(bd("1.5"),  bd("7.5")));
        CHAT_PRICES.put("deepseek",          new ChatPrice(bd("1.5"),  bd("7.5")));
        CHAT_PRICES.put("qwen-vl-max",       new ChatPrice(bd("20"),   bd("20")));
        CHAT_PRICES.put("qwen-vl",           new ChatPrice(bd("8"),    bd("8")));
        CHAT_PRICES.put("qwen",              new ChatPrice(bd("4"),    bd("8")));
        CHAT_PRICES.put("doubao",            new ChatPrice(bd("1"),    bd("3")));
        CHAT_PRICES.put("xai.grok-4",        new ChatPrice(bd("21.6"), bd("108")));
        CHAT_PRICES.put("xai.grok",          new ChatPrice(bd("21.6"), bd("108")));
    }

    /** 图片单价：元 / 张。 */
    private static final Map<String, BigDecimal> IMAGE_PRICES = new LinkedHashMap<>();
    static {
        IMAGE_PRICES.put("doubao-seedream-5.0-lite", bd("0.0259"));
        IMAGE_PRICES.put("doubao-seedream",          bd("0.0259"));
    }

    /** chat / vision 调用的成本。返回 null 表示模型未在表里。 */
    public static BigDecimal computeChat(String model, Integer inTokens, Integer outTokens) {
        ChatPrice p = lookupByPrefix(model, CHAT_PRICES);
        if (p == null) return null;
        BigDecimal in  = p.in().multiply(bd(inTokens  == null ? 0 : inTokens));
        BigDecimal out = p.out().multiply(bd(outTokens == null ? 0 : outTokens));
        return in.add(out)
                .divide(bd("1000000"), 6, RoundingMode.HALF_UP)
                .setScale(4, RoundingMode.HALF_UP);
    }

    /** 图片调用按张计费。 */
    public static BigDecimal computeImage(String model, int n) {
        BigDecimal p = lookupByPrefix(model, IMAGE_PRICES);
        if (p == null) return null;
        return p.multiply(bd(n)).setScale(4, RoundingMode.HALF_UP);
    }

    private static <T> T lookupByPrefix(String model, Map<String, T> table) {
        if (model == null) return null;
        String lower = model.toLowerCase();
        for (Map.Entry<String, T> e : table.entrySet()) {
            if (lower.startsWith(e.getKey())) return e.getValue();
        }
        return null;
    }

    private static BigDecimal bd(Object o) {
        if (o instanceof BigDecimal b) return b;
        if (o instanceof Number n)     return BigDecimal.valueOf(n.longValue());
        return new BigDecimal(o.toString());
    }
}
