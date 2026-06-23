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

/** 把用户在 textarea / 输入框里写的关键词字符串拆成数组,中英文逗号 / 空白 / 换行都分隔。 */
export function splitWords(s: string | null | undefined): string[] {
  if (!s) return []
  return s.split(/[,，\s\n]+/).map((w) => w.trim()).filter(Boolean)
}
