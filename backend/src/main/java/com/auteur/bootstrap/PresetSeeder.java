package com.auteur.bootstrap;

import com.auteur.preset.Preset;
import com.auteur.preset.PresetRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 启动时检查 preset 表,空则 seed 内置示例预设。幂等:已有同名 preset 则跳过。
 */
@Component
@RequiredArgsConstructor
public class PresetSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PresetSeeder.class);

    private final PresetRepository presetRepository;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        seedFreeform();
    }

    private void seedFreeform() throws IOException {
        if (presetRepository.findByName("freeform").isPresent()) {
            return;
        }
        String dir = "preset_seeds/freeform/";
        Preset p = new Preset();
        p.setName("freeform");
        p.setDisplayName("自由创作");
        p.setDescription("通用基础模板:输入主题 + 基调 + 时长,生成一条短视频。无锁脸、无水印、无 hook 段。复制本预设可定制自己的视频形态。");
        p.setInputSchemaJson(read(dir + "input_schema.json"));
        p.setBrainstormPromptYaml(read(dir + "brainstorm.yaml"));
        p.setScriptPromptYaml(read(dir + "script.yaml"));
        p.setStoryboardPromptYaml(read(dir + "storyboard.yaml"));
        p.setStoryboardMode("FREE");
        p.setImageConfigJson(read(dir + "image_config.json"));
        p.setVoiceConfigJson(read(dir + "voice_config.json"));
        p.setBgmEnabled(true);
        p.setCompositionId("StoryVertical");
        p.setFormatWidth(1080);
        p.setFormatHeight(1920);
        p.setHookSegmentEnabled(false);
        p.setCurrentVersion(1);
        Preset saved = presetRepository.save(p);
        log.info("[PresetSeeder] seeded freeform id={}", saved.getId());
    }

    private static String read(String classpathPath) throws IOException {
        try (InputStream in = new ClassPathResource(classpathPath).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
