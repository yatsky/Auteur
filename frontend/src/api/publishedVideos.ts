import { http } from './client'

/** 后端 PublishedVideoDto 形状。手填或 OAuth 同步进来。 */
export interface PublishedVideo {
  id: number
  scriptId: number | null
  topicId: number | null
  title: string
  projectName: string | null
  platform: string
  platformVideoId: string | null
  publishedAt: string
  durationSeconds: number | null
  views: number
  likes: number
  comments: number
  shares: number
  retentionPct: number | null
  avgPlaySeconds: number | null
  drop2sPct: number | null
  play5sPct: number | null
  avgPlayRatioPct: number | null
  favoriteRatePct: number | null
  dislikeRatePct: number | null
  hookCtr: number | null
  costYuan: number | null
  hookTemplate: string | null
  notes: string | null
  // 抖音 list 接口富字段。三个 *Rate 是 0~1 浮点(后端原值),展示时 ×100;
  // coverCtr 已经是 0~100;subscribeCount/unsubscribeCount/homepageVisitCount 是整数。
  coverUrl: string | null
  likeRate: number | null
  shareRate: number | null
  commentRate: number | null
  subscribeCount: number | null
  unsubscribeCount: number | null
  coverCtr: number | null
  homepageVisitCount: number | null
  createdAt: string
  updatedAt: string
}

/** 创建/批量/更新的 payload。create 走 @Valid;PATCH/bulk 走手动校验,字段可全空。 */
export interface PublishedVideoUpsert {
  scriptId?: number | null
  topicId?: number | null
  title: string
  projectName?: string | null
  platform: string
  platformVideoId?: string | null
  publishedAt: string  // ISO LocalDateTime,如 "2026-05-22T09:30:00"
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

export interface BulkResult {
  /** 新插入的行 */
  inserted: number
  /** 同 (platform, vid) 或 (scriptId, platform, publishedAt) 命中,字段级 merge 的行 */
  updated: number
  skipped: number
  errors: string[]
}

export async function listPublishedVideos(): Promise<PublishedVideo[]> {
  const { data } = await http.get<PublishedVideo[]>('/published-videos')
  return data
}

export async function getPublishedVideo(id: number): Promise<PublishedVideo> {
  const { data } = await http.get<PublishedVideo>(`/published-videos/${id}`)
  return data
}

export async function createPublishedVideo(body: PublishedVideoUpsert): Promise<PublishedVideo> {
  const { data } = await http.post<PublishedVideo>('/published-videos', body)
  return data
}

export async function bulkImportPublishedVideos(rows: PublishedVideoUpsert[]): Promise<BulkResult> {
  const { data } = await http.post<BulkResult>('/published-videos/bulk', rows)
  return data
}

export async function updatePublishedVideo(id: number, patch: Partial<PublishedVideoUpsert>): Promise<PublishedVideo> {
  const { data } = await http.patch<PublishedVideo>(`/published-videos/${id}`, patch)
  return data
}

export async function deletePublishedVideo(id: number): Promise<void> {
  await http.delete(`/published-videos/${id}`)
}

// 按 (platform, title, publishedAt) 分组找重复;dryRun=true 只列清单不动数据
export interface DuplicateGroup {
  platform: string
  title: string
  publishedAt: string
  keepId: number
  dropIds: number[]
}
export interface DedupeResult {
  groupCount: number
  dropCount: number
  groups: DuplicateGroup[]
  dryRun: boolean
}
export async function dedupePublishedVideos(dryRun: boolean): Promise<DedupeResult> {
  const { data } = await http.post<DedupeResult>('/published-videos/dedupe', null, {
    params: { dryRun },
  })
  return data
}
