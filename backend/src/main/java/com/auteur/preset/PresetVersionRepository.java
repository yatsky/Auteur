package com.auteur.preset;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PresetVersionRepository extends JpaRepository<PresetVersion, Long> {

    /** 拿某 preset 的所有版本,新版在前。 */
    List<PresetVersion> findByPresetIdOrderByVersionDesc(Long presetId);

    Optional<PresetVersion> findByPresetIdAndVersion(Long presetId, Integer version);
}
