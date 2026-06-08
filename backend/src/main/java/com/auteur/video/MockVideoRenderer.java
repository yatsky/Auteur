package com.auteur.video;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Slf4j
@Component
@Primary
@ConditionalOnProperty(name = "auteur.video.provider", havingValue = "mock")
public class MockVideoRenderer implements VideoRenderer {

    @Override
    public Result render(Request req) {
        double totalSec = 0.0;
        if (req.clips() != null) {
            for (ImageClip c : req.clips()) totalSec += Math.max(0.0, c.durationSec());
        }
        if (totalSec < 1.0) totalSec = 1.0;
        int durationSec = (int) Math.round(totalSec);

        BigDecimal cost = BigDecimal.valueOf(durationSec)
                .multiply(BigDecimal.valueOf(0.05))
                .setScale(4, RoundingMode.HALF_UP);

        long ts = System.currentTimeMillis();
        String videoUrl = String.format("/mock/video/script-%d-%d.mp4", req.scriptId(), ts);

        log.info("[VideoMock] scriptId={} clips={} duration={}s audio={} subs={} -> {}",
                req.scriptId(),
                req.clips() == null ? 0 : req.clips().size(),
                durationSec,
                req.audioUrl() != null,
                req.subtitleUrl() != null,
                videoUrl);

        return new Result(videoUrl, durationSec, req.width(), req.height(), req.format(), cost);
    }
}
