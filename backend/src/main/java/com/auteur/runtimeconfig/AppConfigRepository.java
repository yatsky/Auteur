package com.auteur.runtimeconfig;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AppConfigRepository extends JpaRepository<AppConfig, String> {
    List<AppConfig> findAllByOrderByCategoryAscSortOrderAsc();
}
