package com.auteur.preset;

/**
 * Service 层用的 preset 解析结果。preset_id 在 P3 后置 NOT NULL,所有字段必非空。
 *
 * 用法:ctx.preset().getXxx() / ctx.imageConfig() / ctx.voiceConfig()。
 * imageConfig() / voiceConfig() 可能字段全空(对应 preset 没配置该 stage),但本身非 null。
 */
public record PresetContext(Preset preset, ImageConfig imageConfig, VoiceConfig voiceConfig) {
}
