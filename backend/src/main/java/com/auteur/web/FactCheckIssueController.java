package com.auteur.web;

import com.auteur.domain.FactCheckIssue;
import com.auteur.script.FactCheckFixService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/factcheck-issues")
@RequiredArgsConstructor
public class FactCheckIssueController {

    private final FactCheckFixService fixService;

    /** 一键修复:跑 LLM 抽换 → 替换 section 文本 → 重建 fullText → 标 resolved。 */
    @PostMapping("/{id}/apply")
    public FactCheckFixService.ApplyResult apply(@PathVariable Long id) {
        return fixService.applyFix(id);
    }

    /** 不改文,只标 resolved=true(用户人工判断不用修)。 */
    @PostMapping("/{id}/dismiss")
    public FactCheckIssue dismiss(@PathVariable Long id) {
        return fixService.dismiss(id);
    }
}
