package com.auteur.cover;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(CoverProperties.class)
public class CoverConfig {
}
