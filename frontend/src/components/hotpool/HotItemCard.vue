<script setup lang="ts">
import { computed } from 'vue'
import { Check, ExternalLink, Sparkles } from 'lucide-vue-next'
import TimeText from '../TimeText.vue'
import type { HotItem, HotSource } from '../../types'

const props = defineProps<{
  item: HotItem
  source: HotSource | undefined
  selected: boolean
}>()

const emit = defineEmits<{
  (e: 'toggle-select', id: number): void
  (e: 'open-detail', item: HotItem): void
  (e: 'promote', item: HotItem): void
}>()

const tags = computed<string[]>(() => {
  if (!props.item.tagsJson) return []
  try {
    const arr = JSON.parse(props.item.tagsJson)
    return Array.isArray(arr) ? arr : []
  } catch {
    return []
  }
})

const isPromoted = computed(() => props.item.status === 'promoted')

const popPct = computed(() => Math.round(Math.min(1, Math.max(0, props.item.popularity)) * 100))
const popColor = computed(() => {
  if (props.item.popularity >= 0.85) return 'bg-status-done'
  if (props.item.popularity >= 0.6) return 'bg-accent'
  return 'bg-text-muted'
})
</script>

<template>
  <div
    class="card p-4 flex flex-col gap-3 transition-colors"
    :class="[
      selected ? 'border-accent ring-1 ring-accent/30' : 'hover:border-border',
      isPromoted ? 'opacity-60' : '',
    ]"
  >
    <!-- 顶栏:来源徽标 + 时间 + 勾选 / 已送选题徽标 -->
    <div class="flex items-center justify-between gap-2">
      <div class="flex items-center gap-2 min-w-0">
        <span class="chip bg-accent-soft text-accent text-[10px]" v-if="source">{{ source.name }}</span>
        <span class="text-[11px] text-text-muted">
          ·
          <TimeText :value="item.publishedAt ?? item.fetchedAt" />
        </span>
      </div>
      <span
        v-if="isPromoted"
        class="chip bg-status-done/15 text-status-done text-[10px]"
        title="已送选题"
      >
        <Check :size="11" />已送选题
      </span>
      <button
        v-else
        class="w-4 h-4 rounded border flex items-center justify-center transition-colors"
        :class="selected
          ? 'bg-accent border-accent text-white'
          : 'bg-surface-primary border-border text-transparent hover:border-text-muted'"
        @click="emit('toggle-select', item.id)"
        :title="selected ? '取消选中' : '勾选'"
      >
        <Check :size="11" />
      </button>
    </div>

    <!-- 标题(可点开详情) -->
    <h3
      class="text-sm font-semibold text-text-primary leading-snug line-clamp-2 cursor-pointer hover:text-accent"
      @click="emit('open-detail', item)"
    >
      {{ item.title }}
    </h3>

    <!-- 摘要 -->
    <p
      v-if="item.summary"
      class="text-xs text-text-secondary leading-relaxed line-clamp-2"
    >{{ item.summary }}</p>

    <!-- 标签 -->
    <div v-if="tags.length > 0" class="flex flex-wrap gap-1">
      <span
        v-for="t in tags.slice(0, 4)"
        :key="t"
        class="chip bg-surface-tertiary text-text-secondary text-[10px]"
      >{{ t }}</span>
    </div>

    <!-- 底栏:热度条 + CTA -->
    <div class="flex items-center justify-between gap-3 pt-1 mt-auto">
      <div class="flex-1 min-w-0">
        <div class="flex items-center justify-between text-[10px] text-text-muted mb-1">
          <span>热度</span>
          <span class="font-semibold" :class="item.popularity >= 0.85 ? 'text-status-done' : 'text-text-primary'">
            {{ item.popularity.toFixed(2) }}
          </span>
        </div>
        <div class="h-1 rounded-full bg-surface-tertiary overflow-hidden">
          <div class="h-full" :class="popColor" :style="{ width: popPct + '%' }"></div>
        </div>
      </div>
      <button
        v-if="!isPromoted"
        class="btn-primary text-xs px-2.5 py-1"
        @click.stop="emit('promote', item)"
      >
        <Sparkles :size="13" />送选题
      </button>
      <a
        v-else-if="item.promotedTopicId"
        class="btn-ghost text-xs px-2.5 py-1"
        :href="`/topics/${item.promotedTopicId}`"
      >
        <ExternalLink :size="13" />查看选题
      </a>
    </div>
  </div>
</template>
