package com.auteur.domain;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PublishedVideoRepository extends JpaRepository<PublishedVideo, Long> {
    List<PublishedVideo> findAllByOrderByPublishedAtDesc();

    /** drift / daily-trend 用:近 N 天的样本。 */
    List<PublishedVideo> findByPublishedAtAfterOrderByPublishedAtDesc(LocalDateTime since);

    /**
     * Brainstorm 去重用:取某预设近期已发视频标题。通过 topic.preset_id 关联过滤,
     * 跨预设(LifeCopy / 历史悬案号 等)的标题不会污染当前预设的 done_topics。
     * Pageable 控制 LIMIT,调用方传 PageRequest.of(0, 100) 之类。
     */
    @Query("select v.title from PublishedVideo v where v.topicId in " +
            "(select t.id from com.auteur.domain.Topic t where t.presetId = :presetId) " +
            "and v.title is not null and v.title <> '' " +
            "order by v.publishedAt desc")
    List<String> findRecentTitlesByPresetId(@Param("presetId") Long presetId, Pageable pageable);

    /** bulk 导入 merge key 之一:有 vid 时按平台 vid 找已存在的行。 */
    Optional<PublishedVideo> findByPlatformAndPlatformVideoId(String platform, String platformVideoId);

    /**
     * bulk 导入 merge key 之二:vid 缺省时按 (scriptId, platform, publishedAt) 三元组找。
     * 同脚本同平台同发布时间 = 同一条视频的不同字段补录,字段级 merge 而不是再建一条。
     * publishedAt 入参是这一条 merge 的精确时间戳。
     */
    Optional<PublishedVideo> findByScriptIdAndPlatformAndPublishedAt(
            Long scriptId, String platform, LocalDateTime publishedAt);

    /**
     * merge key 之三:即使 vid 与 scriptId 都对不上,(platform, title, publishedAt) 三元组
     * 也能识别同一条视频 —— 抖音/快手/B站 一个账号不可能同一秒发两条完全同名视频。
     * 防止抖音不同入口给出不同 ID 字段(aweme_id vs item_id)时重复插入。
     */
    Optional<PublishedVideo> findFirstByPlatformAndTitleAndPublishedAt(
            String platform, String title, LocalDateTime publishedAt);

    /** 去重扫库:按 (platform,title,publishedAt) 分组找有 ≥2 条的组。返回组键里的 ID 列表(ASC)。 */
    @org.springframework.data.jpa.repository.Query(
        "SELECT v FROM PublishedVideo v WHERE EXISTS ("
        + " SELECT 1 FROM PublishedVideo v2"
        + " WHERE v2.platform = v.platform AND v2.title = v.title"
        + " AND v2.publishedAt = v.publishedAt AND v2.id <> v.id"
        + ") ORDER BY v.platform, v.title, v.publishedAt, v.id ASC")
    List<PublishedVideo> findDuplicateGroups();
}
