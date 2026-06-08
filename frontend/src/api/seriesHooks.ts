import { http } from './client'
import type { SeriesHook, Topic } from '../types'

/** GET /api/series-hooks?status=unresolved —— 后端已 dedupe by fromScriptId。banner 直接用。
 *  includeWeak=true:同时返回 WEAK 钩子(banner 折叠到次区,信号弱不强推)。 */
export async function listUnresolvedHooks(includeWeak = false): Promise<SeriesHook[]> {
  const { data } = await http.get<SeriesHook[]>('/series-hooks', {
    params: { status: 'unresolved', includeWeak },
  })
  return data
}

/** 单查端点 —— 详情页主路径走 Topic.sourceHook enrich,这个备 fallback 用。 */
export async function getHook(id: number): Promise<SeriesHook> {
  const { data } = await http.get<SeriesHook>(`/series-hooks/${id}`)
  return data
}

export async function dismissHook(id: number): Promise<SeriesHook> {
  const { data } = await http.post<SeriesHook>(`/series-hooks/${id}/dismiss`)
  return data
}

/** 撤销忽略 —— 后端会清掉 dismissed_at;已兑现的钩子返回 409。 */
export async function undismissHook(id: number): Promise<SeriesHook> {
  const { data } = await http.post<SeriesHook>(`/series-hooks/${id}/undismiss`)
  return data
}

/** GET /api/series-hooks?status=dismissed —— 已忽略未兑现的钩子,按 dismissed_at 倒序。给"已忽略"抽屉用。 */
export async function listDismissedHooks(): Promise<SeriesHook[]> {
  const { data } = await http.get<SeriesHook[]>('/series-hooks', {
    params: { status: 'dismissed' },
  })
  return data
}

// 跟后端 SeriesHookController.FulfillRequest 字段一一对应。title 必填,其余可空。
export interface FulfillHookBody {
  title: string
  dynasty?: string | null
  genre?: string | null
  protagonist?: string | null
  hookType?: string | null
  emotion?: string | null
  durationMinutes?: number | null
  historicalReference?: string | null
}

export interface FulfillHookResponse {
  topic: Topic
  hook: SeriesHook
}

export async function fulfillHook(
  id: number,
  body: FulfillHookBody,
): Promise<FulfillHookResponse> {
  const { data } = await http.post<FulfillHookResponse>(
    `/series-hooks/${id}/fulfill-with-new-topic`,
    body,
  )
  return data
}
