package com.auteur.hotpool;

import com.auteur.domain.Topic;
import com.auteur.domain.TopicRepository;
import com.auteur.domain.TopicStatus;
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

        Topic topic = new Topic();
        topic.setTitle(truncate(item.getTitle(), 200));
        topic.setStatus(TopicStatus.DRAFT);
        topic.setSource("HOT_POOL");
        topic.setPresetId(presetId);
        topic.setPresetInputJson(buildPresetInput(item));
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
     * 把 hot_item 的有用字段封进 preset_input_json,让下游 prompt 可引用。
     * 字段拍平到顶层 — PresetInputInjector 只 flatten 一级,嵌套对象会被 toString() 成 JSON 字符串,
     * 模板 {{hotItem.title}} 之类的"点访问"不可达。所以这里输出 hotItemTitle / hotItemSummary / hotItemUrl。
     * 预设 yaml 里这样引用:{{hotItemTitle}} / {{hotItemSummary}} / {{hotItemUrl}} / {{hotItemTags}}。
     */
    private String buildPresetInput(HotItem item) {
        ObjectNode root = objectMapper.createObjectNode();
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
