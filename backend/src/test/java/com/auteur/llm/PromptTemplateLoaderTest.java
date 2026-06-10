package com.auteur.llm;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PromptTemplateLoaderTest {


    @Test
    void rendersPlaceholders() {
        String rendered = PromptTemplateLoader.render(
                "生成 {{n}} 个，已做：{{done}}",
                Map.of("n", 20, "done", "故宫渗血墙"));
        assertEquals("生成 20 个，已做：故宫渗血墙", rendered);
    }
}
