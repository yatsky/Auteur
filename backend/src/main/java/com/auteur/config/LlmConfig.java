package com.auteur.config;

import com.auteur.runtimeconfig.RuntimeConfig;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(LlmProperties.class)
public class LlmConfig {

    /** UI 改密钥后需重启后端才会重 build RestClient。 */
    @Bean
    public RestClient llmRestClient(LlmProperties props, RuntimeConfig runtime) {
        int timeoutSec = runtime.getInt("auteur.llm.timeout-seconds", props.getTimeoutSeconds());
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(15).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(timeoutSec).toMillis());

        RestClient.Builder builder = RestClient.builder().requestFactory(factory);
        String baseUrl = runtime.get("auteur.llm.base-url");
        if (!baseUrl.isBlank()) {
            builder.baseUrl(baseUrl);
        }
        String apiKey = runtime.get("auteur.llm.api-key");
        if (!apiKey.isBlank()) {
            builder.defaultHeader("Authorization", "Bearer " + apiKey);
        }
        builder.defaultHeader("Content-Type", "application/json");
        builder.defaultHeader("Accept", "application/json");
        return builder.build();
    }
}
