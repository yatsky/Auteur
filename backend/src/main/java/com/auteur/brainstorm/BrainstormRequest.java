package com.auteur.brainstorm;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class BrainstormRequest {

    @Min(1)
    @Max(50)
    private Integer n = 20;

    private String archiveHint = "无";

    private String doneTopics = "无";

    private String model;

    /** 数据驱动开关：true 时由后端用 published_video 实测数据替代 yaml 写死的权重表。 */
    private Boolean useDataDriven = false;

    /** 数据驱动时的口径：抖音 / B站 / 视频号 / 小红书 / 西瓜；空 = 全平台混合。 */
    private String platform;

    /** 数据驱动统计窗口（天），默认 30。 */
    private Integer windowDays = 30;

    /** 必填:走 preset.brainstorm_prompt_yaml 渲染;为空时拒绝。 */
    private Long presetId;
}
