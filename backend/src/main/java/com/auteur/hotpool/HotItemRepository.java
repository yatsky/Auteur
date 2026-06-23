package com.auteur.hotpool;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface HotItemRepository extends JpaRepository<HotItem, Long> {

    Optional<HotItem> findBySourceIdAndExternalId(Long sourceId, String externalId);

    Page<HotItem> findByStatus(String status, Pageable pageable);

    /**
     * 列表过滤 — status / sourceIds / 时间下界。
     * 标签 / 关键词 / 最低热度过滤都放 Service 层做内存过滤(数据量按周计 < 数千行,够用)。
     */
    @Query("""
            select i from HotItem i
            where (:status is null or i.status = :status)
              and (:since is null or i.publishedAt >= :since or i.fetchedAt >= :since)
            """)
    List<HotItem> findAllForFilter(@Param("status") String status,
                                   @Param("since") LocalDateTime since);

    List<HotItem> findBySourceIdOrderByFetchedAtDesc(Long sourceId);
}
