-- V21:把 Remotion publicBaseUrl 从 localhost 切到 127.0.0.1,锁死 IPv4 避开 Windows 双栈坑。
--
-- 起因:Windows 上 'localhost' 解析先尝试 IPv6 (::1) 再回落 IPv4,Chromium headless
-- (Remotion 渲染容器) 与浏览器在某些场景下会卡几秒到几分钟。改成 127.0.0.1 等价但无歧义。
-- mac/linux 上 'localhost' 本来就 IPv4 优先,行为不变。
--
-- 只改 V11 灌进来的 init 默认值('http://localhost:8082'),保留所有用户自定义值
-- (Docker: http://backend:8082 / 远程: http://1.2.3.4:8082 / 自定义域名等),不会误伤。

UPDATE app_config
SET config_value = 'http://127.0.0.1:8082'
WHERE config_key = 'auteur.video.remotion.public-base-url'
  AND config_value = 'http://localhost:8082';
