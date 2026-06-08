// 异步 run 状态轮询 —— 业务页(factcheck/storyboard/voice/cover/video/topic-script-gen)反复用同一套:
//   POST /xxx-async → 拿 runId → 立即 pollOnce → 每 3s 轮询 → DONE/FAILED/CANCELLED 时 stop 并回调
// 这里抽出来,组件只关心 onDone 后干什么(reload 列表)/ onFailed 后显示什么提示。
import { onBeforeUnmount, ref } from 'vue'
import { getRun } from '../api/runs'
import type { PipelineRun } from '../types'

export interface RunPollHandlers {
  onUpdate?: (r: PipelineRun) => void
  onDone?: (r: PipelineRun) => void | Promise<void>
  onFailed?: (r: PipelineRun) => void | Promise<void>
  intervalMs?: number
}

/** 启动一个独立的轮询。返回 stop 函数。组件级生命周期由调用方管。 */
export function startRunPoll(runId: number, h: RunPollHandlers): () => void {
  const intervalMs = h.intervalMs ?? 3000
  let timer: number | null = null
  let stopped = false
  const stop = () => {
    stopped = true
    if (timer != null) { window.clearInterval(timer); timer = null }
  }
  const tick = async () => {
    if (stopped) return
    try {
      const r = await getRun(runId)
      if (stopped) return
      h.onUpdate?.(r)
      if (r.status === 'DONE') {
        stop()
        await h.onDone?.(r)
      } else if (r.status === 'FAILED' || r.status === 'CANCELLED') {
        stop()
        await h.onFailed?.(r)
      }
    } catch (e: any) {
      console.warn('[run-poll]', e?.message)
    }
  }
  void tick()
  timer = window.setInterval(tick, intervalMs)
  return stop
}

/** 单实例轮询 —— 持有 run + start/stop。组件 unmount 时自动 stop。 */
export function useRunPoll(handlers: RunPollHandlers = {}) {
  const run = ref<PipelineRun | null>(null)
  let stopFn: (() => void) | null = null

  function stop() {
    stopFn?.()
    stopFn = null
  }

  async function start(runId: number) {
    stop()
    run.value = null
    stopFn = startRunPoll(runId, {
      ...handlers,
      onUpdate: (r) => { run.value = r; handlers.onUpdate?.(r) },
      onDone: handlers.onDone,
      onFailed: handlers.onFailed,
    })
  }

  function reset() {
    stop()
    run.value = null
  }

  onBeforeUnmount(stop)

  return { run, start, stop, reset }
}
