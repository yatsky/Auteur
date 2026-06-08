package com.auteur.web;

import com.auteur.domain.StoryboardShot;
import com.auteur.domain.StoryboardShotRepository;
import com.auteur.image.ImageGenService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Shot 级别(单镜头)的精细化操作。批量操作仍走 ScriptController。
 *  - 重生这一镜:POST /api/shots/{shotId}/images/regen-async
 *  - 改 prompt :PUT  /api/shots/{shotId}/prompt
 */
@RestController
@RequestMapping("/api/shots")
@RequiredArgsConstructor
public class ShotController {

    private final ImageGenService imageGenService;
    private final StoryboardShotRepository shotRepository;

    /** 单镜重生,立即返回 runId,前端轮询 GET /api/runs/{runId},DONE 后刷新该镜下图片列表。 */
    @PostMapping("/{shotId}/images/regen-async")
    public Map<String, Object> regenImageAsync(@PathVariable Long shotId) {
        Long runId = imageGenService.regenerateForShotAsync(shotId, "API");
        return Map.of("runId", runId);
    }

    /**
     * 用户手动编辑 prompt（D 兜底路径：自动脱敏失败时人工救场）。
     * 只接受 promptZh / promptEn / negativePrompt 三个字段，其它字段保留原值。
     * 任意字段缺省 = 不动；显式传空字符串 = 清空。
     */
    @PutMapping("/{shotId}/prompt")
    @Transactional
    public ResponseEntity<StoryboardShot> updatePrompt(@PathVariable Long shotId,
                                                       @RequestBody PromptUpdate body) {
        StoryboardShot shot = shotRepository.findById(shotId)
                .orElseThrow(() -> new NotFoundException("Shot not found: " + shotId));
        if (body.getPromptZh() != null) shot.setPromptZh(body.getPromptZh());
        if (body.getPromptEn() != null) shot.setPromptEn(body.getPromptEn());
        if (body.getNegativePrompt() != null) shot.setNegativePrompt(body.getNegativePrompt());
        return ResponseEntity.ok(shotRepository.save(shot));
    }

    public static class PromptUpdate {
        private String promptZh;
        private String promptEn;
        private String negativePrompt;
        public String getPromptZh() { return promptZh; }
        public void setPromptZh(String promptZh) { this.promptZh = promptZh; }
        public String getPromptEn() { return promptEn; }
        public void setPromptEn(String promptEn) { this.promptEn = promptEn; }
        public String getNegativePrompt() { return negativePrompt; }
        public void setNegativePrompt(String negativePrompt) { this.negativePrompt = negativePrompt; }
    }
}
