// 后端 axios 抛错时三层兜底:Spring ProblemDetail.message → axios err.message → 调用方传的中文 fallback。
export function extractError(e: unknown, fallback = '请求失败'): string {
  const any = e as any
  return any?.response?.data?.message ?? any?.message ?? fallback
}

export function formatDuration(sec: number | null | undefined): string {
  if (sec == null || !Number.isFinite(sec) || sec < 0) return '—'
  const m = Math.floor(sec / 60)
  const s = Math.floor(sec % 60)
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
}
