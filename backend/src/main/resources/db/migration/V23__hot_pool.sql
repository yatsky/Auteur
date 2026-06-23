-- V23: 热点池 — 财经/通用赛道选题原料
--
-- 设计要点:
--   1. 一张 hot_item 表,所有赛道共用,靠 tags / hot_source_config 在查询时区分财经/体育/科技...
--   2. 触发模型:手动 — 不做调度 / 不开后台轮询,brainstorm 时按需拉取
--   3. 适配器层目前两个内置:rss (com.auteur.hotpool.adapter.RssHotSource) +
--      http_json (com.auteur.hotpool.adapter.HttpJsonHotSource);加新适配器只写 Java 类
--   4. 默认 seed 4 个国内财经源,由 com.auteur.hotpool.HotSourceSeeder 启动时灌
--   5. preset.hot_source_config 走 JSON 列,结构对应前端「热点订阅」tab 的所有字段,可为 NULL = 不订阅

CREATE TABLE hot_source (
  id              BIGINT       NOT NULL AUTO_INCREMENT,
  name            VARCHAR(100) NOT NULL,
  adapter         VARCHAR(32)  NOT NULL COMMENT 'rss / http_json — 对应一个 com.auteur.hotpool.adapter.HotSource 实现',
  url             VARCHAR(1000) NOT NULL,
  config_json     TEXT         NULL     COMMENT 'adapter 私有配置 — http_json 用来配 title/url/published_at 的 JSON Pointer 表达式',
  default_tags_json TEXT       NULL     COMMENT '该源默认打的 tag 数组(JSON)。LLM 自动 tag 关闭时唯一信息源',
  enabled         TINYINT(1)   NOT NULL DEFAULT 1,
  last_fetched_at DATETIME     NULL,
  last_fetch_count INT         NULL     COMMENT '上一次手动抓到几条(去重后)',
  last_fetch_error VARCHAR(500) NULL    COMMENT '上一次失败原因;成功时清空',
  created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_hot_source_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='热点抓取源 — 用户在系统设置「热点源」页管理';

CREATE TABLE hot_item (
  id              BIGINT       NOT NULL AUTO_INCREMENT,
  source_id       BIGINT       NOT NULL,
  external_id     VARCHAR(200) NOT NULL COMMENT '源站去重 key — URL hash 或源自带 ID;同源 (source_id, external_id) 唯一',
  title           VARCHAR(500) NOT NULL,
  summary         TEXT         NULL,
  url             VARCHAR(1000) NULL,
  body_text       MEDIUMTEXT   NULL    COMMENT '原文全文;部分源不提供则为空',
  tags_json       TEXT         NULL    COMMENT '标签数组(JSON)',
  popularity      DOUBLE       NOT NULL DEFAULT 0.5 COMMENT '归一化 0..1 — 源站热度排序的相对位置',
  locale          VARCHAR(10)  NOT NULL DEFAULT 'zh',
  published_at    DATETIME     NULL,
  fetched_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  raw_payload_json LONGTEXT    NULL    COMMENT '原始响应 JSON,debug/迁移用;过期可置空',
  status          VARCHAR(20)  NOT NULL DEFAULT 'new' COMMENT 'new / promoted / dismissed',
  promoted_topic_id BIGINT     NULL    COMMENT '若 status=promoted,记录转出的 topic.id',
  created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_hot_item_source_ext (source_id, external_id),
  KEY idx_hot_item_status_pub (status, published_at DESC),
  KEY idx_hot_item_fetched (fetched_at DESC),
  CONSTRAINT fk_hot_item_source FOREIGN KEY (source_id) REFERENCES hot_source(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='热点条目池 — brainstorm 阶段从这里选种子';

-- preset.hot_source_config: JSON 列,前端「热点订阅」tab 落盘结构
-- 字段示例: { enabled: true, sourceIds: [1,2,3], includeKeywords: [...], excludeKeywords: [...], maxAgeHours: 48, minPopularity: 0.3 }
-- 为空 = 该预设不订阅热点 (brainstorm 时不拉)
ALTER TABLE preset
  ADD COLUMN hot_source_config_json TEXT NULL COMMENT '预设的热点订阅配置(JSON);为空表示不订阅';
