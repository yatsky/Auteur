package com.auteur.script;

import com.auteur.domain.PipelineRun;
import com.auteur.domain.PipelineRunStatus;
import com.auteur.domain.PipelineStage;
import com.auteur.domain.Script;
import com.auteur.domain.ScriptStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * GET /api/scripts 列表元素。打平 Script + topic.projectName + 该 script 最近一条 PipelineRun
 * 的 stage/status/at —— 让前端 ScriptList / VoiceStudio / CoverList 等工作台不用再
 * 从 PipelineRun 列表凑合反查。
 */
public record ScriptListDto(
        Long id,
        Long topicId,
        String projectName,
        Integer version,
        String modelUsed,
        Integer wordCount,
        Integer durationSeconds,
        ScriptStatus status,
        BigDecimal reviewScore,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        PipelineStage lastRunStage,
        PipelineRunStatus lastRunStatus,
        LocalDateTime lastRunAt
) {
    public static ScriptListDto from(Script s, String projectName, PipelineRun latestRun) {
        return new ScriptListDto(
                s.getId(),
                s.getTopicId(),
                projectName,
                s.getVersion(),
                s.getModelUsed(),
                s.getWordCount(),
                s.getDurationSeconds(),
                s.getStatus(),
                s.getReviewScore(),
                s.getCreatedAt(),
                s.getUpdatedAt(),
                latestRun != null ? latestRun.getStage() : null,
                latestRun != null ? latestRun.getStatus() : null,
                latestRun != null ? latestRun.getCreatedAt() : null
        );
    }
}
