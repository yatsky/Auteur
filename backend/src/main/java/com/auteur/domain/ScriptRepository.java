package com.auteur.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

public interface ScriptRepository extends JpaRepository<Script, Long> {
    List<Script> findByTopicIdOrderByVersionDesc(Long topicId);
    long countByTopicId(Long topicId);

    /** 每 topic 一脚本,生成新脚本前先 cascade 删除旧脚本(连带 sections/shots/images/voice/video/cover)。 */
    @Modifying
    @Transactional
    @Query("DELETE FROM Script s WHERE s.topicId = :topicId")
    int deleteByTopicId(@Param("topicId") Long topicId);

    /** GET /api/scripts 列表分页:全量按 id desc(最近的在前)。 */
    Page<Script> findByOrderByIdDesc(Pageable pageable);

    /** GET /api/scripts?topicId=N 列表分页:某 topic 下的所有 script 按 id desc。 */
    Page<Script> findByTopicIdOrderByIdDesc(Long topicId, Pageable pageable);

    /** [topicId, MAX(scriptId)] 元组,给 TopicController.list 反向回填 latestScriptId 用。 */
    @Query("SELECT s.topicId, MAX(s.id) FROM Script s WHERE s.topicId IN :topicIds GROUP BY s.topicId")
    List<Object[]> findLatestScriptIdsByTopicIds(@Param("topicIds") Collection<Long> topicIds);

    /** [scriptId, topicId] 元组,给 PipelineRunController.list 把 run.scriptId 反推 topicId 用(只批一次)。 */
    @Query("SELECT s.id, s.topicId FROM Script s WHERE s.id IN :scriptIds")
    List<Object[]> findTopicIdsByScriptIds(@Param("scriptIds") Collection<Long> scriptIds);
}
