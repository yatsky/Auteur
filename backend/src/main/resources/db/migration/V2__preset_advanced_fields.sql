-- V2:把 LIFE_COPY 写死的常量下沉到 preset,实现真·数据驱动。
-- 新字段都允许 NULL/默认 0,旧 freeform 行不需要这些值;用户自建"锁脸+漫画风+hook"形态预设时填进去即可。
--
-- 注:style_tag / negative_prompt 这两个原本写死成 STYLE_TAG_LIFE_COPY 等常量的字段,
--    复用现有的 preset.image_config_json.{styleTag,negativePrompt},不再加列。

ALTER TABLE preset
  ADD COLUMN min_extreme_closeup INT NOT NULL DEFAULT 0
    COMMENT 'storyboard critic 极特写最少镜数,0=不检查',
  ADD COLUMN hook_page_flip_sound_url VARCHAR(500) DEFAULT NULL
    COMMENT 'hook 段翻书音效 URL(配 hook_segment_enabled=1 时使用)。空 → 不放音效';
