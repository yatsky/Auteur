package com.auteur.bgm;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Jamendo search RestClient。client_id 走 query 参数,不挂 Authorization。
 */
@Configuration
@EnableConfigurationProperties(JamendoProperties.class)
public class BgmConfig {

    @Bean(name = "jamendoRestClient")
    public RestClient jamendoRestClient(JamendoProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(15).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(props.getTimeoutSeconds()).toMillis());

        RestClient.Builder builder = RestClient.builder().requestFactory(factory);
        if (props.getBaseUrl() != null && !props.getBaseUrl().isBlank()) {
            builder.baseUrl(props.getBaseUrl());
        }
        builder.defaultHeader("Accept", "application/json");
        return builder.build();
    }
}
