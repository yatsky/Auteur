-- 给 preset 加可选的字幕底边比例字段。null = 走 renderer 内置智能默认
-- (竖屏 25.7% / 横屏 12.5%);非 null 时 SubtitleTrack 用 height * 此值。
-- 范围 0.0-0.5;0.5 等于把字幕推到画面正中,大于不合理。
ALTER TABLE `preset`
    ADD COLUMN `subtitle_bottom_ratio` DECIMAL(5,3) NULL DEFAULT NULL
        COMMENT '字幕距底边比例(0.0-0.5);null=走 renderer 智能默认(竖屏 0.257/横屏 0.125)';
