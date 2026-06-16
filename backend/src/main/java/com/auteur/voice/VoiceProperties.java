package com.auteur.voice;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** auteur.voice.* 配置入口。provider=volcano|mock。 */
@Data
@ConfigurationProperties(prefix = "auteur.voice")
public class VoiceProperties {

    /** volcano | mock */
    private String provider = "volcano";

    private final Volcano volcano = new Volcano();
    private final Storage storage = new Storage();

    /**
     * 火山引擎语音服务(直接用预训练音色 voice_type 合成)。
     * 鉴权:新版用 X-Api-Key;旧版用 X-Api-App-Key + X-Api-Access-Key。
     */
    @Data
    public static class Volcano {
        private String baseUrl = "https://openspeech.bytedance.com";
        private String apiKey = "";
        private String appKey = "";
        private String accessKey = "";
        private String resourceId = "";
        private int httpTimeoutSeconds = 90;
        /** 试听文本默认空,客户首次部署后在「系统设置 → AI 语音合成」UI 自己填。 */
        private String demoText = "";
        /**
         * 异步任务模式开关。
         *  false:V3 单向流式 /api/v3/tts/unidirectional,X-Api-Key 鉴权
         *  true: V3 异步任务 submit+query,服务端合成完整 mp3,无流式截断风险
         */
        private boolean asyncMode = false;
        private int asyncPollIntervalSec = 3;
        private int asyncMaxWaitSec = 300;
        private int asyncQueryTimeoutSeconds = 30;
        private int asyncDownloadTimeoutSeconds = 120;
    }

    @Data
    public static class Storage {
        private String localDir = "./storage/voice";
    }
}
