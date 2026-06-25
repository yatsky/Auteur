// 火山引擎豆包 TTS 音色 — 后端 VolcanoVoiceCatalog 是唯一来源。
// 启动时 fetch 一次 /api/voice/catalog 缓存到 Vue ref,本文件只保留 UI 业务分组规则
// (按音色的 gender + group 字段派生),加新音色无需改这里 — 改后端即可,前端自动同步。

import { computed, ref } from 'vue'
import { http } from '../api/client'

export interface Voice {
  voiceType: string
  label: string
  suit: string
  gender: 'male' | 'female'
  group: 'biography' | 'mystery' | 'both'
}

export interface VoiceGroup {
  label: string
  voices: Voice[]
}

interface CatalogResponse {
  defaultVoice: string
  voices: Voice[]
}

const voicesCache = ref<Voice[]>([])
const defaultVoiceRef = ref<string>('')
let loadPromise: Promise<void> | null = null

/** 幂等:多次调用只 fetch 一次。在 VoiceStudio 等需要音色的页面 onMounted 里 await。 */
export async function ensureVoiceCatalogLoaded(): Promise<void> {
  if (loadPromise) return loadPromise
  loadPromise = http.get<CatalogResponse>('/voice/catalog').then(({ data }) => {
    voicesCache.value = data.voices
    defaultVoiceRef.value = data.defaultVoice
  })
  return loadPromise
}

/** UI 业务分组规则。规则改动属于 UI 决策,不污染后端 catalog 字段。
 *  新音色按 gender + group 字段自动落入对应分组。 */
export const voiceGroups = computed<VoiceGroup[]>(() => [
  {
    label: '推荐 · 旁白解说',
    voices: voicesCache.value.filter(
      (v) => v.gender === 'male' && (v.group === 'both' || v.group === 'mystery'),
    ),
  },
  {
    label: '推荐 · 第一视角 · 男声',
    voices: voicesCache.value.filter((v) => v.gender === 'male' && v.group === 'biography'),
  },
  {
    label: '推荐 · 第一视角 · 女声',
    voices: voicesCache.value.filter((v) => v.gender === 'female' && v.group === 'biography'),
  },
  {
    label: '通用',
    voices: voicesCache.value.filter((v) => v.gender === 'female' && v.group === 'both'),
  },
])

/** 后端 VolcanoVoiceCatalog.defaultVoice() 同步过来。preset.voice_config 没指定 voice_id 时用。
 *  注意:catalog 加载完才有值;加载前是空串。VoiceStudio 在 await ensureVoiceCatalogLoaded() 之后再用。 */
export const defaultVoice = computed(() => defaultVoiceRef.value)

/** 反查中文显示名;不在表里就原样返回 ID。catalog 加载前永远返回 ID(回退体验)。 */
export function voiceLabelOf(id: string | null | undefined): string {
  if (!id) return ''
  const v = voicesCache.value.find((x) => x.voiceType === id)
  return v?.label ?? id
}
