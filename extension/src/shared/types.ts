// 跨 background / content / page-hook 共用类型。upsert 部分与后端
// PublishedVideoUpsertRequest 对齐 —— 字段全 optional,后端 upsert 时校验
// title/platform/publishedAt 必填。

export type Platform = '抖音' | 'B站' | '视频号' | '快手'

export const PLATFORMS: Platform[] = ['抖音', 'B站', '视频号', '快手']

export interface PublishedVideoUpsert {
  scriptId?: number | null
  topicId?: number | null
  title: string
  projectName?: string | null
  platform: Platform
  platformVideoId?: string | null
  /** ISO 8601 不带时区的 LocalDateTime,例:"2026-05-26T10:30:00" */
  publishedAt: string
  durationSeconds?: number | null
  views?: number | null
  likes?: number | null
  comments?: number | null
  shares?: number | null
  retentionPct?: number | null
  avgPlaySeconds?: number | null
  drop2sPct?: number | null
  play5sPct?: number | null
  avgPlayRatioPct?: number | null
  favoriteRatePct?: number | null
  dislikeRatePct?: number | null
  hookCtr?: number | null
  costYuan?: number | null
  hookTemplate?: string | null
  notes?: string | null
  coverUrl?: string | null
  likeRate?: number | null
  shareRate?: number | null
  commentRate?: number | null
  subscribeCount?: number | null
  unsubscribeCount?: number | null
  coverCtr?: number | null
  homepageVisitCount?: number | null
}

/** page-hook 通过 window.postMessage 发给 content script 的载荷。 */
export interface HookEnvelope {
  source: 'auteur'
  platform: Platform
  origin: string
  raw: unknown
}

/** content -> background 的运行时消息。 */
export interface CapturedMessage {
  type: 'auteur/captured'
  platform: Platform
  origin: string
  raw: unknown
}

export interface ExtensionSettings {
  backendUrl: string
  token: string
  enabled: Record<Platform, boolean>
}

export const DEFAULT_SETTINGS: ExtensionSettings = {
  backendUrl: 'http://localhost:8081',
  token: '',
  enabled: { 抖音: true, B站: true, 视频号: true, 快手: true },
}

/** popup 渲染所需的最小状态。 */
export interface RuntimeStatus {
  /** 任意平台最近一次成功上报的 epochMs;footer 显示用 */
  lastSentAt: number | null
  /** 最近一次错误 message */
  lastError: string | null
  /** 每个平台独立的最近上报 epochMs;null 表示从未采过 */
  platforms: Record<Platform, number | null>
}
