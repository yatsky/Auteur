<script setup lang="ts">
import { computed } from 'vue'
import { AlertCircle, CheckCircle2, Loader2 } from 'lucide-vue-next'
import type { PipelineRun } from '../types'

const props = defineProps<{
  run: PipelineRun | null
  label?: string
  unit?: string
}>()

const status = computed(() => props.run?.status ?? null)
const isRunning = computed(() => status.value === 'PENDING' || status.value === 'RUNNING')
const isDone = computed(() => status.value === 'DONE')
const isFailed = computed(() => status.value === 'FAILED' || status.value === 'CANCELLED')

const total = computed(() => props.run?.totalItems ?? 0)
const done = computed(() => Math.min(props.run?.lastCompletedIndex ?? 0, total.value || 0))
const pct = computed(() => {
  if (isDone.value) return 100
  if (!total.value) return isRunning.value ? 8 : 0   // 未知总数时给 8% 让条不空
  return Math.round((done.value / total.value) * 100)
})

const barClass = computed(() => {
  if (isFailed.value) return 'bg-status-failed'
  if (isDone.value) return 'bg-status-done'
  return 'bg-accent'
})
</script>

<template>
  <div v-if="run" class="flex items-center gap-2.5 text-xs min-w-0">
    <Loader2 v-if="isRunning" :size="13" class="animate-spin text-accent shrink-0" />
    <CheckCircle2 v-else-if="isDone" :size="13" class="text-status-done shrink-0" />
    <AlertCircle v-else-if="isFailed" :size="13" class="text-status-failed shrink-0" />

    <div class="flex flex-col gap-0.5 min-w-[180px] flex-1">
      <div class="flex items-center justify-between gap-2">
        <span class="truncate" :class="isFailed ? 'text-status-failed' : 'text-text-secondary'">
          {{ label || (isRunning ? '运行中' : isDone ? '完成' : '失败') }}
        </span>
        <span class="font-mono text-text-muted whitespace-nowrap">
          <template v-if="total > 0">
            <span :class="isFailed ? 'text-status-failed' : isDone ? 'text-status-done' : 'text-text-primary'">
              {{ done }}
            </span>
            <span class="opacity-60 mx-0.5">/</span>{{ total }}<span v-if="unit">&nbsp;{{ unit }}</span>
            <span class="opacity-60"> · {{ pct }}%</span>
          </template>
          <template v-else-if="isRunning">提交中…</template>
          <template v-else-if="isDone">完成</template>
        </span>
      </div>
      <div class="h-1 bg-surface-tertiary rounded-full overflow-hidden">
        <div
          class="h-full transition-[width] duration-300"
          :class="barClass"
          :style="{ width: pct + '%' }"
        />
      </div>
    </div>
  </div>
</template>
