package com.auteur.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "script_section")
@Getter
@Setter
public class ScriptSection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "script_id", nullable = false)
    private Long scriptId;

    @Column(name = "section_code", nullable = false, length = 8)
    private String sectionCode;

    @Column(length = 80)
    private String title;

    @Column(name = "start_seconds")
    private Integer startSeconds;

    @Column(name = "end_seconds")
    private Integer endSeconds;

    @Column(name = "text_content", columnDefinition = "TEXT")
    private String textContent;

    @Column(name = "director_note", length = 500)
    private String directorNote;

    @Column(name = "is_golden_line", nullable = false)
    private Boolean isGoldenLine = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
