package com.auteur.video;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SRT (SubRip) 字幕解析器。
 *
 * 容错:BOM、CRLF/LF 混用、多行 text、末尾缺空行、时间戳逗号/句号都接受。
 * 时间戳格式异常的块静默丢弃并 log warn。HTML/ASS inline 标签保留不清洗。
 */
@Slf4j
public final class SrtParser {

    private SrtParser() {}

    public record Cue(int index, long startMs, long endMs, String text) {
        public long durationMs() { return Math.max(0, endMs - startMs); }
    }

    private static final Pattern TIME_PATTERN = Pattern.compile(
            "^(\\d{1,2}):(\\d{2}):(\\d{2})[,.](\\d{1,3})\\s*-->\\s*"
                    + "(\\d{1,2}):(\\d{2}):(\\d{2})[,.](\\d{1,3})\\s*$");

    public static List<Cue> parseFile(Path srtPath) throws IOException {
        String raw = Files.readString(srtPath, StandardCharsets.UTF_8);
        return parse(raw);
    }

    public static List<Cue> parse(String content) {
        if (content == null || content.isBlank()) return List.of();
        if (content.charAt(0) == '﻿') content = content.substring(1);
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        String[] blocks = normalized.split("\n\\s*\n");
        List<Cue> out = new ArrayList<>(blocks.length);
        int dropped = 0;
        // 被丢块的诊断信息(前 50 字),帮助定位 SRT 损坏根因。
        List<String> droppedPreviews = new ArrayList<>(4);
        for (String block : blocks) {
            String trimmed = block.trim();
            if (trimmed.isEmpty()) continue;
            String[] lines = trimmed.split("\n");
            // 找时间戳行(允许第一行是 index,也允许直接是时间戳)
            int timeLine = -1;
            for (int i = 0; i < Math.min(2, lines.length); i++) {
                if (lines[i].contains("-->")) { timeLine = i; break; }
            }
            if (timeLine < 0 || timeLine + 1 > lines.length - 1) {
                dropped++;
                if (droppedPreviews.size() < 3) {
                    droppedPreviews.add(trimmed.length() <= 50 ? trimmed : trimmed.substring(0, 50) + "...");
                }
                continue;
            }

            Matcher m = TIME_PATTERN.matcher(lines[timeLine].trim());
            if (!m.matches()) {
                dropped++;
                if (droppedPreviews.size() < 3) {
                    droppedPreviews.add(trimmed.length() <= 50 ? trimmed : trimmed.substring(0, 50) + "...");
                }
                continue;
            }

            long startMs = toMs(m.group(1), m.group(2), m.group(3), m.group(4));
            long endMs   = toMs(m.group(5), m.group(6), m.group(7), m.group(8));
            int index = -1;
            if (timeLine == 1) {
                try { index = Integer.parseInt(lines[0].trim()); } catch (NumberFormatException ignore) {}
            }

            StringBuilder text = new StringBuilder();
            for (int i = timeLine + 1; i < lines.length; i++) {
                if (text.length() > 0) text.append(' ');
                text.append(lines[i].trim());
            }
            out.add(new Cue(index, startMs, endMs, text.toString()));
        }
        if (dropped > 0) {
            log.warn("[SrtParser] dropped {} malformed blocks (kept {}); sample: {}",
                    dropped, out.size(), droppedPreviews);
        }
        return out;
    }

    private static long toMs(String h, String m, String s, String ms) {
        // ms 段可能 1-3 位,补齐到 3 位
        String msPadded = (ms + "000").substring(0, 3);
        return Long.parseLong(h) * 3_600_000L
                + Long.parseLong(m) * 60_000L
                + Long.parseLong(s) * 1_000L
                + Long.parseLong(msPadded);
    }

    /** SRT 时间戳格式化:ms → "HH:MM:SS,mmm"。负值按 0 处理。 */
    public static String formatTime(long ms) {
        if (ms < 0) ms = 0;
        long h = ms / 3_600_000;
        long m = (ms / 60_000) % 60;
        long s = (ms / 1_000) % 60;
        long mm = ms % 1_000;
        return String.format("%02d:%02d:%02d,%03d", h, m, s, mm);
    }
}
