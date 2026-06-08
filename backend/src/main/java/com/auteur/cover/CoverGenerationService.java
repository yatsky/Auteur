package com.auteur.cover;

import com.auteur.domain.CoverAsset;
import com.auteur.domain.CoverAssetRepository;
import com.auteur.domain.PipelineRun;
import com.auteur.domain.PipelineStage;
import com.auteur.domain.Script;
import com.auteur.domain.ScriptRepository;
import com.auteur.domain.TopicStatus;
import com.auteur.pipeline.PipelineRunService;
import com.auteur.topic.TopicStatusAdvancer;
import com.auteur.voice.VoiceProperties;
import com.auteur.web.NotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * 一键生成 3 张封面:3:4(1080×1440)、4:3(1440×1080)、16:9(1920×1080)。走 PipelineRun 异步框架。
 */
@Slf4j
@Service
public class CoverGenerationService {

    private static final List<Ratio> RATIOS = List.of(
            new Ratio("3:4", 1080, 1440),
            new Ratio("4:3", 1440, 1080),
            new Ratio("16:9", 1920, 1080)
    );

    private static final String VOICE_URL_PREFIX = "/api/files/voice/";
    private static final String COVER_URL_PREFIX = "/api/files/cover/";
    private static final List<String> KNOWN_FILE_PREFIXES = List.of(
            "/api/files/voice/", "/api/files/video/", "/api/files/cover/", "/api/files/image/");

    private final Java2DCoverRenderer renderer;
    private final ScriptRepository scriptRepo;
    private final CoverAssetRepository coverRepo;
    private final BrandIdentityService brandService;
    private final PipelineRunService runService;
    private final Executor pipelineExecutor;
    private final VoiceProperties voiceProps;
    private final CoverProperties coverProps;
    private final TopicStatusAdvancer topicStatusAdvancer;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public CoverGenerationService(Java2DCoverRenderer renderer,
                                  ScriptRepository scriptRepo,
                                  CoverAssetRepository coverRepo,
                                  BrandIdentityService brandService,
                                  PipelineRunService runService,
                                  @Qualifier("pipelineExecutor") Executor pipelineExecutor,
                                  VoiceProperties voiceProps,
                                  CoverProperties coverProps,
                                  TopicStatusAdvancer topicStatusAdvancer) {
        this.renderer = renderer;
        this.scriptRepo = scriptRepo;
        this.coverRepo = coverRepo;
        this.brandService = brandService;
        this.runService = runService;
        this.pipelineExecutor = pipelineExecutor;
        this.voiceProps = voiceProps;
        this.coverProps = coverProps;
        this.topicStatusAdvancer = topicStatusAdvancer;
    }

    public record GenerateParams(String templateId, String titleText, String heroImageUrl) {}

    public Long generateAsync(Long scriptId, GenerateParams p, String triggeredBy) {
        scriptRepo.findById(scriptId).orElseThrow(
                () -> new NotFoundException("Script not found: " + scriptId));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("scriptId", scriptId);
        params.put("templateId", p.templateId());
        params.put("titleText", p.titleText());
        params.put("heroImageUrl", p.heroImageUrl());
        params.put("ratios", RATIOS.stream().map(Ratio::label).toList());

        PipelineRun run = runService.start(PipelineStage.COVER, null, scriptId, params,
                triggeredBy != null ? triggeredBy : "API");
        Long runId = run.getId();
        pipelineExecutor.execute(() -> runWorker(scriptId, p, runId));
        return runId;
    }

    private void runWorker(Long scriptId, GenerateParams p, Long runId) {
        Path heroPath = null;
        boolean heroIsTemp = false;
        try {
            BrandIdentity brand = brandService.getOrCreate();
            heroPath = resolveLocalPath(p.heroImageUrl());
            heroIsTemp = heroPath != null && heroPath.startsWith(
                    Paths.get(coverProps.getStorage().getWorkDir()).toAbsolutePath());
            if (p.heroImageUrl() != null && !p.heroImageUrl().isBlank() && heroPath == null) {
                log.warn("[CoverGen] runId={} heroImageUrl 解析失败,使用占位: {}", runId, p.heroImageUrl());
            }

            String templateId = p.templateId() != null && !p.templateId().isBlank()
                    ? p.templateId() : brand.getDefaultTemplateId();

            int total = RATIOS.size();
            runService.updateProgress(runId, 0, total);
            for (int i = 0; i < RATIOS.size(); i++) {
                Ratio r = RATIOS.get(i);
                Java2DCoverRenderer.RenderResult res = renderer.render(
                        new Java2DCoverRenderer.RenderRequest(
                                scriptId, r.label(), r.width(), r.height(), templateId,
                                p.titleText(), heroPath, brand));

                CoverAsset c = new CoverAsset();
                c.setScriptId(scriptId);
                c.setRatio(r.label());
                c.setWidth(r.width());
                c.setHeight(r.height());
                c.setTemplateId(templateId);
                c.setTitleText(p.titleText());
                c.setHeroImageUrl(p.heroImageUrl());
                c.setFileUrl(res.url());
                c.setFileSizeBytes(res.sizeBytes());
                c.setRunId(runId);
                persistInNewTx(c);

                runService.updateProgress(runId, i + 1, total);
                log.info("[CoverGen] runId={} scriptId={} ratio={} done file={}",
                        runId, scriptId, r.label(), c.getFileUrl());
            }
            runService.markDone(runId, total);
            try {
                Long topicId = scriptRepo.findById(scriptId).map(Script::getTopicId).orElse(null);
                topicStatusAdvancer.advance(topicId, TopicStatus.PRODUCED, TopicStatus.PUBLISHED);
            } catch (RuntimeException ex) {
                log.warn("[CoverGen] topic status advance 失败 scriptId={} err={}", scriptId, ex.toString());
            }
        } catch (RuntimeException | IOException e) {
            log.error("[CoverGen] worker crashed runId={} scriptId={}: {}",
                    runId, scriptId, e.toString(), e);
            runService.markFailed(runId, e.toString());
        } finally {
            if (heroIsTemp && heroPath != null) {
                try { Files.deleteIfExists(heroPath); } catch (IOException ignored) {}
            }
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CoverAsset persistInNewTx(CoverAsset c) {
        return coverRepo.save(c);
    }

    /**
     * /api/files/{voice|video|cover|image}/* → 本地路径;http(s) → 下到 work-dir 临时文件;
     * 解析失败返 null,worker 用占位渲染。
     */
    private Path resolveLocalPath(String url) {
        if (url == null || url.isBlank()) return null;
        if (url.startsWith("data:")) return null;
        for (String prefix : KNOWN_FILE_PREFIXES) {
            if (url.startsWith(prefix)) {
                String name = url.substring(prefix.length());
                String localBase = switch (prefix) {
                    case "/api/files/voice/" -> voiceProps.getStorage().getLocalDir();
                    case "/api/files/cover/" -> coverProps.getStorage().getLocalDir();
                    case "/api/files/video/" -> "./storage/video";
                    case "/api/files/image/" -> "./storage/image";
                    default -> null;
                };
                if (localBase == null) return null;
                Path p = Paths.get(localBase).resolve(name).toAbsolutePath();
                return Files.exists(p) ? p : null;
            }
        }
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return downloadToWorkDir(url);
        }
        return null;
    }

    private Path downloadToWorkDir(String url) {
        try {
            Path workDir = Paths.get(coverProps.getStorage().getWorkDir()).toAbsolutePath();
            Files.createDirectories(workDir);
            Path tmp = workDir.resolve("hero-" + UUID.randomUUID() + extensionFromUrl(url));
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .GET().build();
            HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() / 100 != 2) {
                log.warn("[CoverGen] download hero failed status={} url={}", resp.statusCode(), url);
                return null;
            }
            try (InputStream in = resp.body()) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            return tmp;
        } catch (Exception e) {
            log.warn("[CoverGen] download hero crashed url={}: {}", url, e.toString());
            return null;
        }
    }

    private static String extensionFromUrl(String url) {
        int q = url.indexOf('?');
        String clean = q > 0 ? url.substring(0, q) : url;
        int dot = clean.lastIndexOf('.');
        if (dot < 0 || dot < clean.lastIndexOf('/')) return ".jpg";
        String ext = clean.substring(dot).toLowerCase();
        return ext.length() <= 6 ? ext : ".jpg";
    }

    private record Ratio(String label, int width, int height) {}
}
