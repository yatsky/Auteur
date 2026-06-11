/**
 * Demo 模式 axios interceptor
 *
 * 拦截所有发往 /api/** 的请求,根据 URL + method 匹配 fixtures,假延时后返回。
 *
 * 匹配规则:
 *   1. 把请求 URL 的所有数字 ID 替换成 :id,得到模板 key,如 "/topics/42" → "/topics/:id"
 *   2. 把 method 拼上去,如 "GET /topics/:id"
 *   3. 在 fixturesTable 里查
 *      - 命中 → fake delay → 返回 fixture
 *      - 未命中 → 返回标准 demo 响应:200 空数据 + console.warn(避免 demo 站到处弹错)
 *
 * 异步触发类(POST /generate /assemble 等)返回伪 runId,前端会轮询 GET /runs/{id};
 * fixtures 里 GET /runs/:id 配套一个会"从 RUNNING 渐变到 DONE" 的 generator。
 */

import type { AxiosInstance, InternalAxiosRequestConfig, AxiosResponse } from 'axios'
import { DELAYS, delay } from './delays'
import { fixturesTable, getRunStatus, type FixtureFn } from './fixtures'

/** 把 URL 里的数字 id 替换成 :id,用于 fixtures table 匹配 */
function templatize(url: string): string {
  // 去掉 query string
  const path = url.split('?')[0]
  // /api 前缀(client baseURL 已加 /api,axios.config.url 不会带 /api,但保险起见)
  const stripped = path.replace(/^\/api/, '')
  // 把所有数字段替换成 :id
  return stripped.replace(/\/\d+/g, '/:id')
}

function buildKey(method: string | undefined, url: string | undefined): string {
  return `${(method ?? 'GET').toUpperCase()} ${templatize(url ?? '/')}`
}

/** 给一个伪造的 AxiosResponse */
function fakeResponse<T>(config: InternalAxiosRequestConfig, data: T, status = 200): AxiosResponse<T> {
  return {
    data,
    status,
    statusText: status === 200 ? 'OK' : 'NoContent',
    headers: {},
    config,
    request: undefined,
  }
}

/**
 * 安装 demo interceptor 到给定的 axios 实例。
 *
 * 实现技巧:用 request interceptor 把 config.adapter 替换成自定义函数。
 * 这样就能完全跳过真实 HTTP 发送,伪造响应。
 */
export function installDemoInterceptor(http: AxiosInstance): void {
  http.interceptors.request.use(async (config) => {
    const key = buildKey(config.method, config.url)
    const entry = fixturesTable[key]

    // 自定义 adapter:接管整个请求,返回伪造响应
    config.adapter = async () => {
      // 1) 长任务轮询: GET /runs/:id 走特殊处理,模拟 RUNNING → DONE
      if (key === 'GET /runs/:id') {
        const runId = extractLastNumber(config.url)
        await delay(DELAYS.fast)
        return fakeResponse(config, getRunStatus(runId))
      }

      // 2) 表里有 → 走 fixture
      if (entry !== undefined) {
        const fn: FixtureFn = typeof entry === 'function' ? entry : (() => entry)
        // 选 delay profile:写入类(POST/PUT/PATCH)走 trigger,LLM 类显式标注用 llm,默认 fast
        const isMutating = ['POST', 'PUT', 'PATCH', 'DELETE'].includes(
          (config.method ?? 'GET').toUpperCase(),
        )
        const profile = isMutating
          ? key.includes('brainstorm') || key.includes('script') || key.includes('optimize')
            ? DELAYS.llm
            : DELAYS.trigger
          : DELAYS.fast
        await delay(profile)
        return fakeResponse(config, fn(config))
      }

      // 3) 没 mock → 给一个安全空响应 + warn,不让 demo 站到处弹错
      console.warn('[demo] no fixture for', key, '— returning empty response')
      await delay(DELAYS.fast)
      // 默认空数组(列表) / null(单条);拿不准的就 null,前端通常会展示 "没有数据"
      return fakeResponse(config, null)
    }

    return config
  })
}

function extractLastNumber(url: string | undefined): number {
  if (!url) return 0
  const m = url.match(/\/(\d+)(?:\?|$)/)
  return m ? Number(m[1]) : 0
}
