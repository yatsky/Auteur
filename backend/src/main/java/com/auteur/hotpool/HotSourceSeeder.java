package com.auteur.hotpool;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时若 hot_source 表为空,灌 4 条默认源(国内财经为主)。
 *
 * 默认源选择原则:
 *   - 不依赖外部部署(36氪/新浪)默认 enabled=true
 *   - 依赖 RSSHub 自部署的(微博热搜)默认 enabled=false,用户起 RSSHub 后手动启用
 *   - 不内置任何爬虫;不内置海外源(GFW 不通)
 *
 * 探测过的源现状(2026-06):
 *   ✅ 36氪 RSS feed/feed-newsflash 可用,标准 RSS 2.0
 *   ✅ 新浪 mix API 可用(feed.mix.sina.com.cn),JSON
 *   ❌ 财联社/华尔街见闻/财新 一律 404 或 paywall
 *   ❌ 知乎/百度/抖音/头条 热搜 一律 Forbidden/反爬
 *   🟡 微博/知乎/B 站/小红书 走 RSSHub 自部署(用户起容器后,本地 :1200 暴露)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HotSourceSeeder implements ApplicationRunner {

    private final HotSourceRepository sourceRepo;

    @Override
    public void run(ApplicationArguments args) {
        if (sourceRepo.countAll() > 0) return;
        log.info("[hotpool] 检测到 hot_source 表为空,灌默认源...");

        seed(s -> {
            s.setName("36氪 快讯");
            s.setAdapter("rss");
            s.setUrl("https://36kr.com/feed-newsflash");
            s.setDefaultTagsJson("[\"财经\",\"快讯\",\"创投\"]");
            s.setEnabled(true);
        });

        seed(s -> {
            s.setName("36氪 文章");
            s.setAdapter("rss");
            s.setUrl("https://36kr.com/feed");
            s.setDefaultTagsJson("[\"财经\",\"商业\",\"深度\"]");
            s.setEnabled(true);
        });

        seed(s -> {
            s.setName("新浪滚动 财经");
            s.setAdapter("http_json");
            s.setUrl("https://feed.mix.sina.com.cn/api/roll/get?pageid=153&lid=2516&num=50&versionNumber=1.2.4");
            s.setConfigJson("""
                    {
                      "itemsPointer": "/result/data",
                      "titlePointer": "/title",
                      "urlPointer": "/url",
                      "summaryPointer": "/intro",
                      "externalIdPointer": "/wapurl",
                      "publishedPointer": "/ctime",
                      "publishedFormat": "epoch_seconds",
                      "limit": 50
                    }
                    """);
            s.setDefaultTagsJson("[\"财经\",\"综合\"]");
            s.setEnabled(true);
        });

        seed(s -> {
            s.setName("微博热搜（需 RSSHub）");
            s.setAdapter("rss");
            // 用户起 RSSHub 后(本机 / docker compose / pnpm start),改成实际地址再启用
            s.setUrl("http://localhost:1200/weibo/search/hot");
            s.setDefaultTagsJson("[\"社会\",\"热搜\"]");
            s.setEnabled(false); // 默认关闭 — 用户起 RSSHub 后在 UI 启用
        });

        log.info("[hotpool] 默认源 seed 完成({} 条)", sourceRepo.countAll());
    }

    private void seed(java.util.function.Consumer<HotSource> filler) {
        HotSource s = new HotSource();
        filler.accept(s);
        sourceRepo.save(s);
    }
}
