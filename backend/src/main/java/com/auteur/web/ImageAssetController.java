package com.auteur.web;

import com.auteur.domain.ImageAsset;
import com.auteur.domain.ImageAssetRepository;
import com.auteur.image.ImageAuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/** Image asset 级别(单张图)的精细化操作。批量操作仍走 ScriptController。 */
@RestController
@RequestMapping("/api/images")
@RequiredArgsConstructor
public class ImageAssetController {

    private final ImageAuditService imageAuditService;
    private final ImageAssetRepository imageAssetRepository;

    @PostMapping("/{assetId}/audit-async")
    public Map<String, Object> auditAssetAsync(@PathVariable Long assetId) {
        Long runId = imageAuditService.auditAssetAsync(assetId, "API");
        return Map.of("runId", runId);
    }

    /** 把这张 asset 设为本镜的 final。同 shot 下其他 asset 全部 unset。 */
    @PostMapping("/{assetId}/select-final")
    @Transactional
    public Map<String, Object> selectAsFinal(@PathVariable Long assetId) {
        ImageAsset target = imageAssetRepository.findById(assetId).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "image asset " + assetId + " 不存在"));
        if (target.getFileUrl() == null || target.getFileUrl().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "asset " + assetId + " 没有 fileUrl(可能被审查拦截或还在生成中),不能选作 final");
        }
        List<ImageAsset> siblings = imageAssetRepository.findByShotIdOrderByIdAsc(target.getShotId());
        for (ImageAsset a : siblings) {
            boolean shouldBeFinal = a.getId().equals(assetId);
            if (Boolean.TRUE.equals(a.getIsFinal()) != shouldBeFinal) {
                a.setIsFinal(shouldBeFinal);
                imageAssetRepository.save(a);
            }
        }
        return Map.of("assetId", assetId, "shotId", target.getShotId(), "isFinal", true);
    }
}
