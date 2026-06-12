package com.auteur.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "auteur.llm")
public class LlmProperties {
    private String baseUrl;
    private String apiKey;
    private int timeoutSeconds = 360;
}
