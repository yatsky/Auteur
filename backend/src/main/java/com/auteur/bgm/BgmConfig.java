package com.auteur.bgm;

import com.auteur.runtimeconfig.RuntimeConfig;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * RestClient 是启动时构造的单例 bean,base-url / timeout 改 DB 后需重启 backend。
 */
@Configuration
@EnableConfigurationProperties(JamendoProperties.class)
public class BgmConfig {

    @Bean(name = "jamendoRestClient")
    public RestClient jamendoRestClient(JamendoProperties props, RuntimeConfig runtimeConfig) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(15).toMillis());
        int timeoutSec = runtimeConfig.getInt("auteur.bgm.jamendo.timeout-seconds", props.getTimeoutSeconds());
        factory.setReadTimeout((int) Duration.ofSeconds(timeoutSec).toMillis());

        RestClient.Builder builder = RestClient.builder().requestFactory(factory);
        String baseUrl = runtimeConfig.get("auteur.bgm.jamendo.base-url", props.getBaseUrl());
        if (baseUrl != null && !baseUrl.isBlank()) {
            builder.baseUrl(baseUrl);
        }
        builder.defaultHeader("Accept", "application/json");
        return builder.build();
    }
}
