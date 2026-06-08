package com.auteur.preset;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PresetRepository extends JpaRepository<Preset, Long> {

    Optional<Preset> findByName(String name);

    boolean existsByName(String name);

    /** 列出 visibility = 'public' 的预设,按 updated_at 倒序。给"主题广场/默认场景"用。 */
    List<Preset> findByVisibilityOrderByUpdatedAtDesc(String visibility);

    /** owner_name 自己 + visibility=public 的合集。给 admin 模式用。 */
    List<Preset> findByOwnerNameOrVisibilityOrderByUpdatedAtDesc(String ownerName, String visibility);
}
