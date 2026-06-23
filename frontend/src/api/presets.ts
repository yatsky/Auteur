import { http } from './client'

export interface Preset {
  id: number
  name: string
  displayName?: string | null
  description?: string | null
  inputSchemaJson?: any
  brainstormPromptYaml?: string | null
  scriptPromptYaml: string
  scriptCriticPromptYaml?: string | null
  scriptCriticThreshold?: number | null
  storyboardPromptYaml: string
  storyboardMode: 'PRECISE_BY_CUE' | 'FREE'
  assistantDirectorPromptYaml?: string | null
  bgmMoodPromptYaml?: string | null
  imageConfigJson?: any
  voiceConfigJson?: any
  /** 热点订阅配置 JSON 字符串(后端 @JsonRawValue) — 见 HotSourceConfig */
  hotSourceConfigJson?: string | null
  bgmEnabled: boolean
  /** true = BGM 锁定模式,用户不让选,后端按 preset 兜底出曲。仅 bgmEnabled=true 时有意义。 */
  bgmLocked: boolean
  compositionId: string
  formatWidth: number
  formatHeight: number
  watermarkText?: string | null
  hookSegmentEnabled: boolean
  chapterBreakSec: number
  currentVersion: number
  createdAt?: string
  updatedAt?: string
}

export interface PresetVersion {
  id: number
  presetId: number
  version: number
  snapshotJson: string
  comment?: string | null
  createdAt: string
}

export interface PresetAsset {
  id: number
  presetId: number
  kind: string
  localPath: string
  uploadedAt: string
}

export async function listPresets(): Promise<Preset[]> {
  const { data } = await http.get<Preset[]>('/presets')
  return data
}

export async function getPreset(id: number): Promise<Preset> {
  const { data } = await http.get<Preset>(`/presets/${id}`)
  return data
}

export async function getPresetByName(name: string): Promise<Preset> {
  const { data } = await http.get<Preset>(`/presets/by-name/${encodeURIComponent(name)}`)
  return data
}

export async function createPreset(draft: Partial<Preset>): Promise<Preset> {
  const { data } = await http.post<Preset>('/presets', draft)
  return data
}

export async function updatePreset(id: number, patch: Partial<Preset>): Promise<Preset> {
  const { data } = await http.put<Preset>(`/presets/${id}`, patch)
  return data
}

export async function saveAsNewVersion(
  id: number,
  patch: Partial<Preset>,
  comment?: string,
): Promise<Preset> {
  const { data } = await http.post<Preset>(`/presets/${id}/save-version`, patch, {
    params: comment ? { comment } : {},
  })
  return data
}

export async function rollbackPreset(id: number, version: number): Promise<Preset> {
  const { data } = await http.post<Preset>(`/presets/${id}/rollback`, null, {
    params: { version },
  })
  return data
}

export async function deletePreset(id: number): Promise<void> {
  await http.delete(`/presets/${id}`)
}

export async function listPresetVersions(id: number): Promise<PresetVersion[]> {
  const { data } = await http.get<PresetVersion[]>(`/presets/${id}/versions`)
  return data
}

export async function listPresetAssets(id: number): Promise<PresetAsset[]> {
  const { data } = await http.get<PresetAsset[]>(`/presets/${id}/assets`)
  return data
}

/**
 * 不落库,前端拿到结果再决定是否写回 draft。LLM 调用慢,timeout 拉到 120s。
 */
export interface PresetOptimizeRequest {
  section: string
  userFeedback: string
  /** 用户在编辑器中尚未保存的草稿字段值,缺则后端读 DB 当前值。 */
  currentValues?: Record<string, any>
}

export interface PresetOptimizeResponse {
  section: string
  fields: Record<string, any>
  explanation?: string | null
}

export async function optimizePreset(
  id: number,
  req: PresetOptimizeRequest,
): Promise<PresetOptimizeResponse> {
  const { data } = await http.post<PresetOptimizeResponse>(`/presets/${id}/optimize`, req, {
    timeout: 120_000,
  })
  return data
}
