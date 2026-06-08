import axios from 'axios'
import { isAdmin, getOwnerName } from '../lib/admin'

// 后端 RestController 都挂在 /api 下；vite dev proxy 把 /api 转到 :8082
// timeout=60s —— LLM 调用普遍 20-40s(brainstorm 快/script 慢),15s 撑不住
export const http = axios.create({
  baseURL: '/api',
  timeout: 60_000,
})

// admin 模式时给请求加 X-Auteur-Admin / X-Auteur-Owner 软提示头,后端按这个过滤私有 preset
http.interceptors.request.use((config) => {
  if (isAdmin()) {
    config.headers.set('X-Auteur-Admin', '1')
    const owner = getOwnerName()
    if (owner) config.headers.set('X-Auteur-Owner', owner)
  }
  return config
})

// 跳错误页时不能直接 import router(循环引用),用 location.assign
// 只在「后端完全不可达」时跳页 —— 超时和 5xx 都不跳,因为后端可能已经处理了请求
// (比如 script 生成 LLM 跑 30s,前端早就 timeout 了,但 scriptId 实际已经入库)
function redirectToError(code: 'network' | '500' | '404') {
  if (window.location.pathname.startsWith('/error')) return
  window.location.assign(`/error?code=${code}`)
}

http.interceptors.response.use(
  (resp) => resp,
  (err) => {
    if (err?.response) {
      // 拿到响应了 —— 5xx/4xx 都让调用方的 errorMsg 显示,不强跳页
      console.error('[api]', err.response.status, err.config?.url, err.response.data)
    } else if (err?.code === 'ECONNABORTED') {
      // 超时:后端可能仍在处理(LLM 调用经常慢) —— 让调用方显示「请求超时,刷新看看是否已生成」
      console.warn('[api] timeout', err.config?.url, '—— 后端可能仍在处理,刷新页面看实际状态')
    } else if (err?.code === 'ERR_NETWORK') {
      // 真正连不上后端(8081 没起 / vite proxy 失败) —— 这种才跳错误页
      console.error('[api] network unreachable', err.message)
      redirectToError('network')
    } else {
      console.error('[api]', err.message)
    }
    return Promise.reject(err)
  },
)
