package com.auteur.preset;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

/**
 * preset.voice_config_json 的强类型视图。
 *
 * preset.voice_config_json 整个为 null 时表示"该预设不要旁白"(如纯 BGM 风景片),
 * VoiceGenService 看到这种情况直接跳过 TTS 阶段。
 *
 * 例 voice_config:
 *   {
 *     "voice_id": "BV001_streaming",
 *     "speed_ratio": 1.0,
 *     "volume_ratio": 1.0
 *   }
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class VoiceConfig {

    @JsonProperty("voice_id")
    private String voiceId;

    /** 语速,1.0 = 默认。 */
    @JsonProperty("speed_ratio")
    private Double speedRatio = 1.0;

    /** 音量,1.0 = 默认。 */
    @JsonProperty("volume_ratio")
    private Double volumeRatio = 1.0;
}
