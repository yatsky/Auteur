// 后端 /api/extension/sync 上报客户端。background 实际上报 + options 健康检测 ping 都走这个。
// 两个调用方在 chrome MV3 都允许 fetch:background 是 service worker(支持),
// options 是普通扩展页面(支持),没有环境差异需要兜底。
import { loadSettings } from './settings'
import type { ExtensionSettings, PublishedVideoUpsert } from './types'

export const SYNC_PATH = '/api/extension/sync'

export function syncEndpoint(s: Pick<ExtensionSettings, 'backendUrl'>): string {
  return s.backendUrl.replace(/\/+$/, '') + SYNC_PATH
}

/**
 * 把 payload POST 到后端 /api/extension/sync。
 * - 401/403/4xx/5xx 一律抛 Error,message = `HTTP {status}: {body 前 200 字}`,由调用方决定重试或上报错误。
 * - 不读 settings 自动注入,允许 caller 显式传 —— 方便 options 用未保存的 form 值做连通测试。
 */
export async function postSync(
  payload: PublishedVideoUpsert[] | unknown[],
  settings?: ExtensionSettings,
): Promise<void> {
  const s = settings ?? (await loadSettings())
  if (!s.token) throw new Error('extension token 未配置(请在选项页填入)')
  if (!s.backendUrl) throw new Error('backendUrl 未配置')
  const r = await fetch(syncEndpoint(s), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Extension-Token': s.token },
    body: JSON.stringify(payload),
  })
  if (!r.ok) {
    const text = await r.text().catch(() => '')
    throw new Error(`HTTP ${r.status}: ${text.slice(0, 200)}`)
  }
}
