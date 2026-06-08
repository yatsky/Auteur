package com.auteur.web;

import com.auteur.runtimeconfig.RuntimeConfig;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 校验浏览器插件请求里的 X-Extension-Token,保护 /api/extension/** 不被任意来源调用。
 * 每次请求从 RuntimeConfig 拿 token(纯 DB),允许 UI 改 token 立即生效。
 */
@Slf4j
@Component
public class ExtensionTokenFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-Extension-Token";
    private static final String PATH_PREFIX = "/api/extension/";

    private final RuntimeConfig runtimeConfig;

    public ExtensionTokenFilter(RuntimeConfig runtimeConfig) {
        this.runtimeConfig = runtimeConfig;
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri == null || !uri.startsWith(PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        String expected = runtimeConfig.get("auteur.extension.token");
        if (expected.isBlank()) {
            reject(response, HttpStatus.SERVICE_UNAVAILABLE, "EXTENSION_TOKEN_NOT_CONFIGURED",
                    "服务端未配置 auteur.extension.token,请到「系统设置 → 浏览器插件」填写");
            return;
        }
        String header = request.getHeader(HEADER);
        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        if (header == null || header.isBlank()
                || !java.security.MessageDigest.isEqual(header.getBytes(StandardCharsets.UTF_8), expectedBytes)) {
            reject(response, HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", "X-Extension-Token 缺失或错误");
            return;
        }
        chain.doFilter(request, response);
    }

    private static void reject(HttpServletResponse response, HttpStatus status,
                               String code, String message) throws IOException {
        response.setStatus(status.value());
        response.setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }
}
