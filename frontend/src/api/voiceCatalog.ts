import { http } from './client'

export interface VolcanoVoice {
  voiceType: string  // zh_male_xxx_uranus_bigtts
  label: string      // 中文可读名,如"高冷沉稳"
  suit: string       // 适合场景描述
  gender: 'male' | 'female'
  group: 'biography' | 'mystery' | 'both'
}

let cache: VolcanoVoice[] | null = null

export async function listVolcanoVoices(): Promise<VolcanoVoice[]> {
  if (cache) return cache
  const { data } = await http.get<VolcanoVoice[]>('/voice/catalog')
  cache = data
  return data
}

/**
 * 仅 provider=volcano 可用,mock 时后端返回 500,前端兜底提示。
 */
export async function previewVoice(voiceType: string, speed?: number): Promise<string> {
  const { data } = await http.post<{ audioUrl: string }>(
    '/voice/demo',
    { voiceType, speed },
    { timeout: 60_000 },  // 首次合成约 3-8s,留余量
  )
  return data.audioUrl
}
