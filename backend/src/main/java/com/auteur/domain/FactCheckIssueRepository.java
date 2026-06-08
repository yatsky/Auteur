package com.auteur.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface FactCheckIssueRepository extends JpaRepository<FactCheckIssue, Long> {
    List<FactCheckIssue> findByScriptIdOrderBySeverityAscIdAsc(Long scriptId);

    /**
     * 重跑事实核查时,把同 scriptId 下还没人处理的旧 issue 全清掉 ——
     * 不然 LLM 又会把同一批事实再标一遍,前端列表里出现两份重复;
     * 已 resolved 的(用户已经修复/忽略过)保留作历史,不动.
     *
     * @Transactional 注在 repo 方法上 —— 异步事实核查路径外层没有事务,而 @Modifying 必须在事务里跑;
     * 之前在 service 上包了一层 @Transactional(REQUIRES_NEW) 但走的是 this.方法() 自调用,
     * 不经过 Spring AOP 代理,注解被忽略,运行时抛 TransactionRequiredException。
     */
    @Modifying
    @Transactional
    @Query("delete from FactCheckIssue f where f.scriptId = :scriptId and f.resolved = false")
    int deleteUnresolvedByScriptId(Long scriptId);

    /** 原地重新生成脚本时,把同 scriptId 下所有 issue 全清掉(连已 resolved 的都删) —— 因为段落整体重写后,旧 issue 引用的文本片段已不存在,留着会指向无效快照. */
    @Transactional
    void deleteByScriptId(Long scriptId);
}

