<script setup lang="ts">
import { computed, ref } from 'vue'
import { ChevronDown, ChevronUp, Link2, X } from 'lucide-vue-next'
import type { SeriesHook } from '../types'

const props = defineProps<{ hooks: SeriesHook[] }>()
const emit = defineEmits<{
  (e: 'dismiss', id: number): void
  (e: 'fulfill', hook: SeriesHook): void
}>()

const PREVIEW = 3
const expandedStrong = ref(false)
const expandedWeak = ref(false)

const strong = computed(() => props.hooks.filter((h) => h.strength === 'STRONG'))
const weak = computed(() => props.hooks.filter((h) => h.strength === 'WEAK'))

const visibleStrong = computed(() =>
  expandedStrong.value ? strong.value : strong.value.slice(0, PREVIEW),
)
const hiddenStrongCount = computed(() => Math.max(0, strong.value.length - PREVIEW))
</script>

<template>
  <div v-if="hooks.length > 0" class="card p-4 mb-4 border-accent/30 bg-accent-soft/40">
    <div class="flex items-center gap-2 mb-3">
      <Link2 :size="16" class="text-accent" />
      <span class="text-sm font-medium text-text-primary">
        待还钩子
      </span>
      <span class="text-xs text-text-muted">
        来自 {{ hooks.length }} 篇上集 E 段。点「填坑」用 LLM 建议建下一集 topic
      </span>
    </div>

    <ul v-if="strong.length > 0" class="space-y-2">
      <li
        v-for="h in visibleStrong" :key="h.id"
        class="card p-3 bg-surface-base/60 flex items-start gap-3"
      >
        <div class="flex-1 min-w-0">
          <div class="flex items-center gap-2 text-xs text-text-muted mb-1">
            <span class="chip bg-accent/15 text-accent text-[10px] px-1.5 py-0">STRONG</span>
            <span class="font-mono">来自 {{ h.fromScriptId }}</span>
            <span v-if="h.suggestedDynasty" class="chip bg-surface-tertiary text-text-secondary">
              {{ h.suggestedDynasty }}
            </span>
          </div>
          <div class="text-sm text-text-primary truncate">
            {{ h.nextEpisodeHint || h.hookText }}
          </div>
          <div v-if="h.suggestedTitle" class="text-xs text-text-muted mt-1 truncate">
            建议标题:{{ h.suggestedTitle }}
          </div>
        </div>
        <div class="flex flex-col gap-1.5 shrink-0">
          <button
            class="btn-primary text-xs px-3 py-1"
            @click="emit('fulfill', h)"
          >
            填坑
          </button>
          <button
            class="chip bg-surface-tertiary text-text-muted hover:text-text-primary text-xs px-3 py-1"
            @click="emit('dismiss', h.id)"
          >
            <X :size="12" /> 忽略
          </button>
        </div>
      </li>
    </ul>

    <button
      v-if="hiddenStrongCount > 0"
      class="mt-3 text-xs text-text-muted hover:text-text-primary flex items-center gap-1"
      @click="expandedStrong = !expandedStrong"
    >
      <template v-if="!expandedStrong">
        <ChevronDown :size="12" /> STRONG 还有 {{ hiddenStrongCount }} 条 · 展开
      </template>
      <template v-else>
        <ChevronUp :size="12" /> 收起
      </template>
    </button>

    <div v-if="weak.length > 0" class="mt-3 pt-3 border-t border-border-subtle">
      <button
        class="text-xs text-text-muted hover:text-text-primary flex items-center gap-1.5"
        @click="expandedWeak = !expandedWeak"
      >
        <ChevronDown v-if="!expandedWeak" :size="12" />
        <ChevronUp v-else :size="12" />
        WEAK 钩子 {{ weak.length }} 条
        <span class="text-text-muted/70">(信号弱,可能不值得追,自行判断)</span>
      </button>
      <ul v-if="expandedWeak" class="space-y-2 mt-2 opacity-75">
        <li
          v-for="h in weak" :key="h.id"
          class="card p-3 bg-surface-base/40 flex items-start gap-3"
        >
          <div class="flex-1 min-w-0">
            <div class="flex items-center gap-2 text-xs text-text-muted mb-1">
              <span class="chip bg-text-muted/15 text-text-muted text-[10px] px-1.5 py-0">WEAK</span>
              <span class="font-mono">来自 {{ h.fromScriptId }}</span>
              <span v-if="h.suggestedDynasty" class="chip bg-surface-tertiary text-text-secondary">
                {{ h.suggestedDynasty }}
              </span>
            </div>
            <div class="text-sm text-text-secondary truncate">
              {{ h.nextEpisodeHint || h.hookText }}
            </div>
            <div v-if="h.suggestedTitle" class="text-xs text-text-muted mt-1 truncate">
              建议标题:{{ h.suggestedTitle }}
            </div>
          </div>
          <div class="flex flex-col gap-1.5 shrink-0">
            <button
              class="chip bg-surface-tertiary text-text-secondary hover:text-text-primary text-xs px-3 py-1"
              @click="emit('fulfill', h)"
            >
              填坑
            </button>
            <button
              class="chip bg-surface-tertiary text-text-muted hover:text-text-primary text-xs px-3 py-1"
              @click="emit('dismiss', h.id)"
            >
              <X :size="12" /> 忽略
            </button>
          </div>
        </li>
      </ul>
    </div>
  </div>
</template>
