<script setup lang="ts">
// 共用组件 —— 选一个脚本进入下游详情页
// StoryboardList / ImageList / FactCheckList / VideoAssemblyList 都靠它
import { computed, ref } from 'vue'
import { ArrowLeft, Layers, Loader2, Search } from 'lucide-vue-next'
import { useRecentScripts } from '../composables/useRecentScripts'
import ErrorBanner from './ErrorBanner.vue'
import StatusChip from './StatusChip.vue'
import TimeText from './TimeText.vue'
import { STAGE_LABELS, type PipelineRunStatus } from '../types'

const props = defineProps<{
  title: string
  subtitle?: string
  /** 点击行跳转到 `${routePrefix}/${scriptId}` */
  routePrefix: string
  /** 顶部下方的提示文案,可选 */
  hint?: string
  /** 表格无数据时的文案,默认「还没有脚本相关的 pipeline run」 */
  emptyText?: string
}>()

const { items, loading, errorMsg } = useRecentScripts()

type Tab = 'all' | 'running' | 'done' | 'failed'
const tab = ref<Tab>('all')
const search = ref('')

function inTab(status: PipelineRunStatus): boolean {
  if (tab.value === 'all') return true
  if (tab.value === 'running') return status === 'PENDING' || status === 'RUNNING'
  if (tab.value === 'done') return status === 'DONE'
  return status === 'FAILED' || status === 'CANCELLED'
}

const tabCounts = computed(() => {
  const c = { all: items.value.length, running: 0, done: 0, failed: 0 }
  for (const it of items.value) {
    if (it.lastRunStatus === 'PENDING' || it.lastRunStatus === 'RUNNING') c.running++
    else if (it.lastRunStatus === 'DONE') c.done++
    else if (it.lastRunStatus === 'FAILED' || it.lastRunStatus === 'CANCELLED') c.failed++
  }
  return c
})

const filtered = computed(() => {
  const q = search.value.trim().toLowerCase()
  let arr = items.value.filter((h) => inTab(h.lastRunStatus))
  if (q) {
    arr = arr.filter((h) =>
      String(h.scriptId).includes(q)
      || (h.projectName?.toLowerCase().includes(q))
      || (h.topicId != null && String(h.topicId).includes(q)),
    )
  }
  return [...arr].sort((a, b) => new Date(b.lastRunAt).getTime() - new Date(a.lastRunAt).getTime())
})

const TABS: { key: Tab; label: string }[] = [
  { key: 'all', label: '全部' },
  { key: 'running', label: '进行中' },
  { key: 'done', label: '已完成' },
  { key: 'failed', label: '失败/取消' },
]
</script>

<template>
  <div class="min-h-full">
    <!-- sticky 顶栏 -->
    <div class="sticky top-0 z-10 bg-surface-primary border-b border-border-subtle">
      <div class="px-8 py-3 max-w-[1400px] mx-auto flex flex-col gap-1">
        <div class="flex items-center gap-4 flex-wrap">
          <button
            class="flex items-center gap-1.5 text-sm text-text-muted hover:text-text-primary"
            @click="$router.push('/')"
          >
            <ArrowLeft :size="14" /> 首页
          </button>
          <h1 class="text-lg font-semibold flex items-center gap-2">
            <Layers :size="16" class="text-accent" /> {{ title }}
          </h1>
          <span class="chip text-[11px] bg-surface-tertiary text-text-muted">{{ items.length }} 个脚本</span>
          <Loader2 v-if="loading" :size="14" class="animate-spin text-text-muted ml-auto" />
        </div>
        <div v-if="subtitle" class="text-xs text-text-muted">{{ subtitle }}</div>
      </div>
    </div>

    <div class="px-8 py-5 max-w-[1400px] mx-auto">
      <ErrorBanner :msg="errorMsg" />

      <div class="card p-3 mb-4 flex items-center gap-3 flex-wrap">
        <span class="text-xs text-text-muted">状态</span>
        <div class="flex items-center gap-1.5 flex-wrap">
          <button
            v-for="t in TABS" :key="t.key"
            class="chip text-xs cursor-pointer transition-colors"
            :class="tab === t.key
              ? 'bg-accent-soft text-accent font-semibold'
              : 'bg-surface-tertiary text-text-secondary hover:text-text-primary'"
            @click="tab = t.key"
          >
            {{ t.label }} {{ tabCounts[t.key] }}
          </button>
        </div>
        <label class="flex items-center gap-1.5 bg-surface-tertiary border border-border-subtle rounded px-2 py-1.5 ml-auto">
          <Search :size="13" class="text-text-muted" />
          <input v-model="search" placeholder="搜索脚本 / 项目"
                 class="bg-transparent outline-none text-xs w-48" />
        </label>
      </div>

      <p v-if="hint" class="text-xs text-text-muted mb-3 px-1">{{ hint }}</p>

      <div class="card overflow-hidden">
        <table class="w-full text-sm">
          <thead class="bg-surface-tertiary text-xs uppercase text-text-muted">
            <tr>
              <th class="text-left px-4 py-3 font-medium w-[120px]">脚本</th>
              <th class="text-left px-4 py-3 font-medium">项目名</th>
              <th class="text-left px-4 py-3 font-medium w-[100px]">topic</th>
              <th class="text-left px-4 py-3 font-medium w-[140px]">最近阶段</th>
              <th class="text-left px-4 py-3 font-medium w-[120px]">状态</th>
              <th class="text-left px-4 py-3 font-medium w-[140px]">最近更新</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="h in filtered" :key="h.scriptId"
              class="border-t border-border-subtle hover:bg-surface-tertiary/40 cursor-pointer"
              @click="$router.push(`${routePrefix}/${h.scriptId}`)"
            >
              <td class="px-4 py-3 font-mono text-text-secondary">#{{ h.scriptId }}</td>
              <td class="px-4 py-3 truncate text-text-primary">{{ h.projectName || '—' }}</td>
              <td class="px-4 py-3 font-mono text-text-muted">
                <a v-if="h.topicId"
                   class="hover:text-accent hover:underline cursor-pointer"
                   @click.stop="$router.push(`/topics/${h.topicId}`)">
                  {{ h.topicId }}
                </a>
                <span v-else>—</span>
              </td>
              <td class="px-4 py-3">
                <span class="text-text-primary">{{ STAGE_LABELS[h.lastStage] }}</span>
                <div class="text-[10px] text-text-muted">{{ h.lastStage }}</div>
              </td>
              <td class="px-4 py-3">
                <StatusChip :status="h.lastRunStatus" />
              </td>
              <td class="px-4 py-3 text-text-muted"><TimeText :value="h.lastRunAt" relative /></td>
            </tr>
            <tr v-if="!loading && filtered.length === 0">
              <td colspan="6" class="text-center py-12 text-text-muted text-sm">
                <span v-if="search.trim()">没有匹配「{{ search }}」的脚本</span>
                <span v-else-if="tab !== 'all'">该状态下暂无脚本</span>
                <span v-else>{{ emptyText ?? '还没有脚本相关的 pipeline run' }}</span>
              </td>
            </tr>
            <tr v-if="loading && items.length === 0">
              <td colspan="6" class="text-center py-12 text-text-muted">
                <Loader2 :size="18" class="animate-spin mx-auto" />
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <p class="text-xs text-text-muted mt-3">
        共 {{ filtered.length }} / {{ items.length }} 条
      </p>
    </div>
  </div>
</template>
