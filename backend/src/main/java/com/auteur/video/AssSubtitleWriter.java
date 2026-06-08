package com.auteur.video;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SRT cues → ASS subtitle 转换器。SRT 不支持 inline 颜色 tag,关键词高亮必须走 ASS。
 *
 * 关键词抽取规则三类:数字+量词、书名号/引号短引文、朝代/帝号字典。命中区间合并去重后插入 inline tag。
 *
 * 软断行先 wrap 再插 tag,避免 {\c...} 被换行切断。ASS 换行用 \N。
 *
 * libass 默认 PlayResY=288,fontsize/marginV 在该坐标系下与 SRT force_style 视觉一致
 * (fontSize=11 → 1920p 下约 73px;marginV=50 → 距底约 333px)。
 */
@Slf4j
public final class AssSubtitleWriter {

    private AssSubtitleWriter() {}

    /** ASS 颜色字面是 &HAABBGGRR(BGR 顺序);#FFD700 在 ASS 里是 0000D7FF。 */
    private static final String GOLD_BGR = "&H0000D7FF";

    /** 数字 + 量词。\\d+ 必须跟一个单位,避免裸数字误命中(年份/编号都能套上"年""号")。 */
    private static final Pattern NUM_UNIT = Pattern.compile(
            "\\d+(?:\\.\\d+)?\\s*(?:年|岁|人|两|万|千|百|匹|斤|里|天|月|日|次|个|位|名|户|尺|寸|丈|斗|桶)|"
                    + "两万|十万|百万|千万|半年|半月|两年|三年|五年|十年|百年|千年|万人|十日|百日|千日");

    private static final Pattern QUOTED = Pattern.compile(
            "《[^》\\n]{1,12}》|『[^』\\n]{1,12}』|「[^」\\n]{1,12}」|\"[^\"\\n]{1,12}\"|\"[^\"\\n]{1,12}\"");

    private static final Set<String> DYNASTY_DICT = Set.of(
            "嘉靖", "崇祯", "万历", "永乐", "洪武", "正德", "弘治", "天启", "成化", "正统",
            "康熙", "乾隆", "雍正", "顺治", "光绪", "同治", "道光", "咸丰",
            "武则天", "朱元璋", "赵匡胤", "李世民", "刘邦", "曹操",
            "诸葛亮", "和珅", "魏忠贤", "严嵩", "张居正", "刘瑾", "客氏"
    );
    private static final Pattern DYNASTY_PATTERN = Pattern.compile(String.join("|", DYNASTY_DICT));

    public record AssStyle(String fontName, int fontSize, int marginV) {}

    public static void writeHighlight(List<SrtParser.Cue> cues, Path dst,
                                      AssStyle style, int maxCharsPerLine) throws IOException {
        StringBuilder sb = new StringBuilder(2048);
        appendHeader(sb, style);
        int hits = 0;
        for (SrtParser.Cue cue : cues) {
            String text = cue.text() == null ? "" : cue.text().trim();
            if (text.isEmpty()) continue;
            String wrapped = FfmpegVideoRenderer.softWrap(text, maxCharsPerLine);
            String highlighted = applyHighlight(wrapped);
            if (highlighted.contains("{\\c")) hits++;
            String assLine = highlighted.replace("\n", "\\N");
            sb.append("Dialogue: 0,")
              .append(formatAssTime(cue.startMs())).append(',')
              .append(formatAssTime(cue.endMs()))
              .append(",Default,,0,0,0,,").append(assLine).append('\n');
        }
        Files.writeString(dst, sb.toString(), StandardCharsets.UTF_8);
        log.info("[AssSubtitleWriter] wrote {} ({} cues, {} 条命中关键词高亮)",
                dst.getFileName(), cues.size(), hits);
    }

    private static void appendHeader(StringBuilder sb, AssStyle style) {
        sb.append("[Script Info]\n")
          .append("ScriptType: v4.00+\n")
          .append("PlayResX: 512\n")
          .append("PlayResY: 288\n")
          .append("ScaledBorderAndShadow: yes\n\n")
          .append("[V4+ Styles]\n")
          .append("Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n")
          .append("Style: Default,").append(style.fontName()).append(',')
          .append(style.fontSize()).append(',')
          .append("&H00FFFFFF,&H00FFFFFF,&H00000000,&H00000000,")
          .append("0,0,0,0,")
          .append("100,100,0,0,")
          // borderStyle=1 outline+shadow / outline=3 / shadow=1 / alignment=2 (bottom-center)
          .append("1,3,1,2,")
          .append("20,20,").append(style.marginV()).append(',')
          .append("1\n\n")
          .append("[Events]\n")
          .append("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n");
    }

    static String applyHighlight(String text) {
        if (text == null || text.isEmpty()) return "";
        List<int[]> spans = new ArrayList<>();
        collectMatches(spans, NUM_UNIT.matcher(text));
        collectMatches(spans, QUOTED.matcher(text));
        collectMatches(spans, DYNASTY_PATTERN.matcher(text));
        if (spans.isEmpty()) return text;
        // 合并重叠区间,避免相邻命中插出嵌套 tag
        spans.sort(Comparator.comparingInt(s -> s[0]));
        List<int[]> merged = new ArrayList<>();
        for (int[] s : spans) {
            if (!merged.isEmpty() && s[0] <= merged.get(merged.size() - 1)[1]) {
                merged.get(merged.size() - 1)[1] =
                        Math.max(merged.get(merged.size() - 1)[1], s[1]);
            } else {
                merged.add(new int[]{s[0], s[1]});
            }
        }
        StringBuilder out = new StringBuilder(text.length() + merged.size() * 24);
        int cursor = 0;
        for (int[] span : merged) {
            out.append(text, cursor, span[0])
               .append("{\\c").append(GOLD_BGR).append("&\\b1}")
               .append(text, span[0], span[1])
               .append("{\\r}");
            cursor = span[1];
        }
        out.append(text, cursor, text.length());
        return out.toString();
    }

    private static void collectMatches(List<int[]> out, Matcher m) {
        while (m.find()) {
            if (m.end() > m.start()) out.add(new int[]{m.start(), m.end()});
        }
    }

    /** ASS 时间戳格式 H:MM:SS.cc(centisecond),与 SRT 的逗号毫秒不同。 */
    static String formatAssTime(long ms) {
        if (ms < 0) ms = 0;
        long h  = ms / 3_600_000;
        long m  = (ms / 60_000) % 60;
        long s  = (ms / 1_000) % 60;
        long cs = (ms % 1_000) / 10;
        return String.format("%d:%02d:%02d.%02d", h, m, s, cs);
    }
}
