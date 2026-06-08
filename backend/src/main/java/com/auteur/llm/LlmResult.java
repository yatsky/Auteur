package com.auteur.llm;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LlmResult {
    private String content;
    private String model;
    private String provider;
    private Integer inputTokens;
    private Integer outputTokens;
    private Integer durationMs;
}
