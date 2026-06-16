package com.auteur.web;

import com.auteur.cover.CoverContentExtractor;
import com.auteur.cover.CoverGenerationService;
import com.auteur.domain.CoverAsset;
import com.auteur.domain.CoverAssetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class CoverController {

    private final CoverGenerationService generationService;
    private final CoverContentExtractor extractor;
    private final CoverAssetRepository coverRepo;

    public record GenerateRequest(String templateId, String titleText, String heroImageUrl) {}

    @PostMapping("/api/scripts/{scriptId}/covers/generate-async")
    public Map<String, Object> generateAsync(@PathVariable Long scriptId,
                                             @RequestBody(required = false) GenerateRequest body) {
        GenerateRequest b = body != null ? body : new GenerateRequest(null, null, null);
        CoverGenerationService.GenerateParams p = new CoverGenerationService.GenerateParams(
                b.templateId(), b.titleText(), b.heroImageUrl());
        Long runId = generationService.generateAsync(scriptId, p, "API");
        return Map.of("runId", runId);
    }

    @GetMapping("/api/scripts/{scriptId}/covers")
    public List<CoverAsset> list(@PathVariable Long scriptId) {
        return coverRepo.findByScriptIdOrderByIdDesc(scriptId);
    }

    @GetMapping("/api/scripts/{scriptId}/covers/defaults")
    public CoverContentExtractor.Defaults defaults(@PathVariable Long scriptId) {
        return extractor.extract(scriptId);
    }

    @PostMapping("/api/covers/{id}/finalize")
    public CoverAsset finalize(@PathVariable Long id) {
        CoverAsset target = coverRepo.findById(id).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Cover not found: " + id));
        // 同 script 同 ratio 独占：批量清零其它行（一条 UPDATE 取代 N 次 save）
        if (target.getRatio() != null) {
            coverRepo.clearFinalExcept(target.getScriptId(), target.getRatio(), id);
        }
        target.setIsFinal(true);
        return coverRepo.save(target);
    }
}
