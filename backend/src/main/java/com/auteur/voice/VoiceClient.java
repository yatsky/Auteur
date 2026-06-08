package com.auteur.voice;

import java.math.BigDecimal;

/**
 * TTS 抽象,实现按 auteur.voice.provider 切换(volcano / mock)。
 */
public interface VoiceClient {

    Result synthesize(Request req);

    /**
     * @param speed         语速倍率,0.6~1.5
     * @param pitch         音调偏移,-6~6
     * @param subtitleStyle standard / highlight(后期 video 渲染管线读取)
     * @param trimGaps      启用句间停顿裁剪(超 threshold 的 gap 裁到 target),最终生效还要看 yml 开关
     */
    record Request(Long scriptId, String fullText, String voiceModel, String voiceLabel,
                   BigDecimal speed, Integer pitch, String subtitleStyle,
                   boolean trimGaps) {}

    record Result(String audioUrl, String subtitleUrl, Integer durationSeconds,
                  BigDecimal costYuan, String model) {}
}
