import { ref, onMounted, onBeforeUnmount } from 'vue'
import { listScripts } from '../api/scripts'
import { extractError } from '../lib/format'
import type { PipelineRunStatus, PipelineStage } from '../types'

// 字段名保留 lastStage/lastRunAt/lastRunStatus 兼容老消费者。
export interface ScriptHandle {
  scriptId: number
  topicId: number | null
  lastStage: PipelineStage
  lastRunAt: string
  lastRunStatus: PipelineRunStatus
  /** 后端 list 反向 enrich 的项目名。null = topic 没填。 */
  projectName?: string | null
}

export function useRecentScripts() {
  const items = ref<ScriptHandle[]>([])
  const loading = ref(false)
  const errorMsg = ref<string | null>(null)
  let timer: number | null = null

  async function fetchOnce() {
    loading.value = true
    try {
      const resp = await listScripts({ page: 0, size: 200 })
      const out: ScriptHandle[] = []
      for (const s of resp.content) {
        // script 行刚落但 SCRIPT run 还没 markDone(竞态),lastRun* 全 null,跳过。
        if (s.lastRunStage == null || s.lastRunStatus == null || s.lastRunAt == null) continue
        out.push({
          scriptId: s.id,
          topicId: s.topicId,
          lastStage: s.lastRunStage,
          lastRunAt: s.lastRunAt,
          lastRunStatus: s.lastRunStatus,
          projectName: s.projectName,
        })
      }
      items.value = out
      errorMsg.value = null
    } catch (e: any) {
      errorMsg.value = extractError(e, 'fetch failed')
    } finally {
      loading.value = false
    }
  }

  onMounted(() => {
    fetchOnce()
    timer = window.setInterval(fetchOnce, 15_000)
  })
  onBeforeUnmount(() => {
    if (timer) window.clearInterval(timer)
  })

  return { items, loading, errorMsg, refresh: fetchOnce }
}
