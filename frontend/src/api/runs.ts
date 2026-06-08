import { http } from './client'
import type { PipelineRun } from '../types'

/** 创作流程页面用 —— 轮询异步 run 状态。其余 list/pause/resume/cancel/rerun 已随 tools UI 一并下线。 */
export async function getRun(id: number): Promise<PipelineRun> {
  const { data } = await http.get<PipelineRun>(`/runs/${id}`)
  return data
}
