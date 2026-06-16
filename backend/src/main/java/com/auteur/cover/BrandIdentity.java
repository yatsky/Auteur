package com.auteur.cover;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/** 账号品牌包,单行表(强制 id=1)。 */
@Entity
@Table(name = "brand_identity")
@Getter
@Setter
public class BrandIdentity {

    public static final Long SINGLETON_ID = 1L;

    @Id
    @Column(name = "id")
    private Long id = SINGLETON_ID;

    @Column(name = "brand_name", length = 40)
    private String brandName;

    @Column(name = "author_name", length = 40)
    private String authorName;

    /** data:image/png;base64,...;null 时角标只画文字 */
    @Column(name = "logo_data_url", columnDefinition = "MEDIUMTEXT")
    private String logoDataUrl;

    @Column(name = "primary_color", nullable = false, length = 20)
    private String primaryColor = "#0F1F33";

    @Column(name = "secondary_color", nullable = false, length = 20)
    private String secondaryColor = "#C9A961";

    @Column(name = "accent_color", nullable = false, length = 20)
    private String accentColor = "#E04A2A";

    @Column(name = "bg_color", nullable = false, length = 20)
    private String bgColor = "#F5EFE0";

    @Column(name = "title_font", nullable = false, length = 120)
    private String titleFont = "\"Noto Serif SC\", \"PingFang SC\", serif";

    @Column(name = "default_template_id", nullable = false, length = 40)
    private String defaultTemplateId = "bottom-caption";

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
