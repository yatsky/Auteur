package com.auteur.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SeriesRepository extends JpaRepository<Series, Long> {
    Optional<Series> findByName(String name);
    Optional<Series> findBySlug(String slug);
}
