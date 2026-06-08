package com.auteur.voice;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * auteur.voice.* 配置入口。provider=volcano|mock。
 */
@Data
@ConfigurationProperties(prefix = "auteur.voice")
public class VoiceProperties {

    /** volcano | mock */
    private String provider = "volcano";

    private final Volcano volcano = new Volcano();
    private final Storage storage = new Storage();

    /**
     * 火山引擎语音服务(直接用预训练音色 voice_type 合成,不走音色设计 API)。
     *
     * 鉴权:新版控制台用 X-Api-Key;旧版用 X-Api-App-Key + X-Api-Access-Key。
     * X-Api-Resource-Id 区分资源版本(seed-tts-2.0 / seed-tts-1.0 / seed-icl-* 等),启动期校验非空。
     */
    @Data
    public static class Volcano {
        private String baseUrl = "https://openspeech.bytedance.com";
        private String apiKey = "";
        private String appKey = "";
        private String accessKey = "";
        private String resourceId = "";
        private int httpTimeoutSeconds = 90;
        private String demoText = "夜色渐浓,我望着窗外的灯火,想起了那段往事。";
        /**
         * 异步任务模式开关。
         *  false:V3 单向流式 /api/v3/tts/unidirectional,X-Api-Key 鉴权
         *  true: V3 异步任务 submit+query,X-Api-App-Id+X-Api-Access-Key 鉴权,服务端合成完整 mp3,无流式截断风险
         */
        private boolean asyncMode = false;
        private int asyncPollIntervalSec = 3;
        private int asyncMaxWaitSec = 300;
    }

    @Data
    public static class Storage {
        private String localDir = "./storage/voice";
    }
}
