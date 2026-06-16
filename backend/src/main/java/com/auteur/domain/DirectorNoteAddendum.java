package com.auteur.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * director_note_addendum — 导演笔记群聊增量。
 *
 * 每个 stage 完成后写一行,记录"我这一关实际定的方向"。
 * 下游 stage 渲染 prompt 时,DirectorNoteService.buildBlock 把所有 addendum 按时间排序拼成
 * "群聊汇总段"注入。
 */
@Entity
@Table(name = "director_note_addendum")
@Getter
@Setter
public class DirectorNoteAddendum {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "topic_id", nullable = false)
    private Long topicId;

    @Column(nullable = false, length = 20)
    private String role;

    @Column(name = "addendum_text", nullable = false, columnDefinition = "TEXT")
    private String addendumText;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
