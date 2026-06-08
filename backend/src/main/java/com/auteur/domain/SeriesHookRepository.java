package com.auteur.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface SeriesHookRepository extends JpaRepository<SeriesHook, Long> {

    /** GET /api/series-hooks?status=unresolved 用 —— STRONG 且未兑现(toTopicId IS NULL)且未忽略(dismissedAt IS NULL)。 */
    List<SeriesHook> findByStrengthAndToTopicIdIsNullAndDismissedAtIsNullOrderByCreatedAtDesc(String strength);

    /** GET /api/series-hooks?status=unresolved&includeWeak=true 用 —— 不限 strength,其余条件相同。WEAK 在前端 banner 折叠区。 */
    List<SeriesHook> findByToTopicIdIsNullAndDismissedAtIsNullOrderByCreatedAtDesc();

    /** GET /api/series-hooks?status=dismissed 用 —— 已忽略且未兑现的钩子,按忽略时间倒序。给"已忽略"抽屉用。 */
    List<SeriesHook> findByDismissedAtIsNotNullAndToTopicIdIsNullOrderByDismissedAtDesc();

    /** 反查某个 script 是否已抽过钩子(避免重复触发抽取时插重)。 */
    List<SeriesHook> findByFromScriptId(Long fromScriptId);

    /** 原地重新生成时,把这条 script 之前抽出来的下集钩子全删掉 —— 新文本会重新触发 hook 抽取. */
    @Transactional
    void deleteByFromScriptId(Long fromScriptId);
}

