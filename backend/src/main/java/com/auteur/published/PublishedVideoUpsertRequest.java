package com.auteur.published;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * published_video upsert payload。create 时 title/platform/publishedAt 必填;PATCH/bulk/extension 走手动校验。
 */
public record PublishedVideoUpsertRequest(
        Long scriptId,
        Long topicId,
        @NotBlank @Size(max = 300) String title,
        @Size(max = 40) String projectName,
        @NotBlank @Size(max = 40) String platform,
        @Size(max = 120) String platformVideoId,
        @NotNull LocalDateTime publishedAt,
        Integer durationSeconds,
        Long views,
        Long likes,
        Long comments,
        Long shares,
        BigDecimal retentionPct,
        BigDecimal avgPlaySeconds,
        BigDecimal drop2sPct,
        BigDecimal play5sPct,
        BigDecimal avgPlayRatioPct,
        BigDecimal favoriteRatePct,
        BigDecimal dislikeRatePct,
        BigDecimal hookCtr,
        BigDecimal costYuan,
        @Size(max = 100) String hookTemplate,
        String notes,
        @Size(max = 500) String coverUrl,
        BigDecimal likeRate,
        BigDecimal shareRate,
        BigDecimal commentRate,
        Integer subscribeCount,
        Integer unsubscribeCount,
        BigDecimal coverCtr,
        Integer homepageVisitCount
) {}
