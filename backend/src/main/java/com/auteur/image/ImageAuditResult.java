package com.auteur.image;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ImageAuditResult {
    private Integer score;
    private String decision;
    private List<String> issues;
}
