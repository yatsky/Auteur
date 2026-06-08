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
 * 火山预训练音色目录给前端用。前端 PersonaDrawer 的 select 直接从这里拉,
 * 跟后端 prompt 里给 LLM 的列表是同一份(VolcanoVoiceCatalog),保持一致。
 *
 * 同时挂一条 /demo 试听端点,前端选音色后点 ▶ 试听用。
 */
@RestController
@RequestMapping("/api/voice")
@RequiredArgsConstructor
public class VoiceCatalogController {

    private final VolcanoVoiceCatalog catalog;
    private final VoiceDemoService demoService;

    @GetMapping("/catalog")
    public List<VolcanoVoiceCatalog.Voice> list() {
        return catalog.all();
    }

    public record DemoRequest(String voiceType, Double speed) {}

    /**
     * 试听:用配置的 demoText + 入参 voiceType/speed 合成 mp3,缓存到 storage/voice/demo-*.mp3。
     * 同 voiceType + speed 命中缓存零成本;切 speed 才重合成。
     */
    @PostMapping("/demo")
    public Map<String, String> demo(@RequestBody DemoRequest body) {
        String url = demoService.getDemoUrl(body.voiceType(), body.speed());
        return Map.of("audioUrl", url);
    }
}
