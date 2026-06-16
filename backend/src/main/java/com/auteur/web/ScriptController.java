package com.auteur.web;

import com.auteur.domain.FactCheckIssue;
import com.auteur.domain.FactCheckIssueRepository;
import com.auteur.domain.ImageAsset;
import com.auteur.domain.ImageAssetRepository;
import com.auteur.domain.PipelineRun;
import com.auteur.domain.PipelineRunRepository;
import com.auteur.domain.Script;
import com.auteur.domain.ScriptRepository;
import com.auteur.domain.ScriptSection;
import com.auteur.domain.ScriptSectionRepository;
import com.auteur.domain.SeriesHook;
import com.auteur.domain.StoryboardShot;
import com.auteur.domain.StoryboardShotRepository;
import com.auteur.domain.TopicRepository;
import com.auteur.image.ImageAuditService;
import com.auteur.image.ImageGenService;
import com.auteur.script.FactCheckService;
import com.auteur.script.HookExtractor;
import com.auteur.script.ScriptAlignmentService;
import com.auteur.script.ScriptListDto;
import com.auteur.script.ScriptService;
import com.auteur.storyboard.StoryboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/scripts")
@RequiredArgsConstructor
public class ScriptController {

    private final ScriptRepository scriptRepository;
    private final ScriptSectionRepository sectionRepository;
    private final FactCheckIssueRepository issueRepository;
    private final FactCheckService factCheckService;
    private final ScriptService scriptService;
    private final ScriptAlignmentService alignmentService;
    private final StoryboardService storyboardService;
    private final StoryboardShotRepository shotRepository;
    private final ImageGenService imageGenService;
    private final ImageAuditService imageAuditService;
    private final ImageAssetRepository assetRepository;
    private final HookExtractor hookExtractor;
    private final TopicRepository topicRepository;
    private final PipelineRunRepository runRepository;
    private final com.auteur.preset.PresetRepository presetRepository;

    /** 列表分页:支持 ?topicId 过滤;批量 enrich projectName + 每个 script 最近一条 run 的 stage/status/at。 */
    @GetMapping
    public Page<ScriptListDto> list(
            @RequestParam(value = "topicId", required = false) Long topicId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "30") int size
    ) {
        PageRequest pr = PageRequest.of(page, size);
        Page<Script> p = topicId != null
                ? scriptRepository.findByTopicIdOrderByIdDesc(topicId, pr)
                : scriptRepository.findByOrderByIdDesc(pr);
        if (p.isEmpty()) return p.map(s -> ScriptListDto.from(s, null, null));

        // batch enrich projectName (按 topicId 反查 topic)
        List<Long> topicIds = p.getContent().stream()
                .map(Script::getTopicId).filter(java.util.Objects::nonNull).distinct().toList();
        Map<Long, String> projectNameByTopic = new HashMap<>();
        if (!topicIds.isEmpty()) {
            for (Object[] row : topicRepository.findProjectNamesByIds(topicIds)) {
                projectNameByTopic.put((Long) row[0], (String) row[1]);
            }
        }

        // batch enrich latestRun
        List<Long> scriptIds = p.getContent().stream().map(Script::getId).toList();
        Map<Long, PipelineRun> latestByScript = runRepository.findLatestRunsByScriptIds(scriptIds)
                .stream().collect(Collectors.toMap(PipelineRun::getScriptId, r -> r));

        return p.map(s -> ScriptListDto.from(
                s,
                projectNameByTopic.get(s.getTopicId()),
                latestByScript.get(s.getId())
        ));
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable Long id) {
        Script s = scriptRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Script not found: " + id));
        List<ScriptSection> sections = sectionRepository.findByScriptIdOrderBySectionCodeAsc(id);
        // 响应附带 presetName + bgmLocked,前端 preset-aware 组件(BgmPicker 等)一次拿到
        String presetName = null;
        Boolean bgmLocked = null;
        Long topicId = s.getTopicId();
        if (topicId != null) {
            Long presetId = topicRepository.findById(topicId)
                    .map(t -> t.getPresetId())
                    .orElse(null);
            if (presetId != null) {
                com.auteur.preset.Preset preset = presetRepository.findById(presetId).orElse(null);
                if (preset != null) {
                    presetName = preset.getName();
                    bgmLocked = preset.isBgmLocked();
                }
            }
        }
        Map<String, Object> resp = new java.util.HashMap<>();
        resp.put("script", s);
        resp.put("sections", sections);
        resp.put("presetName", presetName);
        resp.put("bgmLocked", bgmLocked);
        return resp;
    }

    /** published_video / pipeline_run 是软引用,保留作历史。 */
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        if (!scriptRepository.existsById(id)) {
            throw new NotFoundException("Script not found: " + id);
        }
        scriptRepository.deleteById(id);
        return Map.of("ok", true, "id", id);
    }

    @PutMapping("/{scriptId}/sections/{sectionId}")
    public ScriptSection updateSection(@PathVariable Long scriptId,
                                       @PathVariable Long sectionId,
                                       @RequestBody UpdateSectionBody body) {
        return scriptService.updateSection(scriptId, sectionId,
                body.textContent(), body.title());
    }

    public record UpdateSectionBody(String textContent, String title) {}

    @PostMapping("/{id}/factcheck")
    public List<FactCheckIssue> factCheck(@PathVariable Long id) {
        return factCheckService.factCheck(id);
    }

    @PostMapping("/{id}/factcheck-async")
    public Map<String, Object> factCheckAsync(@PathVariable Long id) {
        Long runId = factCheckService.factCheckAsync(id, "API");
        return Map.of("runId", runId);
    }

    @GetMapping("/{id}/issues")
    public List<FactCheckIssue> issues(@PathVariable Long id) {
        return issueRepository.findByScriptIdOrderBySeverityAscIdAsc(id);
    }

    @PostMapping("/{id}/storyboard/generate")
    public List<StoryboardShot> generateStoryboard(@PathVariable Long id,
                                                   @RequestParam(value = "force", required = false, defaultValue = "false") boolean force) {
        return storyboardService.generate(id, force);
    }

    @PostMapping("/{id}/storyboard/generate-async")
    public Map<String, Object> generateStoryboardAsync(@PathVariable Long id,
                                                       @RequestParam(value = "force", required = false, defaultValue = "false") boolean force) {
        Long runId = storyboardService.generateAsync(id, force, "API");
        return Map.of("runId", runId);
    }

    @GetMapping("/{id}/shots")
    public List<StoryboardShot> shots(@PathVariable Long id) {
        return shotRepository.findByScriptIdOrderByShotIndexAsc(id);
    }

    @PostMapping("/{id}/images/generate")
    public List<ImageAsset> generateImages(@PathVariable Long id,
                                           @RequestParam(value = "limit", required = false) Integer limit) {
        return imageGenService.generateForScript(id, limit);
    }

    @PostMapping("/{id}/images/generate-async")
    public Map<String, Object> generateImagesAsync(@PathVariable Long id,
                                                   @RequestParam(value = "limit", required = false) Integer limit,
                                                   @RequestParam(value = "force", required = false, defaultValue = "false") boolean force) {
        Long runId = imageGenService.generateForScriptAsync(id, force, limit, "API");
        return Map.of("runId", runId);
    }

    @PostMapping("/{id}/images/audit")
    public List<ImageAsset> auditImages(@PathVariable Long id) {
        return imageAuditService.auditScript(id);
    }

    @PostMapping("/{id}/images/audit-async")
    public Map<String, Object> auditImagesAsync(@PathVariable Long id) {
        Long runId = imageAuditService.auditScriptAsync(id, "API");
        return Map.of("runId", runId);
    }

    @GetMapping("/{id}/images")
    public List<ImageAsset> images(@PathVariable Long id) {
        List<StoryboardShot> all = shotRepository.findByScriptIdOrderByShotIndexAsc(id);
        List<ImageAsset> out = new ArrayList<>();
        for (StoryboardShot s : all) {
            out.addAll(assetRepository.findByShotIdOrderByIdAsc(s.getId()));
        }
        return out;
    }

    /** 同步抽下集钩子并返回新落库的 SeriesHook。用来对历史 script 回填 / 调 prompt。 */
    @PostMapping("/{id}/extract-hook")
    public SeriesHook extractHook(@PathVariable Long id) {
        return hookExtractor.extract(id);
    }

    /** 清空下游(分镜/图/语音/视频/封面/钩子/事实核查)后用 LLM 重写,保留 Script.id 与 version 不变。 */
    @PostMapping("/{id}/regenerate-async")
    public Map<String, Object> regenerateAsync(@PathVariable Long id, @RequestBody(required = false) RegenerateBody body) {
        String anchor = body == null ? null : body.anchor();
        Long runId = scriptService.regenerateInPlaceAsync(id, anchor, "API");
        return Map.of("runId", runId);
    }

    public record RegenerateBody(String anchor) {}

    /** 同步执行:有 voice+SRT 走真实对齐,否则按 4.5 字/秒估算。 */
    @PostMapping("/{id}/align-timing")
    public ScriptAlignmentService.AlignmentResult alignTiming(@PathVariable Long id) {
        return alignmentService.align(id);
    }
}
