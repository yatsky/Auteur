package com.auteur.voice;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Slf4j
@Component
@ConditionalOnProperty(name = "auteur.voice.provider", havingValue = "mock")
public class MockVoiceClient implements VoiceClient {

    @Override
    public Result synthesize(Request req) {
        String text = req.fullText() == null ? "" : req.fullText();
        int chars = text.length();
        BigDecimal speed = req.speed() != null ? req.speed() : BigDecimal.ONE;
        double baseSec = chars / 4.0;
        int durationSec = (int) Math.round(baseSec / Math.max(0.6, Math.min(1.5, speed.doubleValue())));
        if (durationSec < 1) durationSec = 1;

        BigDecimal cost = BigDecimal.valueOf(chars)
                .multiply(BigDecimal.valueOf(0.0005))
                .setScale(4, RoundingMode.HALF_UP);

        long ts = System.currentTimeMillis();
        String voiceTag = req.voiceModel() != null ? req.voiceModel() : "mock";
        String audio = String.format("/mock/voice/script-%d-%s-%d.mp3", req.scriptId(), voiceTag, ts);
        String subs  = String.format("/mock/voice/script-%d-%s-%d.srt", req.scriptId(), voiceTag, ts);

        log.info("[VoiceMock] scriptId={} model={} chars={} duration={}s cost=¥{} audio={}",
                req.scriptId(), req.voiceModel(), chars, durationSec, cost, audio);

        return new Result(audio, subs, durationSec, cost, req.voiceModel());
    }
}
