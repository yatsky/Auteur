package com.auteur.voice.volcano;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 句级 cue 拆短器:火山 TTS 的 sentence 平均 ~13s 太长,在 voice 端按中文标点拆成更细子 cue
 * (目标 ~6s/cue),让 storyboard LLM 看到更密的 cue → 出更密的 shot。
 */
@Slf4j
public class VolcanoSentenceSplitter {

    /** 长 sentence 阈值。 */
    private static final long MAX_MS = 8_000;
    /** 拆分后每段目标长度。 */
    private static final long TARGET_MS = 6_000;
    private static final Pattern PUNCT = Pattern.compile("[,。?!;:、]");

    public static List<VolcanoTtsHttpClient.Sentence> split(List<VolcanoTtsHttpClient.Sentence> sentences) {
        if (sentences == null || sentences.isEmpty()) return List.of();
        List<VolcanoTtsHttpClient.Sentence> out = new ArrayList<>();
        int splitCount = 0;
        for (VolcanoTtsHttpClient.Sentence s : sentences) {
            // 跳空文本/异常时间戳(顺便防末尾空 sentence 拉高 durationSec)
            if (s.text() == null || s.text().isBlank() || s.endMs() <= s.beginMs()) {
                continue;
            }
            long durMs = s.endMs() - s.beginMs();
            if (durMs <= MAX_MS) {
                out.add(s);
                continue;
            }
            List<VolcanoTtsHttpClient.Sentence> parts = splitOne(s);
            out.addAll(parts);
            splitCount++;
        }
        log.info("[SentenceSplit] 输入 {} 句,拆分了 {} 句长 sentence,输出 {} 句子 cue",
                sentences.size(), splitCount, out.size());
        return out;
    }

    private static List<VolcanoTtsHttpClient.Sentence> splitOne(VolcanoTtsHttpClient.Sentence s) {
        String text = s.text().trim();
        int n = text.length();
        long durMs = s.endMs() - s.beginMs();
        int targetPieces = Math.max(2, (int) Math.ceil((double) durMs / TARGET_MS));
        if (targetPieces > 6) targetPieces = 6;

        List<Integer> punctPoints = new ArrayList<>();
        Matcher m = PUNCT.matcher(text);
        while (m.find()) {
            int p = m.end();
            if (p > 0 && p < n) punctPoints.add(p);
        }

        List<Integer> chosen = new ArrayList<>();
        if (punctPoints.isEmpty()) {
            // 无标点 → 按字数硬切
            for (int i = 1; i < targetPieces; i++) {
                chosen.add(i * n / targetPieces);
            }
        } else {
            // 按字数比例选最接近的标点
            for (int i = 1; i < targetPieces; i++) {
                double targetCharPos = (double) i * n / targetPieces;
                int best = punctPoints.get(0);
                double bestDist = Math.abs(best - targetCharPos);
                for (int p : punctPoints) {
                    double d = Math.abs(p - targetCharPos);
                    if (d < bestDist) {
                        bestDist = d;
                        best = p;
                    }
                }
                if (!chosen.contains(best)) chosen.add(best);
            }
        }
        chosen.sort(Integer::compare);
        chosen.add(n);

        List<VolcanoTtsHttpClient.Sentence> parts = new ArrayList<>();
        int prevPos = 0;
        long prevMs = s.beginMs();
        for (int idx = 0; idx < chosen.size(); idx++) {
            int splitPos = chosen.get(idx);
            if (splitPos <= prevPos) continue;
            String subText = text.substring(prevPos, splitPos).trim();
            if (subText.isEmpty()) {
                prevPos = splitPos;
                continue;
            }
            long thisEndMs;
            if (idx == chosen.size() - 1) {
                thisEndMs = s.endMs();  // 末段精确到原 endMs
            } else {
                thisEndMs = s.beginMs() + (long) ((double) splitPos / n * durMs);
            }
            parts.add(new VolcanoTtsHttpClient.Sentence(subText, prevMs, thisEndMs));
            prevPos = splitPos;
            prevMs = thisEndMs;
        }
        return parts;
    }
}
