package com.auteur.config;

import com.auteur.bgm.JamendoProperties;
import com.auteur.cover.CoverProperties;
import com.auteur.video.VideoProperties;
import com.auteur.voice.VoiceProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 把 generation 落盘的产物通过 /api/files/{voice,video}/** 暴露给浏览器。
 * 走 /api 前缀是因为 Vite dev 只代理 /api 到后端。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class StaticFilesConfig implements WebMvcConfigurer {

    private final VoiceProperties voiceProperties;
    private final VideoProperties videoProperties;
    private final CoverProperties coverProperties;
    private final JamendoProperties jamendoProperties;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // voice: 历史产物 + VoiceDemoService 试听 demo 仍在本地磁盘，需要 serve
        mount(registry, "/api/files/voice/**", voiceProperties.getStorage().getLocalDir(), "voice");
        // sfx 路径基于 voice 同级目录推导(./storage/voice → ./storage/sfx),
        // 保证 cwd 正确性跟 voice 一致(voice work 则 sfx 也 work)。
        String sfxDir = Paths.get(voiceProperties.getStorage().getLocalDir())
                .toAbsolutePath().normalize().getParent().resolve("sfx").toString();
        mount(registry, "/api/files/sfx/**", sfxDir, "sfx");
    }

    private void mount(ResourceHandlerRegistry registry, String pattern, String localDir, String label) {
        Path dir = Paths.get(localDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            log.warn("[StaticFiles] create {} dir failed (will be created on first write): {}", label, dir);
        }
        // file:/abs/path/ 必须以 / 结尾,否则 Spring 把最后一级当文件名,匹配会失效
        String location = "file:" + dir + "/";
        log.info("[StaticFiles] mapping {} -> {}", pattern, location);
        registry.addResourceHandler(pattern).addResourceLocations(location);
    }
}
