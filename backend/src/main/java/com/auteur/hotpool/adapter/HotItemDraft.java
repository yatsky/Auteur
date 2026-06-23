package com.auteur.hotpool.adapter;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 适配器输出的归一化结构 — 所有 HotSourceAdapter.fetch() 都吐这个。
 *
 * 下游 HotFetchService 把它转成 HotItem 写库;转换时:
 *   externalId — 优先 externalId,缺则用 url 的 md5
 *   tags        — 适配器自己打的 tags 与 HotSource.defaultTags 合并去重
 *   popularity  — 适配器若没排序信息则统一 0.5,否则按 [0,1] 归一化
 */
@Data
@Builder
@AllArgsConstructor
public class HotItemDraft {

    /** 源站去重 key,可空(空则用 url 的 md5)。 */
    private String externalId;

    private String title;
    private String summary;
    private String url;
    private String bodyText;

    /** 归一化 0..1。null 时下游回填 0.5。 */
    private Double popularity;

    private List<String> tags;
    private LocalDateTime publishedAt;
    private String locale;

    /** 原始响应片段(可空) — 入库到 raw_payload_json,debug 用。 */
    private String rawPayload;
}
