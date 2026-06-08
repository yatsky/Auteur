<script setup lang="ts">
// 左侧分镜网格的单张卡片。selected/blocked/missing 状态边框由 props 决定。
import { computed } from 'vue'
import { ImageIcon, ImageOff, ShieldAlert, Sparkles } from 'lucide-vue-next'
import type { ImageAsset, StoryboardShot } from '../../types'
import { REVIEW_DECISION_LABELS } from '../../types'

const props = defineProps<{
  shot: StoryboardShot
  selected: boolean
  latestAsset: ImageAsset | null
  hasImage: boolean
}>()

defineEmits<{ (e: 'select'): void }>()

const DECISION_CHIP: Record<string, string> = {
  PASS: 'bg-status-done/15 text-status-done',
  FAIL: 'bg-status-failed/15 text-status-failed',
  REJECT: 'bg-status-failed/15 text-status-failed',
  REGENERATE: 'bg-status-paused/15 text-status-paused',
  REVIEW: 'bg-status-paused/15 text-status-paused',
  MANUAL: 'bg-status-paused/15 text-status-paused',
}

const cardClass = computed(() => {
  if (props.selected) return 'border-accent ring-1 ring-accent'
  if (props.latestAsset?.reviewDecision === 'SENSITIVE_BLOCKED') return 'border-status-failed/40 hover:border-status-failed/60'
  if (!props.hasImage) return 'border-status-paused/40 hover:border-status-paused/60'
  return 'hover:border-accent/50'
})

const wasAutoDesensitized = computed(
  () => !!props.latestAsset?.fileUrl && props.latestAsset?.reviewIssues === 'auto-desensitized',
)
const isBlocked = computed(() => props.latestAsset?.reviewDecision === 'SENSITIVE_BLOCKED')
const chip = computed<{ decision: string; class: string } | null>(() => {
  const a = props.latestAsset
  if (!a?.reviewDecision) return null
  return {
    decision: REVIEW_DECISION_LABELS[a.reviewDecision] || a.reviewDecision,
    class: DECISION_CHIP[a.reviewDecision] || 'bg-text-muted/15 text-text-muted',
  }
})
</script>

<template>
  <article
    class="card p-4 cursor-pointer transition-colors"
    :class="cardClass"
    @click="$emit('select')"
  >
    <div class="flex items-center justify-between mb-2">
      <div class="flex items-center gap-2">
        <span class="chip bg-accent-soft text-accent font-mono">{{ shot.shotIndex }}</span>
        <span v-if="shot.timeRange" class="text-xs text-text-muted font-mono">{{ shot.timeRange }}</span>
        <span v-if="shot.shotType" class="chip bg-surface-tertiary text-text-secondary">{{ shot.shotType }}</span>
      </div>
      <div class="flex items-center gap-1.5">
        <span v-if="isBlocked"
              class="chip bg-status-failed/15 text-status-failed text-xs">
          <ShieldAlert :size="11" /> 敏感拦截
        </span>
        <span v-else-if="chip"
              class="chip text-xs" :class="chip.class">
          {{ chip.decision }}
        </span>
        <span v-else-if="hasImage" class="chip bg-status-done/15 text-status-done text-xs">
          <ImageIcon :size="11" /> 已生图
        </span>
        <span v-else class="chip bg-status-paused/15 text-status-paused text-xs">
          <ImageOff :size="11" /> 未生图
        </span>
        <span v-if="wasAutoDesensitized"
              class="chip bg-accent-soft text-accent text-xs" title="prompt 被自动改写以避开内容审查">
          <Sparkles :size="11" /> 已脱敏
        </span>
      </div>
    </div>
    <div v-if="shot.promptZh" class="text-sm text-text-primary line-clamp-2 mb-1">{{ shot.promptZh }}</div>
    <div v-if="shot.promptEn" class="text-xs text-text-muted line-clamp-2 italic">{{ shot.promptEn }}</div>
  </article>
</template>
