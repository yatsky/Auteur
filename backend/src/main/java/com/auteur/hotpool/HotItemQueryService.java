package com.auteur.hotpool;

import com.auteur.preset.Preset;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Stream;

/**
 * 热点条目过滤 + 排序。
 *
 * 设计原则:
 *   - 数据库只做粗筛(status/since),细筛全在内存(数据按周计 < 数千行)
 *   - 预设的过滤规则与列表页的过滤规则是同一套(HotSourceConfig DTO)
 *   - 关键词命中策略: include 用 OR(任一命中即纳入),exclude 用 OR(任一命中即排除)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HotItemQueryService {

    private final HotItemRepository itemRepo;
    private final ObjectMapper objectMapper;

    /** 主列表查询入口。filter 任何字段为 null 表示不过滤。 */
    public List<HotItem> query(HotItemFilter filter) {
        LocalDateTime since = filter.maxAgeHours == null ? null
                : LocalDateTime.now().minusHours(filter.maxAgeHours);
        List<HotItem> raw = itemRepo.findAllForFilter(filter.status, since);
        return raw.stream()
                .filter(it -> matchesSourceIds(it, filter.sourceIds))
                .filter(it -> matchesIncludeKeywords(it, filter.includeKeywords))
                .filter(it -> !matchesExcludeKeywords(it, filter.excludeKeywords))
                .filter(it -> filter.minPopularity == null || it.getPopularity() >= filter.minPopularity)
                .filter(it -> matchesIncludeTags(it, filter.includeTags))
                .sorted(Comparator.comparing(HotItem::getPopularity).reversed()
                        .thenComparing(HotItem::getFetchedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(filter.limit == null ? 200 : filter.limit)
                .toList();
    }

    /** 按预设 hot_source_config 解出 Filter,brainstorm 接入点用。enabled=false 时返回 disabled=true 的标记。 */
    public HotItemFilter filterFromPreset(Preset preset) {
        HotItemFilter f = new HotItemFilter();
        f.status = "new";
        if (preset.getHotSourceConfigJson() == null || preset.getHotSourceConfigJson().isBlank()) {
            f.disabled = true;
            return f;
        }
        try {
            JsonNode cfg = objectMapper.readTree(preset.getHotSourceConfigJson());
            if (!cfg.path("enabled").asBoolean(false)) {
                f.disabled = true;
                return f;
            }
            f.sourceIds = asLongList(cfg.path("sourceIds"));
            f.includeKeywords = asStringList(cfg.path("includeKeywords"));
            f.excludeKeywords = asStringList(cfg.path("excludeKeywords"));
            f.includeTags = asStringList(cfg.path("includeTags"));
            if (cfg.hasNonNull("maxAgeHours")) f.maxAgeHours = cfg.get("maxAgeHours").asInt();
            if (cfg.hasNonNull("minPopularity")) f.minPopularity = cfg.get("minPopularity").asDouble();
        } catch (Exception e) {
            log.warn("[hotpool] 解析 preset hot_source_config 失败: {}", e.getMessage());
            f.disabled = true;
        }
        return f;
    }

    private boolean matchesSourceIds(HotItem it, List<Long> sourceIds) {
        if (sourceIds == null || sourceIds.isEmpty()) return true;
        return sourceIds.contains(it.getSourceId());
    }

    private boolean matchesIncludeKeywords(HotItem it, List<String> kws) {
        if (kws == null || kws.isEmpty()) return true;
        String hay = haystack(it);
        return kws.stream().anyMatch(k -> k != null && !k.isBlank() && hay.contains(k));
    }

    private boolean matchesExcludeKeywords(HotItem it, List<String> kws) {
        if (kws == null || kws.isEmpty()) return false;
        String hay = haystack(it);
        return kws.stream().anyMatch(k -> k != null && !k.isBlank() && hay.contains(k));
    }

    private boolean matchesIncludeTags(HotItem it, List<String> includeTags) {
        if (includeTags == null || includeTags.isEmpty()) return true;
        if (it.getTagsJson() == null) return false;
        try {
            List<String> tags = asStringList(objectMapper.readTree(it.getTagsJson()));
            return tags.stream().anyMatch(includeTags::contains);
        } catch (Exception e) {
            return false;
        }
    }

    private static String haystack(HotItem it) {
        return Stream.of(it.getTitle(), it.getSummary())
                .filter(Objects::nonNull)
                .reduce("", (a, b) -> a + " " + b);
    }

    private static List<String> asStringList(JsonNode arr) {
        if (arr == null || !arr.isArray()) return List.of();
        List<String> out = new ArrayList<>();
        arr.forEach(n -> out.add(n.asText()));
        return out;
    }

    private static List<Long> asLongList(JsonNode arr) {
        if (arr == null || !arr.isArray()) return List.of();
        List<Long> out = new ArrayList<>();
        arr.forEach(n -> out.add(n.asLong()));
        return out;
    }

    public static class HotItemFilter {
        public String status;                 // "new" / "promoted" / "dismissed";null = 所有
        public List<Long> sourceIds;
        public List<String> includeKeywords;
        public List<String> excludeKeywords;
        public List<String> includeTags;
        public Integer maxAgeHours;
        public Double minPopularity;
        public Integer limit;
        /** 仅 filterFromPreset 设置:true = 该预设关闭了热点订阅,调用方应直接跳过查询。 */
        public boolean disabled;
    }
}
