package com.auteur.topic;

import com.auteur.domain.Topic;
import com.auteur.domain.TopicRepository;
import com.auteur.domain.TopicStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * topic.status 自动推进 —— 流水线下游事件触发。
 *
 * 规则:
 *   SCHEDULED → PRODUCED   ← VideoAssemblyService 落 final video 后
 *   PRODUCED  → PUBLISHED  ← CoverGenerationService 三比例生成完
 *   PUBLISHED → ARCHIVED   ← 浏览器插件首次抓到 published_video
 *
 * 幂等:只在当前 status==expectedFrom 时切换;其它情况(用户已手动改、重复事件、流水线乱序)
 * 一律 no-op,不抛异常,避免后端事件回放污染状态。
 *
 * 事务:REQUIRES_NEW —— 钩子调用方(VideoAssembly / Cover / Upsert)的事务失败不应回滚
 * 状态推进;反过来,本方法失败也不能影响主流程(catch 在 callsite)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TopicStatusAdvancer {

    private final TopicRepository topicRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean advance(Long topicId, TopicStatus expectedFrom, TopicStatus to) {
        if (topicId == null) return false;
        Topic t = topicRepository.findById(topicId).orElse(null);
        if (t == null) {
            log.warn("[StatusAdvance] topic {} 不存在,跳过 {} → {}", topicId, expectedFrom, to);
            return false;
        }
        if (t.getStatus() != expectedFrom) {
            log.debug("[StatusAdvance] topic {} 当前 {} 不匹配预期 {},跳过推进到 {}",
                    topicId, t.getStatus(), expectedFrom, to);
            return false;
        }
        t.setStatus(to);
        topicRepository.save(t);
        log.info("[StatusAdvance] topic {} {} → {}", topicId, expectedFrom, to);
        return true;
    }
}
