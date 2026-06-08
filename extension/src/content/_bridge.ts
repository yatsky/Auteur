// "Extension context invalidated" 在开发期 reload 后会从 sync throw / Promise reject / window error 三处冒出,
// 任一捕获到就置 invalidated=true 静默退出。
import type { CapturedMessage, HookEnvelope, Platform } from '../shared/types'

const isContextInvalidated = (err: unknown) => /Extension context invalidated/i.test(String(err))

export function setupBridge(platform: Platform): void {
  let invalidated = false

  const trapInvalidation = (err: unknown) => {
    if (!isContextInvalidated(err)) return false
    invalidated = true
    return true
  }

  window.addEventListener('unhandledrejection', (ev) => trapInvalidation(ev.reason) && ev.preventDefault())
  window.addEventListener('error', (ev) => trapInvalidation(ev.error ?? ev.message) && ev.preventDefault())

  window.addEventListener('message', (e: MessageEvent) => {
    if (invalidated || e.source !== window) return
    const data = e.data as HookEnvelope | undefined
    if (data?.source !== 'auteur' || data.platform !== platform) return

    const msg: CapturedMessage = { type: 'auteur/captured', platform, origin: data.origin, raw: data.raw }
    try {
      const ret = chrome.runtime.sendMessage(msg)
      ;(ret as Promise<unknown>)?.catch?.((err) => {
        if (trapInvalidation(err)) return
        // SW 刚被叫醒等失败,下一次 capture 还会再发,丢了不致命
        console.debug(`[auteur/${platform}] sendMessage failed`, err)
      })
    } catch (err) {
      if (trapInvalidation(err)) return
      console.debug(`[auteur/${platform}] listener threw`, err)
    }
  })
}
