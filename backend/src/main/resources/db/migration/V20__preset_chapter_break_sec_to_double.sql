-- V20:把 V19 加的 chapter_break_sec 从 DECIMAL(3,2) 改成 DOUBLE,与 entity 的 `double` 字段对齐。
--
-- 原因:Preset.chapterBreakSec 是 Java `double` 基本类型,Hibernate ddl-auto: validate
-- 会期望列类型为 FLOAT(53)(MySQL 里 DOUBLE 即 FLOAT(53))。V19 写成 DECIMAL(3,2) 导致
-- schema-validation 失败:"found [decimal (Types#DECIMAL)], but expecting [float(53) (Types#FLOAT)]"。
--
-- 改成 DOUBLE 不损失精度(0.30/0.60/0.80 这类值都能精确表示),且省去 entity 改 BigDecimal
-- 带来的一连串签名改动。原 V19 不动,Flyway 已 applied 不能改 checksum;新部署会先 V19
-- 建 DECIMAL 列再 V20 改 DOUBLE,中间多一次 ALTER 但功能无差。

ALTER TABLE preset
  MODIFY COLUMN chapter_break_sec DOUBLE NOT NULL DEFAULT 0.30
    COMMENT '章节边界黑帧时长(秒);前后各 0.1s 渐变包含在内;与字幕 cue 重叠时回退白闪';
