<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ExternalLink, Flame, Sparkles, Target, X } from 'lucide-vue-next'
import TimeText from '../TimeText.vue'
import { extractError } from '../../lib/format'
import { dismissHotItem, promoteHotItem } from '../../api/hotpool'
import type { Preset } from '../../api/presets'
import type { HotItem, HotSource } from '../../types'

const props = defineProps<{
  open: boolean
  item: HotItem | null
  source: HotSource | undefined
  presets: Preset[]
}>()

const emit = defineEmits<{
  (e: 'close'): void
  (e: 'promoted', item: HotItem, topicId: number): void
  (e: 'dismissed', item: HotItem): void
}>()

const presetId = ref<number | null>(null)
const busy = ref(false)
const error = ref<string | null>(null)

watch(
  () => props.item,
  (it) => {
    error.value = null
    if (it && it.status === 'new' && props.presets.length > 0 && presetId.value == null) {
      presetId.value = props.presets[0].id
    }
  },
  { immediate: true },
)

const tags = computed<string[]>(() => {
  if (!props.item?.tagsJson) return []
  try {
    const arr = JSON.parse(props.item.tagsJson)
    return Array.isArray(arr) ? arr : []
  } catch {
    return []
  }
})

const canPromote = computed(
  () => !!props.item && props.item.status === 'new' && presetId.value != null,
)

async function doPromote() {
  if (!props.item || presetId.value == null) return
  busy.value = true
  error.value = null
  try {
    const topic = await promoteHotItem(props.item.id, presetId.value)
    emit('promoted', props.item, topic.id)
  } catch (e) {
    error.value = extractError(e, '送选题失败')
  } finally {
    busy.value = false
  }
}

async function doDismiss() {
  if (!props.item) return
  busy.value = true
  error.value = null
  try {
    await dismissHotItem(props.item.id)
    emit('dismissed', props.item)
  } catch (e) {
    error.value = extractError(e, '忽略失败')
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <Teleport to="body">
    <Transition name="drawer">
      <div
        v-if="open && item"
        class="fixed inset-0 z-50 flex justify-end bg-black/40"
        @click.self="emit('close')"
      >
        <div
          class="w-[520px] max-w-full h-full bg-surface-secondary border-l border-border-subtle flex flex-col"
        >
          <!-- 顶部 -->
          <div class="p-5 border-b border-border-subtle">
            <div class="flex items-start justify-between gap-2">
              <div class="flex-1 min-w-0">
                <div class="flex items-center gap-2 mb-2 text-[11px] text-text-muted">
                  <span v-if="source" class="chip bg-accent-soft text-accent text-[10px]">
                    {{ source.name }}
                  </span>
                  <span>· <TimeText :value="item.publishedAt ?? item.fetchedAt" /></span>
                  <a
                    v-if="item.url"
                    :href="item.url"
                    target="_blank"
                    class="flex items-center gap-1 hover:text-accent"
                  >
                    <ExternalLink :size="11" />
                    原文
                  </a>
                </div>
                <h2 class="text-lg font-semibold text-text-primary leading-snug">
                  {{ item.title }}
                </h2>
              </div>
              <button class="btn-icon" @click="emit('close')" title="关闭">
                <X :size="16" />
              </button>
            </div>
          </div>

          <!-- 内容 -->
          <div class="flex-1 overflow-y-auto p-5 space-y-5">
            <!-- 指标卡 -->
            <div class="grid grid-cols-2 gap-3">
              <div class="card p-3 bg-status-done/5">
                <div class="flex items-center gap-1.5 text-[10px] text-status-done font-medium mb-1">
                  <Flame :size="12" /> 热度
                </div>
                <div class="text-xl font-bold text-status-done">{{ item.popularity.toFixed(2) }}</div>
              </div>
              <div class="card p-3 bg-accent/5">
                <div class="flex items-center gap-1.5 text-[10px] text-accent font-medium mb-1">
                  <Target :size="12" /> 状态
                </div>
                <div class="text-xl font-bold text-accent">
                  {{ item.status === 'new' ? '待处理' : item.status === 'promoted' ? '已送选题' : '已忽略' }}
                </div>
              </div>
            </div>

            <!-- 摘要 -->
            <div v-if="item.summary">
              <div class="text-[11px] font-semibold text-text-muted tracking-wide mb-2">原文摘要</div>
              <p class="text-sm text-text-primary leading-relaxed whitespace-pre-line">{{ item.summary }}</p>
            </div>

            <!-- 标签 -->
            <div v-if="tags.length > 0">
              <div class="text-[11px] font-semibold text-text-muted tracking-wide mb-2">标签</div>
              <div class="flex flex-wrap gap-1.5">
                <span
                  v-for="t in tags"
                  :key="t"
                  class="chip bg-accent-soft text-accent"
                >{{ t }}</span>
              </div>
            </div>

            <!-- 跳转预设 -->
            <div v-if="item.status === 'new'">
              <div class="text-[11px] font-semibold text-text-muted tracking-wide mb-2">送入哪个预设</div>
              <select
                v-model="presetId"
                class="w-full bg-surface-primary border border-border rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-accent/30 focus:border-accent"
              >
                <option v-for="p in presets" :key="p.id" :value="p.id">
                  {{ p.displayName || p.name }}
                </option>
              </select>
            </div>

            <div
              v-if="error"
              class="text-xs text-status-failed bg-status-failed/10 border border-status-failed/30 rounded-md px-3 py-2"
            >{{ error }}</div>
          </div>

          <!-- 底栏 -->
          <div
            v-if="item.status === 'new'"
            class="p-4 border-t border-border-subtle flex items-center justify-between gap-2"
          >
            <button class="btn-ghost text-sm" :disabled="busy" @click="doDismiss">忽略</button>
            <button class="btn-primary text-sm" :disabled="!canPromote || busy" @click="doPromote">
              <Sparkles :size="14" />
              {{ busy ? '处理中…' : '送入选题' }}
            </button>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.drawer-enter-active,
.drawer-leave-active {
  transition: opacity 0.15s ease;
}
.drawer-enter-from,
.drawer-leave-to {
  opacity: 0;
}
</style>
