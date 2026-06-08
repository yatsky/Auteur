package com.auteur.pipeline;

import com.auteur.domain.PipelineRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/**
 * 减法之后的 pipeline run 只读端点。仅保留 GET /{id} 给创作流程页面轮询异步 run 状态。
 * 列表 / 暂停 / 取消 / 重跑 都属于 tools UI,本期已删。
 */
@RestController
@RequestMapping("/api/runs")
@RequiredArgsConstructor
public class PipelineRunController {

    private final PipelineRunRepository runRepo;

    @GetMapping("/{id}")
    public PipelineRunDto get(@PathVariable Long id) {
        return runRepo.findById(id)
                .map(PipelineRunDto::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "run not found: " + id));
    }
}
