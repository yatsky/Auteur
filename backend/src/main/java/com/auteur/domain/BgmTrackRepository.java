package com.auteur.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BgmTrackRepository extends JpaRepository<BgmTrack, Long> {
    Optional<BgmTrack> findByJamendoId(Long jamendoId);

    List<BgmTrack> findByMoodTagOrderByIdAsc(String moodTag);
}
