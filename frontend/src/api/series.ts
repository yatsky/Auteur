import { http } from './client'
import type { Topic } from '../types'

export interface Series {
  id: number
  name: string
  slug: string
  description: string | null
  coverUrl: string | null
  status: string
  topicCount: number
  createdAt: string
  updatedAt: string
}

export interface SeriesUpsert {
  name: string
  slug: string
  description?: string | null
  coverUrl?: string | null
  status?: string
}

export async function listSeries(): Promise<Series[]> {
  const { data } = await http.get<Series[]>('/series')
  return data
}

export async function getSeries(id: number): Promise<Series> {
  const { data } = await http.get<Series>(`/series/${id}`)
  return data
}

export async function listTopicsInSeries(id: number): Promise<Topic[]> {
  const { data } = await http.get<Topic[]>(`/series/${id}/topics`)
  return data
}

export async function createSeries(body: SeriesUpsert): Promise<Series> {
  const { data } = await http.post<Series>('/series', body)
  return data
}

export async function updateSeries(id: number, body: Partial<SeriesUpsert>): Promise<Series> {
  const { data } = await http.patch<Series>(`/series/${id}`, body)
  return data
}

/** 该系列下还有 topic 时后端 409;前端兜住把 message 弹给用户。 */
export async function deleteSeries(id: number): Promise<void> {
  await http.delete(`/series/${id}`)
}
