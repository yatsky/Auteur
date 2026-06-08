package com.auteur.script;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ScriptDraft {

    @JsonProperty("word_count")
    private Integer wordCount;

    @JsonProperty("duration_seconds")
    private Integer durationSeconds;

    private List<SectionDraft> sections;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SectionDraft {
        @JsonProperty("section_code")
        private String sectionCode;
        private String title;
        @JsonProperty("start_seconds")
        private Integer startSeconds;
        @JsonProperty("end_seconds")
        private Integer endSeconds;
        @JsonProperty("text_content")
        private String textContent;
        @JsonProperty("director_note")
        private String directorNote;
        @JsonProperty("is_golden_line")
        private Boolean isGoldenLine;
    }
}
