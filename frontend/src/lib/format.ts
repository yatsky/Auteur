// 跨页通用格式化/错误提取工具。

// 后端 axios 抛错时三层兜底:Spring ProblemDetail.message → axios err.message → 调用方传的中文 fallback。
// 调用方:try { ... } catch (e) { errorMsg.value = extractError(e, '加载失败') }
export function extractError(e: unknown, fallback = '请求失败'): string {
  const any = e as any
  return any?.response?.data?.message ?? any?.message ?? fallback
}

// "MM:SS",sec 缺失/无穷返回占位符。SC 段时长 / BGM 时长 / 视频长度通用。
export function formatDuration(sec: number | null | undefined): string {
  if (sec == null || !Number.isFinite(sec) || sec < 0) return '—'
  const m = Math.floor(sec / 60)
  const s = Math.floor(sec % 60)
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
}
