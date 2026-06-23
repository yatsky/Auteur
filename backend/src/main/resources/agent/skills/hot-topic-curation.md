---
name: hot-topic-curation
summary: 从热点池筛选条目并送入选题的剧本
when: 用户要"看看今天热点"、"挑几条做选题"、要求按某主题找当前社会上正在热的话题;或要补充财经/社会赛道的内容素材
---

# 热点池策展(任务剧本)

## 触发本 skill 的信号

- 用户说"看看今天有什么热点"、"挑几条财经/社会热点"
- 用户说"按 X 主题(降息/通胀/房价/养老金/...)找当前热门话题"
- 用户在 brainstorm 前需要补"当下正在发生什么"的素材
- 准备调 `fetch_hot_now` / `list_hot_items` / `promote_hot_to_topic` 之前

## 标准流程

### 1. 先确认对话目标

用户的诉求通常是这两种之一,先分辨清楚:

| 诉求 | 你该走的路径 |
|---|---|
| "看看 / 浏览 / 我感受一下今天热点" | 只调 `list_hot_items`,呈现内容,不动 `promote` |
| "挑几条做选题" / "按 X 主题给我抓一批" | 走完整流程: fetch → 筛选 → 贴出 → 等用户点头 → promote |

### 2. 抓数据是按需的,不是默认要做

`fetch_hot_now` **会写 DB**,触发实际网络抓取。不要无脑先 fetch 再说:

- 如果用户说"看看今天热点"且 `list_hot_items` 已经返回若干条 → **直接展示就够,不要 fetch**
- 仅在以下情况主动 fetch:
  - 用户明确说"立刻拉一次" / "刷新一下"
  - `list_hot_items` 返回为空 / 最近一条 `fetchedAt` 超过 4 小时
  - 用户要求按某关键词找,但库里这类条目 0 命中

抓取时优先指定 `sourceIds`(更省时间);不传 = 全量抓。

### 3. 筛选(必须基于用户给的角度)

调 `list_hot_items` 时把过滤条件填实:

- `includeKeywords` — 用户提到的主题关键词(降息/通胀/房价/...)
- `excludeKeywords` — 项目的合规护栏词(股票推荐/必涨/翻倍/内幕等);**这个一定要默认带上**
- `maxAgeHours` — 默认 48,用户没明确要求"老内容"就保持
- `minPopularity` — 默认 0.3,过滤掉源站排末位的条目
- `limit` — 用户要"几条"就传几条 + 2(给挑选余地)

### 4. 贴出来让用户选

**不要静默 promote**。把候选条目用以下格式列给用户看:

```
今天匹配到 N 条,我挑了 K 条候选:

1. [财联社 · 2h · 热度 0.92] 央行宣布定向降准 0.5 个百分点
   摘要: 此次降准旨在支持实体经济回暖...
   建议预设: finance_translator

2. [华尔街见闻 · 5h · 热度 0.78] 美联储议息会议纪要...
   ...

要不要把这 K 条都送选题?或者你选其中几条?
```

**用户点头**才调 `promote_hot_to_topic`。送选题工具是 PreviewableHandler — 用户会在审批卡上再看一次,但你列得越清楚审批越快。

### 5. promote 一次一条,不要批量包装

工具签名是单条:`promote_hot_to_topic(hotItemId, presetId)`。一次推 5 条就调 5 次。
**不要试图通过 `notes` 字段把 5 条信息打包塞进一个 topic**——拆开才能各自走流水线。

## 给 brainstorm 当种子的另一条路径

用户如果说"按这些热点 brainstorm 一批选题"(而不是直接 promote),走这条:

1. `list_hot_items` 筛出候选
2. **不调 promote**
3. 调 `start_brainstorm`(原有工具)并传 `hotItemIds=[...]`
4. brainstorm 内部会把这些条目的 title+summary 拼进 prompt context,LLM 出的选题就会跟这些热点联动

两条路径的区别:
- **promote**: 一对一,直接造 topic
- **brainstorm with hotItemIds**: 多对多,LLM 综合提炼

## 常见误区

- ❌ 用户说"看看热点"就立刻 fetch + promote 一条龙
- ❌ 不传 `excludeKeywords`,合规词混进选题(股票推荐/必涨等)
- ❌ 一次推 10 条都进同一个预设(财经预设跑社会热点会跑偏)
- ❌ `maxAgeHours=null` 默认拉历史所有条目(占 LLM 上下文)
- ❌ list_hot_items 返回空就直接告诉用户"没数据"——应该先看 `lastFetchedAt`,可能要 fetch 一次

## 一个完整范例

用户:"今天有什么财经热点能做视频的"

✅ 你:
> 我看看(调 `list_hot_items` includeKeywords=["降息","通胀","房价","养老金","汇率"] excludeKeywords=["股票推荐","必涨","翻倍","内幕"] maxAgeHours=48 limit=8)

(返回 5 条)

> 今天匹配到 5 条,排前面的:
> 
> 1. **央行宣布定向降准 0.5 个百分点** [财联社 · 2h · 0.92]
>    "支持实体经济回暖,中小银行获得更高额度..."
> 2. **美联储议息纪要:通胀回落进展或慢于预期** [华尔街见闻 · 5h · 0.85]
> 3. **一线城市核心区房价企稳,二手房成交量环比+23%** [36氪 · 3h · 0.71]
> 
> 你想都做还是挑几条?用哪个预设?(我看到你的 finance_translator 预设比较合)
