package com.auteur.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WeeklyReviewRepository extends JpaRepository<WeeklyReview, Long> {
    Optional<WeeklyReview> findByWeekCode(String weekCode);
}
