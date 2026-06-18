package com.auteur.storage;

import com.volcengine.tos.TOSV2;
import com.volcengine.tos.TOSV2ClientBuilder;
import com.volcengine.tos.auth.StaticCredentials;
import com.volcengine.tos.model.object.PutObjectInput;
import com.volcengine.tos.model.object.UploadFileV2Input;
import com.volcengine.tos.transport.TransportConfig;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 火山引擎 TOS 对象存储上传服务。
 *
 * 公网 URL 格式：https://{bucket}.{endpoint}/{key}
 * 对象 key 约定：scripts/{scriptId}/{type}/{filename}
 *   type = images | voice | video | cover
 */
@Slf4j
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(TosProperties.class)
public class TosStorageService {

    /** 大文件阈值:超过用 multipart 分片上传(SDK 自带分片+并发+断点续传),小文件走单次 PUT。 */
    private static final long MULTIPART_THRESHOLD_BYTES = 50L * 1024 * 1024; // 50 MB
    /** multipart 单分片大小。5 MB 是 TOS 推荐下限,文件越大并发收益越明显。 */
    private static final long MULTIPART_PART_SIZE_BYTES = 5L * 1024 * 1024;
    /** multipart 并发分片上传的工作线程数。家庭网络 4 路并发足够,过多反而互相挤兑。 */
    private static final int MULTIPART_TASK_NUM = 4;

    private final TosProperties props;
    private final com.auteur.runtimeconfig.RuntimeConfig runtimeConfig;
    private TOSV2 tos;
    /** 启动时锁定一次的有效配置;UI 改值后需重启后端才会重 build。 */
    private String effectiveBucket;
    private String effectiveEndpoint;

    @PostConstruct
    public void init() {
        // 非 secret 字段(endpoint/region/bucket)允许 yml 默认;secret 字段必须从 DB(UI 配置)
        String region    = runtimeConfig.get("auteur.tos.region",      props.getRegion());
        String endpoint  = runtimeConfig.get("auteur.tos.endpoint",    props.getEndpoint());
        String bucket    = runtimeConfig.get("auteur.tos.bucket",      props.getBucket());
        String accessKey = runtimeConfig.get("auteur.tos.access-key");
        String secretKey = runtimeConfig.get("auteur.tos.secret-key");
        if (region.isBlank() || endpoint.isBlank() || accessKey.isBlank() || secretKey.isBlank() || bucket.isBlank()) {
            log.warn("[TOS] 配置不完整(region/endpoint/access-key/secret-key/bucket 至少一个空),上传功能不可用。请到「系统设置」配置。");
            return;
        }
        // 显式 TransportConfig:SDK 默认不设 socket timeout,运营商/路由 RST 长连接时
        // 上传线程会永久阻塞(看到 CLOSE_WAIT 满天飞、Tomcat worker 啃光)。
        // write 拉到 120s 给 5 MB 分片留充足窗口,read 60s 兜响应,connect 30s 兜 DNS+握手。
        TransportConfig transport = new TransportConfig()
                .setConnectTimeoutMills(30_000)
                .setReadTimeoutMills(60_000)
                .setWriteTimeoutMills(120_000)
                .setIdleConnectionTimeMills(60_000)
                .setMaxRetryCount(2);
        tos = new TOSV2ClientBuilder().build(
                region, endpoint, new StaticCredentials(accessKey, secretKey), transport);
        this.effectiveBucket = bucket;
        this.effectiveEndpoint = endpoint;
        log.info("[TOS] 初始化完成 bucket={} endpoint={} (timeouts: connect=30s read=60s write=120s)", bucket, endpoint);
    }

    /** 上传字节数组，返回公网 URL。 */
    public String upload(String key, byte[] data, String contentType) {
        ensureReady();
        PutObjectInput input = new PutObjectInput()
                .setBucket(effectiveBucket)
                .setKey(key)
                .setContent(new ByteArrayInputStream(data))
                .setContentLength((long) data.length);
        tos.putObject(input);
        String url = toPublicUrl(key);
        log.info("[TOS] uploaded key={} size={} url={}", key, data.length, url);
        return url;
    }

    /** 上传本地文件。≤50MB 走单次 PUT;>50MB 走 multipart 分片+并发(SDK 自带断点续传)。 */
    public String upload(String key, Path localFile, String contentType) {
        ensureReady();
        long size;
        try {
            size = Files.size(localFile);
        } catch (IOException e) {
            throw new RuntimeException("TOS upload failed (stat " + localFile + "): " + e.getMessage(), e);
        }
        if (size > MULTIPART_THRESHOLD_BYTES) {
            // 大文件 multipart:每分片 5 MB 单独 PUT,4 路并发,单分片失败 SDK 自动重试,
            // 整体上传时间 = 总大小 / (并发 × 单线程速率),且任一分片网络抖动只重传该分片。
            UploadFileV2Input input = new UploadFileV2Input()
                    .setBucket(effectiveBucket)
                    .setKey(key)
                    .setFilePath(localFile.toString())
                    .setPartSize(MULTIPART_PART_SIZE_BYTES)
                    .setTaskNum(MULTIPART_TASK_NUM)
                    .setEnableCheckpoint(false);
            tos.uploadFile(input);
            String url = toPublicUrl(key);
            log.info("[TOS] uploaded(multipart) key={} size={} parts={} url={}",
                    key, size, (size + MULTIPART_PART_SIZE_BYTES - 1) / MULTIPART_PART_SIZE_BYTES, url);
            return url;
        }
        try {
            byte[] data = Files.readAllBytes(localFile);
            return upload(key, data, contentType);
        } catch (IOException e) {
            throw new RuntimeException("TOS upload failed for key=" + key + ": " + e.getMessage(), e);
        }
    }

    /** 上传 InputStream，size 必须已知。 */
    public String upload(String key, InputStream stream, long size, String contentType) {
        ensureReady();
        PutObjectInput input = new PutObjectInput()
                .setBucket(effectiveBucket)
                .setKey(key)
                .setContent(stream)
                .setContentLength(size);
        tos.putObject(input);
        String url = toPublicUrl(key);
        log.info("[TOS] uploaded key={} size={} url={}", key, size, url);
        return url;
    }

    /** 构造 key：scripts/{scriptId}/{type}/{filename} */
    public static String buildKey(Long scriptId, String type, String filename) {
        return String.format("scripts/%d/%s/%s", scriptId, type, filename);
    }

    private void ensureReady() {
        if (tos == null) {
            throw new IllegalStateException("TOS 未配置 — 请到「系统设置 → 对象存储」填写 access-key/secret-key/bucket 并重启后端");
        }
    }

    private String toPublicUrl(String key) {
        return String.format("https://%s.%s/%s", effectiveBucket, effectiveEndpoint, key);
    }
}
