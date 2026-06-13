package com.auteur.notify;

import com.auteur.domain.PipelineRun;
import com.auteur.runtimeconfig.RuntimeConfig;
import com.lark.oapi.Client;
import com.lark.oapi.service.contact.v3.model.BatchGetIdUserReq;
import com.lark.oapi.service.contact.v3.model.BatchGetIdUserReqBody;
import com.lark.oapi.service.contact.v3.model.BatchGetIdUserResp;
import com.lark.oapi.service.contact.v3.model.UserContactInfo;
import com.lark.oapi.service.im.v1.model.CreateMessageReq;
import com.lark.oapi.service.im.v1.model.CreateMessageReqBody;
import com.lark.oapi.service.im.v1.model.CreateMessageResp;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;

/**
 * 飞书私聊通知。流水线阶段(脚本/分镜/配音/合成 等)完成或失败时,给配置的邮箱发一条消息。
 *
 * 设计:
 *  - 总开关 + 凭证 + 接收邮箱都从 RuntimeConfig 读,任一项缺失则 short-circuit 不发
 *  - Client 按 (app-id, app-secret) 缓存,凭证变化时自动重建
 *  - email → open_id 缓存避免每次合成都查通讯录
 *  - 通知整链 try/catch 吞掉所有异常 — 飞书挂了不能影响 pipeline 主流程
 *  - 走 ForkJoinPool.commonPool 异步,主线程立即返回,markDone/markFailed 不等网络
 */
@Slf4j
@Service
public class LarkNotifyService {

    private final RuntimeConfig runtimeConfig;

    /** 客户端缓存。key = "app-id:app-secret",变了就重建。 */
    private volatile String cachedKey = "";
    private volatile Client cachedClient;

    /** email → open_id 缓存。凭证重建时一并清。 */
    private final Map<String, String> emailToOpenId = new ConcurrentHashMap<>();

    public LarkNotifyService(RuntimeConfig runtimeConfig) {
        this.runtimeConfig = runtimeConfig;
    }

    public void notifyStageDone(PipelineRun run) {
        sendAsync(() -> formatDoneMessage(run));
    }

    public void notifyStageFailed(PipelineRun run, String errorMsg) {
        sendAsync(() -> formatFailedMessage(run, errorMsg));
    }

    /** 异步发送,主线程立即返回。所有异常吞掉只 warn,不抛回上游。 */
    private void sendAsync(java.util.function.Supplier<String> messageSupplier) {
        if (!runtimeConfig.getBoolean("auteur.lark.enabled", false)) return;
        String email = runtimeConfig.get("auteur.lark.notify-email");
        if (email.isBlank()) return;
        ForkJoinPool.commonPool().submit(() -> {
            try {
                Client client = getOrInitClient();
                if (client == null) return;
                String openId = resolveOpenId(client, email);
                if (openId == null) {
                    log.warn("[LarkNotify] 找不到邮箱 {} 对应的飞书账号(检查应用是否有通讯录读取权限)", email);
                    return;
                }
                sendText(client, openId, messageSupplier.get());
            } catch (Exception e) {
                log.warn("[LarkNotify] send failed: {}", e.toString());
            }
        });
    }

    /** 凭证缺失 → null。凭证变化 → 重建 Client + 清 email 缓存。 */
    private Client getOrInitClient() {
        String appId = runtimeConfig.get("auteur.lark.app-id");
        String appSecret = runtimeConfig.get("auteur.lark.app-secret");
        if (appId.isBlank() || appSecret.isBlank()) return null;
        String key = appId + ":" + appSecret;
        if (!key.equals(cachedKey)) {
            synchronized (this) {
                if (!key.equals(cachedKey)) {
                    cachedClient = Client.newBuilder(appId, appSecret)
                            .requestTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                            .build();
                    emailToOpenId.clear();
                    cachedKey = key;
                    log.info("[LarkNotify] Client 已 (重)建");
                }
            }
        }
        return cachedClient;
    }

    /** 邮箱 → open_id。命中缓存直接返回;否则调通讯录 batch_get_id。 */
    private String resolveOpenId(Client client, String email) throws Exception {
        String cached = emailToOpenId.get(email);
        if (cached != null) return cached;

        BatchGetIdUserReq req = BatchGetIdUserReq.newBuilder()
                .userIdType("open_id")
                .batchGetIdUserReqBody(BatchGetIdUserReqBody.newBuilder()
                        .emails(new String[]{email})
                        .build())
                .build();
        BatchGetIdUserResp resp = client.contact().v3().user().batchGetId(req);
        if (resp == null || !resp.success() || resp.getData() == null) {
            log.warn("[LarkNotify] batchGetId failed code={} msg={}",
                    resp == null ? -1 : resp.getCode(),
                    resp == null ? "null" : resp.getMsg());
            return null;
        }
        UserContactInfo[] list = resp.getData().getUserList();
        if (list == null || list.length == 0 || list[0].getUserId() == null) return null;
        String openId = list[0].getUserId();
        emailToOpenId.put(email, openId);
        return openId;
    }

    private void sendText(Client client, String openId, String text) throws Exception {
        // 飞书 IM text 消息 content 是 JSON 字符串 {"text":"..."}
        String content = "{\"text\":" + jsonEscape(text) + "}";
        CreateMessageReq req = CreateMessageReq.newBuilder()
                .receiveIdType("open_id")
                .createMessageReqBody(CreateMessageReqBody.newBuilder()
                        .receiveId(openId)
                        .msgType("text")
                        .content(content)
                        .build())
                .build();
        CreateMessageResp resp = client.im().v1().message().create(req);
        if (resp == null || !resp.success()) {
            log.warn("[LarkNotify] send failed code={} msg={}",
                    resp == null ? -1 : resp.getCode(),
                    resp == null ? "null" : resp.getMsg());
        }
    }

    private static String formatDoneMessage(PipelineRun run) {
        return String.format("✅ %s 完成\n关联:topic=%s script=%s\n用时:%s",
                stageName(run.getStage().name()),
                String.valueOf(run.getTopicId()),
                String.valueOf(run.getScriptId()),
                durationOf(run));
    }

    private static String formatFailedMessage(PipelineRun run, String errorMsg) {
        String err = errorMsg == null ? "" : errorMsg;
        if (err.length() > 300) err = err.substring(0, 300) + "...";
        return String.format("❌ %s 失败\n关联:topic=%s script=%s\n错误:%s",
                stageName(run.getStage().name()),
                String.valueOf(run.getTopicId()),
                String.valueOf(run.getScriptId()),
                err);
    }

    /** PipelineStage enum name → 中文短语。 */
    private static String stageName(String stage) {
        return switch (stage) {
            case "BRAINSTORM" -> "选题脑暴";
            case "SCRIPT" -> "脚本生成";
            case "FACTCHECK" -> "事实核查";
            case "STORYBOARD" -> "分镜生成";
            case "IMAGEGEN" -> "图像生成";
            case "IMAGEAUDIT" -> "图像审核";
            case "VOICE" -> "配音合成";
            case "VIDEO" -> "视频合成";
            case "COVER" -> "封面生成";
            default -> stage;
        };
    }

    private static String durationOf(PipelineRun run) {
        if (run.getStartedAt() == null || run.getFinishedAt() == null) return "-";
        long sec = Duration.between(run.getStartedAt(),
                run.getFinishedAt() != null ? run.getFinishedAt() : LocalDateTime.now()).getSeconds();
        if (sec < 60) return sec + "s";
        return (sec / 60) + "m" + (sec % 60) + "s";
    }

    /** JSON 字符串转义。手写避免引一个 ObjectMapper 只为转一个字符串。 */
    private static String jsonEscape(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder(s.length() + 16).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"', '\\' -> sb.append('\\').append(c);
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }
}
