-- V20:把 storyboard_shot.negative_prompt 从 VARCHAR(500) 放宽到 TEXT。
--
-- 起因:火山图像模型判定敏感后走自动脱敏(shot_prompt_desensitize),Claude Haiku 改写
--      产出的 negative_prompt 关键词列表常常超过 500 字符,写回时 MySQL 报
--      "Data truncation: Data too long for column 'negative_prompt'",
--      导致脱敏后图片虽已传 TOS,整段任务仍因 DB 写回失败回滚。
--
-- 同表 prompt_zh / prompt_en 已经是 TEXT,negative_prompt 单独限 500 没有道理;
-- 截断会破坏负面 prompt 语义(关键词列表中段截断 = 留半个词),所以扩列而非截断。

ALTER TABLE storyboard_shot
  MODIFY COLUMN negative_prompt TEXT COLLATE utf8mb4_unicode_ci DEFAULT NULL;
