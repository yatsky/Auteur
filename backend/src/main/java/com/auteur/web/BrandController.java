package com.auteur.web;

import com.auteur.cover.BrandIdentity;
import com.auteur.cover.BrandIdentityService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 账号品牌包配置 API,单行模型。
 *  - GET /api/brand-identity   取当前(没有就返回默认)
 *  - PUT /api/brand-identity   全量保存(空串 = 清空对应字段;颜色字段不接受空值)
 * logo 上传走前端转 base64 后塞 logoDataUrl(不开 multipart 端点)。
 */
@RestController
@RequestMapping("/api/brand-identity")
@RequiredArgsConstructor
public class BrandController {

    private final BrandIdentityService service;

    @GetMapping
    public BrandIdentity get() {
        return service.getOrCreate();
    }

    @PutMapping
    public BrandIdentity save(@RequestBody BrandIdentity body) {
        return service.save(body);
    }
}
