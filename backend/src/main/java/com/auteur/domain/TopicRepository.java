package com.auteur.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface TopicRepository extends JpaRepository<Topic, Long> {
    Page<Topic> findByStatusOrderByIdDesc(TopicStatus status, Pageable pageable);

    /** [topicId, projectName] 元组,给 PipelineRun list 批量 enrich 用,避免 N+1。 */
    @Query("SELECT t.id, t.projectName FROM Topic t WHERE t.id IN :ids")
    List<Object[]> findProjectNamesByIds(@Param("ids") Collection<Long> ids);

    /** 系列详情:列出该系列下所有 topic,按 id desc(最近的在前)。 */
    List<Topic> findBySeriesIdOrderByIdDesc(Long seriesId);

    /** 系列列表 enrich:按 series_id 一次性出每个 series 的 topic 数量。 */
    @Query("SELECT t.seriesId, COUNT(t) FROM Topic t WHERE t.seriesId IN :seriesIds GROUP BY t.seriesId")
    List<Object[]> countBySeriesIds(@Param("seriesIds") Collection<Long> seriesIds);

    /** 删 preset 前预检:还有几条 topic 引用这个 preset。0 = 可以安全删。 */
    long countByPresetId(Long presetId);
}
