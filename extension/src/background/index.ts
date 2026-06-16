import { cleanupOldEntries, makeKey, markSent, partitionByThrottle, type ThrottleTier } from '../shared/throttle'
import { normalize } from '../shared/normalize'
import { loadSettings, loadStatus, patchStatus } from '../shared/settings'
import { postSync } from '../shared/syncClient'
import type { CapturedMessage, PublishedVideoUpsert } from '../shared/types'

const RETRY_KEY = 'runtime.retryQueue'
const MAX_RETRY = 3

interface RetryItem {
  attempt: number
  nextAt: number
  payload: PublishedVideoUpsert[]
}

const backoffMs = (attempt: number) => Math.min(60_000, 2_000 * 2 ** attempt)

const getRetryQueue = async (): Promise<RetryItem[]> =>
  ((await chrome.storage.local.get(RETRY_KEY))[RETRY_KEY] as RetryItem[]) ?? []

const setRetryQueue = (items: RetryItem[]) => chrome.storage.local.set({ [RETRY_KEY]: items })

/** 任一 KPI 字段非空当 kpi 行,否则 list 行;两档共享 (platform,vid) 但走独立 5min 窗口,互不阻塞。 */
function tierOf(row: PublishedVideoUpsert): ThrottleTier {
  return row.retentionPct != null ||
    row.play5sPct != null ||
    row.drop2sPct != null ||
    row.avgPlayRatioPct != null ||
    row.avgPlaySeconds != null ||
    row.favoriteRatePct != null ||
    row.dislikeRatePct != null
    ? 'kpi'
    : 'list'
}

async function flushOnce(payload: PublishedVideoUpsert[]): Promise<void> {
  if (payload.length === 0) return
  await postSync(payload)
  const keys = payload
    .filter((p) => p.platformVideoId)
    .map((p) => makeKey(p.platform, String(p.platformVideoId), tierOf(p)))
  await markSent(keys)

  const now = Date.now()
  const status = await loadStatus()
  const platforms = { ...status.platforms }
  for (const row of payload) platforms[row.platform] = now
  await patchStatus({ lastSentAt: now, lastError: null, platforms })
  console.info('[auteur/bg] sync ok count=', payload.length)
}

async function enqueueRetry(payload: PublishedVideoUpsert[], attempt: number, err: Error) {
  const queue = await getRetryQueue()
  queue.push({ attempt, nextAt: Date.now() + backoffMs(attempt), payload })
  await setRetryQueue(queue)
  await patchStatus({ lastError: err.message })
  console.warn('[auteur/bg] queued retry attempt=', attempt, err.message)
}

async function processRetries() {
  const queue = await getRetryQueue()
  if (queue.length === 0) return
  const now = Date.now()
  const remaining: RetryItem[] = []
  for (const item of queue) {
    if (item.nextAt > now) {
      remaining.push(item)
      continue
    }
    try {
      await flushOnce(item.payload)
    } catch (e) {
      const err = e as Error
      const next = item.attempt + 1
      if (next >= MAX_RETRY) {
        await patchStatus({ lastError: `重试 ${MAX_RETRY} 次仍失败: ${err.message}` })
        console.error('[auteur/bg] giving up after', MAX_RETRY, err)
      } else {
        remaining.push({ attempt: next, nextAt: Date.now() + backoffMs(next), payload: item.payload })
      }
    }
  }
  await setRetryQueue(remaining)
}

async function handleCapture(msg: CapturedMessage) {
  const settings = await loadSettings()
  if (!settings.enabled[msg.platform]) return

  const rows = normalize({ platform: msg.platform, origin: msg.origin, raw: msg.raw })
  if (rows.length === 0) {
    console.debug('[auteur/bg] capture origin=', msg.origin, 'normalize 0 rows')
    return
  }

  const keyed = rows
    .filter((r) => r.platformVideoId)
    .map((r) => ({ row: r, key: makeKey(r.platform, String(r.platformVideoId), tierOf(r)) }))
  const { allowed, throttled } = await partitionByThrottle(keyed.map((x) => x.key))
  const allowedSet = new Set(allowed)
  const payload = keyed.filter((x) => allowedSet.has(x.key)).map((x) => x.row)

  console.info('[auteur/bg] capture', msg.origin, 'rows=', rows.length, 'send=', payload.length, 'throttled=', throttled.length)
  if (payload.length === 0) return

  try {
    await flushOnce(payload)
  } catch (e) {
    await enqueueRetry(payload, 0, e as Error)
  }
}

chrome.runtime.onMessage.addListener((msg: CapturedMessage, _sender, sendResponse) => {
  if (msg?.type !== 'auteur/captured') return
  handleCapture(msg).catch((e) => console.error('[auteur/bg] handleCapture failed', e))
  sendResponse({ ok: true })
  return true
})

chrome.alarms.create('cleanup', { periodInMinutes: 30 })
chrome.alarms.create('retry', { periodInMinutes: 1 })
chrome.alarms.onAlarm.addListener((alarm) => {
  if (alarm.name === 'cleanup') cleanupOldEntries().catch(console.error)
  if (alarm.name === 'retry') processRetries().catch(console.error)
})

chrome.runtime.onInstalled.addListener(() => {
  console.info('[auteur/bg] installed at', new Date().toISOString())
})
