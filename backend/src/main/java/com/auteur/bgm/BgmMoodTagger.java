package com.auteur.bgm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.auteur.common.text.TextUtils;
import com.auteur.domain.Script;
import com.auteur.domain.ScriptRepository;
import com.auteur.domain.Topic;
import com.auteur.domain.TopicRepository;
import com.auteur.llm.LlmCallSpec;
import com.auteur.llm.LlmClient;
import com.auteur.llm.LlmResult;
import com.auteur.llm.ModelRegistry;
import com.auteur.llm.PromptTemplateService;
import com.auteur.web.NotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 给 script 打 BGM mood 标签。固定 6 词表,LLM JSON 输出。结果落 script.bgm_mood_tag 缓存。
 */
@Slf4j
@Service
public class BgmMoodTagger {

    /**
     * 6 词表 → Jamendo 标签组合(逗号分隔)。每 mood 用 2 个 tag:
     * Jamendo 多 tag 是严格 AND 匹配,3 个 tag 几乎找不到曲;fuzzytags 参数有 bug 不能传。
     */
    public static final Map<String, String> MOOD_TO_JAMENDO_TAGS = Map.of(
            "dark_suspense",      "cinematic,dark",
            "ancient_solemn",     "ambient,orchestral",
            "tense_thriller",     "cinematic,action",
            "melancholic",        "ambient,sad",
            "epic_documentary",   "cinematic,epic",
            "mysterious_ambient", "ambient,atmospheric"
    );
    public static final String DEFAULT_MOOD = "dark_suspense";

    private final LlmClient llmClient;
    private final PromptTemplateService promptService;
    private final ModelRegistry modelRegistry;
    private final ScriptRepository scriptRepository;
    private final TopicRepository topicRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public BgmMoodTagger(LlmClient llmClient,
                         PromptTemplateService promptService,
                         ModelRegistry modelRegistry,
                         ScriptRepository scriptRepository,
                         TopicRepository topicRepository) {
        this.llmClient = llmClient;
        this.promptService = promptService;
        this.modelRegistry = modelRegistry;
        this.scriptRepository = scriptRepository;
        this.topicRepository = topicRepository;
    }

    /** 已缓存直返,否则调 LLM 并落库。失败回落 DEFAULT_MOOD 不阻塞推荐流程。 */
    @Transactional
    public String resolveMood(Long scriptId) {
        Script script = scriptRepository.findById(scriptId)
                .orElseThrow(() -> new NotFoundException("Script not found: " + scriptId));
        if (script.getBgmMoodTag() != null && MOOD_TO_JAMENDO_TAGS.containsKey(script.getBgmMoodTag())) {
            log.info("[作曲·选曲] cached for scriptId={} mood={}", scriptId, script.getBgmMoodTag());
            return script.getBgmMoodTag();
        }

        Topic topic = topicRepository.findById(script.getTopicId())
                .orElseThrow(() -> new IllegalStateException("Topic not found for script " + scriptId));

        String excerpt = excerpt(script.getFullText(), 600);
        PromptTemplateService.Rendered tpl = promptService.render("bgm_mood", Map.of(
                "title", TextUtils.safe(topic.getTitle()),
                "dynasty", TextUtils.safe(topic.getDynasty()),
                "genre", TextUtils.safe(topic.getGenre()),
                "full_text_excerpt", excerpt
        ));
        LlmCallSpec spec = LlmCallSpec.builder()
                .operation("bgm_mood")
                .relatedType("SCRIPT")
                .relatedId(scriptId)
                .model(modelRegistry.modelFor("bgm_mood"))
                .temperature(tpl.temperature() != null ? tpl.temperature() : 0.3)
                .build();

        String mood;
        try {
            LlmResult result = llmClient.chat(spec, tpl.system(), tpl.user());
            mood = parseMood(result.getContent());
            log.info("[作曲·选曲] tagged scriptId={} mood={} ms={}",
                    scriptId, mood, result.getDurationMs());
        } catch (RuntimeException e) {
            log.warn("[作曲·选曲] LLM failed for scriptId={}, fallback to {}: {}",
                    scriptId, DEFAULT_MOOD, e.toString());
            mood = DEFAULT_MOOD;
        }

        script.setBgmMoodTag(mood);
        scriptRepository.save(script);
        return mood;
    }

    public String jamendoTagsFor(String mood) {
        return MOOD_TO_JAMENDO_TAGS.getOrDefault(mood, MOOD_TO_JAMENDO_TAGS.get(DEFAULT_MOOD));
    }

    private String parseMood(String raw) {
        if (raw == null || raw.isBlank()) return DEFAULT_MOOD;
        String stripped = TextUtils.stripCodeFence(raw).trim();
        int s = stripped.indexOf('{');
        int e = stripped.lastIndexOf('}');
        if (s < 0 || e <= s) {
            log.warn("[作曲·选曲] no JSON object in response: {}", TextUtils.preview(raw));
            return DEFAULT_MOOD;
        }
        try {
            JsonNode node = objectMapper.readTree(stripped.substring(s, e + 1));
            String mood = node.path("mood").asText("");
            if (MOOD_TO_JAMENDO_TAGS.containsKey(mood)) return mood;
            log.warn("[作曲·选曲] unknown mood key '{}', fallback default", mood);
        } catch (Exception ex) {
            log.warn("[作曲·选曲] JSON parse failed: {}, raw={}", ex.toString(), TextUtils.preview(raw));
        }
        return DEFAULT_MOOD;
    }

    private static String excerpt(String text, int max) {
        if (text == null) return "";
        String t = text.trim();
        return t.length() <= max ? t : t.substring(0, max) + "...";
    }
}
