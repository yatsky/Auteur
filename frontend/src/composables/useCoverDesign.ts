// per-script 封面设计表单状态(in-memory only)。真值落后端 cover_asset 表;前端 ref 只是表单临时态。
import { ref, watch, type Ref } from 'vue'
import type { CoverDesign } from '../types'
import { useBrandIdentity } from './useBrandIdentity'

function makeDefault(scriptId: number, defaultTemplateId: string): CoverDesign {
  return {
    scriptId,
    templateId: defaultTemplateId,
    titleText: '',
    heroImageUrl: null,
    heroSource: 'storyboard',
    isFinal: false,
    updatedAt: new Date().toISOString(),
  }
}

export function useCoverDesign(scriptIdRef: Ref<number | null>) {
  const { brand } = useBrandIdentity()
  const design = ref<CoverDesign | null>(null)

  function reset(sid: number | null) {
    if (sid == null) {
      design.value = null
      return
    }
    design.value = makeDefault(sid, brand.value.defaultTemplateId)
  }

  function applyFromAsset(a: {
    templateId: string
    titleText: string | null
    heroImageUrl: string | null
  }) {
    if (!design.value) return
    design.value.templateId = a.templateId || design.value.templateId
    design.value.titleText = a.titleText ?? design.value.titleText
    design.value.heroImageUrl = a.heroImageUrl ?? design.value.heroImageUrl
    if (a.heroImageUrl?.startsWith('data:')) design.value.heroSource = 'upload'
    else if (a.heroImageUrl) design.value.heroSource = 'storyboard'
  }

  watch(scriptIdRef, (sid: number | null) => reset(sid), { immediate: true })

  return {
    design,
    reset: () => reset(scriptIdRef.value),
    applyFromAsset,
  }
}
