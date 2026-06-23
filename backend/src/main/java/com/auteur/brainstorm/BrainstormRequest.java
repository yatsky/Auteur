package com.auteur.brainstorm;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.util.List;

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

    /**
     * 热点池:用户已经手动勾选的 hot_item id 列表。
     * 这些条目的 title+summary 会拼进 prompt 的 {{hot_items_context}} 变量,
     * 让 LLM 围绕它们生成选题。空 = 不接入热点。
     */
    private List<Long> hotItemIds = List.of();

    /**
     * 热点池:true 时 brainstorm 前先按 preset.hot_source_config 规则抓一批热点(写库),
     * 与 hotItemIds 合并后作为种子。配合「生成选题时触发」的 UX。
     */
    private Boolean withHotFetch = false;
}
