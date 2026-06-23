package com.auteur.hotpool;

import com.auteur.domain.Topic;
import com.auteur.domain.TopicRepository;
import com.auteur.domain.TopicStatus;
import com.auteur.preset.Preset;
import com.auteur.preset.PresetRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * 把 hot_item 转为 topic — 单条手动 promote。
 *
 * 不调任何下游 Service(CLAUDE.md 第 3 条):
 *   - 只写 topic 表
 *   - 不触发 brainstorm/script(用户在 topic 详情页另行触发)
 *   - 不写其他流水线表
 *
 * 大批量场景走 TopicService.brainstorm(hotItemIds=...);那条路径才走 LLM。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HotPromoteService {

    private final HotItemRepository itemRepo;
    private final TopicRepository topicRepo;
    private final PresetRepository presetRepo;
    private final ObjectMapper objectMapper;

    @Transactional
    public Topic promote(Long hotItemId, Long presetId) {
        HotItem item = itemRepo.findById(hotItemId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "hot_item id=" + hotItemId));
        if (!"new".equals(item.getStatus())) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "条目状态为 " + item.getStatus() + ",无法再次 promote");
        }
        if (presetId == null) {
            throw new ResponseStatusException(BAD_REQUEST, "presetId 必填");
        }
        Preset preset = presetRepo.findById(presetId)
                .orElseThrow(() -> new ResponseStatusException(BAD_REQUEST, "preset id=" + presetId + " 不存在"));

        Topic topic = new Topic();
        topic.setTitle(truncate(item.getTitle(), 200));
        topic.setStatus(TopicStatus.DRAFT);
        topic.setSource("HOT_POOL");
        topic.setPresetId(presetId);
        topic.setPresetInputJson(buildPresetInput(item, preset));
        // 若 preset 配置了默认导演笔记模板,copy 一份作为该 topic 的 baseline 起点。
        String defaultNote = preset.getDefaultDirectorNoteJson();
        if (defaultNote != null && !defaultNote.isBlank()) {
            topic.setDirectorNote(defaultNote);
        }
        // 简单兜底:用 title 前 10 字做 project_name(跟 brainstorm 来源对齐)
        String projName = topic.getTitle();
        topic.setProjectName(projName.length() <= 10 ? projName : projName.substring(0, 10));

        topic = topicRepo.save(topic);

        item.setStatus("promoted");
        item.setPromotedTopicId(topic.getId());
        itemRepo.save(item);

        log.info("[hotpool] promoted hot_item={} -> topic={}", hotItemId, topic.getId());
        return topic;
    }

    @Transactional
    public void dismiss(Long hotItemId) {
        HotItem item = itemRepo.findById(hotItemId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "hot_item id=" + hotItemId));
        // 只允许 new → dismissed。已 promoted 的条目不能再 dismiss(否则 status=dismissed + promoted_topic_id 残留 → 不可能状态)。
        if ("promoted".equals(item.getStatus())) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "条目已 promote 为 topic id=" + item.getPromotedTopicId() + ",不能 dismiss;请去 topic 池 archive。");
        }
        if ("dismissed".equals(item.getStatus())) return; // 幂等
        item.setStatus("dismissed");
        itemRepo.save(item);
    }

    /**
     * 把 hot_item 的有用字段 + preset.inputSchemaJson 里的 default 值封进 preset_input_json,
     * 让下游 prompt 可引用。
     *
     * 字段拍平到顶层 — PresetInputInjector 只 flatten 一级,嵌套对象会被 toString() 成 JSON 字符串,
     * 模板 {{hotItem.title}} 之类的"点访问"不可达。所以这里输出 hotItemTitle / hotItemSummary / hotItemUrl。
     * 预设 yaml 里这样引用:{{hotItemTitle}} / {{hotItemSummary}} / {{hotItemUrl}} / {{hotItemTags}}。
     *
     * schema 的 default 仅在该字段没被其他来源(目前只有 hot 四项)覆盖时灌入 — hot 永远优先。
     */
    private String buildPresetInput(HotItem item, Preset preset) {
        ObjectNode root = objectMapper.createObjectNode();
        // 先灌 schema default(后面 hot 字段同名覆盖)
        String schemaJson = preset.getInputSchemaJson();
        if (schemaJson != null && !schemaJson.isBlank()) {
            try {
                com.fasterxml.jackson.databind.JsonNode schema = objectMapper.readTree(schemaJson);
                com.fasterxml.jackson.databind.JsonNode props = schema.get("properties");
                if (props != null && props.isObject()) {
                    props.fields().forEachRemaining(e -> {
                        com.fasterxml.jackson.databind.JsonNode def = e.getValue().get("default");
                        if (def != null && !def.isNull()) root.set(e.getKey(), def);
                    });
                }
            } catch (Exception ex) {
                log.warn("[hotpool] preset.inputSchemaJson 解析失败 presetId={}: {}", preset.getId(), ex.toString());
            }
        }
        // hot 字段后写,同名覆盖
        root.put("hotItemTitle", item.getTitle());
        if (item.getSummary() != null) root.put("hotItemSummary", item.getSummary());
        if (item.getUrl() != null) root.put("hotItemUrl", item.getUrl());
        if (item.getTagsJson() != null) {
            try {
                root.set("hotItemTags", objectMapper.readTree(item.getTagsJson()));
            } catch (Exception ignored) {
            }
        }
        return root.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
