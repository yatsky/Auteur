package com.auteur.pipeline;

import com.auteur.domain.PipelineRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

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
