package com.auteur.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "cover_asset")
@Getter
@Setter
public class CoverAsset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "script_id", nullable = false)
    private Long scriptId;

    @Column(length = 8, nullable = false)
    private String ratio;

    @Column(nullable = false)
    private Integer width;

    @Column(nullable = false)
    private Integer height;

    @Column(name = "template_id", length = 40, nullable = false)
    private String templateId;

    @Column(name = "title_text", length = 255)
    private String titleText;

    @Column(name = "hero_image_url", length = 500)
    private String heroImageUrl;

    @Column(name = "file_url", length = 500, nullable = false)
    private String fileUrl;

    @Column(name = "file_size_bytes")
    private Long fileSizeBytes;

    @Column(name = "is_final", nullable = false)
    private Boolean isFinal = false;

    @Column(name = "run_id")
    private Long runId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
