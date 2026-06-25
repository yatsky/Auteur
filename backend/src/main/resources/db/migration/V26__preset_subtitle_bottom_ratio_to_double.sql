-- 修复 V25 的列类型不匹配:V25 用了 DECIMAL(5,3),但 Preset.java 字段是 java 原生 Double,
-- Hibernate validate 期望 FLOAT(53)/DOUBLE。原因跟 V19→V20 同款(chapter_break_sec 也踩过)。
-- ratio 是 IEEE 浮点比例(无金额精度需求),DOUBLE 更合适。
-- 现有 NULL 数据无损迁移。
ALTER TABLE `preset`
  MODIFY COLUMN `subtitle_bottom_ratio` DOUBLE NULL DEFAULT NULL
    COMMENT '字幕距底边比例(0.0-0.5);null=走 renderer 智能默认(竖屏 0.257/横屏 0.125)';
