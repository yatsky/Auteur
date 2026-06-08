package com.auteur.video;

import com.auteur.domain.DirectorNoteAddendum;
import com.auteur.domain.DirectorNoteAddendumRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 导演笔记累积服务:每个 stage 完成后 append 一条,下游 stage 渲染 prompt 前 buildBlock 注入。
 * 写入 REQUIRES_NEW,主流程事务回滚不连带丢笔记。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DirectorNoteService {

    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    /** 单条上限,超长截断防 prompt 爆炸。 */
    private static final int MAX_ADDENDUM_CHARS = 500;

    private static String displayRole(String role) {
        if (role == null) return "未知";
        return switch (role) {
            case "SCRIPT" -> "编剧";
            case "STORYBOARD" -> "摄影";
            case "VOICE" -> "录音";
            case "ASSISTANT_DIRECTOR" -> "副导演";
            default -> role;
        };
    }

    private final DirectorNoteAddendumRepository repo;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void append(String role, Long topicId, String text) {
        if (topicId == null || role == null || text == null || text.isBlank()) return;
        try {
            DirectorNoteAddendum row = new DirectorNoteAddendum();
            row.setTopicId(topicId);
            row.setRole(role);
            String trimmed = text.length() > MAX_ADDENDUM_CHARS
                    ? text.substring(0, MAX_ADDENDUM_CHARS) + "..."
                    : text;
            row.setAddendumText(trimmed);
            repo.save(row);
            log.info("[DirectorNote] @群 role={} topicId={} chars={}", role, topicId, trimmed.length());
        } catch (Exception e) {
            log.warn("[DirectorNote] append 失败 role={} topicId={}: {}", role, topicId, e.toString());
        }
    }

    /** 拼群聊汇总,塞进下游 prompt 的 {{creator_addenda_block}}。无数据返回空串。 */
    @Transactional(readOnly = true)
    public String buildBlock(Long topicId) {
        if (topicId == null) return "";
        List<DirectorNoteAddendum> rows = repo.findByTopicIdOrderByCreatedAtAsc(topicId);
        if (rows.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("【剧组群聊 — 前序角色实际定下的方向(必须遵守)】\n");
        for (DirectorNoteAddendum r : rows) {
            sb.append("- [").append(r.getCreatedAt().format(HM)).append("] ")
              .append(displayRole(r.getRole())).append(": ")
              .append(r.getAddendumText())
              .append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /** topic rerun 时清空旧群聊,避免新一轮被旧讨论污染。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void clear(Long topicId) {
        if (topicId == null) return;
        try {
            repo.deleteByTopicId(topicId);
            log.info("[DirectorNote] 清空旧群聊 topicId={}", topicId);
        } catch (Exception e) {
            log.warn("[DirectorNote] clear 失败 topicId={}: {}", topicId, e.toString());
        }
    }
}
