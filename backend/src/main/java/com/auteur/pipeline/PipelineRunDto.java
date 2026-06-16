package com.auteur.pipeline;

import com.auteur.domain.PipelineRun;
import com.auteur.domain.PipelineRunStatus;
import com.auteur.domain.PipelineStage;

import java.time.LocalDateTime;

public record PipelineRunDto(
        Long id,
        Long topicId,
        Long scriptId,
        PipelineStage stage,
        PipelineRunStatus status,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        String errorMsg,
        Integer lastCompletedIndex,
        Integer totalItems,
        Boolean pauseRequested,
        String paramsJson,
        String triggeredBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        // 列表场景反向 enrich:topic.project_name —— 所有"script 5"显示位都改 "5 红楼梦谜案"。
        // 单查不 enrich(可以走 GET /topics/{id} 或前端拼装),只列表时批查回填。
        String projectName
) {
    public static PipelineRunDto from(PipelineRun r) {
        return from(r, null);
    }

    public static PipelineRunDto from(PipelineRun r, String projectName) {
        return new PipelineRunDto(
                r.getId(), r.getTopicId(), r.getScriptId(), r.getStage(), r.getStatus(),
                r.getStartedAt(), r.getFinishedAt(), r.getErrorMsg(),
                r.getLastCompletedIndex(), r.getTotalItems(),
                r.getPauseRequested(), r.getParamsJson(), r.getTriggeredBy(),
                r.getCreatedAt(), r.getUpdatedAt(),
                projectName
        );
    }
}
