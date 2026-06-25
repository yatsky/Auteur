package com.auteur.video;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;

/** 视频合成抽象。实现包括 ffmpeg / Remotion / mock。 */
public interface VideoRenderer {

    Result render(Request req);

    /**
     * 单镜片段:一张静态图 + 该镜的播放时长。
     * sectionCode 用于章节断点检测(Remotion 路径消费),ffmpeg 路径忽略,可空。
     * anchorText 是脚本字面锚定短语,Remotion 用作 motion intent 启发式输入;ffmpeg 路径忽略,可空。
     */
    record ImageClip(int shotIndex, String imageUrl,
                     double startSec, double durationSec, String caption,
                     String sectionCode, String anchorText) {}

    /**
     * BGM 配置。bgmFile 本地 mp3(ffmpeg 用),httpUrl 同曲 https URL(Remotion 浏览器加载用),
     * volume 0.05~0.6。整片单曲循环 + sidechain ducking(仅 ffmpeg 路径)。
     */
    record BgmConfig(Path bgmFile, String httpUrl, double volume) {}

    /**
     * 开头 hook 段配置(由 preset.hook_segment_enabled 启用)。任一字段缺失走"无 hook"路径。
     *
     * @param pageFlipSoundUrl   翻书音效 mp3 URL,可空。每张图切换时叠播。
     */
    record HookConfig(java.util.List<String> imageUrls, String audioUrl, String text,
                      double durationSec, String pageFlipSoundUrl) {}

    /**
     * @param subtitleStyle "standard" 走 SRT force_style;"highlight" 走 ASS 关键词加色加粗。
     * @param compositionId Remotion composition 名;Remotion 路径必填,ffmpeg 路径可空。
     * @param watermarkText 左上角水印文本;null/空 = 不加水印。ffmpeg 路径忽略。
     * @param chapterBreakSec 章节边界黑帧时长(秒);Remotion 路径消费,ffmpeg 路径忽略。来自 preset.chapter_break_sec。
     * @param subtitleBottomRatio 字幕距底边比例(0.0-0.5);null = 走 renderer 智能默认。来自 preset.subtitle_bottom_ratio。
     */
    record Request(Long scriptId, List<ImageClip> clips, String audioUrl, String subtitleUrl,
                   String format, int width, int height, BgmConfig bgm, String subtitleStyle,
                   String contentType, String personaJson, String protagonistRefUrl, Long topicId,
                   HookConfig hook, String compositionId, String watermarkText,
                   double chapterBreakSec, Double subtitleBottomRatio) {}

    record Result(String videoUrl, Integer durationSeconds, Integer width, Integer height,
                  String format, BigDecimal costYuan) {}
}
