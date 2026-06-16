package com.auteur.bgm;

import com.auteur.common.text.TextUtils;
import com.auteur.domain.BgmTrack;
import com.auteur.domain.BgmTrackRepository;
import com.auteur.domain.ScriptBgmChoice;
import com.auteur.domain.ScriptBgmChoiceRepository;
import com.auteur.domain.ScriptRepository;
import com.auteur.storage.TosStorageService;
import com.auteur.web.NotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
public class BgmService {

    private static final int RECOMMEND_LIMIT = 3;
    /** 第一次内部多拉点缓存进 bgm_track,后续"换一批"基本不打 Jamendo。 */
    private static final int FETCH_BUFFER = 9;


    private final BgmMoodTagger moodTagger;
    private final JamendoClient jamendoClient;
    private final BgmTrackRepository trackRepository;
    private final ScriptBgmChoiceRepository choiceRepository;
    private final ScriptRepository scriptRepository;
    private final JamendoProperties props;
    private final TosStorageService tos;

    public BgmService(BgmMoodTagger moodTagger,
                      JamendoClient jamendoClient,
                      BgmTrackRepository trackRepository,
                      ScriptBgmChoiceRepository choiceRepository,
                      ScriptRepository scriptRepository,
                      JamendoProperties props,
                      TosStorageService tos) {
        this.moodTagger = moodTagger;
        this.jamendoClient = jamendoClient;
        this.trackRepository = trackRepository;
        this.choiceRepository = choiceRepository;
        this.scriptRepository = scriptRepository;
        this.props = props;
        this.tos = tos;
    }

    public record BgmTrackDto(
            Long id, Long jamendoId, String name, String artistName,
            Integer durationSeconds, String audioUrl, String previewUrl,
            String albumImageUrl, String licenseType, String moodTag,
            String localPath
    ) {
        static BgmTrackDto of(BgmTrack t) {
            return new BgmTrackDto(t.getId(), t.getJamendoId(), t.getName(), t.getArtistName(),
                    t.getDurationSeconds(), t.getAudioUrl(), t.getPreviewUrl(),
                    t.getAlbumImageUrl(), t.getLicenseType(), t.getMoodTag(),
                    t.getLocalPath());
        }
    }

    public record ChoiceDto(Long scriptId, Long bgmTrackId, BigDecimal volume,
                            String trackName, String trackArtist) {}

    @Transactional
    public List<BgmTrackDto> recommend(Long scriptId) {
        if (!scriptRepository.existsById(scriptId)) {
            throw new NotFoundException("Script not found: " + scriptId);
        }
        String mood = moodTagger.resolveMood(scriptId);
        return fetchAndUpsert(mood, RECOMMEND_LIMIT, 0);
    }

    @Transactional
    public List<BgmTrackDto> loadMore(Long scriptId, int offset) {
        if (!scriptRepository.existsById(scriptId)) {
            throw new NotFoundException("Script not found: " + scriptId);
        }
        String mood = moodTagger.resolveMood(scriptId);
        return fetchAndUpsert(mood, RECOMMEND_LIMIT, Math.max(0, offset));
    }

    private List<BgmTrackDto> fetchAndUpsert(String mood, int limit, int offset) {
        String tagsCsv = moodTagger.jamendoTagsFor(mood);
        int fetchSize = offset == 0 ? Math.max(limit, FETCH_BUFFER) : limit;
        List<JamendoClient.JamendoTrack> raw = jamendoClient.search(tagsCsv, fetchSize, offset);
        List<BgmTrack> persisted = new ArrayList<>(raw.size());
        for (JamendoClient.JamendoTrack jt : raw) {
            if (jt.id == null || jt.audio == null || jt.audio.isBlank()) continue;
            BgmTrack track = trackRepository.findByJamendoId(jt.id).orElseGet(BgmTrack::new);
            track.setJamendoId(jt.id);
            track.setName(TextUtils.truncate(jt.name, 200));
            track.setArtistName(TextUtils.truncate(jt.artistName, 200));
            track.setDurationSeconds(jt.duration);
            track.setAudioUrl(jt.audio);
            track.setPreviewUrl(jt.audio);
            track.setAlbumImageUrl(TextUtils.truncate(jt.image, 500));
            track.setLicenseType(TextUtils.truncate(jt.licenseUrl, 40));
            // mood_tag 只在首次入库时落,同一首曲在别的 mood 下不覆盖
            if (track.getMoodTag() == null) track.setMoodTag(mood);
            persisted.add(trackRepository.save(track));
        }
        return persisted.stream().limit(limit).map(BgmTrackDto::of).toList();
    }

    /** volume 越界 clamp 到 [0.05, 0.6]。 */
    @Transactional
    public ChoiceDto select(Long scriptId, Long bgmTrackId, BigDecimal volume) {
        if (!scriptRepository.existsById(scriptId)) {
            throw new NotFoundException("Script not found: " + scriptId);
        }
        BgmTrack track = trackRepository.findById(bgmTrackId)
                .orElseThrow(() -> new NotFoundException("BgmTrack not found: " + bgmTrackId));

        BigDecimal vol = clampVolume(volume);

        ScriptBgmChoice choice = choiceRepository.findById(scriptId).orElseGet(ScriptBgmChoice::new);
        choice.setScriptId(scriptId);
        choice.setBgmTrackId(bgmTrackId);
        choice.setVolume(vol);
        choiceRepository.save(choice);

        // 下载到本地临时文件 → 上传 TOS,local_path 改存 TOS URL
        if (track.getLocalPath() == null || !track.getLocalPath().startsWith("https")) {
            Path localTemp = Path.of(props.getStorage().getLocalDir(), track.getJamendoId() + ".mp3");
            try { Files.createDirectories(localTemp.getParent()); } catch (IOException ignored) {}
            jamendoClient.download(track.getAudioUrl(), localTemp);
            String tosUrl = tos.upload("bgm/" + track.getJamendoId() + ".mp3", localTemp, "audio/mpeg");
            try { Files.deleteIfExists(localTemp); } catch (IOException ignored) {}
            track.setLocalPath(tosUrl);
            trackRepository.save(track);
            log.info("[BGM] uploaded to TOS: {}", tosUrl);
        }

        return new ChoiceDto(scriptId, bgmTrackId, vol, track.getName(), track.getArtistName());
    }

    public Optional<ScriptBgmChoice> getChoice(Long scriptId) {
        return choiceRepository.findById(scriptId);
    }

    public boolean hasLocalFile(Long bgmTrackId) {
        return trackRepository.findById(bgmTrackId)
                .map(t -> t.getLocalPath() != null && !t.getLocalPath().isBlank())
                .orElse(false);
    }

    /** Remotion 浏览器不支持 file://;缺记录或非 https → null。 */
    public String tosUrl(Long bgmTrackId) {
        return trackRepository.findById(bgmTrackId)
                .map(BgmTrack::getLocalPath)
                .filter(p -> p != null && p.startsWith("https"))
                .orElse(null);
    }

    /** 给 ffmpeg 渲染用。local_path 是 TOS URL 时下到本地缓存复用,已存在则不重下。 */
    public Path localPath(Long bgmTrackId) {
        BgmTrack t = trackRepository.findById(bgmTrackId)
                .orElseThrow(() -> new IllegalStateException("BgmTrack not found: " + bgmTrackId));
        String stored = t.getLocalPath();
        if (stored == null || stored.isBlank())
            throw new IllegalStateException("BgmTrack #" + bgmTrackId + " has no TOS URL");

        if (stored.startsWith("https")) {
            Path cache = Path.of(props.getStorage().getLocalDir(), t.getJamendoId() + ".mp3");
            if (!Files.exists(cache)) {
                try { Files.createDirectories(cache.getParent()); } catch (IOException ignored) {}
                jamendoClient.download(stored, cache);
                log.info("[BGM] cached from TOS: {} → {}", stored, cache);
            }
            return cache;
        }
        // 兼容旧本地路径
        return Path.of(stored);
    }

    public Optional<ChoiceDto> getChoiceDto(Long scriptId) {
        return choiceRepository.findById(scriptId).map(c -> {
            BgmTrack t = trackRepository.findById(c.getBgmTrackId()).orElse(null);
            return new ChoiceDto(c.getScriptId(), c.getBgmTrackId(), c.getVolume(),
                    t == null ? null : t.getName(),
                    t == null ? null : t.getArtistName());
        });
    }

    /** 批量查避免 N+1。 */
    public Map<Long, BgmTrackDto> findByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return Map.of();
        Map<Long, BgmTrackDto> out = new LinkedHashMap<>();
        for (BgmTrack t : trackRepository.findAllById(ids)) {
            out.put(t.getId(), BgmTrackDto.of(t));
        }
        return out;
    }

    private static BigDecimal clampVolume(BigDecimal v) {
        if (v == null) return new BigDecimal("0.25");
        BigDecimal lo = new BigDecimal("0.05");
        BigDecimal hi = new BigDecimal("0.60");
        if (v.compareTo(lo) < 0) return lo;
        if (v.compareTo(hi) > 0) return hi;
        return v;
    }
}
