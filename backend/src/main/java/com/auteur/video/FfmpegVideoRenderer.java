package com.auteur.video;

import com.auteur.common.path.VoiceFileResolver;
import com.auteur.storage.TosStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * ffmpeg 渲染器:concat demuxer + libass 烧字幕 + AAC 音轨。
 * standard 走 SRT + libass force_style;highlight 走 AssSubtitleWriter 关键词金色加粗。
 */
@Slf4j
@Component
@Primary
@ConditionalOnProperty(name = "auteur.video.provider", havingValue = "ffmpeg", matchIfMissing = true)
public class FfmpegVideoRenderer implements VideoRenderer {

    /** ffmpeg CPU 渲染零边际成本,留 0;接 shotstack 时再算。 */
    private static final BigDecimal COST_PER_SEC = BigDecimal.ZERO;

    private final VideoProperties props;
    private final VoiceFileResolver voiceFileResolver;
    private final TosStorageService tos;
    private final com.auteur.runtimeconfig.RuntimeConfig runtimeConfig;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15)).build();

    public FfmpegVideoRenderer(VideoProperties props, VoiceFileResolver voiceFileResolver, TosStorageService tos,
                               com.auteur.runtimeconfig.RuntimeConfig runtimeConfig) {
        this.props = props;
        this.voiceFileResolver = voiceFileResolver;
        this.tos = tos;
        this.runtimeConfig = runtimeConfig;
    }

    /** 合并 RuntimeConfig DB 值 + props yml 兜底。每次合成现读,改 DB 立即对下次合成生效。 */
    private VideoProperties.Ffmpeg effectiveFfmpeg() {
        VideoProperties.Ffmpeg base = props.getFfmpeg();
        VideoProperties.Ffmpeg c = new VideoProperties.Ffmpeg();
        c.setBinaryPath(base.getBinaryPath()); // 留 yml,不迁
        c.setTimeoutSeconds(runtimeConfig.getIntPositive("auteur.video.ffmpeg.timeout-seconds", base.getTimeoutSeconds()));
        c.setWidth(runtimeConfig.getIntPositive("auteur.video.ffmpeg.width", base.getWidth()));
        c.setHeight(runtimeConfig.getIntPositive("auteur.video.ffmpeg.height", base.getHeight()));
        c.setFps(runtimeConfig.getIntPositive("auteur.video.ffmpeg.fps", base.getFps()));
        c.setVideoBitrateKbps(runtimeConfig.getIntPositive("auteur.video.ffmpeg.video-bitrate-kbps", base.getVideoBitrateKbps()));
        c.setAudioBitrateKbps(runtimeConfig.getIntPositive("auteur.video.ffmpeg.audio-bitrate-kbps", base.getAudioBitrateKbps()));
        c.setSubtitleFont(osAwareSubtitleFont(base.getSubtitleFont()));
        c.setSubtitleFontSize(runtimeConfig.getIntPositive("auteur.video.ffmpeg.subtitle-font-size", base.getSubtitleFontSize()));
        c.setSubtitleMaxCharsPerLine(runtimeConfig.getIntPositive("auteur.video.ffmpeg.subtitle-max-chars-per-line", base.getSubtitleMaxCharsPerLine()));
        // marginV=0 是合法值(无边距),不强制 positive
        c.setSubtitleMarginV(runtimeConfig.getInt("auteur.video.ffmpeg.subtitle-margin-v", base.getSubtitleMarginV()));
        return c;
    }

    /** macOS 默认 PingFang SC;Linux/其它默认 Noto Sans CJK SC。 */
    private String osAwareSubtitleFont(String ymlFallback) {
        String fromDb = runtimeConfig.get("auteur.video.ffmpeg.subtitle-font");
        if (!fromDb.isBlank()) return fromDb;
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac") || os.contains("darwin")) {
            return ymlFallback != null && !ymlFallback.isBlank() ? ymlFallback : "PingFang SC";
        }
        return "Noto Sans CJK SC";
    }

    @Override
    public Result render(Request req) {
        if (req.clips() == null || req.clips().isEmpty()) {
            throw new IllegalArgumentException("FfmpegVideoRenderer: clips empty");
        }

        Path outDir  = ensureDir(Paths.get(props.getStorage().getLocalDir()));
        Path workDir = ensureDir(Paths.get(props.getStorage().getWorkDir())
                .resolve(UUID.randomUUID().toString()));

        // 一次抓取本次渲染用的有效 ffmpeg 配置(DB 优先,yml 兜底)
        VideoProperties.Ffmpeg cfg = effectiveFfmpeg();

        try {
            List<ImageClip> ordered = new ArrayList<>(req.clips());
            ordered.sort(Comparator.comparingInt(ImageClip::shotIndex));

            int width  = req.width()  > 0 ? req.width()  : cfg.getWidth();
            int height = req.height() > 0 ? req.height() : cfg.getHeight();

            List<Path> localImages = downloadImages(ordered, workDir);
            Path concatFile = workDir.resolve("concat.txt");
            writeConcatList(concatFile, localImages, ordered);

            Path audioPath = voiceFileResolver.resolve(req.audioUrl());
            Path srtPath   = voiceFileResolver.resolve(req.subtitleUrl());

            // ffmpeg subtitles= 滤镜对路径字符敏感,复制到 work 目录用简短文件名
            Path workSub = null;
            boolean isHighlight = "highlight".equalsIgnoreCase(req.subtitleStyle());
            if (req.subtitleUrl() == null || req.subtitleUrl().isBlank()) {
                log.warn("[剪辑·FFmpeg] scriptId={} subtitleUrl 为空 -> 不烧字幕。"
                        + "voice_asset.subtitle_url 为空,通常是 voice 合成时 TTS 未返回字幕或下载失败",
                        req.scriptId());
            } else if (srtPath == null) {
                log.warn("[剪辑·FFmpeg] scriptId={} subtitleUrl='{}' 无法解析为本地路径 -> 不烧字幕",
                        req.scriptId(), req.subtitleUrl());
            } else if (!Files.exists(srtPath)) {
                log.warn("[剪辑·FFmpeg] scriptId={} subtitle 本地文件不存在 -> 不烧字幕。期望: {}",
                        req.scriptId(), srtPath);
            } else {
                int maxChars = cfg.getSubtitleMaxCharsPerLine();
                if (isHighlight) {
                    workSub = workDir.resolve("subs.ass");
                    List<SrtParser.Cue> cues = SrtParser.parseFile(srtPath);
                    AssSubtitleWriter.AssStyle style = new AssSubtitleWriter.AssStyle(
                            cfg.getSubtitleFont(),
                            cfg.getSubtitleFontSize(),
                            cfg.getSubtitleMarginV());
                    AssSubtitleWriter.writeHighlight(cues, workSub, style, maxChars);
                    log.info("[剪辑·FFmpeg] scriptId={} subtitle highlight 就位: {} -> {} ({} bytes, {} cues, maxChars={})",
                            req.scriptId(), srtPath, workSub, Files.size(workSub), cues.size(), maxChars);
                } else {
                    workSub = workDir.resolve("subs.srt");
                    int wrapped = softWrapAndWriteSrt(srtPath, workSub, maxChars);
                    log.info("[剪辑·FFmpeg] scriptId={} subtitle 就位: {} -> {} ({} bytes, {} 条 cue 做了软断行, maxChars={})",
                            req.scriptId(), srtPath, workSub, Files.size(workSub), wrapped, maxChars);
                }
            }

            long ts = System.currentTimeMillis();
            String outName = String.format("script-%d-%d.mp4", req.scriptId(), ts);
            Path outPath = outDir.resolve(outName);

            int durationMs = runFfmpeg(concatFile, audioPath, workSub, outPath, width, height,
                    req.bgm(), isHighlight, cfg);

            int durationSec = (int) Math.max(1, Math.round(
                    ordered.stream().mapToDouble(ImageClip::durationSec).sum()));

            log.info("[剪辑·FFmpeg] scriptId={} clips={} audio={} sub={} -> {} ({}ms render, ~{}s video)",
                    req.scriptId(), ordered.size(),
                    audioPath != null ? "yes" : "none",
                    workSub != null   ? (isHighlight ? "ass" : "srt") : "none",
                    outName, durationMs, durationSec);

            String url = tos.upload(
                    TosStorageService.buildKey(req.scriptId(), "video", outName),
                    outPath, "video/mp4");
            deleteQuietly(outPath);
            return new Result(url, durationSec, width, height,
                    req.format() != null ? req.format() : "9:16", COST_PER_SEC);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("ffmpeg render failed: " + e.getMessage(), e);
        } finally {
            cleanWorkDir(workDir);
        }
    }

    // ---------------- 子步骤 ----------------

    private List<Path> downloadImages(List<ImageClip> clips, Path workDir) throws IOException, InterruptedException {
        List<Path> out = new ArrayList<>(clips.size());
        for (int i = 0; i < clips.size(); i++) {
            ImageClip c = clips.get(i);
            String url = c.imageUrl();
            String ext = guessExt(url);
            Path target = workDir.resolve(String.format("img-%03d%s", i, ext));
            if (url.startsWith("http://") || url.startsWith("https://")) {
                HttpRequest hr = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(60)).GET().build();
                HttpResponse<InputStream> r = httpClient.send(hr, HttpResponse.BodyHandlers.ofInputStream());
                if (r.statusCode() / 100 != 2) {
                    throw new IOException("image download HTTP " + r.statusCode() + " for " + url);
                }
                try (InputStream in = r.body()) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } else if (url.startsWith("file:")) {
                Files.copy(Paths.get(URI.create(url)), target, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.copy(Paths.get(url), target, StandardCopyOption.REPLACE_EXISTING);
            }
            out.add(target);
        }
        return out;
    }

    /** ffconcat v1.0 demuxer 格式。最后一张文件名必须重复一次,否则 demuxer 会丢掉它。 */
    private void writeConcatList(Path concatFile, List<Path> images, List<ImageClip> clips) throws IOException {
        StringBuilder sb = new StringBuilder("ffconcat version 1.0\n");
        for (int i = 0; i < images.size(); i++) {
            sb.append("file '").append(images.get(i).getFileName()).append("'\n");
            double dur = clips.get(i).durationSec();
            if (dur <= 0) dur = 5.0;
            sb.append("duration ").append(dur).append('\n');
        }
        sb.append("file '").append(images.get(images.size() - 1).getFileName()).append("'\n");
        Files.writeString(concatFile, sb.toString());
    }

    /** 无音轨时去掉 audio 参数;无字幕时滤镜里去掉 subtitles= 链。有 BGM 必有人声。 */
    private int runFfmpeg(Path concatFile, Path audio, Path subtitle, Path outPath,
                          int width, int height, VideoRenderer.BgmConfig bgm,
                          boolean isHighlightAss, VideoProperties.Ffmpeg cfg)
            throws IOException, InterruptedException {

        boolean hasAudio = audio != null && Files.exists(audio);
        boolean hasBgm   = hasAudio && bgm != null && bgm.bgmFile() != null && Files.exists(bgm.bgmFile());

        List<String> cmd = new ArrayList<>();
        cmd.add(cfg.getBinaryPath());
        cmd.add("-y");
        cmd.add("-hide_banner");
        cmd.add("-loglevel"); cmd.add("error");
        cmd.add("-f"); cmd.add("concat");
        cmd.add("-safe"); cmd.add("0");
        cmd.add("-i"); cmd.add(concatFile.toAbsolutePath().toString());
        if (hasAudio) {
            cmd.add("-i"); cmd.add(audio.toAbsolutePath().toString());
        }
        if (hasBgm) {
            // -stream_loop -1 让 BGM 整片循环(BGM 通常比脚本短)
            cmd.add("-stream_loop"); cmd.add("-1");
            cmd.add("-i"); cmd.add(bgm.bgmFile().toAbsolutePath().toString());
        }

        StringBuilder vf = new StringBuilder();
        vf.append("scale=").append(width).append(':').append(height)
          .append(":force_original_aspect_ratio=decrease");
        vf.append(",pad=").append(width).append(':').append(height)
          .append(":(ow-iw)/2:(oh-ih)/2:color=black");
        vf.append(",fps=").append(cfg.getFps());
        if (subtitle != null && Files.exists(subtitle)) {
            String subPath = subtitle.toAbsolutePath().toString().replace("\\", "/");
            if (isHighlightAss) {
                // ASS 文件内嵌 [V4+ Styles],不要传 force_style — 会刷掉 inline {\c} 关键词高亮。
                vf.append(",subtitles=filename='").append(subPath).append("'");
            } else {
                // libass force_style:逗号要转义成 \, 否则会被 ffmpeg filter 解析器吃掉。
                // BorderStyle=1 = outline + shadow;=3 才是 opaque box。
                String style = "FontName=" + cfg.getSubtitleFont()
                        + "\\,FontSize=" + cfg.getSubtitleFontSize()
                        + "\\,PrimaryColour=&Hffffff&"
                        + "\\,OutlineColour=&H000000&"
                        + "\\,BorderStyle=1"
                        + "\\,Outline=3"
                        + "\\,Shadow=1"
                        + "\\,Alignment=2"
                        + "\\,MarginV=" + cfg.getSubtitleMarginV();
                vf.append(",subtitles=filename='").append(subPath).append("'")
                  .append(":force_style='").append(style).append("'");
            }
        }

        if (hasBgm) {
            // sidechaincompress:BGM 是被压缩的主输入,voice 是 sidechain key。人声段砍半 BGM。
            String fc = "[0:v]" + vf + "[vid];"
                    + "[2:a]volume=" + bgm.volume() + "[bgvol];"
                    + "[bgvol][1:a]sidechaincompress=threshold=0.05:ratio=8:attack=5:release=200[bgduck];"
                    + "[1:a][bgduck]amix=inputs=2:duration=first:dropout_transition=0[mix]";
            cmd.add("-filter_complex"); cmd.add(fc);
            cmd.add("-map"); cmd.add("[vid]");
            cmd.add("-map"); cmd.add("[mix]");
        } else {
            cmd.add("-vf"); cmd.add(vf.toString());
        }

        cmd.add("-c:v"); cmd.add("libx264");
        cmd.add("-preset"); cmd.add("medium");
        cmd.add("-pix_fmt"); cmd.add("yuv420p");
        cmd.add("-r"); cmd.add(String.valueOf(cfg.getFps()));
        cmd.add("-b:v"); cmd.add(cfg.getVideoBitrateKbps() + "k");

        if (hasAudio) {
            cmd.add("-c:a"); cmd.add("aac");
            cmd.add("-b:a"); cmd.add(cfg.getAudioBitrateKbps() + "k");
            cmd.add("-shortest");
        } else {
            cmd.add("-an");
        }
        cmd.add(outPath.toAbsolutePath().toString());

        log.info("[剪辑·FFmpeg] exec: {}", String.join(" ", cmd));
        long t0 = System.currentTimeMillis();
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        Process p = pb.start();
        StringBuilder tail = new StringBuilder();
        try (InputStream in = p.getInputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                String chunk = new String(buf, 0, n);
                tail.append(chunk);
                if (tail.length() > 8192) tail.delete(0, tail.length() - 4096);
            }
        }
        boolean done = p.waitFor(cfg.getTimeoutSeconds(), TimeUnit.SECONDS);
        if (!done) {
            p.destroyForcibly();
            throw new RuntimeException("ffmpeg timeout after " + cfg.getTimeoutSeconds() + "s");
        }
        int code = p.exitValue();
        if (code != 0) {
            throw new RuntimeException("ffmpeg exit=" + code + " tail=" + tail.toString().trim());
        }
        return (int) (System.currentTimeMillis() - t0);
    }

    // ---------------- helpers ----------------

    private Path ensureDir(Path p) {
        try {
            Files.createDirectories(p);
        } catch (IOException e) {
            throw new RuntimeException("create dir failed: " + p, e);
        }
        return p;
    }

    private static void deleteQuietly(Path p) {
        try { if (p != null) Files.deleteIfExists(p); }
        catch (Exception e) { /* ignore */ }
    }

    private void cleanWorkDir(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(x -> {
                try { Files.delete(x); } catch (IOException ignore) {}
            });
        } catch (IOException e) {
            log.warn("[剪辑·FFmpeg] cleanup failed dir={}: {}", dir, e.toString());
        }
    }

    private static String guessExt(String url) {
        if (url == null) return ".jpg";
        int q = url.indexOf('?');
        String u = q >= 0 ? url.substring(0, q) : url;
        int dot = u.lastIndexOf('.');
        if (dot < 0 || dot < u.length() - 6) return ".jpg";
        String ext = u.substring(dot).toLowerCase();
        return switch (ext) {
            case ".jpg", ".jpeg", ".png", ".webp", ".bmp" -> ext;
            default -> ".jpg";
        };
    }

    // ---------------- 字幕软断行 ----------------

    private static int softWrapAndWriteSrt(Path src, Path dst, int maxChars) throws IOException {
        List<SrtParser.Cue> cues = SrtParser.parseFile(src);
        StringBuilder sb = new StringBuilder();
        int wrapped = 0;
        int i = 1;
        for (SrtParser.Cue cue : cues) {
            String text = cue.text() == null ? "" : cue.text();
            String wrappedText = softWrap(text, maxChars);
            if (wrappedText.indexOf('\n') >= 0) wrapped++;
            sb.append(i++).append('\n');
            sb.append(SrtParser.formatTime(cue.startMs())).append(" --> ")
              .append(SrtParser.formatTime(cue.endMs())).append('\n');
            sb.append(wrappedText).append("\n\n");
        }
        Files.writeString(dst, sb.toString(), StandardCharsets.UTF_8);
        return wrapped;
    }

    /** 软断行:中英文每字符算 1。累积到 maxChars 时优先在最近标点后断。 */
    static String softWrap(String text, int maxChars) {
        if (text == null) return "";
        text = text.trim();
        if (maxChars <= 0 || text.length() <= maxChars) return text;
        StringBuilder out = new StringBuilder(text.length() + 8);
        int lineStart = 0;
        int lastPunct = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isBreakablePunct(c)) lastPunct = i;
            int lineLen = i - lineStart + 1;
            if (lineLen >= maxChars) {
                int breakAt = (lastPunct >= lineStart + Math.max(1, maxChars / 2)) ? lastPunct : i;
                out.append(text, lineStart, breakAt + 1);
                out.append('\n');
                lineStart = breakAt + 1;
                lastPunct = -1;
                i = lineStart - 1;
            }
        }
        if (lineStart < text.length()) {
            out.append(text, lineStart, text.length());
        }
        return out.toString();
    }

    private static boolean isBreakablePunct(char c) {
        return c == '，' || c == '。' || c == '！' || c == '？'
            || c == '；' || c == '：' || c == '、'
            || c == ',' || c == '.' || c == '!' || c == '?' || c == ';' || c == ':'
            || c == ' ';
    }
}
