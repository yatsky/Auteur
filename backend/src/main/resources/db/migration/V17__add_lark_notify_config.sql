-- V17: 注册飞书 (Lark) 通知配置
--
-- 用途:流水线某个阶段(脚本生成 / 分镜 / 配音 / 合成 等)跑完后,
--      通过飞书私聊给指定邮箱的用户发一条结果消息(成功 / 失败 + 摘要)。
--
-- 4 个配置项:
--   - enabled:总开关。false / 空 = 全局禁用通知,业务代码直接 short-circuit
--   - app-id / app-secret:飞书企业自建应用凭证
--     (在飞书开发者后台创建一个内部应用,要求 IM 消息发送 + 通讯录读取权限)
--   - notify-email:接收人邮箱(通常是运营同学的飞书账号邮箱)
--
-- 业务代码默认值:
--   enabled 默认 false → 客户没填 app 凭证时不会乱发消息
--   其它三项默认空 → 客户启用后再填

INSERT IGNORE INTO app_config (config_key, description, is_secret, category, sort_order) VALUES
  ('auteur.lark.enabled',      '是否启用飞书通知(填好下面 3 项后开启)',                                  0, 'lark', 10),
  ('auteur.lark.app-id',       '飞书自建应用 App ID(开发者后台→应用→凭证与基础信息)',                 0, 'lark', 20),
  ('auteur.lark.app-secret',   '飞书自建应用 App Secret(同 App ID 同一页)',                              1, 'lark', 30),
  ('auteur.lark.notify-email', '接收通知的飞书账号邮箱(每个流水线阶段完成后给这个邮箱发私聊)',          0, 'lark', 40);

-- 默认值都留空;客户在「系统设置 → 飞书通知」UI 自己填
UPDATE app_config SET config_value = COALESCE(NULLIF(config_value,''), 'false') WHERE config_key = 'auteur.lark.enabled';
