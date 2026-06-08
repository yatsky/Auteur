package com.auteur.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "image_asset")
@Getter
@Setter
public class ImageAsset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "shot_id", nullable = false)
    private Long shotId;

    @Column(length = 60)
    private String model;

    @Column(name = "file_url", length = 500)
    private String fileUrl;

    @Column
    private Integer width;

    @Column
    private Integer height;

    @Column(length = 40)
    private String seed;

    @Column(name = "cost_credits", precision = 8, scale = 4)
    private BigDecimal costCredits;

    @Column(name = "review_score", precision = 5, scale = 2)
    private BigDecimal reviewScore;

    @Column(name = "review_decision", length = 20)
    private String reviewDecision;

    @Column(name = "review_issues", length = 500)
    private String reviewIssues;

    @Column(name = "is_final", nullable = false)
    private Boolean isFinal = false;

    @Column(name = "used_protagonist_ref", nullable = false)
    private Boolean usedProtagonistRef = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
