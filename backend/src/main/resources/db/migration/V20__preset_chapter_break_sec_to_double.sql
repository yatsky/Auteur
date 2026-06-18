-- V20:把 preset.chapter_break_sec 从 DECIMAL(3,2) 改成 DOUBLE,让 schema 与 entity 对齐。
--
-- 起因:V19 加列时用了 DECIMAL(3,2),但 Preset.java 里字段类型一直是 java 原生 double,
--      Hibernate validate 在不同 JDBC 驱动上偶尔会因精度差异告警;且 chapter_break_sec
--      只用于秒级时长(无金额精度需求),DOUBLE 更合适。
--
-- 注:本文件是为了让仓库与已部署到共享 DB 的 schema 保持一致而补登的迁移
--     (该 ALTER 此前曾在另一处环境直接执行,history 已记录但 SQL 文件未入库)。
--     现有数据(0.30 等小数)在 DECIMAL(3,2) → DOUBLE 转换中精确保留。

ALTER TABLE preset
  MODIFY COLUMN chapter_break_sec DOUBLE NOT NULL DEFAULT 0.30
    COMMENT '章节边界黑帧时长(秒);前后各 0.1s 渐变包含在内;与字幕 cue 重叠时回退白闪';
