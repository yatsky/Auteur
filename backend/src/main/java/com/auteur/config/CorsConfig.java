package com.auteur.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 浏览器插件 fetch 时 Origin = chrome-extension://&lt;id&gt;,通配符通过 allowedOriginPatterns 注册。
 * 仅对 /api/extension/** 开放,其他路径走 Spring 默认(同源)。
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(@NonNull CorsRegistry registry) {
        registry.addMapping("/api/extension/**")
                .allowedOriginPatterns("chrome-extension://*", "moz-extension://*")
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("X-Extension-Token", "Content-Type")
                .allowCredentials(false)
                .maxAge(600);
    }
}
