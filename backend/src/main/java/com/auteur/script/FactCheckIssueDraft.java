package com.auteur.script;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class FactCheckIssueDraft {

    @JsonProperty("line_number")
    private String lineNumber;

    @JsonProperty("original_text")
    private String originalText;

    @JsonProperty("issue_type")
    private String issueType;

    private String suggestion;
    private String severity;
}
