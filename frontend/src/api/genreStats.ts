import { http } from './client'

export interface GenreStat {
  id: number
  periodStart: string  // YYYY-MM-DD
  periodEnd: string
  platform: string
  genre: string
  vertical: string
  submissionCount: number
  avgCtrPct: number | null
  avgPlay5sPct: number | null
  avgDrop2sPct: number | null
  avgPlaySeconds: number | null
  medianViews: number | null
  avgLikes: number | null
  avgComments: number | null
  avgShares: number | null
  notes: string | null
}

export interface GenreStatUpsert {
  periodStart: string  // ISO date YYYY-MM-DD
  periodEnd: string
  platform: string
  genre: string
  vertical: string
  submissionCount?: number | null
  avgCtrPct?: number | null
  avgPlay5sPct?: number | null
  avgDrop2sPct?: number | null
  avgPlaySeconds?: number | null
  medianViews?: number | null
  avgLikes?: number | null
  avgComments?: number | null
  avgShares?: number | null
  notes?: string | null
}

export interface GenreStatBulkResult {
  inserted: number
  updated: number
  skipped: number
  errors: string[]
}

export async function listGenreStats(): Promise<GenreStat[]> {
  const { data } = await http.get<GenreStat[]>('/genre-stats')
  return data
}

export async function bulkImportGenreStats(rows: GenreStatUpsert[]): Promise<GenreStatBulkResult> {
  const { data } = await http.post<GenreStatBulkResult>('/genre-stats/bulk', rows)
  return data
}

export async function deleteGenreStat(id: number): Promise<void> {
  await http.delete(`/genre-stats/${id}`)
}
