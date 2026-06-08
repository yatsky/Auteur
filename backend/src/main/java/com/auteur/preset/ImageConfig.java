package com.auteur.preset;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

/**
 * preset.image_config_json 的强类型视图。
 * 所有字段可空 — null 时各 service 应跳过对应行为(不锁脸 / 不加风格后缀 / 用默认负面词等)。
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class ImageConfig {

    /** 主模型名,空 = 用 ImageGenService 内置的 PRIMARY_MODEL。 */
    private String model;

    /** 身份锁定串,prepend 到生图 prompt 前。空 = 不锁脸。 */
    @JsonProperty("identity_lock_text")
    private String identityLockText;

    /** 参考图本地路径(相对启动 cwd)。空 = 走纯 text-to-image。 */
    @JsonProperty("reference_image_path")
    private String referenceImagePath;

    /** 风格后缀,append 到生图 prompt 末尾。 */
    @JsonProperty("style_suffix")
    private String styleSuffix;

    /** 给 storyboard prompt 注入的简短风格标签。 */
    @JsonProperty("style_tag")
    private String styleTag;

    /** 负面词。空 = 用 ImageGenService 内置 default。 */
    @JsonProperty("negative_prompt")
    private String negativePrompt;

    /** 'WxH' 字符串。空 = 用 ImageGenService 内置 default。 */
    @JsonProperty("image_size")
    private String imageSize;
}
