package com.auteur.preset;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PresetAssetRepository extends JpaRepository<PresetAsset, Long> {

    List<PresetAsset> findByPresetId(Long presetId);

    List<PresetAsset> findByPresetIdAndKind(Long presetId, String kind);
}
