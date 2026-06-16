-- V18: 移除 preset.owner_name / visibility — 项目个人部署化,删除多用户 UI 隔离的角色字段。
-- 这两列原是给"多用户共享部署的私有/公开预设隔离"用的(配合前端 X-Auteur-Admin/X-Auteur-Owner 头),
-- 实际部署形态是单用户本机,无威胁面,这套机制只是徒增交互成本,一次性清掉。
--
-- 必须先 DROP KEY 再 DROP COLUMN(MySQL 部分版本会因列被索引引用而拒绝单独删列)。
-- 参照 V7__drop_unused_message_created_index.sql 的 DROP 风格。
--
-- 注:preset_version.snapshot_json 列里历史快照 JSON 仍含这两个键,
-- 由 Preset entity 加 @JsonIgnoreProperties(ignoreUnknown=true) 兜住 rollback 反序列化。

ALTER TABLE `preset`
  DROP KEY `idx_preset_owner`,
  DROP KEY `idx_preset_visibility`,
  DROP COLUMN `owner_name`,
  DROP COLUMN `visibility`;
