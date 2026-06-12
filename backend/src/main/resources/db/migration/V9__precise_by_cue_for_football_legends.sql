-- V9: football_legends preset 切换到 PRECISE_BY_CUE 分镜模式(仅 mode,不含 prompt 内容)
--
-- 背景:
--   原 FREE 模式按"sections × 2-4"目标拆镜,8 段脚本只出 27 镜(平均 11s/镜),
--   每镜要扛 2-3 句字幕,且后端走 PRECISE_BY_SECTION/UNIFORM_SCALE 等比缩放,
--   段内 cue 起点跟画面切换不对齐 → 反馈"分镜太少 + 音画对不上"。
--
-- 修复:
--   storyboard_mode FREE → PRECISE_BY_CUE,跟人生档案馆同款。
--   ShotTimingResolver.tryPreciseByCue() 已就绪,不需要改后端代码。
--
-- 配套(不在本 migration 里):
--   storyboard_prompt_yaml 必须同时改成 cue 锚定版(强制 anchor_cue_indices 输出)。
--   football_legends 是私有频道预设,prompt 内容**不进开源仓**,
--   通过 `.local/update_football_legends.sh` 单独把 .local/football_legends/storyboard.yaml
--   推到本地 DB。
--
-- 部署顺序:
--   1. backend 起来 → Flyway 跑本 migration 切 mode(中间态:mode=PRECISE_BY_CUE 但 yaml 还是旧的,
--      生成分镜会自动 fallback 到 PRECISE_BY_SECTION,等同原 FREE 行为,不会崩)
--   2. 跑 `./.local/update_football_legends.sh` 推新 yaml → 真正生效
--   3. 在 UI force=true 重新生成 script 的分镜
--
-- 影响:
--   - 现有 script 用此 preset 生成的旧分镜需要 force=true 重跑
--   - 镜头数 ~翻倍,下游图片生成成本相应翻倍

UPDATE preset
SET storyboard_mode = 'PRECISE_BY_CUE'
WHERE name = 'football_legends'
  AND storyboard_mode = 'FREE';
