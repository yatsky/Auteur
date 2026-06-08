package com.auteur.preset;

import com.auteur.domain.Script;
import com.auteur.domain.ScriptRepository;
import com.auteur.domain.Topic;
import com.auteur.domain.TopicRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 给下游 service 提供"shot/script → topic → preset"的链式查询。
 * topic.preset_id NOT NULL,所有 topic 必有 preset。空或指向不存在 preset 时抛 IllegalStateException。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TopicPresetResolver {

    private final PresetService presetService;
    private final PresetRepository presetRepository;
    private final TopicRepository topicRepository;
    private final ScriptRepository scriptRepository;

    public PresetContext forTopic(Topic topic) {
        if (topic == null) {
            throw new IllegalStateException("forTopic: topic 为空");
        }
        if (topic.getPresetId() == null) {
            throw new IllegalStateException(
                    "topic id=" + topic.getId() + " 没有 preset_id;P3 后所有 topic 必关联 preset");
        }
        Preset preset = presetRepository.findById(topic.getPresetId())
                .orElseThrow(() -> new IllegalStateException(
                        "topic id=" + topic.getId() + " 的 preset_id=" + topic.getPresetId() + " 指向不存在的 preset"));
        return new PresetContext(
                preset,
                presetService.imageConfig(preset),
                presetService.voiceConfig(preset)
        );
    }

    public PresetContext forTopicId(Long topicId) {
        if (topicId == null) {
            throw new IllegalStateException("forTopicId: topicId 为空");
        }
        Topic topic = topicRepository.findById(topicId)
                .orElseThrow(() -> new IllegalStateException("topic id=" + topicId + " 不存在"));
        return forTopic(topic);
    }

    /** 给只拿到 scriptId 的下游用(StoryboardShot / ImageAsset 等)。 */
    public PresetContext forScriptId(Long scriptId) {
        if (scriptId == null) {
            throw new IllegalStateException("forScriptId: scriptId 为空");
        }
        Script s = scriptRepository.findById(scriptId)
                .orElseThrow(() -> new IllegalStateException("script id=" + scriptId + " 不存在"));
        return forTopicId(s.getTopicId());
    }
}
