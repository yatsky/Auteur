package com.auteur.brainstorm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BrainstormCandidate {
    private String title;
    private String dynasty;
    private String genre;
    private String protagonist;

    @JsonProperty("hook_type")
    private String hookType;

    private String emotion;

    @JsonProperty("duration_minutes")
    private Integer durationMinutes;

    @JsonProperty("potential_score")
    private BigDecimal potentialScore;

    @JsonProperty("historical_reference")
    private String historicalReference;

    @JsonProperty("suggested_series")
    private String suggestedSeries;

    @JsonProperty("identity_tag")
    private String identityTag;

    private String era;

    private String archetype;

    private List<NodeCandidate> nodes;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class NodeCandidate {
        private String phase;
        private String scene;
        private List<String> details;
    }
}
