package com.auteur.llm;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatRequest {

    private String model;
    private List<Message> messages;
    private Double temperature;
    /** 上限输出 token；为 null 时不下发该字段，由网关用默认值。snake_case 跟 OpenAI 兼容接口对齐。 */
    private Integer max_tokens;

    @Data
    public static class Message {
        private String role;
        /** 文字模式：String；多模态：List<Map<String,Object>>，每个元素是 {type, text|image_url} */
        private Object content;

        public static Message system(String content) {
            Message m = new Message();
            m.role = "system";
            m.content = content;
            return m;
        }

        public static Message user(String content) {
            Message m = new Message();
            m.role = "user";
            m.content = content;
            return m;
        }

        /** 多模态 user 消息：一段文字 + 一张图片 URL（OpenAI 兼容形态）。 */
        public static Message userWithImage(String text, String imageUrl) {
            Message m = new Message();
            m.role = "user";
            m.content = List.of(
                    Map.of("type", "text", "text", text),
                    Map.of("type", "image_url", "image_url", Map.of("url", imageUrl))
            );
            return m;
        }
    }
}
