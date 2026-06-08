// 节流:同一 (platform, platformVideoId, tier) 5min 内不重复上报,状态存 chrome.storage.local。
// tier 必要性:抖音同一视频先经 list 拿基础数,后经 mget 拿 KPI(完播/2s跳出);
// 同 key 的话先到的 list 行会把后到的 kpi 行拦掉,完整 KPI 永远写不进库。两档独立窗口互不干扰。
import { FRESH_WINDOW_MS } from './constants'
import type { Platform } from './types'

const STORAGE_KEY = 'throttle.lastSent'
const WINDOW_MS = 5 * 60 * 1000

export type ThrottleTier = 'list' | 'kpi'

export const makeKey = (platform: Platform, platformVideoId: string, tier: ThrottleTier): string =>
  `${platform}:${platformVideoId}:${tier}`

const readMap = async (): Promise<Record<string, number>> =>
  ((await chrome.storage.local.get(STORAGE_KEY))[STORAGE_KEY] as Record<string, number>) ?? {}

const writeMap = (m: Record<string, number>) => chrome.storage.local.set({ [STORAGE_KEY]: m })

/** 分离 keys 为"已超过 5min 可重发"和"5min 内被节流" */
export async function partitionByThrottle(keys: string[]): Promise<{ allowed: string[]; throttled: string[] }> {
  const m = await readMap()
  const now = Date.now()
  const allowed: string[] = []
  const throttled: string[] = []
  for (const k of keys) {
    if (m[k] && now - m[k] < WINDOW_MS) throttled.push(k)
    else allowed.push(k)
  }
  return { allowed, throttled }
}

export async function markSent(keys: string[]): Promise<void> {
  if (keys.length === 0) return
  const m = await readMap()
  const now = Date.now()
  for (const k of keys) m[k] = now
  await writeMap(m)
}

/** chrome.alarms 周期清 24h 前的条目。 */
export async function cleanupOldEntries(): Promise<void> {
  const m = await readMap()
  const cutoff = Date.now() - FRESH_WINDOW_MS
  let dirty = false
  for (const k of Object.keys(m)) {
    if (m[k] < cutoff) {
      delete m[k]
      dirty = true
    }
  }
  if (dirty) await writeMap(m)
}
