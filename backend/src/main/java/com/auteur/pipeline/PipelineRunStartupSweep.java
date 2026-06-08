package com.auteur.pipeline;

import com.auteur.domain.PipelineRun;
import com.auteur.domain.PipelineRunRepository;
import com.auteur.domain.PipelineRunStatus;
import com.auteur.domain.PipelineStage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 启动时扫一遍:上一次进程崩溃 / 重启时还在 RUNNING 的行肯定不是真在跑了。
 *
 * 区分两种情况:
 *   - async stage(IMAGEGEN / IMAGEAUDIT)且 last_completed_index>0:
 *     已持久化部分进度,标 PAUSED 让用户点"继续"。
 *   - 其他:没断点可恢复,标 FAILED。
 *
 * 选 ApplicationReadyEvent 而不是 @PostConstruct:要等 Spring Data 完全就绪。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PipelineRunStartupSweep {

    private static final Set<PipelineStage> RESUMABLE_STAGES = EnumSet.of(
            PipelineStage.IMAGEGEN, PipelineStage.IMAGEAUDIT);

    private final PipelineRunRepository runRepo;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void sweepOrphans() {
        List<PipelineRun> running = runRepo.findByStatus(PipelineRunStatus.RUNNING);
        if (running.isEmpty()) {
            log.info("[StartupSweep] no orphan RUNNING rows");
            return;
        }
        int pausedCount = 0;
        int failedCount = 0;
        LocalDateTime now = LocalDateTime.now();
        for (PipelineRun r : running) {
            if (canResume(r)) {
                // 不设 finishedAt:PAUSED 不是终态
                r.setStatus(PipelineRunStatus.PAUSED);
                r.setPauseRequested(false);
                r.setErrorMsg("system restart: paused at index "
                        + r.getLastCompletedIndex() + ", click resume to continue");
                pausedCount++;
            } else {
                r.setStatus(PipelineRunStatus.FAILED);
                r.setFinishedAt(now);
                r.setErrorMsg("system restart: worker did not finish");
                failedCount++;
            }
            runRepo.save(r);
        }
        log.warn("[StartupSweep] swept {} orphan RUNNING rows ({} -> PAUSED, {} -> FAILED)",
                running.size(), pausedCount, failedCount);
    }

    private boolean canResume(PipelineRun r) {
        return RESUMABLE_STAGES.contains(r.getStage())
                && r.getLastCompletedIndex() != null
                && r.getLastCompletedIndex() > 0;
    }
}
