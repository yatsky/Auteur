import { http } from './client'
import type { PipelineRun } from '../types'

export async function getRun(id: number): Promise<PipelineRun> {
  const { data } = await http.get<PipelineRun>(`/runs/${id}`)
  return data
}
