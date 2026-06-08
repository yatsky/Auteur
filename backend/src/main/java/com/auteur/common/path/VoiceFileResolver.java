package com.auteur.common.path;

import com.auteur.voice.VoiceProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * voice url(/api/files/voice/X) ↔ 本地文件系统路径(voice.localDir/X)的统一解析。
 * 替代散落在 VideoAssemblyService / FfmpegVideoRenderer / ScriptAlignmentService 的同名私有方法。
 */
@Component
public class VoiceFileResolver {

    public static final String VOICE_URL_PREFIX = "/api/files/voice/";

    private final VoiceProperties voiceProps;

    public VoiceFileResolver(VoiceProperties voiceProps) {
        this.voiceProps = voiceProps;
    }

    /** 仅识别 /api/files/voice/X 形式;其它一律 null。 */
    public Path resolveStrict(String url) {
        if (url == null || url.isBlank() || !url.startsWith(VOICE_URL_PREFIX)) return null;
        return toLocal(url);
    }

    /** /api/files/voice/X 走本地;否则把 url 当成普通文件路径处理。 */
    public Path resolve(String url) {
        if (url == null || url.isBlank()) return null;
        if (!url.startsWith(VOICE_URL_PREFIX)) {
            try { return Paths.get(url); } catch (Exception e) { return null; }
        }
        return toLocal(url);
    }

    private Path toLocal(String url) {
        String name = url.substring(VOICE_URL_PREFIX.length());
        return Paths.get(voiceProps.getStorage().getLocalDir()).resolve(name).toAbsolutePath();
    }
}
