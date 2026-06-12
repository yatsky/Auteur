-- V8: 流水线步骤模型集中管理 — 17 个 step model 行,前端「AI 模型」页面统一编辑
-- 方案要点:
--   1. 复用 app_config 表(category='model'),不另起一张表
--   2. INSERT IGNORE + COALESCE seed 默认值,已配置过的非空值不被覆盖(幂等)
--   3. 默认 seed 值 = 当前代码里散落的实际模型 ID,确保跑完迁移后行为不变
--   4. preset 已指定的(brainstorm / script / script_critic / storyboard / image_primary / bgm_mood prompt 等)
--      这里的值仅作"预设未指定时的全局默认",运行时 preset 优先

INSERT IGNORE INTO app_config (config_key, description, is_secret, category, sort_order) VALUES
  -- 脚本流程(预设未指定时使用)
  ('auteur.model.brainstorm',                '选题脑暴 — 预设里 brainstormPromptYaml.model 未指定时的全局默认',          0, 'model', 10),
  ('auteur.model.script',                    '脚本生成 — 预设里 scriptPromptYaml.model 未指定时的全局默认',              0, 'model', 20),
  ('auteur.model.script_critic',             '脚本批评 — 预设里 criticPromptYaml.model 未指定时的全局默认',              0, 'model', 30),
  ('auteur.model.storyboard',                '分镜生成 — 预设里 storyboardPromptYaml.model 未指定时的全局默认',          0, 'model', 40),

  -- BGM / 钩子 / 事实核查 / 复盘
  ('auteur.model.bgm_mood',                  'BGM 情绪标注 — 给脚本打 6 词表 mood',                                       0, 'model', 110),
  ('auteur.model.hook_extract',              '续集钩子抽取 — E 段 → 下一集 hint',                                          0, 'model', 120),
  ('auteur.model.factcheck',                 '事实核查 P1 — 查疑点',                                                       0, 'model', 130),
  ('auteur.model.factcheck_verify',          '事实核查 P2 — 验证 P1 的疑点是否成立',                                       0, 'model', 140),
  ('auteur.model.factcheck_apply',           '事实核查改写 — 把核查建议落到脚本上',                                        0, 'model', 150),
  ('auteur.model.video_attribution',         '视频归因 — 已发布视频的归因分析',                                            0, 'model', 160),
  ('auteur.model.weekly_review',             '周复盘 — 跨视频周度数据洞察',                                                0, 'model', 170),

  -- 分镜 prompt / 图像审核
  ('auteur.model.shot_prompt_refine',        '分镜 prompt 精修 — 中文画面 → 英文 prompt',                                  0, 'model', 210),
  ('auteur.model.shot_prompt_desensitize',   '分镜 prompt 脱敏 — 上游审查触发后自动改写',                                  0, 'model', 220),
  ('auteur.model.image_audit',               '图像审核 — 给单张分镜成片 0-100 分 + PASS/REGENERATE/MANUAL',                 0, 'model', 230),

  -- 图像生成(预设未指定时使用)
  ('auteur.model.image_primary',             '图像主模型 — 预设里 imageConfig.model 未指定时的全局默认',                   0, 'model', 310),
  ('auteur.model.image_fallback',            '图像降级模型 — 主模型超时/不可用时强制走这个,无法被预设覆盖',                0, 'model', 320),

  -- Agent
  ('auteur.model.agent_default',             'Agent 新会话默认模型 — 用户在 /agent 创建会话时不指定 model 就用这个',       0, 'model', 410);

-- 灌默认 seed 值(等价于过去散落在 prompts/*.yaml 与 Java 字面量的硬编码模型 ID)
UPDATE app_config SET config_value = COALESCE(NULLIF(config_value,''), 'DeepSeek-V3.2')              WHERE config_key = 'auteur.model.brainstorm';
UPDATE app_config SET config_value = COALESCE(NULLIF(config_value,''), 'claude-opus-4-7')            WHERE config_key = 'auteur.model.script';
UPDATE app_config SET config_value = COALESCE(NULLIF(config_value,''), 'claude-opus-4-7')            WHERE config_key = 'auteur.model.script_critic';
UPDATE app_config SET config_value = COALESCE(NULLIF(config_value,''), 'claude-opus-4-7')            WHERE config_key = 'auteur.model.storyboard';

UPDATE app_config SET config_value = COALESCE(NULLIF(config_value,''), 'DeepSeek-V3.2')              WHERE config_key = 'auteur.model.bgm_mood';
UPDATE app_config SET config_value = COALESCE(NULLIF(config_value,''), 'DeepSeek-V3.2')              WHERE config_key = 'auteur.model.hook_extract';
UPDATE app_config SET config_value = COALESCE(NULLIF(config_value,''), 'claude-opus-4-7')            WHERE config_key = 'auteur.model.factcheck';
UPDATE app_config SET config_value = COALESCE(NULLIF(config_value,''), 'xai.grok-4-fast-reasoning')  WHERE config_key = 'auteur.model.factcheck_verify';
UPDATE app_config SET config_value = COALESCE(NULLIF(config_value,''), 'DeepSeek-V3.2')              WHERE config_key = 'auteur.model.factcheck_apply';
UPDATE app_config SET config_value = COALESCE(NULLIF(config_value,''), 'Doubao-pro-128k')            WHERE config_key = 'auteur.model.video_attribution';
UPDATE app_config SET config_value = COALESCE(NULLIF(config_value,''), 'Doubao-pro-128k')            WHERE config_key = 'auteur.model.weekly_review';

UPDATE app_config SET config_value = COALESCE(NULLIF(config_value,''), 'claude-haiku-4-5-20251001')  WHERE config_key = 'auteur.model.shot_prompt_refine';
UPDATE app_config SET config_value = COALESCE(NULLIF(config_value,''), 'claude-haiku-4-5-20251001')  WHERE config_key = 'auteur.model.shot_prompt_desensitize';
UPDATE app_config SET config_value = COALESCE(NULLIF(config_value,''), 'Doubao-Seed-1.6-vision')     WHERE config_key = 'auteur.model.image_audit';

UPDATE app_config SET config_value = COALESCE(NULLIF(config_value,''), 'gpt-image-2')                WHERE config_key = 'auteur.model.image_primary';
UPDATE app_config SET config_value = COALESCE(NULLIF(config_value,''), 'qwen-image-2.0-pro')         WHERE config_key = 'auteur.model.image_fallback';

UPDATE app_config SET config_value = COALESCE(NULLIF(config_value,''), 'claude-opus-4-7')            WHERE config_key = 'auteur.model.agent_default';
