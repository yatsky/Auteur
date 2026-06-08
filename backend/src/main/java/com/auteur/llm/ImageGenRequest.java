package com.auteur.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ImageGenRequest {
    private String model;
    private String prompt;
    /** OpenAI-compat: "1024x1024" / "1024x1792" / "1792x1024" */
    private String size;
    private Integer n = 1;
    /** "url" 或 "b64_json"。Doubao 系列需要显式传 "url"；gpt-image-2 不支持此字段，保持 null。 */
    private String response_format;
    /** Doubao Seedream 关水印；gpt-image-2 不支持此字段，保持 null。 */
    private Boolean watermark;
    /**
     * Doubao Seedream image-to-image 参考图 URL（V15 引入）。
     * 非 null 时上游按 reference 生图，保持人物 / 风格连贯。OpenAI 标准 generations 端点
     * 不支持这个字段，但 ARK / 火山的兼容层会透传给 Seedream。null 时按纯 prompt 生图。
     */
    private String image;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Response {
        private List<DataItem> data;

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class DataItem {
            private String url;
            @JsonProperty("b64_json")
            private String b64Json;
            private String revised_prompt;
        }
    }
}
