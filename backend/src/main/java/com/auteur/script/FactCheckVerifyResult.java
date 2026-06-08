package com.auteur.script;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class FactCheckVerifyResult {

    @JsonProperty("source_url")
    private String sourceUrl;

    private String credibility;
    private String verdict;
}
