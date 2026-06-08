package com.auteur.video;

import com.auteur.domain.ScriptSection;
import com.auteur.domain.StoryboardShot;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 解析 storyboard 阶段产出的镜头时长 → ffmpeg 实际烧入的镜头时长。
 *
 * 核心问题:LLM 估算的 duration_seconds 是规划值,与 TTS 实际播放速度无关。
 * 一律按规划值喂 ffconcat 会导致总时长不等于音频时长,声画错位。
 *
 * 三档策略,自上而下回落:
 *   PRECISE_BY_CUE   每 shot 的 anchor_cue_start/end 给出 cue 区间,直接用 cues 时间戳算时长
 *   PRECISE_BY_SECTION  script_section 文本流与 SRT cue 文本流字符位置等比例对齐 → 每段真实起止毫秒;
 *                       同 section 内的 shot 按 LLM 估算占比分配
 *   UNIFORM_SCALE    voiceDurationSec 已知时,所有 shot 时长按 voiceDur/rawTotal 等比缩放
 *   RAW              没有音频时长信息时,LLM 估算原样使用
 *
 * PRECISE 拒绝条件:sections/cues 空、shot 缺 sectionCode、cueChars/sectionChars 越界 [0.6, 1.6]。
 * 字符等比例映射在中文语料上很精准:1 字 ≈ 1 音节,语速恒定时字符位置 ≈ 时间位置。
 */
@Slf4j
public final class ShotTimingResolver {

    private ShotTimingResolver() {}

    public enum Strategy { PRECISE_BY_CUE, PRECISE_BY_SECTION, UNIFORM_SCALE, RAW }

    /**
     * introSec: SRT 第一条 cue 开始时间(秒)。PRECISE 时 > 0 表示音频有前置静音/intro,
     * 调用方应把这段时间加到第一个可渲染 clip 的 duration 上。UNIFORM_SCALE / RAW 时为 0.0。
     */
    public record Resolution(List<Double> durations, Strategy strategy, String reason, double introSec) {}

    /**
     * 仅做 section 级 SRT 对齐(给"对齐画面"按钮用):脚本 sections 与 SRT cue 字符位置等比例对齐,
     * 返回每个 section 的真实起止毫秒。失败原因同 PRECISE 拒绝条件,失败时返回 empty Optional。
     */
    public static java.util.Optional<java.util.LinkedHashMap<String, double[]>> resolveSectionRangesBySrt(
            List<ScriptSection> sectionsInOrder,
            List<SrtParser.Cue> cues) {
        if (sectionsInOrder == null || sectionsInOrder.isEmpty() || cues == null || cues.isEmpty()) {
            return java.util.Optional.empty();
        }
        List<String> sectionTexts = new ArrayList<>();
        int sectionTotalChars = 0;
        for (ScriptSection s : sectionsInOrder) {
            String norm = normalize(s.getTextContent());
            sectionTexts.add(norm);
            sectionTotalChars += norm.length();
        }
        int cueTotalChars = 0;
        List<Integer> cueCharEnd = new ArrayList<>(cues.size());
        for (SrtParser.Cue c : cues) {
            cueTotalChars += normalize(c.text()).length();
            cueCharEnd.add(cueTotalChars);
        }
        if (sectionTotalChars == 0 || cueTotalChars == 0) return java.util.Optional.empty();
        double charRatio = (double) cueTotalChars / sectionTotalChars;
        if (charRatio < 0.6 || charRatio > 1.6) {
            log.info("[ShotTiming] section align REJECTED -> charRatio={} out of [0.6,1.6]", charRatio);
            return java.util.Optional.empty();
        }

        java.util.LinkedHashMap<String, double[]> sectionMs = new java.util.LinkedHashMap<>();
        int sectionCharCum = 0;
        for (int i = 0; i < sectionsInOrder.size(); i++) {
            int sectionEndChar = sectionCharCum + sectionTexts.get(i).length();
            int targetStart = (int) Math.round((double) sectionCharCum / sectionTotalChars * cueTotalChars);
            int targetEnd   = (int) Math.round((double) sectionEndChar / sectionTotalChars * cueTotalChars);
            double startMs = interpMs(cues, cueCharEnd, targetStart);
            double endMs   = interpMs(cues, cueCharEnd, targetEnd);
            sectionMs.put(sectionsInOrder.get(i).getSectionCode(), new double[]{startMs, endMs});
            sectionCharCum = sectionEndChar;
        }
        return java.util.Optional.of(sectionMs);
    }

    public static Resolution resolve(List<StoryboardShot> shotsInOrder,
                                     List<ScriptSection> sectionsInOrder,
                                     List<SrtParser.Cue> cues,
                                     Double voiceDurationSec) {
        int n = shotsInOrder.size();
        List<Double> raw = new ArrayList<>(n);
        for (StoryboardShot s : shotsInOrder) {
            BigDecimal d = s.getDurationSeconds();
            double v = (d != null) ? d.doubleValue() : 5.0;
            if (v <= 0) v = 5.0;
            raw.add(v);
        }
        double rawTotal = 0;
        for (double v : raw) rawTotal += v;

        Resolution preciseByCue = tryPreciseByCue(shotsInOrder, cues);
        if (preciseByCue != null) return preciseByCue;

        Resolution precise = tryPrecise(shotsInOrder, sectionsInOrder, cues, raw, voiceDurationSec);
        if (precise != null) return precise;

        if (voiceDurationSec != null && voiceDurationSec > 0 && rawTotal > 0) {
            double ratio = voiceDurationSec / rawTotal;
            if (Math.abs(ratio - 1.0) <= 0.01) {
                return new Resolution(raw, Strategy.UNIFORM_SCALE,
                        String.format("ratio=%.3f within 1%% - raw used", ratio), 0.0);
            }
            List<Double> scaled = new ArrayList<>(n);
            for (double v : raw) scaled.add(v * ratio);
            return new Resolution(scaled, Strategy.UNIFORM_SCALE,
                    String.format("ratio=%.3f (rawTotal=%.1fs voice=%.1fs)",
                            ratio, rawTotal, voiceDurationSec), 0.0);
        }
        return new Resolution(raw, Strategy.RAW, "no voice duration, raw LLM durations used", 0.0);
    }

    /**
     * PRECISE_BY_CUE:每 shot 的 anchor_cue_start / anchor_cue_end 给出 SRT cue 区间(1 起,inclusive),
     * 直接用 cues 时间戳算时长。
     *
     * shot[i] 时长 = cue[next.start-1].startMs - cue[i.start-1].startMs(中间镜延伸到下个 shot 的 cue 起点),
     * 末镜止于自己 cue 末。这样消化 cue 间换气间隙,画面在每个 cue 起点切换,跟字幕完美同步。
     *
     * introSec(cue[0].startMs 之前的开头静音)由 VideoAssemblyService 单独加给第一镜,这里 result 不含。
     *
     * 拒绝条件:cues 空、shot 缺 anchor、anchor 区间越界/倒序/不连续。
     */
    private static Resolution tryPreciseByCue(List<StoryboardShot> shotsInOrder,
                                              List<SrtParser.Cue> cues) {
        if (cues == null || cues.isEmpty()) return rejectPreciseByCue("srt cues empty");
        int cueSize = cues.size();
        int prevEnd = 0;
        for (StoryboardShot shot : shotsInOrder) {
            Integer s = shot.getAnchorCueStart();
            Integer e = shot.getAnchorCueEnd();
            if (s == null || e == null) {
                return rejectPreciseByCue("shot id=" + shot.getId() + " 缺 anchor_cue_start/end (preset 启用 PRECISE_BY_CUE 但 storyboard 未输出 anchor)");
            }
            if (s < 1 || e > cueSize || s > e) {
                return rejectPreciseByCue(String.format(
                        "shot id=%d anchor_cue=[%d,%d] 越界或倒序(cueSize=%d)", shot.getId(), s, e, cueSize));
            }
            if (s != prevEnd + 1) {
                return rejectPreciseByCue(String.format(
                        "shot id=%d anchor_cue_start=%d 不连续(应该 %d)", shot.getId(), s, prevEnd + 1));
            }
            prevEnd = e;
        }
        if (prevEnd != cueSize) {
            return rejectPreciseByCue(String.format(
                    "末 shot anchor_cue_end=%d,应覆盖到 %d", prevEnd, cueSize));
        }

        double introSec = cues.get(0).startMs() / 1000.0;
        int n = shotsInOrder.size();
        List<Double> result = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            StoryboardShot shot = shotsInOrder.get(i);
            int sStart = shot.getAnchorCueStart();
            int sEnd = shot.getAnchorCueEnd();
            double thisStartMs = cues.get(sStart - 1).startMs();
            double endMs;
            if (i < n - 1) {
                int nextStart = shotsInOrder.get(i + 1).getAnchorCueStart();
                endMs = cues.get(nextStart - 1).startMs();
            } else {
                endMs = cues.get(sEnd - 1).endMs();
            }
            double durSec = (endMs - thisStartMs) / 1000.0;
            if (durSec <= 0) durSec = 0.5;
            result.add(durSec);
        }
        double sum = 0; for (double v : result) sum += v;
        return new Resolution(result, Strategy.PRECISE_BY_CUE,
                String.format("shots=%d cues=%d speechSec=%.1f introSec=%.1f gapAbsorbed=true",
                        n, cueSize, sum, introSec),
                introSec);
    }

    private static Resolution rejectPreciseByCue(String reason) {
        log.info("[ShotTiming] PRECISE_BY_CUE 拒绝 → fallback: {}", reason);
        return null;
    }

    private static Resolution tryPrecise(List<StoryboardShot> shotsInOrder,
                                          List<ScriptSection> sectionsInOrder,
                                          List<SrtParser.Cue> cues,
                                          List<Double> raw,
                                          Double voiceDurationSec) {
        if (sectionsInOrder == null || sectionsInOrder.isEmpty()) return rejectPrecise("sections empty");
        if (cues == null || cues.isEmpty()) return rejectPrecise("srt cues empty");

        Map<String, ScriptSection> byCode = new HashMap<>();
        for (ScriptSection s : sectionsInOrder) byCode.put(s.getSectionCode(), s);
        for (StoryboardShot shot : shotsInOrder) {
            String code = shot.getSectionCode();
            if (code == null || code.isBlank()) {
                return rejectPrecise("shot id=" + shot.getId() + " missing sectionCode (legacy storyboard)");
            }
            if (!byCode.containsKey(code)) {
                return rejectPrecise("shot id=" + shot.getId() + " sectionCode='" + code
                        + "' not in script_section");
            }
        }

        List<String> sectionTexts = new ArrayList<>();
        int sectionTotalChars = 0;
        for (ScriptSection s : sectionsInOrder) {
            String norm = normalize(s.getTextContent());
            sectionTexts.add(norm);
            sectionTotalChars += norm.length();
        }
        int cueTotalChars = 0;
        List<Integer> cueCharEnd = new ArrayList<>(cues.size());
        for (SrtParser.Cue c : cues) {
            cueTotalChars += normalize(c.text()).length();
            cueCharEnd.add(cueTotalChars);
        }
        if (sectionTotalChars == 0 || cueTotalChars == 0) {
            return rejectPrecise("zero-length section or cue text");
        }
        double charRatio = (double) cueTotalChars / sectionTotalChars;
        if (charRatio < 0.6 || charRatio > 1.6) {
            return rejectPrecise(String.format("cue/section char ratio=%.2f out of [0.6,1.6] - SRT/script mismatch",
                    charRatio));
        }

        Map<String, double[]> sectionMs = new HashMap<>();
        int sectionCharCum = 0;
        for (int i = 0; i < sectionsInOrder.size(); i++) {
            int sectionEndChar = sectionCharCum + sectionTexts.get(i).length();
            int targetStart = (int) Math.round((double) sectionCharCum / sectionTotalChars * cueTotalChars);
            int targetEnd   = (int) Math.round((double) sectionEndChar / sectionTotalChars * cueTotalChars);
            double startMs = interpMs(cues, cueCharEnd, targetStart);
            double endMs   = interpMs(cues, cueCharEnd, targetEnd);
            sectionMs.put(sectionsInOrder.get(i).getSectionCode(), new double[]{startMs, endMs});
            sectionCharCum = sectionEndChar;
        }

        // 末段 endMs 兜底:SRT 末尾可能比 voice 早收(火山 sentence text 内嵌 \n 导致末段 SRT 块被
        // SrtParser drop;或 voice 末尾自然收尾静音不在 SRT 里)。
        //   ≤ 2s   :voice 自然收尾余音,不动
        //   2-10s  :小尾部静音,把多出时间扩展到末段 endMs,均摊到该段所有 shot
        //   > 10s  :SRT 数据严重缺失,不扩展(否则末段画面过慢);log warn 提示重跑 voice
        double lastCueEndMs = cues.get(cues.size() - 1).endMs();
        if (voiceDurationSec != null && voiceDurationSec > 0) {
            double voiceMs = voiceDurationSec * 1000.0;
            double tailGapMs = voiceMs - lastCueEndMs;
            if (tailGapMs > 10000.0) {
                log.warn("[ShotTiming] voice 末尾比 SRT 末 cue 长 {}ms (>10s) — SRT 可能有 dropped block,"
                        + "PRECISE 不扩展末段(避免末段画面过慢),建议跑 voice 重新生成。"
                        + "本次成片末镜会被 RemotionVideoRenderer 延长 {}s 补差,画面会冻住。",
                        (long) tailGapMs, String.format("%.1f", tailGapMs / 1000.0));
            } else if (tailGapMs > 2000.0) {
                String lastSectionCode = sectionsInOrder.get(sectionsInOrder.size() - 1).getSectionCode();
                double[] r = sectionMs.get(lastSectionCode);
                if (r != null) {
                    log.info("[ShotTiming] voice 尾部 {}ms (≤10s) 超出 SRT 末 cue,扩展末段 {} 至 voice 末尾",
                            (long) tailGapMs, lastSectionCode);
                    sectionMs.put(lastSectionCode, new double[]{r[0], voiceMs});
                }
            }
        }

        Double[] out = new Double[shotsInOrder.size()];
        Map<String, List<Integer>> shotsBySection = new HashMap<>();
        for (int i = 0; i < shotsInOrder.size(); i++) {
            shotsBySection.computeIfAbsent(shotsInOrder.get(i).getSectionCode(), k -> new ArrayList<>()).add(i);
        }
        for (Map.Entry<String, List<Integer>> e : shotsBySection.entrySet()) {
            double[] range = sectionMs.get(e.getKey());
            double sectionDurSec = Math.max(0.1, (range[1] - range[0]) / 1000.0);
            List<Integer> idxs = e.getValue();
            double sumLlm = 0;
            for (int idx : idxs) sumLlm += raw.get(idx);
            if (sumLlm <= 0) {
                double per = sectionDurSec / idxs.size();
                for (int idx : idxs) out[idx] = per;
            } else {
                for (int idx : idxs) {
                    out[idx] = sectionDurSec * raw.get(idx) / sumLlm;
                }
            }
        }

        List<Double> result = new ArrayList<>(shotsInOrder.size());
        for (Double d : out) result.add(d != null ? d : 5.0);
        double sum = 0; for (double v : result) sum += v;
        double introSec = cues.get(0).startMs() / 1000.0;
        return new Resolution(result, Strategy.PRECISE_BY_SECTION,
                String.format("sections=%d shots=%d cues=%d charRatio=%.2f speechSec=%.1f introSec=%.1f",
                        sectionsInOrder.size(), shotsInOrder.size(), cues.size(), charRatio, sum, introSec),
                introSec);
    }

    private static Resolution rejectPrecise(String reason) {
        log.info("[ShotTiming] precise REJECTED -> fallback: {}", reason);
        return null;
    }

    /** 给定 cue 字符流里的目标位置 charPos,在 cue 时间码上插值出毫秒. */
    private static double interpMs(List<SrtParser.Cue> cues, List<Integer> cueCharEnd, int charPos) {
        if (cues.isEmpty()) return 0;
        if (charPos <= 0) return cues.get(0).startMs();
        int total = cueCharEnd.get(cueCharEnd.size() - 1);
        if (charPos >= total) return cues.get(cues.size() - 1).endMs();
        int lo = 0, hi = cues.size() - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (cueCharEnd.get(mid) > charPos) hi = mid;
            else lo = mid + 1;
        }
        SrtParser.Cue c = cues.get(lo);
        int cueStartChar = lo == 0 ? 0 : cueCharEnd.get(lo - 1);
        int cueLen = cueCharEnd.get(lo) - cueStartChar;
        if (cueLen <= 0) return c.startMs();
        double frac = (double) (charPos - cueStartChar) / cueLen;
        return c.startMs() + frac * (c.endMs() - c.startMs());
    }

    /** 归一化: NFKC + 去标点/空白/控制符,只留汉字 + 英数(小写). */
    static String normalize(String s) {
        if (s == null) return "";
        String t = Normalizer.normalize(s, Normalizer.Form.NFKC);
        StringBuilder out = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if ((c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) {
                out.append(Character.toLowerCase(c));
            } else if (c >= '一' && c <= '鿿') {
                out.append(c);
            }
        }
        return out.toString();
    }
}
