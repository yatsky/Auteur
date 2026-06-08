package com.auteur.web;

import com.auteur.domain.VideoAsset;
import com.auteur.domain.VideoAssetRepository;
import com.auteur.video.VideoAssemblyService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 视频合成 API。POST 触发异步渲染,GET 列出该 script 已生成的 video_asset。
 * 真正的进度通过 GET /api/runs/{runId} 轮询(同 IMAGEGEN / VOICE 模式)。
 */
@RestController
@RequestMapping("/api/scripts/{scriptId}/video")
@RequiredArgsConstructor
public class VideoController {

    private final VideoAssemblyService assemblyService;
    private final VideoAssetRepository videoAssetRepository;

    public record RenderRequest(Long voiceAssetId, String format,
                                Integer width, Integer height, Boolean markFinal) {}

    @PostMapping("/render-async")
    public Map<String, Object> renderAsync(@PathVariable Long scriptId,
                                           @RequestBody(required = false) RenderRequest body) {
        RenderRequest b = body != null ? body
                : new RenderRequest(null, null, null, null, Boolean.TRUE);
        VideoAssemblyService.RenderParams p = new VideoAssemblyService.RenderParams(
                b.voiceAssetId(), b.format(), b.width(), b.height(),
                b.markFinal() == null ? Boolean.TRUE : b.markFinal());
        Long runId = assemblyService.renderAsync(scriptId, p, "API");
        return Map.of("runId", runId);
    }

    @GetMapping
    public List<VideoAsset> list(@PathVariable Long scriptId) {
        return videoAssetRepository.findByScriptIdOrderByIdDesc(scriptId);
    }
}
