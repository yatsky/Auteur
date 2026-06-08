// page-hook(MAIN world):patch fetch / XHR.send。必须 run_at=document_start,
// 在 SPA 缓存原 fetch 引用之前完成包装;flag 防开发期 reload 后链式 wrap。
import type { Platform } from '../shared/types'

export interface InstallOptions {
  platform: Platform
  flag: string
  patterns: RegExp[]
}

// 抖音 aweme_id / 快手 photoId 等是 19 位整数,部分接口返回裸 number,
// JSON.parse 转 Number 时超过 2^53-1 会被 IEEE 754 双精度舍入,例:
//   7644175263462378802 → 7644175263462378000
// 导致后端 (platform, vid) upsert key 命不中,同一物理视频被记成两行(2026-05-28 dup bug 根因)。
// 仅对已知 platformVideoId 字段名 + 长度 ≥ 15 的纯数字命中引号化,不波及 play_count 等真数值字段。
const BIGINT_KEY_RE = /"(aweme_id|item_id|workId|photoId|objectId|aid)"\s*:\s*(\d{15,})(?=[,}\]\s])/g
const parseJson = (text: string): unknown => JSON.parse(text.replace(BIGINT_KEY_RE, '"$1":"$2"'))

export function installHook({ platform, flag, patterns }: InstallOptions): void {
  const flagBag = window as unknown as Record<string, boolean | undefined>
  if (flagBag[flag]) return
  flagBag[flag] = true

  const matches = (url: string) => patterns.some((re) => re.test(url))
  const postBack = (origin: string, raw: unknown) =>
    window.postMessage({ source: 'auteur', platform, origin, raw }, window.location.origin)

  const origFetch = window.fetch.bind(window)
  window.fetch = async function (input, init) {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
    const resp = await origFetch(input as Parameters<typeof origFetch>[0], init)
    if (matches(url)) {
      resp.clone().text()
        .then((text) => postBack(url, parseJson(text)))
        .catch(() => {/* 非 JSON 响应忽略 */})
    }
    return resp
  }

  type Hooked = XMLHttpRequest & { __auteurUrl?: string }
  const origOpen = XMLHttpRequest.prototype.open
  XMLHttpRequest.prototype.open = function (this: Hooked, ...args: unknown[]) {
    const url = args[1]
    this.__auteurUrl = typeof url === 'string' ? url : url instanceof URL ? url.href : undefined
    return (origOpen as (...a: unknown[]) => void).apply(this, args)
  } as typeof origOpen

  const origSend = XMLHttpRequest.prototype.send
  XMLHttpRequest.prototype.send = function (this: Hooked, body?: Document | XMLHttpRequestBodyInit | null) {
    this.addEventListener('load', () => {
      const url = this.__auteurUrl ?? ''
      if (!matches(url)) return
      try {
        const text = this.responseType === '' || this.responseType === 'text' ? this.responseText : null
        if (text) postBack(url, parseJson(text))
      } catch { /* 非 JSON / 解析失败忽略 */ }
    })
    return origSend.call(this, body)
  } as typeof origSend
}
