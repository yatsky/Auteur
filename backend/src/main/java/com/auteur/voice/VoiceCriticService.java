package com.auteur.voice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.auteur.domain.CriticLog;
import com.auteur.domain.CriticLogRepository;
import com.auteur.domain.Script;
import com.auteur.domain.Topic;
import com.auteur.domain.VoiceAsset;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 配音生成后体检并落 critic_log。不重投(语音重生成本高且不一定改善),仅告警。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VoiceCriticService {

    private static final double DURATION_DEVIATION_THRESHOLD = 0.30;
    private static final BigDecimal COST_ALERT_YUAN = new BigDecimal("0.5");

    private final CriticLogRepository criticLogRepository;
    private final com.auteur.preset.TopicPresetResolver presetResolver;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public void auditAndLog(VoiceAsset asset, Script script, Topic topic) {
        if (topic == null || asset == null || script == null) return;

        List<String> issues = new ArrayList<>();

        if (asset.getDurationSeconds() != null && script.getDurationSeconds() != null
                && script.getDurationSeconds() > 0) {
            int target = script.getDurationSeconds();
            int actual = asset.getDurationSeconds();
            double dev = Math.abs(actual - target) * 1.0 / target;
            if (dev > DURATION_DEVIATION_THRESHOLD) {
                issues.add(String.format("duration 偏差大: target=%ds actual=%ds (差 %.0f%%)",
                        target, actual, dev * 100));
            }
        }

        if (asset.getSubtitleUrl() == null || asset.getSubtitleUrl().isBlank()) {
            issues.add("subtitle_url 缺失,下游 storyboard 走 cue 锚定会失败");
        }

        if (asset.getCostYuan() != null && asset.getCostYuan().compareTo(COST_ALERT_YUAN) > 0) {
            issues.add("voice cost 偏高: " + asset.getCostYuan() + " 元 (> " + COST_ALERT_YUAN + ")");
        }

        try {
            com.auteur.preset.PresetContext ctx = presetResolver.forTopic(topic);
            CriticLog row = new CriticLog();
            row.setRole("VOICE");
            row.setTopicId(topic.getId());
            row.setScriptId(script.getId());
            row.setContentType(ctx.preset().getName());
            row.setScore(issues.isEmpty() ? 100 : 0);
            row.setDecision(issues.isEmpty() ? "PASS" : "REWRITE");
            if (!issues.isEmpty()) {
                row.setIssuesJson(objectMapper.writeValueAsString(issues));
                row.setFeedback("voice 体检不通过(语音不重投,仅告警):" + String.join(";", issues));
            }
            criticLogRepository.save(row);
            if (!issues.isEmpty()) {
                log.warn("[VoiceCritic] scriptId={} 不达标 issues={}", script.getId(), issues);
            }
        } catch (Exception e) {
            log.warn("[VoiceCritic] critic_log 写入失败 scriptId={}: {}", script.getId(), e.toString());
        }
    }
}
