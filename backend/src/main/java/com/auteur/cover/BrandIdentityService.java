package com.auteur.cover;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 品牌包(单行)读写。CoverGen 渲染前调 getOrCreate;表一定有一行,误删则兜底插。
 */
@Service
@RequiredArgsConstructor
public class BrandIdentityService {

    private final BrandIdentityRepository repo;

    @Transactional
    public BrandIdentity getOrCreate() {
        return repo.findById(BrandIdentity.SINGLETON_ID)
                .orElseGet(() -> repo.save(new BrandIdentity()));
    }

    /** 前端 PUT /api/brand 入口。强锁 id 防误传新 id 多行。 */
    @Transactional
    public BrandIdentity save(BrandIdentity incoming) {
        BrandIdentity row = getOrCreate();
        row.setBrandName(blankToNull(incoming.getBrandName()));
        row.setAuthorName(blankToNull(incoming.getAuthorName()));
        if (incoming.getLogoDataUrl() != null) {
            // 空串 = 显式清空 logo
            String v = incoming.getLogoDataUrl().trim();
            row.setLogoDataUrl(v.isEmpty() ? null : v);
        }
        if (incoming.getPrimaryColor() != null && !incoming.getPrimaryColor().isBlank())
            row.setPrimaryColor(incoming.getPrimaryColor().trim());
        if (incoming.getSecondaryColor() != null && !incoming.getSecondaryColor().isBlank())
            row.setSecondaryColor(incoming.getSecondaryColor().trim());
        if (incoming.getAccentColor() != null && !incoming.getAccentColor().isBlank())
            row.setAccentColor(incoming.getAccentColor().trim());
        if (incoming.getBgColor() != null && !incoming.getBgColor().isBlank())
            row.setBgColor(incoming.getBgColor().trim());
        if (incoming.getTitleFont() != null && !incoming.getTitleFont().isBlank())
            row.setTitleFont(incoming.getTitleFont().trim());
        if (incoming.getDefaultTemplateId() != null && !incoming.getDefaultTemplateId().isBlank())
            row.setDefaultTemplateId(incoming.getDefaultTemplateId().trim());
        return repo.save(row);
    }

    private static String blankToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
