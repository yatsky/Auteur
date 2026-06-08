package com.auteur.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface ImageAssetRepository extends JpaRepository<ImageAsset, Long> {
    List<ImageAsset> findByShotIdOrderByIdAsc(Long shotId);
    Optional<ImageAsset> findFirstByShotIdOrderByIdDesc(Long shotId);
    long countByShotId(Long shotId);

    @Transactional
    void deleteByShotId(Long shotId);
}
