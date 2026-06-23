package com.auteur.hotpool;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface HotSourceRepository extends JpaRepository<HotSource, Long> {

    Optional<HotSource> findByName(String name);

    List<HotSource> findAllByOrderByIdAsc();

    List<HotSource> findByEnabledTrueOrderByIdAsc();

    @Query("select count(s) from HotSource s")
    long countAll();
}
