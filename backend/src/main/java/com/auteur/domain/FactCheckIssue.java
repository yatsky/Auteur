package com.auteur.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "fact_check_issue")
@Getter
@Setter
public class FactCheckIssue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "script_id", nullable = false)
    private Long scriptId;

    @Column(name = "line_number")
    private Integer lineNumber;

    @Column(name = "original_text", columnDefinition = "TEXT")
    private String originalText;

    @Column(name = "issue_type", length = 40)
    private String issueType;

    @Column(columnDefinition = "TEXT")
    private String suggestion;

    @Column(length = 20)
    private String severity;

    @Column(name = "source_url", length = 500)
    private String sourceUrl;

    @Column(length = 2)
    private String credibility;

    @Column(nullable = false)
    private Boolean resolved = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
