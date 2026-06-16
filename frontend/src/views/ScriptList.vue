<script setup lang="ts">
import { computed, ref } from 'vue'
import { ArrowLeft, ArrowUpDown, FileText, Loader2, Search, Trash2 } from 'lucide-vue-next'
import { useRecentScripts, type ScriptHandle } from '../composables/useRecentScripts'
import { useListFilter } from '../composables/useListFilter'
import { deleteScript } from '../api/scripts'
import ErrorBanner from '../components/ErrorBanner.vue'
import StatusChip from '../components/StatusChip.vue'
import TimeText from '../components/TimeText.vue'
import { STAGE_LABELS, type PipelineRunStatus } from '../types'

const { items, loading, errorMsg } = useRecentScripts()
const deleting = ref<Record<number, boolean>>({})
const deleteError = ref<string | null>(null)

async function onDelete(scriptId: number, projectName: string | null | undefined) {
  if (deleting.value[scriptId]) return
  if (!confirm(`确定删除脚本 #${scriptId}「${projectName || '未命名'}」?\n会连带删除分段/分镜/图片/配音/视频/封面,不可恢复。`)) return
  deleting.value = { ...deleting.value, [scriptId]: true }
  deleteError.value = null
  try {
    await deleteScript(scriptId)
    const idx = items.value.findIndex((x) => x.scriptId === scriptId)
    if (idx >= 0) items.value.splice(idx, 1)
  } catch (e: any) {
    deleteError.value = e?.response?.data?.message || e?.message || '删除失败'
  } finally {
    deleting.value = { ...deleting.value, [scriptId]: false }
  }
}

type Tab = 'all' | 'running' | 'done' | 'failed'
const tab = ref<Tab>('all')

const SORT_LABELS = {
  lastRunAt: '按最近活跃',
  scriptId: '按脚本 ID',
  projectName: '按项目名',
} as const

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

// useListFilter 只管搜索 + 排序 —— Tab 过滤先在 source 里做
const tabFiltered = computed(() => items.value.filter((h) => inTab(h.lastRunStatus)))

const { keyword: search, sortKey: sortBy, filtered } = useListFilter<ScriptHandle>({
  source: tabFiltered,
  searchableText: (h) => [
    String(h.scriptId),
    h.projectName ?? '',
    h.topicId != null ? String(h.topicId) : '',
    STAGE_LABELS[h.lastStage] ?? '',
  ],
  sortOptions: {
    lastRunAt: (a, b) => new Date(b.lastRunAt).getTime() - new Date(a.lastRunAt).getTime(),
    scriptId: (a, b) => b.scriptId - a.scriptId,
    projectName: (a, b) => (a.projectName ?? '').localeCompare(b.projectName ?? '', 'zh-CN'),
  },
  defaultSort: 'lastRunAt',
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
            <FileText :size="16" class="text-accent" /> 脚本工作台
          </h1>
          <span class="chip text-[11px] bg-surface-tertiary text-text-muted">{{ items.length }} 个脚本</span>
          <Loader2 v-if="loading" :size="14" class="animate-spin text-text-muted ml-auto" />
        </div>
        <div class="text-xs text-text-muted">所有脚本按最近活跃时间排序 · 每 15 秒自动刷新</div>
      </div>
    </div>

    <div class="px-8 py-5 max-w-[1400px] mx-auto">
      <ErrorBanner :msg="errorMsg" />

      <div class="card p-3 mb-4">
        <div class="flex items-center gap-1 border-b border-border-subtle -mx-3 px-3 -mt-3 pt-1 mb-3 flex-wrap">
          <button
            v-for="t in TABS" :key="t.key"
            class="flex items-center gap-2 px-3 py-2.5 text-xs font-medium border-b-2 transition-colors"
            :class="tab === t.key
              ? 'text-accent border-accent font-semibold'
              : 'text-text-secondary border-transparent hover:text-text-primary'"
            @click="tab = t.key"
          >
            {{ t.label }}
            <span class="text-[10px] text-text-muted font-normal">{{ tabCounts[t.key] }}</span>
          </button>
        </div>
        <div class="flex items-center gap-2 flex-wrap">
          <label class="flex items-center gap-1.5 bg-surface-tertiary border border-border-subtle rounded px-2 py-1.5">
            <Search :size="13" class="text-text-muted" />
            <input v-model="search" placeholder="搜索脚本 ID / 项目名 / 阶段"
                   class="bg-transparent outline-none text-xs w-56" />
          </label>
          <label class="flex items-center gap-1.5 bg-surface-tertiary border border-border-subtle rounded px-2 py-1.5 ml-auto">
            <ArrowUpDown :size="13" class="text-text-muted" />
            <select v-model="sortBy" class="bg-transparent outline-none text-xs cursor-pointer">
              <option v-for="(label, key) in SORT_LABELS" :key="key" :value="key">{{ label }}</option>
            </select>
          </label>
        </div>
      </div>

      <div class="card overflow-hidden">
        <div v-if="deleteError" class="px-4 py-2 bg-status-failed/10 text-status-failed text-xs border-b border-status-failed/30">
          {{ deleteError }}
        </div>
        <table class="w-full text-sm">
          <thead class="bg-surface-tertiary text-xs uppercase text-text-muted">
            <tr>
              <th class="text-left px-4 py-3 font-medium">脚本</th>
              <th class="text-left px-4 py-3 font-medium w-[100px]">topic</th>
              <th class="text-left px-4 py-3 font-medium w-[160px]">最近阶段</th>
              <th class="text-left px-4 py-3 font-medium w-[120px]">状态</th>
              <th class="text-left px-4 py-3 font-medium w-[140px]">时间</th>
              <th class="text-right px-4 py-3 font-medium w-[80px]">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="h in filtered" :key="h.scriptId"
              class="border-t border-border-subtle hover:bg-surface-tertiary/40 cursor-pointer"
              @click="$router.push(`/scripts/${h.scriptId}`)"
            >
              <td class="px-4 py-3">
                <div class="flex items-center gap-2">
                  <FileText :size="14" class="text-text-muted shrink-0" />
                  <span class="font-mono text-text-secondary">{{ h.scriptId }}</span>
                  <span class="text-text-primary truncate">{{ h.projectName || '—' }}</span>
                </div>
              </td>
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
                <div class="text-xs text-text-muted">{{ h.lastStage }}</div>
              </td>
              <td class="px-4 py-3">
                <StatusChip :status="h.lastRunStatus" />
              </td>
              <td class="px-4 py-3"><TimeText :value="h.lastRunAt" relative /></td>
              <td class="px-4 py-3 text-right">
                <button
                  class="inline-flex items-center justify-center w-7 h-7 rounded text-text-muted hover:text-status-failed hover:bg-status-failed/10 disabled:opacity-50 disabled:cursor-not-allowed"
                  :title="`删除脚本 #${h.scriptId}`"
                  :disabled="!!deleting[h.scriptId]"
                  @click.stop="onDelete(h.scriptId, h.projectName)"
                >
                  <Loader2 v-if="deleting[h.scriptId]" :size="14" class="animate-spin" />
                  <Trash2 v-else :size="14" />
                </button>
              </td>
            </tr>
            <tr v-if="!loading && filtered.length === 0">
              <td colspan="6" class="text-center py-12 text-text-muted text-sm">
                <span v-if="search.trim()">没有匹配「{{ search }}」的脚本</span>
                <span v-else-if="tab !== 'all'">该状态下暂无脚本</span>
                <span v-else>还没有脚本</span>
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
