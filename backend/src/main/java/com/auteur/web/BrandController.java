package com.auteur.web;

import com.auteur.cover.BrandIdentity;
import com.auteur.cover.BrandIdentityService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 账号品牌包配置 API,单行模型。logo 走 base64 dataUrl,不开 multipart 端点。 */
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
