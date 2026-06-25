package com.auteur.web;

import com.auteur.voice.VoiceDemoService;
import com.auteur.voice.volcano.VolcanoVoiceCatalog;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 火山预训练音色目录给前端用。**单一来源** — 跟 LLM prompt 里给的列表是同一份(VolcanoVoiceCatalog)。
 * 前端 frontend/src/lib/voices.ts 只保留 UI 分组规则,音色定义本身都从这里拉。
 * 同时挂一条 /demo 试听端点。
 */
@RestController
@RequestMapping("/api/voice")
@RequiredArgsConstructor
public class VoiceCatalogController {

    private final VolcanoVoiceCatalog catalog;
    private final VoiceDemoService demoService;

    /** 一次性返回 default + 完整 voices 列表。前端启动时拉一次缓存。 */
    public record CatalogResponse(String defaultVoice, List<VolcanoVoiceCatalog.Voice> voices) {}

    @GetMapping("/catalog")
    public CatalogResponse catalog() {
        return new CatalogResponse(catalog.defaultVoice(), catalog.all());
    }

    public record DemoRequest(String voiceType, Double speed) {}

    /** 同 voiceType + speed 命中缓存零成本;切 speed 才重合成。 */
    @PostMapping("/demo")
    public Map<String, String> demo(@RequestBody DemoRequest body) {
        String url = demoService.getDemoUrl(body.voiceType(), body.speed());
        return Map.of("audioUrl", url);
    }
}
