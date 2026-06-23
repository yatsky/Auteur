<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Sparkles, X } from 'lucide-vue-next'
import { promoteHotItem } from '../../api/hotpool'
import { extractError } from '../../lib/format'
import type { Preset } from '../../api/presets'
import type { HotItem, HotSource } from '../../types'

const props = defineProps<{
  open: boolean
  items: HotItem[]
  sources: HotSource[]
  presets: Preset[]
}>()

const emit = defineEmits<{
  (e: 'close'): void
  (e: 'done', topicIds: number[]): void
}>()

const presetId = ref<number | null>(null)
const busy = ref(false)
const error = ref<string | null>(null)
const completed = ref(0)

watch(
  () => props.open,
  (op) => {
    if (op) {
      error.value = null
      completed.value = 0
      if (presetId.value == null && props.presets.length > 0) presetId.value = props.presets[0].id
    }
  },
)

function sourceName(sourceId: number) {
  return props.sources.find((s) => s.id === sourceId)?.name ?? `源 ${sourceId}`
}

const canSubmit = computed(() => props.items.length > 0 && presetId.value != null && !busy.value)

async function submit() {
  if (presetId.value == null) return
  busy.value = true
  error.value = null
  const topicIds: number[] = []
  try {
    for (const it of props.items) {
      const topic = await promoteHotItem(it.id, presetId.value)
      topicIds.push(topic.id)
      completed.value = topicIds.length
    }
    emit('done', topicIds)
  } catch (e) {
    error.value = extractError(e, '批量送选题失败')
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <Teleport to="body">
    <Transition name="modal">
      <div
        v-if="open"
        class="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4"
        @click.self="emit('close')"
      >
        <div class="card bg-surface-secondary w-[560px] max-w-full max-h-[85vh] flex flex-col">
          <!-- 顶部 -->
          <div class="p-5 border-b border-border-subtle flex items-center justify-between gap-3">
            <div class="flex items-center gap-3 min-w-0">
              <div class="w-10 h-10 rounded-lg bg-accent-soft flex items-center justify-center shrink-0">
                <Sparkles :size="18" class="text-accent" />
              </div>
              <div class="min-w-0">
                <h2 class="text-base font-semibold text-text-primary">批量送入选题</h2>
                <div class="text-xs text-text-muted">
                  将创建 {{ items.length }} 条 DRAFT 状态的 topic
                </div>
              </div>
            </div>
            <button class="btn-icon" @click="emit('close')" :disabled="busy"><X :size="16" /></button>
          </div>

          <!-- 目标预设 -->
          <div class="px-5 py-3 bg-surface-tertiary/40 border-b border-border-subtle">
            <div class="flex items-center gap-3">
              <span class="text-xs font-medium text-text-secondary shrink-0">目标预设</span>
              <select
                v-model="presetId"
                :disabled="busy"
                class="flex-1 bg-surface-primary border border-border rounded-md px-3 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-accent/30 focus:border-accent disabled:opacity-50"
              >
                <option v-for="p in presets" :key="p.id" :value="p.id">
                  {{ p.displayName || p.name }}
                </option>
              </select>
            </div>
          </div>

          <!-- 候选条目列表 -->
          <div class="flex-1 overflow-y-auto p-4 space-y-2">
            <div class="text-[11px] font-semibold text-text-muted tracking-wide mb-1 px-1">
              将创建以下选题
            </div>
            <div
              v-for="(it, idx) in items"
              :key="it.id"
              class="card p-3 flex items-start gap-3"
            >
              <div
                class="w-6 h-6 rounded-full flex items-center justify-center text-xs font-semibold shrink-0"
                :class="completed > idx
                  ? 'bg-status-done text-white'
                  : 'bg-accent-soft text-accent'"
              >{{ idx + 1 }}</div>
              <div class="flex-1 min-w-0">
                <div class="text-sm font-semibold text-text-primary leading-snug line-clamp-2">
                  {{ it.title }}
                </div>
                <div class="mt-1 flex items-center gap-2 text-[10px] text-text-muted">
                  <span class="chip bg-accent-soft text-accent text-[10px]">{{ sourceName(it.sourceId) }}</span>
                  <span>· 匹配度 {{ it.popularity.toFixed(2) }}</span>
                </div>
              </div>
            </div>
          </div>

          <!-- 错误 -->
          <div v-if="error" class="px-5 pb-4">
            <div
              class="text-xs text-status-failed bg-status-failed/10 border border-status-failed/30 rounded-md px-3 py-2"
            >{{ error }}</div>
          </div>

          <!-- 底栏 -->
          <div class="px-5 py-4 border-t border-border-subtle flex items-center justify-end gap-2">
            <button class="btn-ghost text-sm" :disabled="busy" @click="emit('close')">取消</button>
            <button class="btn-primary text-sm" :disabled="!canSubmit" @click="submit">
              <Sparkles :size="14" />
              {{ busy ? `处理中… ${completed}/${items.length}` : `确认送入 (${items.length})` }}
            </button>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.modal-enter-active,
.modal-leave-active {
  transition: opacity 0.15s ease;
}
.modal-enter-from,
.modal-leave-to {
  opacity: 0;
}
</style>
