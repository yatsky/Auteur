package com.auteur.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface GenreStatSnapshotRepository extends JpaRepository<GenreStatSnapshot, Long> {

    List<GenreStatSnapshot> findAllByOrderByPeriodEndDescPlatformAscGenreAsc();

    /** Bulk 导入 merge key:(period_start, period_end, platform, genre, vertical) 唯一,命中就字段级 merge。 */
    Optional<GenreStatSnapshot> findByPeriodStartAndPeriodEndAndPlatformAndGenreAndVertical(
            LocalDate periodStart, LocalDate periodEnd,
            String platform, String genre, String vertical);

    /** KpiDrift 体裁基准:取 period_end 最新的所有快照,在 controller 里再按 (platform, vertical) 取最近一份。 */
    List<GenreStatSnapshot> findByPlatformOrderByPeriodEndDesc(String platform);
}
