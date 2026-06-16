package com.auteur.web;

import com.auteur.domain.TopicStatus;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * PATCH /api/topics/{id} 的 body。服务端只 apply 非 null 字段(JSON 里没传 = 不动)。
 */
@Data
public class TopicUpdateRequest {

    @Size(max = 200)
    private String title;

    @Size(max = 40)
    private String projectName;

    @Size(max = 40)
    private String dynasty;

    @Size(max = 40)
    private String genre;

    @Size(max = 120)
    private String protagonist;

    @Size(max = 40)
    private String hookType;

    @Size(max = 40)
    private String emotion;

    @Min(1) @Max(60)
    private Integer durationMinutes;

    @DecimalMin("0.0") @DecimalMax("100.0")
    private BigDecimal potentialScore;

    private String historicalReference;

    private Long seriesId;

    private TopicStatus status;

    private String notes;

    /** 导演笔记 JSON 字符串。null = 不动;空串 / "null" = 清空。 */
    private String directorNote;

    private Long presetId;

    /** 用户填的字段。null = 不动;空串 / "null" = 清空。 */
    private String presetInputJson;
}

