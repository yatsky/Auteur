package com.auteur.preset;

/**
 * preset_id 是 NOT NULL,所有字段必非空。
 * imageConfig() / voiceConfig() 可能字段全空(对应 preset 没配置该 stage),但本身非 null。
 */
public record PresetContext(Preset preset, ImageConfig imageConfig, VoiceConfig voiceConfig) {
}
