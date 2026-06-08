import { http } from './client'

/** 后端 BgmService.BgmTrackDto。Jamendo 的一首曲目,upsert 进 bgm_track 表。 */
export interface BgmTrack {
  id: number
  jamendoId: number
  name: string
  artistName: string | null
  durationSeconds: number | null
  audioUrl: string                    // Jamendo 直链 mp3,前端 <audio> 试听用
  previewUrl: string | null
  albumImageUrl: string | null
  licenseType: string | null
  moodTag: string | null              // 6 项 mood key 之一
  localPath: string | null            // 已下载到本地 → ./storage/bgm/{jamendoId}.mp3
}

/** 后端 BgmService.ChoiceDto —— /choice 和 /select 都返这个形状。 */
export interface BgmChoice {
  scriptId: number
  bgmTrackId: number
  volume: number
  trackName: string
  trackArtist: string | null
}

/** 首次进 BgmPicker 调一次:LLM 打 mood + 拉 Jamendo + 返回前 3 条。 */
export async function recommendBgm(scriptId: number): Promise<BgmTrack[]> {
  const { data } = await http.post<BgmTrack[]>(`/bgm/scripts/${scriptId}/recommend`)
  return data
}

/** 换一批 / 翻页(每次 3 条),offset 由前端累计。 */
export async function loadMoreBgm(scriptId: number, offset: number): Promise<BgmTrack[]> {
  const { data } = await http.get<BgmTrack[]>(`/bgm/scripts/${scriptId}/tracks`, {
    params: { offset },
  })
  return data
}

/** 回填已选;后端没选时返 204 No Content,这里转成 null。 */
export async function getBgmChoice(scriptId: number): Promise<BgmChoice | null> {
  const resp = await http.get<BgmChoice>(`/bgm/scripts/${scriptId}/choice`, {
    // 204 不是错误,axios 默认会把 data 设成空字符串
    validateStatus: (s) => s === 200 || s === 204,
  })
  if (resp.status === 204 || !resp.data || typeof resp.data !== 'object') return null
  return resp.data
}

/** 选曲 + 后端自动下载 mp3(已下载就跳过)。 */
export async function selectBgm(
  scriptId: number,
  bgmTrackId: number,
  volume: number,
): Promise<BgmChoice> {
  const { data } = await http.post<BgmChoice>(`/bgm/scripts/${scriptId}/select`, {
    bgmTrackId,
    volume,
  })
  return data
}
