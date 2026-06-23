import { http } from './client'
import type {
  HotFetchResult,
  HotItem,
  HotItemStatus,
  HotSource,
  Topic,
} from '../types'

// ============ Hot Item ============

export interface HotItemListParams {
  status?: HotItemStatus | 'all'
  sourceIds?: number[]
  includeKeywords?: string[]
  excludeKeywords?: string[]
  includeTags?: string[]
  maxAgeHours?: number
  minPopularity?: number
  limit?: number
}

export async function listHotItems(params: HotItemListParams = {}): Promise<HotItem[]> {
  const { data } = await http.get<HotItem[]>('/hot-items', {
    params: {
      status: params.status ?? 'new',
      sourceIds: params.sourceIds,
      includeKeywords: params.includeKeywords,
      excludeKeywords: params.excludeKeywords,
      includeTags: params.includeTags,
      maxAgeHours: params.maxAgeHours,
      minPopularity: params.minPopularity,
      limit: params.limit,
    },
    // axios 默认会把数组转成 sourceIds[]=1&sourceIds[]=2,Spring 接 List 时反而要 sourceIds=1&sourceIds=2
    paramsSerializer: {
      indexes: null,
    },
  })
  return data
}

export async function getHotItem(id: number): Promise<HotItem> {
  const { data } = await http.get<HotItem>(`/hot-items/${id}`)
  return data
}

export async function promoteHotItem(id: number, presetId: number): Promise<Topic> {
  const { data } = await http.post<Topic>(`/hot-items/${id}/promote`, { presetId })
  return data
}

export async function dismissHotItem(id: number): Promise<void> {
  await http.post(`/hot-items/${id}/dismiss`)
}

/** 手动抓取热点 — sourceIds 空 = 抓所有 enabled 源。返回每源抓取结果。 */
export async function fetchHotNow(sourceIds?: number[]): Promise<HotFetchResult[]> {
  // 抓取可能要 20-40s(RSS / JSON 多源串行),拉长 timeout
  const { data } = await http.post<HotFetchResult[]>(
    '/hot/fetch',
    { sourceIds: sourceIds ?? null },
    { timeout: 90_000 },
  )
  return data
}

// ============ Hot Source ============

export async function listHotSources(): Promise<HotSource[]> {
  const { data } = await http.get<HotSource[]>('/hot-sources')
  return data
}

export async function getHotSource(id: number): Promise<HotSource> {
  const { data } = await http.get<HotSource>(`/hot-sources/${id}`)
  return data
}

export async function listHotAdapters(): Promise<string[]> {
  const { data } = await http.get<string[]>('/hot-sources/adapters')
  return data
}

export interface HotSourceDraft {
  name: string
  adapter: string
  url: string
  configJson?: string | null
  defaultTagsJson?: string | null
  enabled: boolean
}

export async function createHotSource(draft: HotSourceDraft): Promise<HotSource> {
  const { data } = await http.post<HotSource>('/hot-sources', draft)
  return data
}

export async function updateHotSource(id: number, patch: HotSourceDraft): Promise<HotSource> {
  const { data } = await http.put<HotSource>(`/hot-sources/${id}`, patch)
  return data
}

export async function deleteHotSource(id: number): Promise<void> {
  await http.delete(`/hot-sources/${id}`)
}

/** 单源测试抓取 — 不入库,返回前 5 条 preview。添加 / 编辑源时校验用。 */
export async function testFetchHotSource(draft: HotSourceDraft): Promise<HotItem[]> {
  const { data } = await http.post<HotItem[]>('/hot/test-fetch', draft, { timeout: 30_000 })
  return data
}

/** 触发单源抓取入库。 */
export async function triggerSourceFetch(id: number): Promise<HotFetchResult> {
  const { data } = await http.post<HotFetchResult>(`/hot-sources/${id}/fetch`, null, {
    timeout: 60_000,
  })
  return data
}
