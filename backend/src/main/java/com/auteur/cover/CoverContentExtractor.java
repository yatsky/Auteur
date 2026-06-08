package com.auteur.cover;

import com.auteur.domain.Script;
import com.auteur.domain.ScriptRepository;
import com.auteur.domain.Topic;
import com.auteur.domain.TopicRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CoverContentExtractor {

    private final ScriptRepository scriptRepo;
    private final TopicRepository topicRepo;

    public record Defaults(String title) {}

    public Defaults extract(Long scriptId) {
        String title = "";
        Script s = scriptRepo.findById(scriptId).orElse(null);
        if (s != null) {
            Topic t = topicRepo.findById(s.getTopicId()).orElse(null);
            if (t != null && t.getTitle() != null) title = t.getTitle();
        }
        return new Defaults(title);
    }
}
