package com.auteur.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface ScriptSectionRepository extends JpaRepository<ScriptSection, Long> {
    /**
     * 方法名是 OrderBySectionCodeAsc(为了不改 8 个 caller 保留旧名),实际按 start_seconds 排序。
     *
     * 原因:section_code 可能是 Unicode 圆圈数字 ①②③...⑩,MySQL 的 utf8mb4_general_ci /
     * utf8mb4_0900_ai_ci collation 会把它们 normalize 成对应阿拉伯数字做字典序,
     * 导致 "10" 排在 "2" 前面,出现 ① ⑩ ② ③ ... 顺序乱。
     *
     * 改用 start_seconds 排序:每个 section 在 script 生成时就有时间戳(单调递增),不受 collation 影响。
     * COALESCE 处理罕见的旧数据 startSeconds=null 情况。二级排序按 id 保证稳定。
     */
    @Query("SELECT s FROM ScriptSection s WHERE s.scriptId = :scriptId "
            + "ORDER BY COALESCE(s.startSeconds, 0) ASC, s.id ASC")
    List<ScriptSection> findByScriptIdOrderBySectionCodeAsc(@Param("scriptId") Long scriptId);

    @Transactional
    void deleteByScriptId(Long scriptId);
}
