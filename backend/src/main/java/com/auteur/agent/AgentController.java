package com.auteur.agent;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Agent REST + SSE 入口。
 */
@Slf4j
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentController {

    private final AgentSessionRepository sessionRepo;
    private final AgentMessageRepository messageRepo;
    private final AgentLoopService loopService;
    private final ToolRegistry toolRegistry;
    private final SystemPromptBuilder systemPromptBuilder;
    private final ApprovalGate approvalGate;
    private final AgentCancellationRegistry cancellationRegistry;
    private final com.auteur.llm.ModelRegistry modelRegistry;

    /**
     * SSE 长连接和 LLM 调用都不能占着 servlet 容器线程,挪到自己的线程池。
     * 单用户场景给 8 个并发槽位 + 32 等待队列;cachedThreadPool 在异常重连时容易线程爆炸,弃用。
     */
    private static final AtomicLong threadSeq = new AtomicLong();
    private final Executor sseExecutor = new ThreadPoolExecutor(
            2, 8,
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(32),
            r -> {
                Thread t = new Thread(r, "agent-sse-" + threadSeq.incrementAndGet());
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy()
    );

    @PostMapping("/sessions")
    @Transactional
    public AgentSession createSession(@RequestBody(required = false) CreateSessionRequest body) {
        AgentSession s = new AgentSession();
        if (body != null && body.getModel() != null && !body.getModel().isBlank()) {
            s.setModel(body.getModel().trim());
        } else {
            // 用户没指定 → 落库就把 app_config 里的全局 agent_default 写进去,
            // 后续这条会话的所有 LLM 调用直接读 session.model,不再依赖 fallback 路径。
            s.setModel(modelRegistry.modelFor("agent_default"));
        }
        s.setSystemPromptVersion(systemPromptBuilder.version());
        AgentSession saved = sessionRepo.save(s);
        log.info("[Agent] 新建会话 id={} model={}", saved.getId(), saved.getModel());
        return saved;
    }

    @GetMapping("/sessions")
    public List<AgentSession> listSessions() {
        return sessionRepo.findAllByOrderByUpdatedAtDesc();
    }

    @GetMapping("/sessions/{id}")
    public AgentSession getSession(@PathVariable Long id) {
        return sessionRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("session 不存在: " + id));
    }

    @GetMapping("/sessions/{id}/messages")
    public List<AgentMessage> listMessages(@PathVariable Long id) {
        return messageRepo.findBySessionIdOrderBySeqAsc(id);
    }

    @DeleteMapping("/sessions/{id}")
    @Transactional
    public void deleteSession(@PathVariable Long id) {
        sessionRepo.deleteById(id);
    }

    /** SSE:用户发消息触发一轮 agent 循环。 */
    @PostMapping(value = "/sessions/{id}/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@PathVariable Long id, @RequestBody ChatRequestBody body) {
        // LLM 调用 30~90s 都可能,emitter 给 5 分钟超时
        SseEmitter emitter = new SseEmitter(5L * 60 * 1000);
        emitter.onTimeout(() -> {
            log.warn("[Agent] SSE 超时 sessionId={},触发 cancel", id);
            cancellationRegistry.cancel(id);
        });
        emitter.onError(e -> {
            log.warn("[Agent] SSE 错误 sessionId={}: {},触发 cancel", id, e.toString());
            cancellationRegistry.cancel(id);
        });

        try {
            sseExecutor.execute(() -> loopService.turn(id, body == null ? "" : body.getMessage(), emitter));
        } catch (java.util.concurrent.RejectedExecutionException e) {
            log.warn("[Agent] sseExecutor 已满,拒绝 sessionId={}", id);
            try {
                emitter.send(SseEmitter.event().name("error")
                        .data(Map.of("message", "服务器忙(并发会话已满),请稍后重试")));
            } catch (java.io.IOException ignored) {
            }
            emitter.complete();
        }
        return emitter;
    }

    /**
     * 显式取消正在跑的 turn。幂等:找不到对应 cancelSignal 不报错(可能已结束),返回 ok=false。
     */
    @PostMapping("/sessions/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable Long id) {
        boolean ok = cancellationRegistry.cancel(id);
        return Map.of("ok", ok, "sessionId", id, "note", ok ? "已发送取消信号" : "无活跃 turn(可能已结束)");
    }

    @GetMapping("/tools")
    public Map<String, Object> listTools() {
        return Map.of(
                "tools", toolRegistry.snapshot().keySet(),
                "definitions", toolRegistry.definitions()
        );
    }

    /**
     * HITL:用户对一个待审批 tool_call 给出决定。
     *
     * 路径里的 {id} 必须与 toolCallId 注册时绑定的 sessionId 匹配,否则视为跨会话注入直接拒绝。
     */
    @PostMapping("/sessions/{id}/approve")
    public Map<String, Object> approveTool(@PathVariable Long id, @RequestBody ApproveRequest body) {
        ApprovalGate.ResolveOutcome outcome = approvalGate.resolve(
                body.getToolCallId(), id, body.isApproved(), body.getReason());
        log.info("[Agent] approve sessionId={} toolCallId={} approved={} outcome={}",
                id, body.getToolCallId(), body.isApproved(), outcome);
        boolean ok = outcome == ApprovalGate.ResolveOutcome.OK;
        String note = switch (outcome) {
            case OK -> "已送达";
            case NOT_FOUND -> "未找到对应待审批项(可能已超时/被取消/重复响应)";
            case SESSION_MISMATCH -> "审批项不属于该会话,已拒绝";
            case ALREADY_RESOLVED -> "审批已被先一步完成(超时或并发取消),你的决定未生效";
        };
        return Map.of(
                "ok", ok,
                "toolCallId", body.getToolCallId(),
                "approved", body.isApproved(),
                "outcome", outcome.name(),
                "note", note
        );
    }

    @Data
    public static class CreateSessionRequest {
        private String model;
    }

    @Data
    public static class ChatRequestBody {
        private String message;
    }

    @Data
    public static class ApproveRequest {
        private String toolCallId;
        private boolean approved;
        private String reason;
    }
}
