package com.auteur.cover;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "auteur.cover")
public class CoverProperties {

    private final Storage storage = new Storage();
    private final Java2d java2d = new Java2d();

    @Data
    public static class Storage {
        private String localDir = "./storage/cover";
        private String workDir = "./storage/cover/.work";
    }

    @Data
    public static class Java2d {
        /** 标题字体优先栈;系统找不到回 sans-serif。 */
        private String fontFamily = "PingFang SC";
        private int timeoutSeconds = 60;
    }
}
