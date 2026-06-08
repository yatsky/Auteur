package com.auteur.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface CoverAssetRepository extends JpaRepository<CoverAsset, Long> {
    List<CoverAsset> findByScriptIdOrderByIdDesc(Long scriptId);

    @Transactional
    void deleteByScriptId(Long scriptId);

    /** 同 script 同 ratio 下，把 keepId 之外其它行的 isFinal 清零。返回受影响行数。 */
    @Modifying
    @Transactional
    @Query("UPDATE CoverAsset c SET c.isFinal = false " +
           "WHERE c.scriptId = :scriptId AND c.ratio = :ratio " +
           "AND c.id <> :keepId AND c.isFinal = true")
    int clearFinalExcept(@Param("scriptId") Long scriptId,
                         @Param("ratio") String ratio,
                         @Param("keepId") Long keepId);
}
