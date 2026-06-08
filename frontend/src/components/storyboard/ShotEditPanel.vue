<script setup lang="ts">
// 分镜编辑面板:头部信息 + 拦截警告 + prompt 编辑器 + 关联图片缩略
// 父持有 selectedShot / draft / 运行态;子件 emit 全部行为。
import { computed } from 'vue'
import {
  ImageIcon, ImageOff, Loader2, Pencil, RotateCcw, Save, ShieldAlert, Sparkles, X,
} from 'lucide-vue-next'
import type { ImageAsset, StoryboardShot } from '../../types'
import { REVIEW_DECISION_LABELS } from '../../types'

const props = defineProps<{
  selectedShot: StoryboardShot | null
  selectedLatestAsset: ImageAsset | null
  selectedLinkedImages: ImageAsset[]
  draft: { promptZh: string; promptEn: string; negativePrompt: string }
  draftDirty: boolean
  savingPrompt: boolean
  isRegenerating: boolean
  shotRunMsg: string | undefined
}>()

const emit = defineEmits<{
  (e: 'update:draft', value: { promptZh: string; promptEn: string; negativePrompt: string }): void
  (e: 'cancel-edit'): void
  (e: 'save-prompt-only'): void
  (e: 'save-and-regen'): void
  (e: 'select-as-final', assetId: number): void
}>()

const RING_CLASS: Record<string, string> = {
  PASS: 'ring-status-done',
  FAIL: 'ring-status-failed',
  REJECT: 'ring-status-failed',
  REGENERATE: 'ring-status-paused',
  REVIEW: 'ring-status-paused',
  MANUAL: 'ring-status-paused',
  SENSITIVE_BLOCKED: 'ring-status-failed',
}
const DECISION_CHIP: Record<string, string> = {
  PASS: 'bg-status-done/15 text-status-done',
  FAIL: 'bg-status-failed/15 text-status-failed',
  REJECT: 'bg-status-failed/15 text-status-failed',
  REGENERATE: 'bg-status-paused/15 text-status-paused',
  REVIEW: 'bg-status-paused/15 text-status-paused',
  MANUAL: 'bg-status-paused/15 text-status-paused',
}

const selectedIsBlocked = computed(() => props.selectedLatestAsset?.reviewDecision === 'SENSITIVE_BLOCKED')
const selectedWasAutoDesensitized = computed(
  () => !!props.selectedLatestAsset?.fileUrl && props.selectedLatestAsset?.reviewIssues === 'auto-desensitized',
)
const hasFinalLinked = computed(() => props.selectedLinkedImages.some((i) => i.isFinal))

function updateDraftField(field: 'promptZh' | 'promptEn' | 'negativePrompt', value: string) {
  emit('update:draft', { ...props.draft, [field]: value })
}
</script>

<template>
  <aside class="lg:sticky lg:top-6 space-y-3">
    <template v-if="!selectedShot">
      <div class="card p-8 text-center text-sm text-text-muted">
        <Pencil :size="20" class="mx-auto mb-2 opacity-50" />
        从左侧选择一个分镜开始编辑
      </div>
    </template>

    <template v-else>
      <!-- 头部:索引 + 状态 chip + meta -->
      <div class="card p-4">
        <div class="flex items-center gap-2 mb-2">
          <h2 class="text-base font-semibold">S{{ String(selectedShot.shotIndex).padStart(2, '0') }} · 编辑分镜</h2>
          <div class="ml-auto">
            <span v-if="selectedIsBlocked"
                  class="chip bg-status-failed/15 text-status-failed text-xs">
              <ShieldAlert :size="11" /> 敏感拦截
            </span>
            <span v-else-if="selectedLatestAsset?.reviewDecision"
                  class="chip text-xs" :class="DECISION_CHIP[selectedLatestAsset.reviewDecision] || 'bg-text-muted/15 text-text-muted'">
              {{ REVIEW_DECISION_LABELS[selectedLatestAsset.reviewDecision] || selectedLatestAsset.reviewDecision }}
            </span>
          </div>
        </div>
        <div class="text-xs text-text-muted font-mono">
          {{ selectedShot.timeRange ?? '无时间区间' }}
          <span v-if="selectedShot.shotType"> · {{ selectedShot.shotType }}</span>
          <span v-if="selectedShot.styleTag"> · {{ selectedShot.styleTag }}</span>
          <span v-if="selectedShot.seed"> · seed {{ selectedShot.seed }}</span>
        </div>
        <div v-if="selectedWasAutoDesensitized" class="mt-2 text-xs text-accent flex items-center gap-1">
          <Sparkles :size="11" /> 此 prompt 被自动改写以避开内容审查
        </div>
      </div>

      <!-- 拦截警告 -->
      <div v-if="selectedIsBlocked"
           class="card p-4 border-status-failed bg-status-failed/5">
        <div class="flex items-start gap-2">
          <ShieldAlert :size="16" class="text-status-failed shrink-0 mt-0.5" />
          <div>
            <div class="text-sm font-semibold text-status-failed mb-1">上游内容审查拦截 · 自动脱敏未救回</div>
            <div class="text-xs text-text-secondary">
              {{ selectedLatestAsset?.reviewIssues || '未知原因' }}
            </div>
            <div class="text-xs text-text-muted mt-1.5">
              建议人工改写,弱化具象暴力,改为隐喻表达。
            </div>
          </div>
        </div>
      </div>

      <!-- 编辑器:3 个 prompt 字段 + 3 个动作 -->
      <div class="card p-4">
        <div class="flex items-center gap-2 mb-3">
          <Pencil :size="14" class="text-text-secondary" />
          <h3 class="text-sm font-semibold">修改提示词</h3>
          <span class="ml-auto text-xs text-text-muted">保存后会重置 reviewDecision</span>
        </div>

        <div class="space-y-3">
          <div>
            <label class="text-xs text-text-secondary font-medium mb-1 block">promptZh · 中文描述</label>
            <textarea :value="draft.promptZh" rows="4"
                      class="w-full bg-surface-tertiary border border-border-subtle rounded p-2 text-sm font-sans resize-y focus:border-accent outline-none"
                      @input="updateDraftField('promptZh', ($event.target as HTMLTextAreaElement).value)" />
          </div>
          <div>
            <label class="text-xs text-text-secondary font-medium mb-1 block">promptEn · English</label>
            <textarea :value="draft.promptEn" rows="3"
                      class="w-full bg-surface-tertiary border border-border-subtle rounded p-2 text-sm font-sans italic resize-y focus:border-accent outline-none"
                      @input="updateDraftField('promptEn', ($event.target as HTMLTextAreaElement).value)" />
          </div>
          <div>
            <label class="text-xs text-text-secondary font-medium mb-1 block">negativePrompt</label>
            <input :value="draft.negativePrompt" type="text"
                   class="w-full bg-surface-tertiary border border-border-subtle rounded p-2 text-sm font-mono focus:border-accent outline-none"
                   @input="updateDraftField('negativePrompt', ($event.target as HTMLInputElement).value)" />
          </div>
        </div>

        <div class="flex items-center gap-2 mt-4">
          <button class="chip cursor-pointer text-xs bg-surface-tertiary text-text-secondary"
                  :disabled="!draftDirty || savingPrompt"
                  @click="emit('cancel-edit')">
            <X :size="11" /> 取消
          </button>
          <button class="chip cursor-pointer text-xs bg-surface-tertiary text-text-secondary"
                  :disabled="!draftDirty || savingPrompt"
                  @click="emit('save-prompt-only')">
            <Loader2 v-if="savingPrompt" :size="11" class="animate-spin" />
            <Save v-else :size="11" />
            仅保存 prompt
          </button>
          <button class="btn-primary text-xs ml-auto"
                  :disabled="savingPrompt || isRegenerating"
                  @click="emit('save-and-regen')">
            <Loader2 v-if="savingPrompt || isRegenerating" :size="11" class="animate-spin" />
            <RotateCcw v-else :size="11" />
            {{ isRegenerating ? '重生中…' : (draftDirty ? '保存并重新生成' : '重新生成') }}
          </button>
        </div>
        <div v-if="shotRunMsg"
             class="text-xs mt-2"
             :class="shotRunMsg.startsWith('失败') || shotRunMsg.startsWith('保存失败')
                      ? 'text-status-failed'
                      : shotRunMsg === '✓'
                          ? 'text-status-done'
                          : 'text-text-muted'">
          {{ shotRunMsg }}
        </div>
      </div>

      <!-- 关联图片:每张缩略图带 6 状态色环 -->
      <div class="card p-4">
        <div class="flex items-center gap-2 mb-3">
          <ImageIcon :size="14" class="text-text-secondary" />
          <h3 class="text-sm font-semibold">关联图片 · {{ selectedLinkedImages.length }} 次尝试</h3>
        </div>
        <div v-if="selectedLinkedImages.length === 0" class="text-sm text-text-muted py-3 text-center">
          这一镜还没有生图
        </div>
        <div v-else class="grid grid-cols-3 gap-2">
          <div
            v-for="img in selectedLinkedImages" :key="img.id"
            class="relative rounded-md overflow-hidden bg-surface-tertiary border border-border-subtle group"
            :class="[
              img.reviewDecision && RING_CLASS[img.reviewDecision] ? `ring-2 ${RING_CLASS[img.reviewDecision]}` : '',
              img.isFinal ? 'ring-2 ring-status-done' : '',
              img.fileUrl && !img.isFinal ? 'cursor-pointer hover:ring-2 hover:ring-blue-400' : '',
            ]"
            :title="img.fileUrl ? (img.isFinal ? '当前已选作 final' : '点击选作 final(替换当前已选)') : ''"
            @click="img.fileUrl && !img.isFinal ? emit('select-as-final', img.id) : null"
          >
            <div class="aspect-square bg-surface-primary flex items-center justify-center">
              <img v-if="img.fileUrl" :src="img.fileUrl" class="w-full h-full object-cover" />
              <ShieldAlert v-else-if="img.reviewDecision === 'SENSITIVE_BLOCKED'" :size="20" class="text-status-failed" />
              <ImageOff v-else :size="20" class="text-text-muted" />
            </div>
            <!-- hover 浮层:点击选作 final 的强提示。only on non-final 图 -->
            <div
              v-if="img.fileUrl"
              class="absolute inset-0 flex items-center justify-center transition-all opacity-0 group-hover:opacity-100"
              :class="img.isFinal ? 'group-hover:bg-black/15' : 'group-hover:bg-black/40'"
            >
              <span class="text-[11px] font-medium px-2.5 py-1 rounded-full bg-black/30 text-white/90 backdrop-blur-sm border border-white/15">
                {{ img.isFinal ? '已选定' : (hasFinalLinked ? '替换此图' : '设为选定') }}
              </span>
            </div>
            <div class="p-1.5 text-[10px] font-mono">
              <div class="flex items-center justify-between">
                <span class="text-text-muted">#{{ img.id }}</span>
                <span v-if="img.isFinal" class="text-status-done">已选</span>
              </div>
              <div v-if="img.reviewDecision"
                   class="mt-0.5 truncate"
                   :class="img.reviewDecision === 'PASS' ? 'text-status-done'
                            : img.reviewDecision === 'REGENERATE' || img.reviewDecision === 'REVIEW' || img.reviewDecision === 'MANUAL'
                                ? 'text-status-paused'
                                : 'text-status-failed'">
                {{ REVIEW_DECISION_LABELS[img.reviewDecision] || img.reviewDecision }}
              </div>
            </div>
          </div>
        </div>
      </div>
    </template>
  </aside>
</template>
