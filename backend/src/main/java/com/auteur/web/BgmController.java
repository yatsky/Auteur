package com.auteur.web;

import com.auteur.bgm.BgmService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/bgm/scripts/{scriptId}")
@RequiredArgsConstructor
public class BgmController {

    private final BgmService bgmService;

    @PostMapping("/recommend")
    public List<BgmService.BgmTrackDto> recommend(@PathVariable Long scriptId) {
        try {
            return bgmService.recommend(scriptId);
        } catch (NotFoundException e) {
            throw e;
        } catch (IllegalStateException e) {
            // client_id 缺失 / 配置异常
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        } catch (RuntimeException e) {
            log.warn("[BgmController] recommend failed scriptId={}: {}", scriptId, e.toString());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Jamendo 失败:" + e.getMessage());
        }
    }

    @GetMapping("/tracks")
    public List<BgmService.BgmTrackDto> loadMore(@PathVariable Long scriptId,
                                                 @RequestParam(defaultValue = "0") int offset) {
        try {
            return bgmService.loadMore(scriptId, offset);
        } catch (NotFoundException e) {
            throw e;
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        } catch (RuntimeException e) {
            log.warn("[BgmController] loadMore failed scriptId={} offset={}: {}", scriptId, offset, e.toString());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Jamendo 失败:" + e.getMessage());
        }
    }

    /** 没选时返 204 No Content。 */
    @GetMapping("/choice")
    public BgmService.ChoiceDto getChoice(@PathVariable Long scriptId) {
        return bgmService.getChoiceDto(scriptId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NO_CONTENT, "未选"));
    }

    @PostMapping("/select")
    public BgmService.ChoiceDto select(@PathVariable Long scriptId,
                                       @Valid @RequestBody SelectRequest req) {
        try {
            return bgmService.select(scriptId, req.bgmTrackId(), req.volume());
        } catch (NotFoundException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("[BgmController] select failed scriptId={} trackId={}: {}",
                    scriptId, req.bgmTrackId(), e.toString());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "选曲下载失败:" + e.getMessage());
        }
    }

    public record SelectRequest(
            @NotNull Long bgmTrackId,
            @DecimalMin("0.05") @DecimalMax("0.60") BigDecimal volume
    ) {}
}
