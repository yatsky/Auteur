package com.auteur.llm;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LlmCallSpec {

    /** 业务操作名，如 brainstorm / script / factcheck，写入 cost_log.operation */
    private String operation;

    /** 关联实体类型，如 TOPIC / SCRIPT，写入 cost_log.related_type */
    private String relatedType;

    /** 关联实体 id；可空 */
    private Long relatedId;

    /** 所属 script id（生图时用于确定 TOS 存储路径，可空） */
    private Long scriptId;

    /** 模型名，覆盖默认。可空表示用 prompt 模板里的 model */
    private String model;

    /** 温度，覆盖默认。可空。 */
    private Double temperature;

    /** 单次调用的输出 token 上限，覆盖 prompt YAML 里的 max_tokens。可空——空则用 YAML，再空则不下发。 */
    private Integer maxTokens;
}
