package com.auteur.agent.tools;

import com.auteur.agent.PreviewableHandler;
import com.auteur.agent.ToolHandler;
import com.auteur.agent.ToolRegistry;
import com.auteur.agent.WriteToolHandler;
import com.auteur.domain.Topic;
import com.auteur.hotpool.*;
import com.auteur.llm.ChatRequest;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 热点池 Agent 工具集 — 让 Agent 能驱动「选源 → 抓取 → 筛选 → 送选题」全流程。
 *
 * 风格对齐 PresetReadTools / PresetCrudTools:
 *   - 读工具 = ToolHandler(默认 READ,无审批)
 *   - 写工具 = WriteToolHandler / PreviewableHandler
 *   - 大颗粒度 + 有 diff 的用 PreviewableHandler
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HotPoolTools {

    private final ToolRegistry registry;
    private final HotItemRepository itemRepo;
    private final HotSourceRepository sourceRepo;
    private final HotItemQueryService queryService;
    private final HotFetchService fetchService;
    private final HotPromoteService promoteService;

    @PostConstruct
    public void init() {
        registry.register(new ListHotItems());
        registry.register(new ListHotSources());
        registry.register(new FetchHotNow());
        registry.register(new PromoteHotToTopic());
        registry.register(new DismissHot());
    }

    private Map<String, Object> summarize(HotItem it) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", it.getId());
        m.put("sourceId", it.getSourceId());
        m.put("title", it.getTitle());
        if (it.getSummary() != null) {
            m.put("summary", it.getSummary().length() > 200
                    ? it.getSummary().substring(0, 200) + "..."
                    : it.getSummary());
        }
        m.put("url", it.getUrl());
        m.put("popularity", it.getPopularity());
        m.put("publishedAt", it.getPublishedAt() == null ? null : it.getPublishedAt().toString());
        m.put("status", it.getStatus());
        if (it.getTagsJson() != null) m.put("tags", it.getTagsJson());
        return m;
    }

    /* -------- list_hot_items -------- */

    private class ListHotItems implements ToolHandler {
        @Override
        public ChatRequest.Tool definition() {
            return ChatRequest.Tool.of(
                    "list_hot_items",
                    "列出热点条目(已按热度降序)。所有过滤条件都可选。" +
                            "典型用法:list_hot_items(includeKeywords=['降息','通胀'], maxAgeHours=48, limit=10)。",
                    Map.of(
                            "type", "object",
                            "properties", Map.ofEntries(
                                    Map.entry("status", Map.of("type", "string", "enum",
                                            List.of("new", "promoted", "dismissed", "all"),
                                            "description", "默认 new")),
                                    Map.entry("sourceIds", Map.of("type", "array",
                                            "items", Map.of("type", "integer"))),
                                    Map.entry("includeKeywords", Map.of("type", "array",
                                            "items", Map.of("type", "string"))),
                                    Map.entry("excludeKeywords", Map.of("type", "array",
                                            "items", Map.of("type", "string"))),
                                    Map.entry("includeTags", Map.of("type", "array",
                                            "items", Map.of("type", "string"))),
                                    Map.entry("maxAgeHours", Map.of("type", "integer")),
                                    Map.entry("minPopularity", Map.of("type", "number")),
                                    Map.entry("limit", Map.of("type", "integer", "description", "默认 20"))
                            ),
                            "required", List.of()
                    )
            );
        }

        @Override
        public Object execute(JsonNode args) {
            HotItemQueryService.HotItemFilter f = new HotItemQueryService.HotItemFilter();
            String st = args.path("status").asText("new");
            f.status = "all".equals(st) ? null : st;
            f.sourceIds = readLongs(args, "sourceIds");
            f.includeKeywords = readStrings(args, "includeKeywords");
            f.excludeKeywords = readStrings(args, "excludeKeywords");
            f.includeTags = readStrings(args, "includeTags");
            if (args.hasNonNull("maxAgeHours")) f.maxAgeHours = args.get("maxAgeHours").asInt();
            if (args.hasNonNull("minPopularity")) f.minPopularity = args.get("minPopularity").asDouble();
            f.limit = args.hasNonNull("limit") ? args.get("limit").asInt() : 20;
            List<Map<String, Object>> rows = queryService.query(f).stream()
                    .map(HotPoolTools.this::summarize).toList();
            return Map.of("count", rows.size(), "items", rows);
        }
    }

    /* -------- list_hot_sources -------- */

    private class ListHotSources implements ToolHandler {
        @Override
        public ChatRequest.Tool definition() {
            return ChatRequest.Tool.of(
                    "list_hot_sources",
                    "列出已配置的热点源(名称 / 适配器 / 启用状态 / 上次抓取信息)。",
                    Map.of("type", "object", "properties", Map.of(), "required", List.of())
            );
        }

        @Override
        public Object execute(JsonNode args) {
            List<Map<String, Object>> rows = sourceRepo.findAllByOrderByIdAsc().stream()
                    .map(s -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id", s.getId());
                        m.put("name", s.getName());
                        m.put("adapter", s.getAdapter());
                        m.put("url", s.getUrl());
                        m.put("enabled", s.isEnabled());
                        m.put("lastFetchedAt", s.getLastFetchedAt() == null ? null
                                : s.getLastFetchedAt().toString());
                        m.put("lastFetchCount", s.getLastFetchCount());
                        m.put("lastFetchError", s.getLastFetchError());
                        return m;
                    }).toList();
            return Map.of("count", rows.size(), "sources", rows);
        }
    }

    /* -------- fetch_hot_now -------- */

    private class FetchHotNow implements WriteToolHandler {
        @Override
        public ChatRequest.Tool definition() {
            return ChatRequest.Tool.of(
                    "fetch_hot_now",
                    "立即拉取热点(写 hot_item 表)。不传 sourceIds = 抓所有 enabled 源;" +
                            "传子集 = 只抓指定源。返回每源抓到 / 跳过 / 失败信息。",
                    Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "sourceIds", Map.of("type", "array",
                                            "items", Map.of("type", "integer"),
                                            "description", "源 id 列表;空 = 全部 enabled")
                            ),
                            "required", List.of()
                    )
            );
        }

        @Override
        public Object execute(JsonNode args) {
            List<Long> ids = readLongs(args, "sourceIds");
            List<HotFetchService.FetchResult> results = (ids == null || ids.isEmpty())
                    ? fetchService.fetchAll()
                    : ids.stream().map(fetchService::fetch).toList();
            int totalNew = results.stream().mapToInt(HotFetchService.FetchResult::inserted).sum();
            return Map.of(
                    "totalInserted", totalNew,
                    "results", results
            );
        }
    }

    /* -------- promote_hot_to_topic -------- */

    private class PromoteHotToTopic implements PreviewableHandler {
        @Override
        public ChatRequest.Tool definition() {
            return ChatRequest.Tool.of(
                    "promote_hot_to_topic",
                    "把一条热点转为 DRAFT 状态的 topic(不触发 brainstorm/script)。" +
                            "用户后续在 topic 详情页人工触发流水线,或调 brainstorm 工具批量生成。",
                    Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "hotItemId", Map.of("type", "integer"),
                                    "presetId", Map.of("type", "integer",
                                            "description", "目标预设 id — 决定后续流水线走哪条")
                            ),
                            "required", List.of("hotItemId", "presetId")
                    )
            );
        }

        @Override
        public Preview preview(JsonNode args) {
            long hid = args.get("hotItemId").asLong();
            long pid = args.get("presetId").asLong();
            HotItem item = itemRepo.findById(hid)
                    .orElseThrow(() -> new IllegalArgumentException("hot_item id=" + hid + " 不存在"));
            String summary = "送选题: [" + item.getTitle() + "] → preset id=" + pid;
            return new Preview(
                    "topic.create",
                    "(无 — 新建)",
                    "title: " + item.getTitle() + "\nsource: HOT_POOL\npresetId: " + pid,
                    summary
            );
        }

        @Override
        public Object execute(JsonNode args) {
            long hid = args.get("hotItemId").asLong();
            long pid = args.get("presetId").asLong();
            Topic topic = promoteService.promote(hid, pid);
            return Map.of(
                    "ok", true,
                    "topicId", topic.getId(),
                    "title", topic.getTitle()
            );
        }
    }

    /* -------- dismiss_hot -------- */

    private class DismissHot implements PreviewableHandler {
        @Override
        public ChatRequest.Tool definition() {
            return ChatRequest.Tool.of(
                    "dismiss_hot",
                    "把一条热点标记为 dismissed(不再出现在主列表)。可恢复需走 UI 手动改 status。",
                    Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "hotItemId", Map.of("type", "integer")
                            ),
                            "required", List.of("hotItemId")
                    )
            );
        }

        @Override
        public Preview preview(JsonNode args) {
            long hid = args.get("hotItemId").asLong();
            HotItem item = itemRepo.findById(hid)
                    .orElseThrow(() -> new IllegalArgumentException("hot_item id=" + hid + " 不存在"));
            return new Preview(
                    "hot_item.status",
                    item.getStatus(),
                    "dismissed",
                    "忽略: [" + item.getTitle() + "]"
            );
        }

        @Override
        public Object execute(JsonNode args) {
            long hid = args.get("hotItemId").asLong();
            promoteService.dismiss(hid);
            return Map.of("ok", true, "id", hid, "status", "dismissed");
        }
    }

    /* -------- helpers -------- */

    private static List<String> readStrings(JsonNode args, String key) {
        JsonNode arr = args.path(key);
        if (!arr.isArray()) return null;
        List<String> out = new ArrayList<>(arr.size());
        arr.forEach(n -> out.add(n.asText()));
        return out;
    }

    private static List<Long> readLongs(JsonNode args, String key) {
        JsonNode arr = args.path(key);
        if (!arr.isArray()) return null;
        List<Long> out = new ArrayList<>(arr.size());
        arr.forEach(n -> out.add(n.asLong()));
        return out;
    }
}
