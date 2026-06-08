package com.auteur.web;

import com.auteur.common.text.TextUtils;
import com.auteur.domain.SeriesHook;
import com.auteur.domain.SeriesHookRepository;
import com.auteur.domain.Topic;
import com.auteur.domain.TopicRepository;
import com.auteur.domain.TopicStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 钩子兑现链路:
 * <p>GET /api/series-hooks?status=unresolved 返回 STRONG 且未兑现且未忽略的钩子;
 *    Java 层按 fromScriptId dedupe 留最新一条。
 * <p>POST /{id}/dismiss 软删,设 dismissed_at = NOW()。
 * <p>POST /{id}/fulfill-with-new-topic 事务里建 Topic + 回填 toTopicId。
 */
@Slf4j
@RestController
@RequestMapping("/api/series-hooks")
@RequiredArgsConstructor
public class SeriesHookController {

    private final SeriesHookRepository hookRepository;
    private final TopicRepository topicRepository;

    @GetMapping
    public List<SeriesHook> list(
            @RequestParam(defaultValue = "unresolved") String status,
            @RequestParam(defaultValue = "false") boolean includeWeak
    ) {
        if ("dismissed".equalsIgnoreCase(status)) {
            return hookRepository.findByDismissedAtIsNotNullAndToTopicIdIsNullOrderByDismissedAtDesc();
        }
        if (!"unresolved".equalsIgnoreCase(status)) {
            return hookRepository.findAll();
        }
        // includeWeak=true:STRONG + WEAK 都拿;false 只拿 STRONG。
        List<SeriesHook> raw = includeWeak
                ? hookRepository.findByToTopicIdIsNullAndDismissedAtIsNullOrderByCreatedAtDesc()
                : hookRepository.findByStrengthAndToTopicIdIsNullAndDismissedAtIsNullOrderByCreatedAtDesc("STRONG");
        // 同 fromScriptId 只留最新(raw 已按 createdAt DESC 排序);fromScriptId null 用负 id 当 key 防覆盖
        Map<Long, SeriesHook> dedupe = new LinkedHashMap<>();
        for (SeriesHook h : raw) {
            Long key = h.getFromScriptId() != null ? h.getFromScriptId() : -h.getId();
            if (!dedupe.containsKey(key)) dedupe.put(key, h);
        }
        return new ArrayList<>(dedupe.values());
    }

    @GetMapping("/{id}")
    public SeriesHook get(@PathVariable Long id) {
        return hookRepository.findById(id).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "钩子 " + id + " 不存在"));
    }

    @PostMapping("/{id}/dismiss")
    @Transactional
    public SeriesHook dismiss(@PathVariable Long id) {
        SeriesHook hook = hookRepository.findById(id).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "hook " + id + " 不存在"));
        if (hook.getDismissedAt() != null) {
            // 幂等:已忽略再点忽略不报错
            return hook;
        }
        hook.setDismissedAt(LocalDateTime.now());
        SeriesHook saved = hookRepository.save(hook);
        log.info("[Hook] dismiss id={} fromScriptId={}", id, hook.getFromScriptId());
        return saved;
    }

    /** 撤销忽略 —— 已兑现的钩子拒绝撤销。 */
    @PostMapping("/{id}/undismiss")
    @Transactional
    public SeriesHook undismiss(@PathVariable Long id) {
        SeriesHook hook = hookRepository.findById(id).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "hook " + id + " 不存在"));
        if (hook.getToTopicId() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "钩子已兑现到 topic T#" + hook.getToTopicId() + ",不可撤销忽略");
        }
        if (hook.getDismissedAt() == null) return hook; // 幂等
        hook.setDismissedAt(null);
        SeriesHook saved = hookRepository.save(hook);
        log.info("[Hook] undismiss id={} fromScriptId={}", id, hook.getFromScriptId());
        return saved;
    }

    @PostMapping("/{id}/fulfill-with-new-topic")
    @Transactional
    public FulfillResponse fulfill(@PathVariable Long id, @Valid @RequestBody FulfillRequest req) {
        SeriesHook hook = hookRepository.findById(id).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "hook " + id + " 不存在"));
        if (hook.getToTopicId() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "钩子已兑现到 topic T#" + hook.getToTopicId() + ",不可重复兑现");
        }

        Topic t = new Topic();
        String trimmedTitle = TextUtils.trimToMax(req.title(), 200);
        t.setTitle(trimmedTitle);
        if (trimmedTitle != null && !trimmedTitle.isBlank()) {
            t.setProjectName(trimmedTitle.substring(0, Math.min(trimmedTitle.length(), 10)));
        }
        t.setDynasty(TextUtils.trimToMax(req.dynasty(), 40));
        t.setGenre(TextUtils.trimToMax(req.genre(), 40));
        t.setProtagonist(TextUtils.trimToMax(req.protagonist(), 120));
        t.setHookType(TextUtils.trimToMax(req.hookType(), 40));
        t.setEmotion(TextUtils.trimToMax(req.emotion(), 40));
        t.setDurationMinutes(req.durationMinutes());
        t.setHistoricalReference(req.historicalReference());
        t.setStatus(TopicStatus.DRAFT);
        t.setSource("HOOK_FULFILL");
        t.setSourceHookId(hook.getId());
        // 系列接力:from_topic 有 series_id 就继承
        Long fromTopicId = hook.getFromTopicId();
        if (fromTopicId != null) {
            topicRepository.findById(fromTopicId).ifPresent(from -> {
                if (from.getSeriesId() != null) {
                    t.setSeriesId(from.getSeriesId());
                }
            });
        }
        Topic saved = topicRepository.save(t);

        hook.setToTopicId(saved.getId());
        SeriesHook savedHook = hookRepository.save(hook);
        log.info("[Hook] fulfill id={} → newTopicId={} fromScript=S#{} seriesId={}",
                id, saved.getId(), hook.getFromScriptId(), saved.getSeriesId());
        return new FulfillResponse(saved, savedHook);
    }

    public record FulfillRequest(
            @NotBlank String title,
            String dynasty,
            String genre,
            String protagonist,
            String hookType,
            String emotion,
            Integer durationMinutes,
            String historicalReference
    ) {}

    public record FulfillResponse(Topic topic, SeriesHook hook) {}
}
