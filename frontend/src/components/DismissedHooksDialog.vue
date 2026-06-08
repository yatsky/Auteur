<script setup lang="ts">
// DismissedHooksDialog —— "已忽略"抽屉。在 TopicList 顶部链接 [查看已忽略 N 条] 触发。
// 列出 dismissed_at IS NOT NULL 的钩子,每行 [恢复] 按钮调 undismiss API。
// 恢复成功后 emit('restored', id),父组件刷新主 banner 列表。
import { onMounted, ref } from 'vue'
import { Loader2, RotateCcw, X } from 'lucide-vue-next'
import { listDismissedHooks, undismissHook } from '../api/seriesHooks'
import { extractError } from '../lib/format'
import type { SeriesHook } from '../types'
import TimeText from './TimeText.vue'

const emit = defineEmits<{
  (e: 'close'): void
  (e: 'restored', id: number): void
}>()

const items = ref<SeriesHook[]>([])
const loading = ref(false)
const errorMsg = ref<string | null>(null)
const restoringId = ref<number | null>(null)

async function load() {
  loading.value = true
  errorMsg.value = null
  try {
    items.value = await listDismissedHooks()
  } catch (e: any) {
    errorMsg.value = extractError(e, '加载失败')
  } finally {
    loading.value = false
  }
}

async function onRestore(id: number) {
  restoringId.value = id
  try {
    await undismissHook(id)
    items.value = items.value.filter((h) => h.id !== id)
    emit('restored', id)
  } catch (e: any) {
    alert(`恢复失败:${extractError(e)}`)
  } finally {
    restoringId.value = null
  }
}

onMounted(load)
</script>

<template>
  <div class="fixed inset-0 z-50 bg-black/40 flex items-start justify-end" @click.self="emit('close')">
    <div class="bg-surface-base h-full w-full max-w-[480px] shadow-xl flex flex-col">
      <div class="flex items-center justify-between px-5 py-3 border-b border-border-subtle">
        <div>
          <div class="text-sm font-medium">已忽略的钩子</div>
          <div class="text-xs text-text-muted mt-0.5">
            点「恢复」让它重新回到顶部 banner
          </div>
        </div>
        <button class="text-text-muted hover:text-text-primary p-1" @click="emit('close')">
          <X :size="16" />
        </button>
      </div>

      <div class="flex-1 overflow-y-auto p-4 space-y-2">
        <div v-if="errorMsg" class="card p-3 bg-status-failed/10 text-xs text-status-failed">
          {{ errorMsg }}
        </div>
        <div v-if="loading" class="text-center py-12 text-text-muted">
          <Loader2 :size="18" class="animate-spin mx-auto" />
        </div>
        <div v-else-if="items.length === 0" class="text-center py-12 text-text-muted text-sm">
          没有被忽略的钩子
        </div>
        <div
          v-for="h in items" :key="h.id"
          class="card p-3 bg-surface-tertiary/40"
        >
          <div class="flex items-center gap-2 text-xs text-text-muted mb-1">
            <span :class="['chip text-[10px] px-1.5 py-0', h.strength === 'STRONG'
              ? 'bg-accent/15 text-accent'
              : 'bg-text-muted/15 text-text-muted']">
              {{ h.strength }}
            </span>
            <span class="font-mono">来自 {{ h.fromScriptId }}</span>
            <TimeText :value="h.dismissedAt!" relative class="ml-auto" />
          </div>
          <div class="text-sm text-text-secondary line-clamp-2 leading-relaxed">
            {{ h.nextEpisodeHint || h.hookText }}
          </div>
          <div v-if="h.suggestedTitle" class="text-xs text-text-muted mt-1 truncate">
            建议标题:{{ h.suggestedTitle }}
          </div>
          <div class="mt-2 flex justify-end">
            <button
              class="chip bg-accent/15 text-accent hover:bg-accent/25 text-xs px-3 py-1"
              :disabled="restoringId === h.id"
              @click="onRestore(h.id)"
            >
              <Loader2 v-if="restoringId === h.id" :size="12" class="animate-spin" />
              <RotateCcw v-else :size="12" />
              恢复
            </button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
