import { http } from './client'

/** GET /api/reviews/weekly?week=YYYY-Www 返回体 —— 4 段都可能 null,数据库无记录时 updatedAt=null。 */
export interface WeeklyReviewView {
  weekCode: string
  highlights: string | null
  lessons: string | null
  experiments: string | null
  nextWeek: string | null
  updatedAt: string | null
}

export interface WeeklyReviewSavePayload {
  highlights: string
  lessons: string
  experiments: string
  nextWeek: string
}

export async function getWeeklyReview(weekCode: string): Promise<WeeklyReviewView> {
  const { data } = await http.get<WeeklyReviewView>('/reviews/weekly', {
    params: { week: weekCode },
  })
  return data
}

export async function saveWeeklyReview(
  weekCode: string,
  payload: WeeklyReviewSavePayload,
): Promise<WeeklyReviewView> {
  const { data } = await http.put<WeeklyReviewView>('/reviews/weekly', payload, {
    params: { week: weekCode },
  })
  return data
}
