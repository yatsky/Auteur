package com.auteur.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface VoiceAssetRepository extends JpaRepository<VoiceAsset, Long> {
    List<VoiceAsset> findByScriptIdOrderByIdDesc(Long scriptId);
    Optional<VoiceAsset> findFirstByScriptIdAndIsFinalTrueOrderByIdDesc(Long scriptId);

    @Transactional
    void deleteByScriptId(Long scriptId);
}
