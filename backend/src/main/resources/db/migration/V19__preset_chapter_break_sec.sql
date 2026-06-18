-- V19:加 preset.chapter_break_sec 字段,把章节断点黑帧时长从 Remotion 端常量(0.3s)下沉到 preset。
--
-- 语义:
--   章节边界(shot.section_code 切换处)插入的 BlackHoldFrame 时长(秒,前后各 0.1s 渐变)。
--   值越大黑过渡越明显;但黑帧 + 安全缓冲(±0.1s)若与字幕 cue 重叠会自动回退到 FlashHint 白闪,
--   值过大反而更容易被降级。建议范围 0.30-1.00。
--
-- 默认 0.30 沿用旧行为;需要更明显黑过渡的预设(典型:lifecopy)在 PresetEditor 里调到 0.6-0.8 即可。

ALTER TABLE preset
  ADD COLUMN chapter_break_sec DECIMAL(3,2) NOT NULL DEFAULT 0.30
    COMMENT '章节边界黑帧时长(秒);前后各 0.1s 渐变包含在内;与字幕 cue 重叠时回退白闪';
