import { http } from './client'

/** /api/insights/dimension-weights 返回结构 —— 后端字段一一对应。 */
export interface DimensionValueWeight {
  value: string
  avgRetention: number | null
  avgEngagement: number | null
  avgViews: number | null
  count: number
  credible: boolean
  avgLikeRate: number | null         // 0~1 浮点(展示时 ×100)
  avgShareRate: number | null
  avgSubscribePerVideo: number | null  // 平均涨粉数
  avgCoverCtr: number | null         // 0~100
}

export interface DimensionWeightReport {
  platform: string | null
  days: number
  minSamples: number
  globalAvgRetention: number | null
  totalSample: number
  /** key = dynasty / genre / hookType / emotion / durationMinutes */
  weights: Record<string, DimensionValueWeight[]>
}

export interface VideoFeature {
  id: number
  title: string
  projectName: string | null
  platform: string
  publishedAt: string
  retentionPct: number | null
  views: number
  likesPlusComments: number
  /** key 同上 */
  dimensions: Record<string, string>
  likeRate: number | null
  shareRate: number | null
  subscribeCount: number | null
  coverCtr: number | null
}

export interface TopBottomReport {
  platform: string | null
  days: number
  n: number
  top: VideoFeature[]              // 完播率 Top
  bottom: VideoFeature[]           // 完播率 Bottom
  topCommonality: Record<string, string>
  bottomCommonality: Record<string, string>
  topByLikeRate: VideoFeature[]
  topByShareRate: VideoFeature[]
  topBySubscribe: VideoFeature[]
  topByCoverCtr: VideoFeature[]
}

export interface RecomputeResult {
  updated: number
}

export async function getDimensionWeights(
  platform?: string,
  days = 30,
  minSamples = 5,
): Promise<DimensionWeightReport> {
  const { data } = await http.get<DimensionWeightReport>('/insights/dimension-weights', {
    params: { platform: platform || undefined, days, minSamples },
  })
  return data
}

export async function getTopBottom(
  platform?: string,
  days = 30,
  n = 5,
): Promise<TopBottomReport> {
  const { data } = await http.get<TopBottomReport>('/insights/top-bottom', {
    params: { platform: platform || undefined, days, n },
  })
  return data
}

export async function recomputePotentialScores(
  platform?: string,
  days = 30,
): Promise<RecomputeResult> {
  const { data } = await http.post<RecomputeResult>('/insights/recompute-scores', null, {
    params: { platform: platform || undefined, days },
  })
  return data
}

/** 周复盘 4 段 LLM 生成结果。stats 顺手返,UI 可省一次循环。fallback=true 表示兜底文案(样本不足或 LLM 失败),前端不应自动落库。 */
export interface WeeklyReviewResult {
  highlights: string
  lessons: string
  experiments: string
  nextWeek: string
  fallback: boolean
  stats?: {
    videoCount: number
    totalViews: number
    avgRetention: number | null
  }
}

export async function generateWeeklyReview(
  platform?: string,
  days = 7,
): Promise<WeeklyReviewResult> {
  const { data } = await http.post<WeeklyReviewResult>('/insights/weekly-review', null, {
    params: { platform: platform || undefined, days },
  })
  return data
}

/** 单视频 AI 归因结果。fallback=true 同义于 LLM 失败兜底,前端不应长期展示。 */
export interface VideoAttributionResult {
  verdict: string
  whatWorked: string
  whatFailed: string
  recommendations: string
  fallback: boolean
}

export async function generateVideoAttribution(videoId: number): Promise<VideoAttributionResult> {
  const { data } = await http.post<VideoAttributionResult>('/insights/video-attribution', null, {
    params: { videoId },
  })
  return data
}

/** 维度 key → 中文 label,前端展示统一用。 */
export const DIMENSION_LABELS: Record<string, string> = {
  dynasty: '朝代',
  genre: '题材',
  hookType: '钩子',
  emotion: '情绪',
  durationMinutes: '时长',
}

/** 与后端 DIMENSIONS 系数同步,Insights 页展示。 */
export const DIMENSION_COEFFICIENTS: Record<string, number> = {
  dynasty: 0.20,
  genre: 0.30,
  hookType: 0.25,
  emotion: 0.15,
  durationMinutes: 0.10,
}
