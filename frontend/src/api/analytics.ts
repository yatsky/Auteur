import { http } from './client'

/** GET /api/analytics/compare 单元素 —— Dashboard / ReviewWeekly / ReviewCompare 共用。 */
export interface VideoCompare {
  id: number
  scriptId: number | null
  topicId: number | null
  title: string
  projectName: string | null
  platform: string
  publishedAt: string
  durationSeconds: number | null
  views: number
  likes: number
  comments: number
  shares: number
  retentionPct: number | null
  hookCtr: number | null
  costYuan: number | null
  avgPlaySeconds: number | null
  drop2sPct: number | null
  play5sPct: number | null
  avgPlayRatioPct: number | null
  favoriteRatePct: number | null
  dislikeRatePct: number | null
  hookTemplate: string | null
  /** 后端算好:(likes+comments)/views * 100,百分点。 */
  engagementPct: number
}

export interface DailyTrendPoint {
  date: string         // YYYY-MM-DD
  views: number
  engagementPct: number
}

export async function getAnalyticsCompare(): Promise<VideoCompare[]> {
  const { data } = await http.get<VideoCompare[]>('/analytics/compare')
  return data
}

export async function getDailyTrend(): Promise<DailyTrendPoint[]> {
  const { data } = await http.get<DailyTrendPoint[]>('/analytics/daily-trend')
  return data
}
