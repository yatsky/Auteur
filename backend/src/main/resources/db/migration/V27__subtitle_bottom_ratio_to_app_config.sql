-- 字幕距底边比例从 preset 字段升级为 app_config 全局基础设置
-- 起因:用户说"作为基础设置,不只 finance_translator",所有 preset 共享同一个值。
-- V25/V26 加的列实际上没人 set 过具体值(都是 NULL),DROP 安全。
-- 新位置:app_config.config_key='auteur.video.subtitle-bottom-ratio',VideoAssemblyService 用 RuntimeConfig.getDouble(key, 0.0) 读。
-- 0 = renderer 走智能默认(竖屏 25.7% / 横屏 12.5%);0-0.5 之间手动定值。

ALTER TABLE `preset`
  DROP COLUMN `subtitle_bottom_ratio`;

INSERT IGNORE INTO `app_config` (`config_key`, `config_value`, `description`)
VALUES (
  'auteur.video.subtitle-bottom-ratio',
  '',
  '字幕距视频底边的比例 0.0-0.5,空 = 走 renderer 智能默认(竖屏 25.7%/横屏 12.5%);拉大可避开抖音底部标题 UI'
);
