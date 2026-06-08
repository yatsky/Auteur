package com.auteur.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface StoryboardShotRepository extends JpaRepository<StoryboardShot, Long> {
    List<StoryboardShot> findByScriptIdOrderByShotIndexAsc(Long scriptId);
    long countByScriptId(Long scriptId);

    @Transactional
    void deleteByScriptId(Long scriptId);
}
