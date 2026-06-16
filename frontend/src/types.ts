export type PipelineStage =
  | 'BRAINSTORM'
  | 'SCRIPT'
  | 'FACTCHECK'
  | 'STORYBOARD'
  | 'IMAGEGEN'
  | 'IMAGEAUDIT'
  | 'VOICE'
  | 'VIDEO'
  | 'COVER'

export const ALL_STAGES: PipelineStage[] = [
  'BRAINSTORM', 'SCRIPT', 'FACTCHECK', 'STORYBOARD', 'IMAGEGEN', 'IMAGEAUDIT', 'VOICE', 'VIDEO', 'COVER',
]

export type PipelineRunStatus =
  | 'PENDING'
  | 'RUNNING'
  | 'DONE'
  | 'FAILED'
  | 'PAUSED'
  | 'CANCELLED'

export const ALL_STATUSES: PipelineRunStatus[] = [
  'PENDING', 'RUNNING', 'DONE', 'FAILED', 'PAUSED', 'CANCELLED',
]

export interface PipelineRun {
  id: number
  topicId: number | null
  scriptId: number | null
  stage: PipelineStage
  status: PipelineRunStatus
  startedAt: string | null
  finishedAt: string | null
  errorMsg: string | null
  lastCompletedIndex: number | null
  totalItems: number | null
  pauseRequested: boolean
  paramsJson: string | null
  triggeredBy: string | null
  createdAt: string
  updatedAt: string
  /** 后端 list 接口反向 enrich:run 关联 topic 的 project_name(显示用)。单查不带,列表才有。 */
  projectName?: string | null
}

export interface RunListResponse {
  items: PipelineRun[]
  total: number
  page: number
  size: number
}

export interface ActionResponse {
  ok: boolean
  reason?: string
  status?: PipelineRunStatus
  stage?: PipelineStage
  runId?: number
  originalRunId?: number
  newRunId?: number
  startIndex?: number
  note?: string
}

export const STAGE_LABELS: Record<PipelineStage, string> = {
  BRAINSTORM: '选题',
  SCRIPT: '脚本',
  FACTCHECK: '考据',
  STORYBOARD: '分镜',
  IMAGEGEN: '出图',
  IMAGEAUDIT: '图审',
  VOICE: '配音',
  VIDEO: '视频',
  COVER: '封面',
}

// PipelineRunStatus 中文标签 —— UI 上展示状态一律用这个,不要直接渲染英文枚举
export const RUN_STATUS_LABELS: Record<PipelineRunStatus, string> = {
  PENDING: '待运行',
  RUNNING: '运行中',
  DONE: '完成',
  FAILED: '失败',
  PAUSED: '暂停',
  CANCELLED: '已取消',
}

export const ASYNC_STAGES: Set<PipelineStage> = new Set(['IMAGEGEN', 'IMAGEAUDIT', 'VOICE', 'VIDEO', 'COVER'])

// 钩子 —— 上一集 E 段埋的下集种子。LLM 抽取(STRONG/WEAK 分级)。
export interface SeriesHook {
  id: number
  fromTopicId: number
  toTopicId: number | null          // 兑现后回填新 topic id
  hookText: string                   // E 段原文
  nextEpisodeHint: string | null     // LLM 1 行总结(≤30 字)
  strength: 'STRONG' | 'WEAK'
  suggestedTitle: string | null      // LLM 建议的下集标题(≤25 字)
  suggestedDynasty: string | null    // 朝代(明/清/唐/宋/民国/灵异)
  fromScriptId: number | null        // 钩子从哪条 script 的 E 段抽出来的
  dismissedAt: string | null         // 软删时间;NULL=未处理
  createdAt: string
}

// 总导演 LLM 产出的"导演笔记"。schema 与后端 prompts/director.yaml 对齐。
export interface DirectorNote {
  /** 整体调性,如"沉重克制" */
  tone: string
  /** 整体节奏,如"快入慢出" */
  pacing: string
  /** 五段(A/B/C/D/E)节奏指导 */
  narrativeArc: Array<{ section: 'A' | 'B' | 'C' | 'D' | 'E' | string; guidance: string }>
  /** 美术指导 */
  visualStyle: {
    palette: string
    depthOfField: string
    lighting: string
    avoidWords: string[]
  }
  /** 演员气质 */
  protagonistVibe: {
    appearance: string
    voiceVibe: string
    speakingPace: string
  }
  /** 全片关键瞬间(剪辑师会强调) */
  keyMoments: Array<{ time: string; what: string }>
  /** 字幕高亮主题词 */
  highlightThemes: string[]
  /** 散文体补充指令 */
  directorNotes: string
}

export type TopicStatus = 'DRAFT' | 'SCHEDULED' | 'PRODUCED' | 'PUBLISHED' | 'ARCHIVED'
export const ALL_TOPIC_STATUSES: TopicStatus[] = [
  'DRAFT', 'SCHEDULED', 'PRODUCED', 'PUBLISHED', 'ARCHIVED',
]

export const TOPIC_STATUS_LABELS: Record<TopicStatus, string> = {
  DRAFT: '草稿',
  SCHEDULED: '已排期',
  PRODUCED: '已制作',
  PUBLISHED: '已发布',
  ARCHIVED: '已归档',
}

export interface Topic {
  id: number
  title: string
  /** 项目名(显示用,默认 LEFT(title,10),SCHEDULED 阶段确认时可改);列表里替代"script 5"做视觉锚。 */
  projectName: string | null
  dynasty: string | null
  genre: string | null
  protagonist: string | null
  hookType: string | null
  emotion: string | null
  durationMinutes: number | null
  potentialScore: number | null
  historicalReference: string | null
  seriesId: number | null
  aiSuggestedSeries: string | null
  status: TopicStatus
  source: string
  /** 总导演 LLM 产出的"导演笔记"。 */
  directorNote?: DirectorNote | null
  /** 绑定 preset.id(必有)。 */
  presetId?: number | null
  /** 用户填的字段(per preset.input_schema)。后端原始 JSON 字符串/对象。 */
  presetInputJson?: any
  /** 跑流水线时使用的 preset 版本号。 */
  presetVersionUsed?: number | null
  /** fulfill 来源钩子 id;NULL=非钩子来源(brainstorm/手建)。 */
  sourceHookId?: number | null
  /** GET /api/topics/{id} 反向 enrich 的钩子摘要(原文 + 上集 scriptId)。 */
  sourceHook?: SeriesHook | null
  notes: string | null
  createdAt: string
  updatedAt: string
  /** 后端 list 接口反向 enrich:这条 topic 已生成过的最新脚本 id。无脚本则 null。 */
  latestScriptId?: number | null
}

// 后端 Spring Page<T> JSON 结构(@EnableSpringDataWebSupport VIA_DTO 模式)
export interface SpringPage<T> {
  content: T[]
  page: {
    size: number
    number: number  // 当前页(0-indexed)
    totalElements: number
    totalPages: number
  }
}

export type ScriptStatus = 'DRAFT' | 'REVIEWING' | 'APPROVED' | 'REJECTED'

export const SCRIPT_STATUS_LABELS: Record<ScriptStatus, string> = {
  DRAFT: '草稿',
  REVIEWING: '审核中',
  APPROVED: '已通过',
  REJECTED: '已驳回',
}

// PASS/FAIL/REJECT/REGENERATE/REVIEW/MANUAL 都可能从后端来
export const REVIEW_DECISION_LABELS: Record<string, string> = {
  PASS: '通过',
  FAIL: '不通过',
  REJECT: '驳回',
  REGENERATE: '需重新生成',
  REVIEW: '人工复核',
  MANUAL: '人工处理',
}

// 后端 prompt 用 CRITICAL/MAJOR/MINOR,旧 demo 数据可能残留 HIGH/MEDIUM/LOW
export const SEVERITY_LABELS: Record<string, string> = {
  CRITICAL: '严重',
  MAJOR: '高',
  HIGH: '高',
  MEDIUM: '中',
  MINOR: '低',
  LOW: '低',
}

export interface Script {
  id: number
  topicId: number
  version: number
  modelUsed: string | null
  wordCount: number | null
  durationSeconds: number | null
  fullText: string | null
  status: ScriptStatus
  reviewScore: number | null
  protagonistRefAssetId: number | null  // 首张 PASS final 锁定的主角基准照 asset id
  createdAt: string
  updatedAt: string
}

// GET /api/scripts/{id} 返回 { script, sections } —— sections 是脚本切段
export interface ScriptSection {
  id: number
  scriptId: number
  sectionCode: string             // "A" | "B" | "C" | ... 段落语义编号
  title: string | null            // 钩子段 / 信息累积 / 揭秘前奏 / 反转 / 收尾...
  startSeconds: number | null
  endSeconds: number | null
  textContent: string | null      // 段正文(口播文本)
  directorNote: string | null     // 镜头/调度/BGM 备注 —— 走分镜 stage 输入
  isGoldenLine: boolean           // 金句段(回看 / 抓帧位)
  createdAt: string
}

export interface ScriptDetailResponse {
  script: Script
  sections: ScriptSection[]
  // V3 起后端联查 topic.preset 直接打平 presetName + bgmLocked
  presetName?: string | null
  bgmLocked?: boolean | null
}

/**
 * GET /api/scripts 列表元素 —— Script 实体打平 + projectName + 该 script 最近一条 PipelineRun 的 stage/status/at。
 */
export interface ScriptListItem {
  id: number
  topicId: number | null
  projectName: string | null
  version: number
  modelUsed: string | null
  wordCount: number | null
  durationSeconds: number | null
  status: ScriptStatus
  reviewScore: number | null
  createdAt: string
  updatedAt: string
  lastRunStage: PipelineStage | null
  lastRunStatus: PipelineRunStatus | null
  lastRunAt: string | null
}

export interface FactCheckIssue {
  id: number
  scriptId: number
  lineNumber: number | null
  originalText: string | null
  issueType: string | null
  suggestion: string | null
  severity: string | null
  sourceUrl: string | null
  credibility: string | null
  resolved: boolean
  createdAt: string
}

export interface StoryboardShot {
  id: number
  scriptId: number
  shotIndex: number
  timeRange: string | null
  durationSeconds: number | null
  promptZh: string | null
  promptEn: string | null
  negativePrompt: string | null
  styleTag: string | null
  shotType: string | null
  seed: string | null
  createdAt: string
}

export interface ImageAsset {
  id: number
  shotId: number
  model: string | null
  fileUrl: string | null
  width: number | null
  height: number | null
  seed: string | null
  costCredits: number | null
  reviewScore: number | null
  reviewDecision: string | null
  reviewIssues: string | null
  isFinal: boolean
  usedProtagonistRef: boolean   // 生成此图时是否带了 protagonist reference 做 image-to-image
  createdAt: string
}

export interface VoiceAsset {
  id: number
  scriptId: number
  model: string | null
  voiceLabel: string | null
  speed: number
  pitch: number
  subtitleStyle: string         // standard / highlight
  audioUrl: string | null
  subtitleUrl: string | null
  durationSeconds: number | null
  costYuan: number | null
  isFinal: boolean
  createdAt: string
}

export interface VideoAsset {
  id: number
  scriptId: number
  voiceAssetId: number | null
  videoUrl: string | null
  durationSeconds: number | null
  width: number | null
  height: number | null
  format: string | null         // 9:16 / 16:9 / 1:1
  shotCount: number | null
  costYuan: number | null
  isFinal: boolean
  bgmTrackId: number | null     // 这条成片用的 BGM bgm_track.id;null = 无 BGM
  bgmVolume: number | null      // 实际渲染时 BGM 音量(0.05~0.6),null = 无 BGM
  timingStrategy: 'PRECISE_BY_SECTION' | 'UNIFORM_SCALE' | 'RAW' | null
  timingNote: string | null
  createdAt: string
}

// 账号级品牌包 —— 同账号下所有视频封面强制套这套色板/字体/logo。
export interface BrandIdentity {
  brandName: string             // "历史密档" 之类的频道名
  authorName: string            // 显示在封面副标题或角标
  logoDataUrl: string | null    // 用户上传 logo,base64 落后端 brand_identity.logo_data_url(MEDIUMTEXT)
  primaryColor: string          // hex 主色,大块底色 / 标题色
  secondaryColor: string        // hex 副色,辅助元素
  accentColor: string           // hex 强调色,标签 / 数字 / 关键词
  bgColor: string               // hex 底色
  titleFont: string             // CSS font-family: PingFang SC / Noto Serif SC / ZCOOL KuaiLe / 站酷高端黑
  defaultTemplateId: string     // 新建 cover 默认套哪个模板
  updatedAt: string
}

// per-script 封面设计
export interface CoverDesign {
  scriptId: number
  templateId: string
  titleText: string
  heroImageUrl: string | null   // storyboard 里挑的 ImageAsset.fileUrl,或自上传 base64
  heroSource: 'storyboard' | 'upload'
  isFinal: boolean
  updatedAt: string
}

export interface CoverTemplate {
  id: string
  name: string
  description: string
}

// 三比例画板规格 —— 导出图直接能用,小红书/微博/B站 三种比例覆盖完
export type CoverRatio = '3:4' | '4:3' | '16:9'

export const COVER_RATIO_DIMENSIONS: Record<CoverRatio, { w: number; h: number; label: string }> = {
  '3:4':  { w: 1080, h: 1440, label: '3:4 · 小红书 / 微信' },
  '4:3':  { w: 1440, h: 1080, label: '4:3 · 微博 / 视频号' },
  '16:9': { w: 1920, h: 1080, label: '16:9 · B站 / YouTube' },
}

// 一次 generate 落 3 行(3 比例)
export interface CoverAsset {
  id: number
  scriptId: number
  ratio: CoverRatio
  width: number
  height: number
  templateId: string
  titleText: string | null
  heroImageUrl: string | null
  fileUrl: string                 // /api/files/cover/cover-<scriptId>-<ratio>-<uuid>.png
  fileSizeBytes: number | null
  isFinal: boolean
  runId: number | null
  createdAt: string
}
