-- V3:加 preset.bgm_locked 字段,把"BGM 由后端兜底锁定、前端不让用户选曲"这种行为从代码硬编码下沉到数据。
--
-- 语义:
--   bgm_enabled = false                   → 整段无 BGM
--   bgm_enabled = true,  bgm_locked = false → 用户在 BgmPicker 里选(默认 freeform 行为)
--   bgm_enabled = true,  bgm_locked = true  → 用户不让选,后端按预设的兜底逻辑出曲
--
-- 老 freeform/lifecopy 行均默认 false;需要锁定的预设(典型:lifecopy)启动后在 PresetEditor 里勾上即可。

ALTER TABLE preset
  ADD COLUMN bgm_locked BOOLEAN NOT NULL DEFAULT FALSE
    COMMENT 'BGM 选曲是否锁定(true=用户不让选,后端按 preset 兜底逻辑出曲)';
