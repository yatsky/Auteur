# Auteur · 一个由 AI 剧组协作完成的中文短视频流水线

> **AI Short Video Pipeline · Multi-Role Studio · Preset-Driven · Self-Hosted**

[English version →](./README.en.md)

---

Auteur 把"做一条短视频"分解成一个**剧组**：每一个生产环节都由一位 AI 角色负责（编剧、摄影、美术、配音、作曲、审片官、制片……），他们之间通过显式的中间产物交接。给一个主题、按一下生成，剧组就把全流程跑完——脚本、字幕、分镜、生图、配音、BGM、封面、合成视频，全部落库可追溯，每一步都可中断、人工介入、单独重做。

每种"内容形态"（横屏纪录片 / 竖屏故事 / 锁脸人设短剧……）由一行**预设（preset）** 决定。预设里写明：每位角色的 prompt yaml、画面风格、模型、音色、Remotion composition、画幅、水印、BGM 模板……换一种内容形态 = 在 UI 里建一行预设，**不改代码**。

```
主题 → 选题脑暴 → 编剧（+ 自审 / 历史核查） → 配音字幕
          ↓
       摄影指导（+ 自审）→ 美术指导（+ 审片）→ 制片合成 → 复盘分析师
```

---

### 系统的核心特点：AI 剧组多角色协作

Auteur 不是一个把 prompt 串起来的脚本，它是一个**有分工、有审查、有反馈循环、有数据复盘**的虚拟剧组。每个角色都是一个独立的 Spring Service，有自己的 prompt 模板、自己的 LLM 调用与重试策略、自己的产物落库表。

#### 创作角色

| 角色 | 后端服务 | 职责 | 产物 |
|---|---|---|---|
| 🎯 **选题策划** | `BrainstormService` | 基于历史数据 + 系列脉络给出 5–10 个选题候选，自动按权重做朝代/题材/钩子打分 | `topic` 多条候选 |
| 📝 **编剧** | `ScriptService` | 把选题展开成 5 段叙事结构（A 钩子 / B 累积 / C 中段 / D 揭秘 / E 留白），高分走旗舰模型，普通走批量便宜模型 | `script` + `script_section × 5` |
| 🔍 **编剧自审编辑** | `ScriptCriticService` | LLM 自审打分；低于阈值时把审稿意见塞回上下文让编剧重写一稿（最多 1 轮） | `critic_log` |
| 📚 **历史核查员** | `FactCheckService` + `FactCheckFixService` | 抽出脚本中的史实声明，逐条验证可信度，给出修复建议或自动改写 | `fact_check_issue` |
| 🪝 **钩子分析师** | `HookExtractor` | 从已发布脚本里反向提取"下集预告"作为下一期种子 | `series_hook` |
| 🎬 **摄影指导** | `StoryboardService` | 把脚本拆成 20–28 个分镜（中文 prompt + 英文 prompt + shot_type + 时长），可选 PRECISE_BY_CUE 模式按 SRT cue **逐字锚定**到音频时间戳 | `storyboard_shot × N` |
| 🎬 **摄影自审编辑** | `StoryboardCriticService` | 校验景别多样性、极特写比例、anchor 命中率等硬指标 | `critic_log` |
| 🎨 **美术指导** | `ImageGenService` + `ShotPromptRefineService` | 按分镜 prompt 批量出图，并发限速 + 失败重试；锁脸预设会注入 reference image | `image_asset` |
| 🖼️ **美术审片** | `ImageAuditService` | 出图后做一次构图/手部/水印/锁脸一致性检查，标 `review_issues` | `image_asset.review_issues` |
| 🎙️ **配音演员** | `VoiceGenService` (火山豆包 TTS) | 按 voice_id / speed_ratio 合成 narration + SRT 字幕，回写真实音频时长 | `voice_asset` + `.srt` |
| 🎵 **作曲/选曲** | `BgmService` + `BgmMoodTagger` | LLM 根据脚本情绪打 mood 标签，调 Jamendo API 选 CC 协议 BGM，落库可锁定 | `bgm_track` + `script_bgm_choice` |
| 🖼️ **封面设计** | `CoverGenerationService` + `Java2DCoverRenderer` | 抽脚本要点生成多版封面草图，含品牌识别（brand identity） | `cover_asset` |
| 🎬 **总导演** | `DirectorNoteService` | 跨角色的视觉风格 + 叙事弧线 + 关键节拍笔记，所有下游角色都消费它 | `director_note` |
| 🎞️ **制片** | `VideoAssemblyService` + `FfmpegVideoRenderer` / `RemotionVideoRenderer` | 解析 SRT → ShotTimingResolver 算每镜真实时长 → 拼接 ImageClip → ffmpeg/Remotion 合成 → 烧字幕 + sidechaincompress BGM ducking | `video_asset` |
| 📊 **数据分析师** | `InsightService` + `WeeklyReviewService` + `VideoAttributionService` | 拉浏览器扩展回写的真实播放数据，做完播率/钩子归因/周复盘，反哺下一轮选题脑暴 | `weekly_review`, `genre_stat_snapshot` |
| 🧭 **系列规划** | `SeriesResolver` | 跨视频的"下集预告"链路，把上一期的 hook 转成下一期的 topic 种子 | `series_hook` |

#### 角色之间是怎么协作的

1. **显式产物交接**：每个角色读上游 DB 表，写下游 DB 表。这不是 prompt chaining——任何一步失败都可以单独重跑，中间产物在 UI 里都能看到、能改、能删。
2. **审稿反馈环**：自审角色（Script Critic / Storyboard Critic / Image Auditor）打分低于阈值时，把审稿意见塞回原角色的 prompt 重投一次。这把 LLM 的不确定性局限到"自己给自己改一稿"的小循环里，而不是污染整条流水线。
3. **导演笔记共享**：DirectorNoteService 维护一个跨角色的视觉/叙事中央笔记，编剧定下叙事弧后写入，摄影读它定 vibe，美术读它定 style，制片读它选 BGM——避免每个角色各自漂移。
4. **流水线状态机**：`pipeline_run` 表跟踪每一段的 `PENDING / RUNNING / DONE / FAILED`，前端轮询 `GET /api/runs/{id}` 看进度，断点续跑。

---

### 其它关键特点

#### 1. 预设驱动（preset-driven）

预设决定**所有内容形态相关的**配置：每位角色的 prompt yaml、画面尺寸、模型、音色、composition、水印、BGM 是否启用、是否锁脸……换内容形态不用 PR，UI 里建一行预设即可。

```
preset
  ├─ brainstorm_prompt_yaml          # 给"选题策划"的剧本
  ├─ script_prompt_yaml              # 给"编剧"
  ├─ script_critic_prompt_yaml       # 给"编剧自审"
  ├─ script_critic_threshold: 80     # 自审分数阈值
  ├─ storyboard_prompt_yaml          # 给"摄影"
  ├─ storyboard_mode: PRECISE_BY_CUE # 强制按 SRT cue 锚定
  ├─ bgm_mood_prompt_yaml            # 给"作曲"
  ├─ image_config_json: {model, identity_lock_text, reference_image_path, style_suffix, ...}
  ├─ voice_config_json: {voice_id, speed_ratio, volume_ratio}
  ├─ composition_id: LifeCopy        # Remotion composition
  ├─ format_width / format_height
  ├─ watermark_text
  ├─ hook_segment_enabled
  └─ bgm_enabled
```

每次保存预设都会写一份 `preset_version` 快照，UI 里一键回滚到任意版本。导出 = `GET /api/presets/{id}` 拿 JSON，导入 = `POST /api/presets`，可直接放进 Git 做版本控制。

仓库内置：
- **freeform** — 通用基础模板（默认 seed），输入 `theme + tone + duration_minutes` 就能跑通一条竖屏短视频
- **LifeCopy**（仓库里有实现代码但**默认不 seed**）— 横屏 1920×1080 + 锁脸人设 + 漫画风 + 翻书音效 hook + PRECISE_BY_CUE 强对齐

#### 2. PRECISE_BY_CUE：让分镜与音频字面对齐

普通流水线把"一句话占多少秒"丢给 LLM 估算，结果常常字幕和镜头错位。Auteur 在 PRECISE_BY_CUE 模式下要求摄影指导给每个 shot 一段**精确的字面 anchor_text**（必须是脚本里的连续子串），后端在 SRT 解析后把 anchor 在音频时间轴上反查出真实秒数，**镜头时长 = anchor 在 SRT 里实际占的秒数**。后端会校验：
- anchor 是否真的是脚本子串（normalize 后比对）
- 相邻 shot 的 anchor 在脚本里的位置是否单调递增（防止 LLM 乱排顺序）
- 没命中 anchor 的镜头会被标 `anchor_match=false`，视频还能渲，但日志和 UI 都会提示

这把"画面与字幕掐不准"这个老问题在生成时就堵掉了。

#### 3. 数据回写驱动复盘

`extension/` 目录是一个浏览器扩展（抖音 / B 站 / 视频号 / 快手），插到平台的"创作者后台"页面，自动抓播放数 / 完播率 / 互动数据 POST 回 Auteur，落到 `published_video` 表。

`WeeklyReviewService` 每周根据这些数据算：
- 哪些朝代 × 题材 × 钩子组合表现最好（特征贡献度归因）
- 上周哪些计划落地了 / 没落地
- 给下周的选题脑暴一个"权重表 + 重点改进项"

下一次"选题策划"角色就会读这份周报，按数据权重影响候选打分。**这是流水线的元学习层。**

#### 4. 本地优先 + 可降级

- TOS（火山对象存储）配了就用，没配自动降级到 `backend/storage/` 本地路径 + `/api/files/...` 静态服务。
- 火山 TTS 没配，配音环节 graceful disabled，前端会提示。
- BGM Jamendo client_id 没配，BGM 不推荐，制片照常合成（没 BGM 而已）。
- Remotion 没装，`auteur.video.provider=ffmpeg` 走纯 ffmpeg 路径。
- LLM 走 OpenAI 兼容协议，自部署 vLLM 或商业网关都行。

后端不会因为某个外部依赖缺失而启动失败——降级路径都打过。

---

### 架构

```
backend/      Spring Boot 3.3 + JPA + Flyway + MySQL    16 个 AI 角色 + 流水线编排 + REST API
frontend/     Vue 3 + Vite + TypeScript + Pinia          创作工作台 + 预设库 UI（含 admin 模式）
renderer/     Remotion (TypeScript)                      视频合成器（可选，ffmpeg 默认走通）
extension/    Chrome 扩展（4 平台）                       回写抖音 / B 站 / 视频号 / 快手 后台数据
docs/         设计文档
```

数据流以 DB 表为骨架：`topic → script → storyboard_shot → image_asset → voice_asset → video_asset → published_video → weekly_review`，每一阶段产物落库，所有 AI 角色之间不直接 RPC，全部通过 DB 解耦。

---

### 快速启动

#### 0. 前置依赖

- JDK 21
- Node.js 20+ / npm（或 pnpm）
- MySQL 8.0+
- ffmpeg（`brew install ffmpeg` / `apt install ffmpeg`）
- LLM 中继：自部署 vLLM 或任意 OpenAI 兼容协议网关

#### 1. 数据库

```sql
CREATE DATABASE auteur CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

Flyway 在 `backend/src/main/resources/db/migration/` 启动时自动建表。`PresetSeeder` 在 preset 表为空时从 `preset_seeds/freeform/` 注入示例预设。

#### 2. 配置

```bash
cd backend/src/main/resources
cp application-local.yml.example application-local.yml
# 编辑 application-local.yml，填:
#   spring.datasource.password           - MySQL 密码
#   auteur.llm.base-url + api-key        - LLM 中继地址 + key
#   auteur.voice.volcano.api-key         - 火山豆包 TTS（可选；空 → 配音不可用）
#   auteur.tos.access-key/secret-key/bucket  - 火山 TOS（可选；空 → 本地路径）
#   auteur.bgm.jamendo.client-id         - Jamendo（可选；空 → BGM 不可用）
```

#### 3. 启动

```bash
# 后端
cd backend && mvn spring-boot:run                 # :8082

# 前端
cd frontend && npm install && npm run dev         # :5174，代理 /api → :8082

# Remotion（可选；首次会拉 Chromium ≈150 MB）
cd renderer && npm install
npm run dev                                       # 打开 Remotion Studio 预览 composition
```

后端在合成阶段自动调 `npx remotion render`，**不需要手动起 Studio**——只在你想可视化调试 composition 时开。

#### 4. 跑通第一条视频

1. 「选题池」→「AI 头脑风暴」→ 选 `freeform` 预设 → 写主题 → 生成
2. 选题详情 →「配置预设输入」→ 填 `theme / tone / duration_minutes`
3. 「生成脚本」（异步，~30s）
4. 脚本工作台 → 跳过事实核查（freeform 默认不跑） → 「配音字幕」生成
5. 「分镜工作台」生成镜头 prompt → 「生图工作台」批量出图
6. 「视频组装」点击合成 → ffmpeg/Remotion 渲染 → 落到 `backend/storage/video/`

---

### 自定义新预设

1. 「预设库」→「新建预设」（admin 模式按右上角切换）
2. 填基本信息 + `input_schema`：定义你想让用户填的字段（这会变成创建 topic 时的动态表单）
3. 编辑三份核心 prompt yaml：brainstorm / script / storyboard，用 `{{key}}` 引用 `input_schema` 字段
4. 选 composition：横屏 → `StoryHorizontal`，竖屏 → `StoryVertical`，特殊形态 → 自己写一个 Remotion composition 注册到 `renderer/src/Root.tsx`
5. 保存 → 在选题池用这个预设建 topic

---

### 配置参考

`application-local.yml` 必填：

```yaml
spring.datasource.password: <你的 MySQL 密码>
auteur.llm.base-url: <OpenAI 兼容 LLM 网关>
auteur.llm.api-key: <key>
```

可选（留空则对应功能降级，后端不挂）：

| 配置项 | 用途 |
|---|---|
| `auteur.voice.volcano.api-key` | 火山豆包 TTS。空 → 配音不可用 |
| `auteur.tos.access-key/secret-key/bucket` | 火山 TOS。空 → 走本地路径，公网访问受限 |
| `auteur.bgm.jamendo.client-id` | Jamendo 选曲。空 → BGM 推荐不可用 |
| `auteur.video.ffmpeg.binary-path` | ffmpeg 路径。默认 `/opt/homebrew/bin/ffmpeg` |
| `auteur.video.remotion.enabled` | Remotion 合成。默认 true，需 `cd renderer && npm install` 跑过一次 |
| `auteur.alert.feishu.webhook-url` | 飞书告警机器人。空 → 不告警 |
| `auteur.extension.token` | 浏览器扩展回写校验。生产部署务必覆盖默认值 |

---

### 安全说明

- `application-local.yml` 已加入 `.gitignore`，**永远不要把真实凭据 commit**
- `extension/` 浏览器插件回写时校验 `auteur.extension.token`，生产环境必须通过环境变量覆盖默认值
- 预设的 `visibility=private` 是**软标记**（不是真鉴权），只在 UI 层按 `X-Auteur-Admin` 头隔离——部署到公网时需要在反向代理层加真实鉴权（Nginx basic auth / OAuth2 proxy 都行）
- LLM 中继自托管推荐：vLLM + Caddy 反代 + IP 白名单。商业网关（DeepSeek / Anthropic / 智谱）走 https + key rotation

---

### 贡献

PR / Issue 欢迎。开发前请阅读 `docs/PRESET_REFACTOR_PLAN.md`。

提 PR 时：
- backend 改动确保 `mvn -DskipTests compile` 通过
- frontend 改动确保 `npx vue-tsc --noEmit` 通过
- 改 schema → 加 Flyway migration 用递增 `V*` 编号
- 改预设相关代码 → 同步更新 `preset_seeds/<name>/` 下的 seed 文件

### License

待定。
