package com.auteur.bgm;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * auteur.bgm.jamendo.* 配置。Jamendo 免费 API:35k req/月,曲目 CC 协议。
 * client-id 必填(https://devportal.jamendo.com/)。
 */
@Data
@ConfigurationProperties(prefix = "auteur.bgm.jamendo")
public class JamendoProperties {

    private String clientId = "";

    private String baseUrl = "https://api.jamendo.com/v3.0";
    private int timeoutSeconds = 30;

    private final Storage storage = new Storage();

    @Data
    public static class Storage {
        private String localDir = "./storage/bgm";
        private String publicBase = "/api/files/bgm";
    }
}
