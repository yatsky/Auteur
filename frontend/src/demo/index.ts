/**
 * Demo 模式入口。在 main.ts 启动时调用一次:
 *
 *   import { setupDemoMode } from './demo'
 *   setupDemoMode()
 *
 * 内部:
 *   - 检测 demo mode
 *   - 是 → 给 axios 装 interceptor + 给 window 挂个全局标记(可选给组件读)
 *   - 否 → 啥都不做
 *
 * DemoBanner 组件由 App.vue 根据 isDemoMode() 自行判断挂不挂。
 */

import { http } from '../api/client'
import { isDemoMode } from './demoMode'
import { installDemoInterceptor } from './demoInterceptor'

export function setupDemoMode(): void {
  if (!isDemoMode()) return

  installDemoInterceptor(http)

  // 标个全局标志,方便其他模块直接读(比如某些组件要在 demo 模式下隐藏"危险按钮")
  if (typeof window !== 'undefined') {
    ;(window as unknown as { __AUTEUR_DEMO__: boolean }).__AUTEUR_DEMO__ = true
  }

  // 留个 console 标记,方便调试时判断当前是不是 demo 模式
  console.info(
    '%c🎭 Auteur Demo Mode',
    'background:#f59e0b;color:white;padding:2px 8px;border-radius:4px;font-weight:bold',
    '\n所有 API 调用走 mock fixture,不会打到真实后端。\n退出方式:右上角按钮 / 访问 ?demo=0',
  )
}

export { isDemoMode, exitDemoMode } from './demoMode'
