package com.auteur.preset;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PresetRepository extends JpaRepository<Preset, Long> {

    Optional<Preset> findByName(String name);

    boolean existsByName(String name);

    /** 全部预设按 updated_at 倒序。个人部署单用户,无 owner/visibility 过滤。 */
    default List<Preset> findAllByUpdatedAtDesc() {
        return findAll(Sort.by(Sort.Direction.DESC, "updatedAt"));
    }
}
