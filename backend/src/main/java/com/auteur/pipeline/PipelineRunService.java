package com.auteur.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.auteur.common.text.TextUtils;
import com.auteur.domain.PipelineRun;
import com.auteur.domain.PipelineRunRepository;
import com.auteur.domain.PipelineRunStatus;
import com.auteur.domain.PipelineStage;
import com.auteur.web.NotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.LongToIntFunction;

/**
 * pipeline_run 的所有写入入口。每个 stage 服务通过这里 start / progress / finish。
 *
 * 关键点:
 *  - 每个状态变更都用 Propagation.REQUIRES_NEW,独立事务。即使外层业务事务回滚,
 *    pipeline_run 行也保留下来当审计线索。
 *  - shouldPause(runId) 是 worker 在 item 之间轮询用的;读取 pause_requested 字段。
 */
@Slf4j
@Service
public class PipelineRunService {

    private final PipelineRunRepository runRepo;
    private final Executor pipelineExecutor;
    private final com.auteur.notify.LarkNotifyService larkNotify;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PipelineRunService(PipelineRunRepository runRepo,
                              @Qualifier("pipelineExecutor") Executor pipelineExecutor,
                              com.auteur.notify.LarkNotifyService larkNotify) {
        this.runRepo = runRepo;
        this.pipelineExecutor = pipelineExecutor;
        this.larkNotify = larkNotify;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PipelineRun start(PipelineStage stage, Long topicId, Long scriptId,
                             Map<String, Object> params, String triggeredBy) {
        PipelineRun r = new PipelineRun();
        r.setStage(stage);
        r.setTopicId(topicId);
        r.setScriptId(scriptId);
        r.setStatus(PipelineRunStatus.RUNNING);
        r.setStartedAt(LocalDateTime.now());
        r.setTriggeredBy(triggeredBy != null ? triggeredBy : "API");
        r.setParamsJson(serialize(params));
        return runRepo.save(r);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markDone(Long runId, int totalItems) {
        runRepo.findById(runId).ifPresent(r -> {
            r.setStatus(PipelineRunStatus.DONE);
            r.setFinishedAt(LocalDateTime.now());
            r.setTotalItems(totalItems);
            r.setLastCompletedIndex(totalItems);
            runRepo.save(r);
            larkNotify.notifyStageDone(r);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long runId, String errorMsg) {
        runRepo.findById(runId).ifPresent(r -> {
            r.setStatus(PipelineRunStatus.FAILED);
            r.setFinishedAt(LocalDateTime.now());
            r.setErrorMsg(TextUtils.truncate(errorMsg, 1000));
            runRepo.save(r);
            larkNotify.notifyStageFailed(r, errorMsg);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markPaused(Long runId, int lastCompletedIndex) {
        runRepo.findById(runId).ifPresent(r -> {
            r.setStatus(PipelineRunStatus.PAUSED);
            r.setLastCompletedIndex(lastCompletedIndex);
            r.setPauseRequested(false);
            runRepo.save(r);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateProgress(Long runId, int completed, int total) {
        runRepo.findById(runId).ifPresent(r -> {
            r.setLastCompletedIndex(completed);
            r.setTotalItems(total);
            runRepo.save(r);
        });
    }

    /** 异步 SCRIPT stage 启动时 scriptId=null,worker 把 Script 写库后回填上来。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void setScriptId(Long runId, Long scriptId) {
        runRepo.findById(runId).ifPresent(r -> {
            r.setScriptId(scriptId);
            runRepo.save(r);
        });
    }

    /** 长任务 worker 在每个 item 前调一次。每次会查 DB,不能在 hot loop 里调。 */
    @Transactional(readOnly = true)
    public boolean shouldPause(Long runId) {
        return runRepo.findById(runId)
                .map(r -> Boolean.TRUE.equals(r.getPauseRequested())
                        || r.getStatus() == PipelineRunStatus.CANCELLED)
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public boolean isCancelled(Long runId) {
        return runRepo.findById(runId)
                .map(r -> r.getStatus() == PipelineRunStatus.CANCELLED)
                .orElse(false);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void requestPause(Long runId) {
        runRepo.findById(runId).ifPresent(r -> {
            if (r.getStatus() == PipelineRunStatus.RUNNING) {
                r.setPauseRequested(true);
                runRepo.save(r);
            }
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void cancel(Long runId) {
        runRepo.findById(runId).ifPresent(r -> {
            if (r.getStatus() == PipelineRunStatus.RUNNING
                    || r.getStatus() == PipelineRunStatus.PAUSED
                    || r.getStatus() == PipelineRunStatus.PENDING) {
                r.setStatus(PipelineRunStatus.CANCELLED);
                r.setFinishedAt(LocalDateTime.now());
                r.setPauseRequested(false);
                runRepo.save(r);
            }
        });
    }

    /** Resume:把 PAUSED 行重新置 RUNNING。返回 last_completed_index。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int resumeToRunning(Long runId) {
        return runRepo.findById(runId).map(r -> {
            if (r.getStatus() != PipelineRunStatus.PAUSED) {
                throw new IllegalStateException("Run " + runId + " is not PAUSED, status=" + r.getStatus());
            }
            r.setStatus(PipelineRunStatus.RUNNING);
            r.setStartedAt(LocalDateTime.now());
            r.setFinishedAt(null);
            r.setPauseRequested(false);
            runRepo.save(r);
            return r.getLastCompletedIndex() == null ? 0 : r.getLastCompletedIndex();
        }).orElseThrow(() -> new NotFoundException("Run not found: " + runId));
    }

    private String serialize(Map<String, Object> params) {
        if (params == null || params.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(params);
        } catch (Exception e) {
            log.warn("[PipelineRun] serialize params failed: {}", e.toString());
            return null;
        }
    }

    /**
     * 标准异步骨架:start → executor 内 try { work; markDone } catch { markFailed }。
     * 适用于"单次性 worker"(一次 LLM 调用 / 一次组合操作)。
     * 不适用于带 pause/cancel/throttle 的多步循环(那类自己写)。
     */
    public Long runAsync(PipelineStage stage, Long topicId, Long scriptId,
                         Map<String, Object> params, String triggeredBy,
                         String logTag, LongToIntFunction work) {
        PipelineRun run = start(stage, topicId, scriptId, params,
                triggeredBy != null ? triggeredBy : "API");
        Long runId = run.getId();
        pipelineExecutor.execute(() -> {
            try {
                int itemsDone = work.applyAsInt(runId);
                markDone(runId, itemsDone);
            } catch (RuntimeException e) {
                log.error("[{}] async worker crashed runId={}: {}",
                        logTag, runId, e.toString(), e);
                markFailed(runId, e.toString());
            }
        });
        return runId;
    }
}
