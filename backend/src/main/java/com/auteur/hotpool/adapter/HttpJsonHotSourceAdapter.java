package com.auteur.hotpool.adapter;

import com.auteur.hotpool.HotSource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * 通用 HTTP JSON 适配器 — 用 JSON Pointer (RFC 6901) 表达式抽字段。
 *
 * HotSource.configJson 必填:
 * {
 *   "itemsPointer":     "/result/data",          // 必填,指向 item 数组
 *   "titlePointer":     "/title",                 // item 内字段路径 — 必填
 *   "urlPointer":       "/url",                   // 可空
 *   "summaryPointer":   "/intro",                 // 可空
 *   "externalIdPointer":"/wapurl",                // 可空,缺则用 url
 *   "publishedPointer": "/pubdate",               // 可空 — 取到值后按 epoch / ISO / RFC 三种试
 *   "publishedFormat":  "epoch_seconds",          // epoch_seconds / epoch_millis / iso / rfc1123 — 可空,默认自动嗅探
 *   "limit":            50,
 *   "headers":          { "Referer": "..." }      // 可空,自定义请求头
 * }
 *
 * 例:新浪滚动 — feed.mix.sina.com.cn/api/roll/get
 *   itemsPointer=/result/data, title=/title, url=/url, summary=/intro,
 *   externalId=/wapurl, published=/ctime, publishedFormat=epoch_seconds
 */
@Slf4j
@Component
public class HttpJsonHotSourceAdapter implements HotSourceAdapter {

    private static final String DEFAULT_UA =
            "Mozilla/5.0 (compatible; Auteur-HotPool/0.1)";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final int DEFAULT_LIMIT = 50;

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    @Override
    public String id() {
        return "http_json";
    }

    @Override
    public List<HotItemDraft> fetch(HotSource source) {
        try {
            JsonNode cfg = json.readTree(source.getConfigJson() == null ? "{}" : source.getConfigJson());
            String itemsP = required(cfg, "itemsPointer");
            String titleP = required(cfg, "titlePointer");
            String urlP = cfg.path("urlPointer").asText(null);
            String summaryP = cfg.path("summaryPointer").asText(null);
            String extIdP = cfg.path("externalIdPointer").asText(null);
            String pubP = cfg.path("publishedPointer").asText(null);
            String pubFmt = cfg.path("publishedFormat").asText("auto");
            int limit = cfg.path("limit").asInt(DEFAULT_LIMIT);

            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(source.getUrl()))
                    .timeout(TIMEOUT)
                    .header("User-Agent", DEFAULT_UA)
                    .header("Accept", "application/json, */*")
                    .GET();
            JsonNode headers = cfg.path("headers");
            if (headers.isObject()) {
                Iterator<String> fields = headers.fieldNames();
                while (fields.hasNext()) {
                    String k = fields.next();
                    rb.header(k, headers.get(k).asText());
                }
            }
            HttpResponse<String> resp = http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                throw new RuntimeException("HTTP " + resp.statusCode());
            }

            JsonNode root = json.readTree(resp.body());
            JsonNode items = root.at(itemsP);
            if (!items.isArray()) {
                throw new RuntimeException("itemsPointer 未指向 JSON 数组: " + itemsP);
            }

            List<HotItemDraft> drafts = new ArrayList<>();
            int total = items.size();
            // popularity 排名归一化的分母:用真正会保留的窗口大小(min(total, limit) - 1),
            // 否则当 total > limit 时,被保留的 limit 条 popularity 全挤在 [接近1, 1.0],
            // 用户拖最低热度 slider 完全无效。
            int kept = Math.max(Math.min(total, limit) - 1, 1);
            int rank = 0;
            for (JsonNode it : items) {
                if (rank >= limit) break;
                String title = at(it, titleP);
                if (title == null || title.isBlank()) {
                    rank++;
                    continue;
                }
                String url = urlP == null ? null : at(it, urlP);
                String summary = summaryP == null ? null : at(it, summaryP);
                String extId = extIdP == null ? null : at(it, extIdP);
                LocalDateTime pub = pubP == null ? null : parsePublished(at(it, pubP), pubFmt);
                double pop = total <= 1 ? 0.8 : 1.0 - (rank * 0.9 / kept);

                drafts.add(HotItemDraft.builder()
                        .externalId(extId != null ? extId : url)
                        .title(title.trim())
                        .summary(summary == null ? null : summary.trim())
                        .url(url)
                        .popularity(pop)
                        .publishedAt(pub)
                        .locale("zh")
                        .build());
                rank++;
            }
            return drafts;
        } catch (Exception e) {
            log.warn("[hotpool] http_json fetch failed src={} url={} err={}",
                    source.getName(), source.getUrl(), e.getMessage());
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    private static String required(JsonNode cfg, String key) {
        String v = cfg.path(key).asText(null);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException(key + " 配置缺失");
        }
        return v;
    }

    private static String at(JsonNode node, String pointer) {
        JsonNode v = node.at(pointer);
        if (v.isMissingNode() || v.isNull()) return null;
        if (v.isValueNode()) return v.asText();
        return v.toString();
    }

    private static LocalDateTime parsePublished(String raw, String fmt) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim();
        try {
            switch (fmt) {
                case "epoch_seconds":
                    // 用 Instant.atZone() — 让 JDK 按事件时刻自身的偏移转,DST 边界 / UTC 容器都正确。
                    return java.time.Instant.ofEpochSecond(Long.parseLong(s))
                            .atZone(ZoneId.systemDefault())
                            .toLocalDateTime();
                case "epoch_millis":
                    return java.time.Instant.ofEpochMilli(Long.parseLong(s))
                            .atZone(ZoneId.systemDefault())
                            .toLocalDateTime();
                case "iso":
                    return LocalDateTime.parse(s, DateTimeFormatter.ISO_DATE_TIME);
                case "rfc1123":
                    return ZonedDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME)
                            .withZoneSameInstant(ZoneId.systemDefault())
                            .toLocalDateTime();
                default:
                    // auto 嗅探
                    if (s.matches("^\\d{10}$")) return parsePublished(s, "epoch_seconds");
                    if (s.matches("^\\d{13}$")) return parsePublished(s, "epoch_millis");
                    if (s.contains("T")) return parsePublished(s, "iso");
                    if (s.contains(",")) return parsePublished(s, "rfc1123");
                    return null;
            }
        } catch (Exception e) {
            return null;
        }
    }
}
