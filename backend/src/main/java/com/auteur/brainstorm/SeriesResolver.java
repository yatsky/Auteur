package com.auteur.brainstorm;

import com.auteur.domain.Series;
import com.auteur.domain.SeriesRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 把 LLM 输出的 ai_suggested_series（自由文本）映射到 series 表的 id。
 * - 第一优先：精确匹配 series.name
 * - 第二优先：题材含"灵异" → 灵异档案
 * - 第三优先：按朝代回退到默认系列
 * - 都失败 → 返回 null（topic.series_id 留空，之后人工补）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeriesResolver {

    private static final String S_MING       = "明朝悬案";
    private static final String S_QING       = "清宫秘闻";
    private static final String S_MINGUO     = "民国奇案";
    private static final String S_TANGSONG   = "唐宋疑云";
    private static final String S_PARANORMAL = "灵异档案";

    private static final Map<String, String> DYNASTY_FALLBACK = new HashMap<>();
    static {
        DYNASTY_FALLBACK.put("明",   S_MING);
        DYNASTY_FALLBACK.put("清",   S_QING);
        DYNASTY_FALLBACK.put("民国", S_MINGUO);
        DYNASTY_FALLBACK.put("唐",   S_TANGSONG);
        DYNASTY_FALLBACK.put("宋",   S_TANGSONG);
    }

    private final SeriesRepository seriesRepository;

    public Long resolveSeriesId(String aiSuggestedSeries, String dynasty, String genre) {
        String target = pickName(aiSuggestedSeries, dynasty, genre);
        if (target == null) return null;
        Optional<Series> hit = seriesRepository.findByName(target);
        if (hit.isEmpty()) {
            log.warn("[SeriesResolver] series '{}' not found in DB; topic will keep series_id=null", target);
            return null;
        }
        return hit.get().getId();
    }

    private static String pickName(String aiSuggestedSeries, String dynasty, String genre) {
        // 1. 精确命中固定 5 系列
        if (aiSuggestedSeries != null) {
            String s = aiSuggestedSeries.trim();
            if (s.equals(S_MING) || s.equals(S_QING) || s.equals(S_MINGUO)
                    || s.equals(S_TANGSONG) || s.equals(S_PARANORMAL)) {
                return s;
            }
        }
        // 2. 题材为灵异 → 灵异档案（跨朝代）
        if (genre != null && genre.contains("灵异")) {
            return S_PARANORMAL;
        }
        // 3. 朝代回退
        if (dynasty != null) {
            for (Map.Entry<String, String> e : DYNASTY_FALLBACK.entrySet()) {
                if (dynasty.contains(e.getKey())) return e.getValue();
            }
        }
        return null;
    }
}
