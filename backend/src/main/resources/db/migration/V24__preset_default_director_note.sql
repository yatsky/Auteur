-- 给 preset 表加默认导演笔记模板列。
-- 用途:新建 topic 时(目前只接入 HotPromoteService)把此 JSON copy 到 topic.director_note,
-- 作为该频道的 baseline 创作起点 — tone/pacing/narrativeArc/visualStyle 等。
-- NULL = 该 preset 不带默认模板,promote 出的 topic 的 director_note 保持 NULL(下游降级到默认风格词)。
ALTER TABLE `preset`
    ADD COLUMN `default_director_note_json` JSON DEFAULT NULL
        COMMENT '该 preset 自带的默认导演笔记模板,promote 新建 topic 时 copy 到 topic.director_note';
