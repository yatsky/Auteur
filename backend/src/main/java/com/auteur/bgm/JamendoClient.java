package com.auteur.bgm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Jamendo Free API client。
 *
 * search 注意:
 *   - 不传 audioformat=mp32:会过滤掉只有 mp31 (96kbps stream) 的曲目,导致大部分 mood tag 返 0 条
 *   - 不传 fuzzytags:Jamendo 这个参数有 bug,传任何值都返 0 条;默认行为已是模糊匹配
 *   - tags 多个值是严格 AND 匹配,3 个 tag 几乎找不到曲;映射用 2 个 tag
 */
@Slf4j
@Component
public class JamendoClient {

    private static final String SEARCH_PATH = "/tracks/";

    private final RestClient restClient;
    private final JamendoProperties props;
    private final com.auteur.runtimeconfig.RuntimeConfig runtimeConfig;
    private final HttpClient downloader = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15)).build();

    public JamendoClient(@Qualifier("jamendoRestClient") RestClient restClient,
                         JamendoProperties props,
                         com.auteur.runtimeconfig.RuntimeConfig runtimeConfig) {
        this.restClient = restClient;
        this.props = props;
        this.runtimeConfig = runtimeConfig;
    }

    private String clientId() {
        return runtimeConfig.get("auteur.bgm.jamendo.client-id");
    }

    public List<JamendoTrack> search(String tagsCsv, int limit, int offset) {
        String clientId = clientId();
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalStateException("Jamendo client_id 未配置,去 https://devportal.jamendo.com/ 注册免费 client_id 填到 auteur.bgm.jamendo.client-id");
        }
        log.info("[作曲·Jamendo] search tags={} limit={} offset={}", tagsCsv, limit, offset);
        SearchResponse resp = restClient.get()
                .uri(uri -> uri.path(SEARCH_PATH)
                        .queryParam("client_id", clientId)
                        .queryParam("format", "json")
                        .queryParam("include", "licenses")
                        .queryParam("tags", tagsCsv)
                        .queryParam("limit", limit)
                        .queryParam("offset", offset)
                        .queryParam("order", "popularity_total_desc")
                        .build())
                .retrieve()
                .body(SearchResponse.class);

        if (resp == null || resp.headers == null) {
            throw new RuntimeException("Jamendo search returned no headers");
        }
        int code = resp.headers.code == null ? -1 : resp.headers.code;
        if (code != 0 && code != 200) {
            // Jamendo 用 headers.code=0 表示 OK,1+ 都是错
            String msg = resp.headers.errorMessage != null ? resp.headers.errorMessage : resp.headers.status;
            throw new RuntimeException("Jamendo search failed code=" + code + " msg=" + msg);
        }
        List<JamendoTrack> results = resp.results == null ? Collections.emptyList() : resp.results;
        log.info("[作曲·Jamendo] search returned {} tracks", results.size());
        return results;
    }

    /** Jamendo audio URL 是 24h 失效的签名直链,调用方应立即下载并上 TOS。 */
    public Optional<JamendoTrack> fetchById(long jamendoId) {
        String clientId = clientId();
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalStateException("Jamendo client_id 未配置");
        }
        log.info("[作曲·Jamendo] fetchById id={}", jamendoId);
        SearchResponse resp = restClient.get()
                .uri(uri -> uri.path(SEARCH_PATH)
                        .queryParam("client_id", clientId)
                        .queryParam("format", "json")
                        .queryParam("id", jamendoId)
                        .build())
                .retrieve()
                .body(SearchResponse.class);
        if (resp == null || resp.results == null || resp.results.isEmpty()) return Optional.empty();
        return Optional.of(resp.results.get(0));
    }

    /**
     * 流式下载 Jamendo CDN 直链 mp3 落本地。
     * 用 ofInputStream + Files.copy 自己写文件 — Jamendo audio 字段是 stream URL,响应没 Content-Disposition,
     * ofFileDownload 强制要求该 header 会失败。
     */
    public void download(String audioUrl, Path target) {
        try {
            Files.createDirectories(target.getParent());
            HttpRequest req = HttpRequest.newBuilder(URI.create(audioUrl))
                    .timeout(Duration.ofSeconds(120))
                    .GET().build();
            HttpResponse<InputStream> resp = downloader.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() / 100 != 2) {
                try (InputStream ignore = resp.body()) { /* drain */ }
                throw new IOException("Jamendo download HTTP " + resp.statusCode());
            }
            try (InputStream in = resp.body()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            log.info("[作曲·Jamendo] downloaded {} → {} ({} bytes)", audioUrl, target, Files.size(target));
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new RuntimeException("Jamendo download failed: " + audioUrl, e);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class SearchResponse {
        public Headers headers;
        public List<JamendoTrack> results;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class Headers {
        public String status;
        public Integer code;
        @JsonProperty("error_message") public String errorMessage;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class JamendoTrack {
        public Long id;
        public String name;
        @JsonProperty("artist_name") public String artistName;
        public Integer duration;
        public String audio;
        @JsonProperty("audiodownload") public String audioDownload;
        public String image;
        @JsonProperty("license_ccurl") public String licenseUrl;
        @JsonProperty("musicinfo") public MusicInfo musicInfo;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MusicInfo {
        public List<String> tags;
        public Vocals vocalinstrumental;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Vocals {
    }
}
