package com.auteur.published;

import com.auteur.domain.PublishedVideo;
import com.auteur.domain.PublishedVideoRepository;
import com.auteur.domain.Script;
import com.auteur.domain.ScriptRepository;
import com.auteur.domain.TopicStatus;
import com.auteur.topic.TopicStatusAdvancer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * published_video 的 upsert 语义集中地。
 * 给 PublishedVideoController(create / bulk / patch)和浏览器插件回写共用同一套 merge 规则。
 *
 * 字段级 merge 策略:
 *  - 有 (platform, platformVideoId):按 vid 找,命中就 applyPartial
 *  - 没 vid 但有 scriptId:按 (scriptId, platform, publishedAt) 三元组找,命中就 applyPartial
 *  - 都没有:走纯 INSERT
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PublishedVideoUpsertService {

    private final PublishedVideoRepository repo;
    private final ScriptRepository scriptRepo;
    private final TopicStatusAdvancer topicStatusAdvancer;

    public enum Result { INSERTED, UPDATED }

    public record UpsertOutcome(Result result, PublishedVideo entity) {}

    /**
     * 执行 upsert,返回结果。bulk 与 extension/sync 都调它。
     * 必填字段缺失抛 IllegalArgumentException(GlobalExceptionHandler 转 400)。
     */
    @Transactional
    public UpsertOutcome upsert(PublishedVideoUpsertRequest r) {
        if (r.title() == null || r.title().isBlank()) {
            throw new IllegalArgumentException("title 必填");
        }
        if (r.platform() == null || r.platform().isBlank()) {
            throw new IllegalArgumentException("platform 必填");
        }
        if (r.publishedAt() == null) {
            throw new IllegalArgumentException("publishedAt 必填");
        }

        Optional<PublishedVideo> existing = Optional.empty();
        if (r.platformVideoId() != null && !r.platformVideoId().isBlank()) {
            existing = repo.findByPlatformAndPlatformVideoId(r.platform(), r.platformVideoId());
        } else if (r.scriptId() != null) {
            existing = repo.findByScriptIdAndPlatformAndPublishedAt(
                    r.scriptId(), r.platform(), r.publishedAt());
        }
        // 兜底:即使 vid 不同 / scriptId 没绑,(platform, title, publishedAt) 命中也算同一条。
        // 防止抖音 aweme_id vs item_id 等不同入口造成重复插入。
        if (existing.isEmpty()) {
            existing = repo.findFirstByPlatformAndTitleAndPublishedAt(
                    r.platform(), r.title().trim(), r.publishedAt());
        }

        if (existing.isPresent()) {
            PublishedVideo v = existing.get();
            applyUpsertPartial(v, r);
            return new UpsertOutcome(Result.UPDATED, repo.save(v));
        }
        PublishedVideo saved;
        try {
            saved = repo.save(applyUpsert(new PublishedVideo(), r));
        } catch (DataIntegrityViolationException dup) {
            // DB 层 UNIQUE (platform, published_at, title(100)) 兜底拒掉重复 INSERT(并发 race)。
            // 回查兜底键再 partial update。
            log.warn("[PublishedUpsert] UNIQUE 冲突,回查兜底键做 update title='{}' platform='{}': {}",
                    r.title(), r.platform(), dup.getMostSpecificCause().getMessage());
            PublishedVideo conflicted = repo.findFirstByPlatformAndTitleAndPublishedAt(
                    r.platform(), r.title().trim(), r.publishedAt())
                    .orElseThrow(() -> new IllegalStateException(
                            "UNIQUE 冲突但回查不到行,可能是别的约束(platform_vid)冲突", dup));
            applyUpsertPartial(conflicted, r);
            return new UpsertOutcome(Result.UPDATED, repo.save(conflicted));
        }
        // 首次抓到 → 自动把 topic 推到 ARCHIVED。仅 INSERTED 触发,UPDATED 跳过。
        try {
            Long topicId = saved.getTopicId();
            if (topicId == null && saved.getScriptId() != null) {
                topicId = scriptRepo.findById(saved.getScriptId()).map(Script::getTopicId).orElse(null);
            }
            topicStatusAdvancer.advance(topicId, TopicStatus.PUBLISHED, TopicStatus.ARCHIVED);
        } catch (RuntimeException ex) {
            log.warn("[PublishedUpsert] topic status advance 失败 publishedId={} err={}",
                    saved.getId(), ex.toString());
        }
        return new UpsertOutcome(Result.INSERTED, saved);
    }

    /** create + bulk insert 路径用:全量赋值,空值清空。 */
    public PublishedVideo applyUpsert(PublishedVideo v, PublishedVideoUpsertRequest r) {
        v.setScriptId(r.scriptId());
        v.setTopicId(r.topicId());
        v.setTitle(r.title().trim());
        v.setProjectName(blankToNull(r.projectName()));
        v.setPlatform(r.platform().trim());
        v.setPlatformVideoId(blankToNull(r.platformVideoId()));
        v.setPublishedAt(r.publishedAt());
        v.setDurationSeconds(r.durationSeconds());
        v.setViews(r.views() != null ? r.views() : 0L);
        v.setLikes(r.likes() != null ? r.likes() : 0L);
        v.setComments(r.comments() != null ? r.comments() : 0L);
        v.setShares(r.shares() != null ? r.shares() : 0L);
        v.setRetentionPct(r.retentionPct());
        v.setAvgPlaySeconds(r.avgPlaySeconds());
        v.setDrop2sPct(r.drop2sPct());
        v.setPlay5sPct(r.play5sPct());
        v.setAvgPlayRatioPct(r.avgPlayRatioPct());
        v.setFavoriteRatePct(r.favoriteRatePct());
        v.setDislikeRatePct(r.dislikeRatePct());
        v.setHookCtr(r.hookCtr());
        v.setCostYuan(r.costYuan());
        v.setHookTemplate(blankToNull(r.hookTemplate()));
        v.setNotes(blankToNull(r.notes()));
        v.setCoverUrl(blankToNull(r.coverUrl()));
        v.setLikeRate(r.likeRate());
        v.setShareRate(r.shareRate());
        v.setCommentRate(r.commentRate());
        v.setSubscribeCount(r.subscribeCount());
        v.setUnsubscribeCount(r.unsubscribeCount());
        v.setCoverCtr(r.coverCtr());
        v.setHomepageVisitCount(r.homepageVisitCount());
        return v;
    }

    /** PATCH / bulk update / extension 路径用:只覆盖 req 里非 null 字段;空串视为清空。 */
    public void applyUpsertPartial(PublishedVideo v, PublishedVideoUpsertRequest r) {
        if (r.scriptId() != null) v.setScriptId(r.scriptId());
        if (r.topicId() != null) v.setTopicId(r.topicId());
        if (r.title() != null && !r.title().isBlank()) v.setTitle(r.title().trim());
        if (r.projectName() != null) v.setProjectName(blankToNull(r.projectName()));
        if (r.platform() != null && !r.platform().isBlank()) v.setPlatform(r.platform().trim());
        if (r.platformVideoId() != null) v.setPlatformVideoId(blankToNull(r.platformVideoId()));
        if (r.publishedAt() != null) v.setPublishedAt(r.publishedAt());
        if (r.durationSeconds() != null) v.setDurationSeconds(r.durationSeconds());
        if (r.views() != null) v.setViews(r.views());
        if (r.likes() != null) v.setLikes(r.likes());
        if (r.comments() != null) v.setComments(r.comments());
        if (r.shares() != null) v.setShares(r.shares());
        if (r.retentionPct() != null) v.setRetentionPct(r.retentionPct());
        if (r.avgPlaySeconds() != null) v.setAvgPlaySeconds(r.avgPlaySeconds());
        if (r.drop2sPct() != null) v.setDrop2sPct(r.drop2sPct());
        if (r.play5sPct() != null) v.setPlay5sPct(r.play5sPct());
        if (r.avgPlayRatioPct() != null) v.setAvgPlayRatioPct(r.avgPlayRatioPct());
        if (r.favoriteRatePct() != null) v.setFavoriteRatePct(r.favoriteRatePct());
        if (r.dislikeRatePct() != null) v.setDislikeRatePct(r.dislikeRatePct());
        if (r.hookCtr() != null) v.setHookCtr(r.hookCtr());
        if (r.costYuan() != null) v.setCostYuan(r.costYuan());
        if (r.hookTemplate() != null) v.setHookTemplate(blankToNull(r.hookTemplate()));
        if (r.notes() != null) v.setNotes(blankToNull(r.notes()));
        if (r.coverUrl() != null) v.setCoverUrl(blankToNull(r.coverUrl()));
        if (r.likeRate() != null) v.setLikeRate(r.likeRate());
        if (r.shareRate() != null) v.setShareRate(r.shareRate());
        if (r.commentRate() != null) v.setCommentRate(r.commentRate());
        if (r.subscribeCount() != null) v.setSubscribeCount(r.subscribeCount());
        if (r.unsubscribeCount() != null) v.setUnsubscribeCount(r.unsubscribeCount());
        if (r.coverCtr() != null) v.setCoverCtr(r.coverCtr());
        if (r.homepageVisitCount() != null) v.setHomepageVisitCount(r.homepageVisitCount());
    }

    private static String blankToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
