package com.auteur;

import com.auteur.video.VideoProperties;
import com.auteur.voice.VoiceProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.web.config.EnableSpringDataWebSupport;
import org.springframework.data.web.config.EnableSpringDataWebSupport.PageSerializationMode;

// VIA_DTO:Page<T> 序列化为 { content, page:{ size, number, totalElements, totalPages } },
// 避免 Spring Data 3.3 的 PageImpl 不稳定结构 WARN;前端读分页元信息走 resp.page.*。
@SpringBootApplication
@EnableSpringDataWebSupport(pageSerializationMode = PageSerializationMode.VIA_DTO)
@EnableConfigurationProperties({VideoProperties.class, VoiceProperties.class})
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
