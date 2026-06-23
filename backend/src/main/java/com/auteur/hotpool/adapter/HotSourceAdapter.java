package com.auteur.hotpool.adapter;

import com.auteur.hotpool.HotSource;

import java.util.List;

/**
 * 热点抓取适配器 — 一个实现 = 一种源类型(rss / http_json / hn / ...)。
 *
 * 约束:
 *   - 实现类必须是 @Component,被 HotSourceRegistry 启动扫描
 *   - fetch() 单源失败不能 throw 出适配器边界 — 内部 catch 后返回空列表 + 调用方读 last-error
 *     这是 CLAUDE.md 第 6 条「本地优先 + 可降级」的体现
 *   - 内置适配器禁止依赖 RestTemplate/WebClient — 用 JDK 21 自带 HttpClient,
 *     避免被 starter 隐式注入 timeout/proxy 拖累
 */
public interface HotSourceAdapter {

    /** 适配器 id,如 "rss" / "http_json"。与 hot_source.adapter 列对齐。 */
    String id();

    /**
     * 拉一批最新条目。
     *
     * @param source 整行配置 — adapter 自己解析 source.getConfigJson() 来读私有配置
     * @return 抓到的条目;失败时返回空列表(不要 throw)
     */
    List<HotItemDraft> fetch(HotSource source);
}
