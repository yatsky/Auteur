package com.auteur.published;

import com.auteur.domain.PublishedVideo;
import com.auteur.domain.PublishedVideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * published_video 去重 —— 同 (platform, title, publishedAt) 视为同一条视频。
 *
 * 合并策略:
 *  - 每组保留 ID 最小那条(最早抓到的)
 *  - 把同组其它行的 platformVideoId / scriptId / topicId / projectName / hookTemplate 等
 *    "可补全"字段 merge 到保留行(仅当保留行该字段为 null 才补)
 *  - 数值字段(views/likes 等)以保留行最新值为准
 *  - 删除多余行
 *
 * dryRun=true 时只列清单不动数据。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PublishedDedupeService {

    private final PublishedVideoRepository repo;

    public record DuplicateGroup(
            String platform,
            String title,
            String publishedAt,
            Long keepId,
            List<Long> dropIds
    ) {}

    public record DedupeResult(
            int groupCount,
            int dropCount,
            List<DuplicateGroup> groups,
            boolean dryRun
    ) {}

    @Transactional
    public DedupeResult dedupe(boolean dryRun) {
        List<PublishedVideo> rows = repo.findDuplicateGroups();
        Map<String, List<PublishedVideo>> byKey = new LinkedHashMap<>();
        for (PublishedVideo v : rows) {
            String key = v.getPlatform() + "|" + v.getTitle() + "|" + v.getPublishedAt();
            byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(v);
        }

        List<DuplicateGroup> groups = new ArrayList<>();
        int dropCount = 0;
        for (List<PublishedVideo> g : byKey.values()) {
            if (g.size() < 2) continue;
            PublishedVideo keep = g.get(0); // ID 最小
            List<Long> dropIds = new ArrayList<>();
            for (int i = 1; i < g.size(); i++) {
                PublishedVideo dup = g.get(i);
                dropIds.add(dup.getId());
                if (!dryRun) mergeInto(keep, dup);
            }
            if (!dryRun) {
                repo.save(keep);
                for (int i = 1; i < g.size(); i++) repo.delete(g.get(i));
            }
            groups.add(new DuplicateGroup(
                    keep.getPlatform(), keep.getTitle(),
                    String.valueOf(keep.getPublishedAt()),
                    keep.getId(), dropIds));
            dropCount += dropIds.size();
        }

        log.info("[Dedupe] dryRun={} groups={} drops={}", dryRun, groups.size(), dropCount);
        return new DedupeResult(groups.size(), dropCount, groups, dryRun);
    }

    /** 把 dup 上 keep 缺失的"标识/归属"字段补到 keep。数值字段不动。 */
    private void mergeInto(PublishedVideo keep, PublishedVideo dup) {
        if (keep.getPlatformVideoId() == null && dup.getPlatformVideoId() != null) {
            keep.setPlatformVideoId(dup.getPlatformVideoId());
        }
        if (keep.getScriptId() == null && dup.getScriptId() != null) {
            keep.setScriptId(dup.getScriptId());
        }
        if (keep.getTopicId() == null && dup.getTopicId() != null) {
            keep.setTopicId(dup.getTopicId());
        }
        if (keep.getProjectName() == null && dup.getProjectName() != null) {
            keep.setProjectName(dup.getProjectName());
        }
        if (keep.getHookTemplate() == null && dup.getHookTemplate() != null) {
            keep.setHookTemplate(dup.getHookTemplate());
        }
        if (keep.getNotes() == null && dup.getNotes() != null) {
            keep.setNotes(dup.getNotes());
        }
    }
}
