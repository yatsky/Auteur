package com.auteur.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface VideoAssetRepository extends JpaRepository<VideoAsset, Long> {
    List<VideoAsset> findByScriptIdOrderByIdDesc(Long scriptId);

    @Transactional
    void deleteByScriptId(Long scriptId);
}
