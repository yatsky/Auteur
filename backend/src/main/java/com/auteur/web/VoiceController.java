package com.auteur.web;

import com.auteur.domain.VoiceAsset;
import com.auteur.domain.VoiceAssetRepository;
import com.auteur.voice.VoiceGenService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 配音 / 字幕 API。所有真正的产物都通过 voice_asset 表流转,
 * UI 通过 GET /api/runs/{runId} 轮询异步进度,DONE 后再拉 listVoices 刷新展示。
 */
@RestController
@RequestMapping("/api/scripts/{scriptId}/voice")
@RequiredArgsConstructor
public class VoiceController {

    private final VoiceGenService voiceGenService;
    private final VoiceAssetRepository voiceAssetRepository;

    public record GenerateRequest(String voiceModel, String voiceLabel,
                                  BigDecimal speed, Integer pitch,
                                  String subtitleStyle, Boolean markFinal) {}

    @PostMapping("/generate-async")
    public Map<String, Object> generateAsync(@PathVariable Long scriptId,
                                             @RequestBody GenerateRequest body) {
        VoiceGenService.GenParams p = new VoiceGenService.GenParams(
                body.voiceModel(), body.voiceLabel(),
                body.speed(), body.pitch(),
                body.subtitleStyle(), body.markFinal());
        Long runId = voiceGenService.generateAsync(scriptId, p, "API");
        return Map.of("runId", runId);
    }

    @GetMapping
    public List<VoiceAsset> list(@PathVariable Long scriptId) {
        return voiceAssetRepository.findByScriptIdOrderByIdDesc(scriptId);
    }
}
