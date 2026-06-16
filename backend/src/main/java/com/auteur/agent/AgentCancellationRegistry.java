package com.auteur.agent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 按 sessionId 维护一个取消信号(AtomicBoolean)。
 *
 * 同一 session 不并发跑多个 turn(前端 busy 锁),所以一对一映射够用。
 * 即使竞态导致老 signal 被覆盖,老 turn 持有的局部引用仍指向自己那个 AtomicBoolean,
 * cancel 只影响"现役"那一个,不会误伤。
 *
 * 联动:cancel(sessionId) 同时调 ApprovalGate.cancelSession,
 * 让阻塞在审批 future.get 的线程立即解除等待,不必等 60s 自然超时。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentCancellationRegistry {

    private final ApprovalGate approvalGate;

    private final ConcurrentHashMap<Long, AtomicBoolean> signals = new ConcurrentHashMap<>();

    /** turn 开始时调,返回自己持有的 signal 引用。 */
    public AtomicBoolean register(Long sessionId) {
        AtomicBoolean signal = new AtomicBoolean(false);
        signals.put(sessionId, signal);
        return signal;
    }

    /**
     * conditional remove:只移除我注册的那个,不误删后来者。
     * 防止新 turn 已经 register 后,老 turn 的 finally 把新 signal 抹掉。
     */
    public void unregister(Long sessionId, AtomicBoolean ownSignal) {
        signals.remove(sessionId, ownSignal);
    }

    public boolean cancel(Long sessionId) {
        AtomicBoolean s = signals.get(sessionId);
        if (s == null) {
            log.debug("[Agent] cancel 找不到 sessionId={} (可能已结束)", sessionId);
            // 即使没有活跃 turn,也尝试清掉残留的审批 future(极端 race 兜底)。
            approvalGate.cancelSession(sessionId);
            return false;
        }
        boolean fresh = s.compareAndSet(false, true);
        if (fresh) {
            log.info("[Agent] cancel sessionId={}", sessionId);
            // 同步唤醒挂起的审批等待,turn 不必等 500ms 轮询或 60s 超时。
            approvalGate.cancelSession(sessionId);
        }
        return fresh;
    }
}
