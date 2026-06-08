<script setup lang="ts">
// 封面工作台 —— sticky 顶栏 + script picker + 嵌入 CoverDesigner
import { computed, ref } from 'vue'
import { ArrowLeft, Loader2, Wand2 } from 'lucide-vue-next'
import { useRecentScripts } from '../composables/useRecentScripts'
import ErrorBanner from '../components/ErrorBanner.vue'
import TimeText from '../components/TimeText.vue'
import CoverDesigner from './CoverDesigner.vue'

const { items, loading, errorMsg } = useRecentScripts()

const selectedScriptId = ref<number | null>(null)
const selected = computed(() => items.value.find((s) => s.scriptId === selectedScriptId.value) ?? null)
</script>

<template>
  <div class="min-h-full">
    <div class="sticky top-0 z-10 bg-surface-primary border-b border-border-subtle">
      <div class="px-8 py-3 max-w-[1400px] mx-auto flex flex-col gap-1">
        <div class="flex items-center gap-4 flex-wrap">
          <button
            class="flex items-center gap-1.5 text-sm text-text-muted hover:text-text-primary"
            @click="$router.push('/')"
          >
            <ArrowLeft :size="14" /> 首页
          </button>
          <h1 class="text-lg font-semibold">封面工作台</h1>
          <span v-if="selected" class="chip text-[11px] bg-accent-soft text-accent">
            #{{ selected.scriptId }}{{ selected.projectName ? ' · ' + selected.projectName : '' }}
          </span>
        </div>
        <div class="text-xs text-text-muted">
          点一下出 3 张封面 · 1080×1440 / 1440×1080 / 1920×1080 · 后端 Java2D 渲染
        </div>
      </div>
    </div>

    <div class="px-8 py-5 max-w-[1400px] mx-auto">
      <ErrorBanner :msg="errorMsg" />

      <div class="card p-3 mb-4 text-xs text-text-muted">
        ℹ️ 封面落 <code class="font-mono">./storage/cover/</code>,通过 <code class="font-mono">/api/files/cover/</code> 暴露。
        品牌色板 / 字体在 <a class="text-accent underline cursor-pointer" @click="$router.push('/brand')">/brand</a> 全局维护。
      </div>

      <div class="grid grid-cols-1 lg:grid-cols-[280px_1fr] gap-4">
        <aside class="card p-3 max-h-[680px] overflow-y-auto">
          <div class="text-xs text-text-muted px-2 py-1.5">选 Script</div>
          <div v-if="loading && items.length === 0" class="py-8 text-center">
            <Loader2 :size="18" class="animate-spin text-text-muted mx-auto" />
          </div>
          <div v-else-if="items.length === 0" class="py-8 text-center text-xs text-text-muted">
            没有可用脚本
          </div>
          <ul v-else class="space-y-0.5">
            <li
              v-for="h in items" :key="h.scriptId"
              :class="['flex items-center justify-between px-2 py-2 rounded text-sm cursor-pointer transition-colors gap-2',
                       selectedScriptId === h.scriptId
                         ? 'bg-accent-soft text-accent'
                         : 'hover:bg-surface-tertiary text-text-secondary']"
              @click="selectedScriptId = h.scriptId"
            >
              <span class="min-w-0 truncate">
                <span class="font-mono">#{{ h.scriptId }}</span>
                <span v-if="h.projectName" class="ml-1.5">{{ h.projectName }}</span>
              </span>
              <TimeText :value="h.lastRunAt" relative class="text-xs text-text-muted shrink-0" />
            </li>
          </ul>
        </aside>

        <div>
          <div v-if="!selected" class="card p-12 text-center text-text-muted text-sm">
            <Wand2 :size="24" class="mx-auto mb-2 opacity-50" />
            左侧选一个 script 开始设计封面
          </div>
          <CoverDesigner
            v-else
            :key="selected.scriptId"
            :scriptId="selected.scriptId"
            :embedded="true"
          />
        </div>
      </div>
    </div>
  </div>
</template>
