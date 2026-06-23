package com.auteur.hotpool;

import com.auteur.common.text.TextUtils;
import com.auteur.hotpool.adapter.HotItemDraft;
import com.auteur.hotpool.adapter.HotSourceAdapter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

import static org.springframework.http.HttpStatus.*;

/**
 * 手动触发热点拉取。
 *
 * 入口:
 *   fetchAll()          — 抓所有 enabled 源
 *   fetch(sourceId)     — 单源
 *   fetchForPreset(p)   — 按预设的 hotSourceConfig.sourceIds 抓子集(brainstorm 前置)
 *   testFetch(source)   — 不入库,返回前 N 条 preview(添加源时校验用)
 *
 * 写库语义: (source_id, external_id) 唯一 — 重复抓取自动去重,不会产生重复行。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HotFetchService {

    private static final int PREVIEW_LIMIT = 5;

    private final HotSourceRepository sourceRepo;
    private final HotItemRepository itemRepo;
    private final HotSourceRegistry registry;
    private final ObjectMapper objectMapper;

    public record FetchResult(long sourceId, String sourceName, int inserted,
                              int skipped, String error) {
    }

    @Transactional
    public List<FetchResult> fetchAll() {
        List<HotSource> sources = sourceRepo.findByEnabledTrueOrderByIdAsc();
        return sources.stream().map(this::fetchOne).toList();
    }

    @Transactional
    public List<FetchResult> fetchForPreset(Long presetId, List<Long> sourceIds) {
        if (sourceIds == null || sourceIds.isEmpty()) return List.of();
        List<HotSource> sources = sourceRepo.findAllById(sourceIds).stream()
                .filter(HotSource::isEnabled).toList();
        return sources.stream().map(this::fetchOne).toList();
    }

    @Transactional
    public FetchResult fetch(Long sourceId) {
        HotSource source = sourceRepo.findById(sourceId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "hot_source id=" + sourceId));
        return fetchOne(source);
    }

    /** 单源测试 — 不入库,只返回前 N 条预览。添加/编辑源时校验用。 */
    public List<HotItemDraft> testFetch(HotSource source) {
        HotSourceAdapter adapter = registry.find(source.getAdapter())
                .orElseThrow(() -> new ResponseStatusException(BAD_REQUEST,
                        "未知适配器: " + source.getAdapter() + " (可选: " + registry.knownIds() + ")"));
        try {
            List<HotItemDraft> drafts = adapter.fetch(source);
            return drafts.stream().limit(PREVIEW_LIMIT).toList();
        } catch (Exception e) {
            throw new ResponseStatusException(BAD_REQUEST, "抓取失败: " + e.getMessage());
        }
    }

    private FetchResult fetchOne(HotSource source) {
        HotSourceAdapter adapter = registry.find(source.getAdapter()).orElse(null);
        if (adapter == null) {
            String err = "未知适配器: " + source.getAdapter();
            log.warn("[hotpool] {} (source={})", err, source.getName());
            updateStatus(source, 0, err);
            return new FetchResult(source.getId(), source.getName(), 0, 0, err);
        }
        try {
            List<HotItemDraft> drafts = adapter.fetch(source);
            List<String> defaultTags = parseDefaultTags(source);
            int inserted = 0, skipped = 0;
            for (HotItemDraft d : drafts) {
                String extId = stableExternalId(d);
                if (extId == null) {
                    skipped++;
                    continue;
                }
                if (itemRepo.findBySourceIdAndExternalId(source.getId(), extId).isPresent()) {
                    skipped++;
                    continue;
                }
                HotItem item = toEntity(d, source, extId, defaultTags);
                itemRepo.save(item);
                inserted++;
            }
            updateStatus(source, inserted, null);
            log.info("[hotpool] fetched src={} inserted={} skipped={}",
                    source.getName(), inserted, skipped);
            return new FetchResult(source.getId(), source.getName(), inserted, skipped, null);
        } catch (Exception e) {
            String err = e.getMessage();
            log.warn("[hotpool] fetchOne failed src={} err={}", source.getName(), err);
            updateStatus(source, 0, err);
            return new FetchResult(source.getId(), source.getName(), 0, 0, err);
        }
    }

    private void updateStatus(HotSource source, int count, String error) {
        source.setLastFetchedAt(LocalDateTime.now());
        source.setLastFetchCount(count);
        source.setLastFetchError(error);
        sourceRepo.save(source);
    }

    private HotItem toEntity(HotItemDraft d, HotSource source, String extId, List<String> defaultTags) {
        HotItem item = new HotItem();
        item.setSourceId(source.getId());
        item.setExternalId(extId);
        item.setTitle(TextUtils.truncate(d.getTitle(), 500));
        item.setSummary(d.getSummary());
        item.setUrl(d.getUrl());
        item.setBodyText(d.getBodyText());
        item.setPopularity(d.getPopularity() == null ? 0.5 : d.getPopularity());
        item.setLocale(d.getLocale() == null ? "zh" : d.getLocale());
        item.setPublishedAt(d.getPublishedAt());
        item.setFetchedAt(LocalDateTime.now());
        item.setRawPayloadJson(d.getRawPayload());
        item.setStatus("new");

        // 合并 default_tags 与适配器自带 tags
        List<String> merged = new ArrayList<>(defaultTags);
        if (d.getTags() != null) for (String t : d.getTags()) if (!merged.contains(t)) merged.add(t);
        if (!merged.isEmpty()) {
            try {
                item.setTagsJson(objectMapper.writeValueAsString(merged));
            } catch (Exception ignored) {
            }
        }
        return item;
    }

    private List<String> parseDefaultTags(HotSource source) {
        if (source.getDefaultTagsJson() == null || source.getDefaultTagsJson().isBlank()) return List.of();
        try {
            JsonNode node = objectMapper.readTree(source.getDefaultTagsJson());
            if (!(node instanceof ArrayNode arr)) return List.of();
            List<String> out = new ArrayList<>(arr.size());
            arr.forEach(t -> out.add(t.asText()));
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String stableExternalId(HotItemDraft d) {
        if (d.getExternalId() != null && !d.getExternalId().isBlank()) {
            return TextUtils.truncate(d.getExternalId(), 200);
        }
        if (d.getUrl() != null && !d.getUrl().isBlank()) return md5(d.getUrl());
        if (d.getTitle() != null) return md5(d.getTitle());
        return null;
    }

    private static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] bytes = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
