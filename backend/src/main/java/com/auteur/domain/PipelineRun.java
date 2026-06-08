package com.auteur.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "pipeline_run")
@Getter
@Setter
public class PipelineRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "topic_id")
    private Long topicId;

    @Column(name = "script_id")
    private Long scriptId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PipelineStage stage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PipelineRunStatus status = PipelineRunStatus.PENDING;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "error_msg", length = 1000)
    private String errorMsg;

    @Column(name = "last_completed_index", nullable = false)
    private Integer lastCompletedIndex = 0;

    @Column(name = "total_items", nullable = false)
    private Integer totalItems = 0;

    @Column(name = "pause_requested", nullable = false)
    private Boolean pauseRequested = false;

    @Column(name = "params_json", columnDefinition = "TEXT")
    private String paramsJson;

    @Column(name = "triggered_by", length = 60)
    private String triggeredBy;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
