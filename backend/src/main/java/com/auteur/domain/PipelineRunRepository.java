package com.auteur.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PipelineRunRepository extends JpaRepository<PipelineRun, Long> {

    Page<PipelineRun> findByTopicIdOrderByIdDesc(Long topicId, Pageable pageable);

    Page<PipelineRun> findByScriptIdOrderByIdDesc(Long scriptId, Pageable pageable);

    Optional<PipelineRun> findFirstByScriptIdAndStageOrderByIdDesc(Long scriptId, PipelineStage stage);

    Optional<PipelineRun> findFirstByTopicIdAndStageOrderByIdDesc(Long topicId, PipelineStage stage);

    Optional<PipelineRun> findFirstByStageOrderByIdDesc(PipelineStage stage);

    /** Startup sweep: finds RUNNING rows orphaned by JVM crash/restart. */
    List<PipelineRun> findByStatus(PipelineRunStatus status);

    Page<PipelineRun> findAllByOrderByIdDesc(Pageable pageable);

    /**
     * 给 ScriptController.list 批量 enrich "最近一条 run" 用:
     * 每个 scriptId 的 max(run.id) 对应的整行(只查这批 scriptIds)。
     */
    @Query("""
            SELECT pr FROM PipelineRun pr
            WHERE pr.id IN (
                SELECT MAX(pr2.id) FROM PipelineRun pr2
                WHERE pr2.scriptId IN :scriptIds
                GROUP BY pr2.scriptId
            )
            """)
    List<PipelineRun> findLatestRunsByScriptIds(@Param("scriptIds") Collection<Long> scriptIds);
}
