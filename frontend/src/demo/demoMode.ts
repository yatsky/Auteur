/**
 * Demo mode 检测
 *
 * 三个触发源(任一为真即开):
 *   1. 编译时 env: VITE_DEMO_MODE=force / always —— Vercel 部署时强制开
 *   2. URL 参数: ?demo=1 —— 本地 dev 调试用
 *   3. localStorage: auteur.demoMode=1 —— 用户在站内点了 "进入 demo 模式" 按钮后持久化
 *
 * 一旦进入 demo mode,axios 全部走 mock,后端不会被调用(因为 Vercel 上根本没后端,
 * 即使调用也是 404)。
 */

const URL_FLAG = 'demo'
const STORAGE_KEY = 'auteur.demoMode'

let cached: boolean | null = null

export function isDemoMode(): boolean {
  if (cached !== null) return cached

  // 1) 编译期强制
  const envFlag = (import.meta.env.VITE_DEMO_MODE ?? '').toString().toLowerCase()
  if (envFlag === 'force' || envFlag === 'always' || envFlag === 'true') {
    cached = true
    return true
  }

  // 2) URL 参数
  if (typeof window !== 'undefined') {
    const params = new URLSearchParams(window.location.search)
    const v = params.get(URL_FLAG)
    if (v === '1' || v === 'true') {
      // 顺手持久化,后续刷新页面不掉
      try {
        localStorage.setItem(STORAGE_KEY, '1')
      } catch {
        // 无痕模式可能写不进 storage,无所谓
      }
      cached = true
      return true
    }
    if (v === '0' || v === 'false') {
      // 显式退出
      try {
        localStorage.removeItem(STORAGE_KEY)
      } catch {}
      cached = false
      return false
    }
  }

  // 3) localStorage
  if (typeof window !== 'undefined') {
    try {
      if (localStorage.getItem(STORAGE_KEY) === '1') {
        cached = true
        return true
      }
    } catch {}
  }

  cached = false
  return false
}

/** 从站内主动退出 demo mode(给 DemoBanner 的 "退出 demo" 按钮用) */
export function exitDemoMode(): void {
  try {
    localStorage.removeItem(STORAGE_KEY)
  } catch {}
  cached = false
  // 同步去掉 URL 参数,避免刷新又进来
  if (typeof window !== 'undefined') {
    const url = new URL(window.location.href)
    url.searchParams.delete(URL_FLAG)
    window.history.replaceState({}, '', url.toString())
  }
}
