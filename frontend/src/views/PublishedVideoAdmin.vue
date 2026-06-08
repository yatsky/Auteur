<script setup lang="ts">
// 已发布视频管理:数据复盘看板的数据源。
// 单行表单 + Excel/CSV 文件导入。手填或后续 OAuth(#7)接进来后改自动同步。
import { computed, onMounted, ref } from 'vue'
import { ArrowLeft, Loader2, Plus, Sparkles, Upload } from 'lucide-vue-next'
import ErrorBanner from '../components/ErrorBanner.vue'
import PublishedVideoTable from '../components/published/PublishedVideoTable.vue'
import PublishedVideoEditModal from '../components/published/PublishedVideoEditModal.vue'
import CsvImporter from '../components/published/CsvImporter.vue'
import {
  dedupePublishedVideos, deletePublishedVideo, listPublishedVideos,
  type PublishedVideo,
} from '../api/publishedVideos'
import { listScripts } from '../api/scripts'
import { extractError } from '../lib/format'
import type { ScriptListItem } from '../types'

const items = ref<PublishedVideo[]>([])
const loading = ref(false)
const errorMsg = ref<string | null>(null)
const deletingId = ref<number | null>(null)

// /assembly 同款脚本下拉:跑过 pipeline 的脚本,新→旧
const scripts = ref<ScriptListItem[]>([])

// 单行编辑 dialog
const dialogOpen = ref(false)
const editingItem = ref<PublishedVideo | null>(null)

// 文件导入 dialog
const csvOpen = ref(false)

const totalViews = computed(() => items.value.reduce((s, v) => s + (v.views || 0), 0))
const totalCost = computed(() => items.value.reduce((s, v) => s + (v.costYuan || 0), 0))

async function load() {
  loading.value = true
  errorMsg.value = null
  try {
    items.value = await listPublishedVideos()
  } catch (e: any) {
    errorMsg.value = extractError(e, '加载失败')
  } finally {
    loading.value = false
  }
}

// 一键去重 —— 同 (platform, title, publishedAt) 视为重复(抖音 aweme_id vs item_id 等不同入口
// 会让 upsert 把同一视频插成两行)。先 dryRun 让用户看清单,确认后真删。
const dedupeBusy = ref(false)
async function onDedupe() {
  if (dedupeBusy.value) return
  dedupeBusy.value = true
  try {
    const preview = await dedupePublishedVideos(true)
    if (preview.groupCount === 0) {
      alert('没有发现重复行')
      return
    }
    const sample = preview.groups.slice(0, 5).map((g) =>
      `· ${g.platform} | ${g.title.slice(0, 30)} | 保留 #${g.keepId},删除 #${g.dropIds.join(', #')}`
    ).join('\n')
    const more = preview.groupCount > 5 ? `\n…还有 ${preview.groupCount - 5} 组` : ''
    if (!confirm(
      `发现 ${preview.groupCount} 组重复,共 ${preview.dropCount} 条多余记录会被删除\n\n` +
      `${sample}${more}\n\n` +
      `合并策略:每组保留 ID 最小那条,把另几条的 vid / scriptId / topicId / 项目名 等字段补到保留行,然后删除多余行。\n\n` +
      `确认执行?`
    )) return
    const result = await dedupePublishedVideos(false)
    alert(`已去重:${result.groupCount} 组 / 删除 ${result.dropCount} 条`)
    await load()
  } catch (e: any) {
    alert(`去重失败:${extractError(e, '调用失败')}`)
  } finally {
    dedupeBusy.value = false
  }
}

// 脚本下拉:同 /assembly 列表口径(跑过 pipeline 的)。失败静默,允许用户继续手填。
async function loadScripts() {
  try {
    const resp = await listScripts({ page: 0, size: 200 })
    scripts.value = resp.content
      .filter((s) => s.lastRunStage != null)
      .sort((a, b) => (b.lastRunAt || '').localeCompare(a.lastRunAt || ''))
  } catch {
    scripts.value = []
  }
}

function openCreate() {
  editingItem.value = null
  dialogOpen.value = true
}

function openEdit(v: PublishedVideo) {
  editingItem.value = v
  dialogOpen.value = true
}

async function onDelete(v: PublishedVideo) {
  if (!confirm(`确定删除 「${v.title}」?\n\n该条指标行删除后无法恢复(数据复盘看板的趋势会丢这条样本)。`)) return
  deletingId.value = v.id
  try {
    await deletePublishedVideo(v.id)
    items.value = items.value.filter((x) => x.id !== v.id)
    errorMsg.value = null
  } catch (e: any) {
    errorMsg.value = extractError(e, '删除失败')
  } finally {
    deletingId.value = null
  }
}

onMounted(() => {
  load()
  loadScripts()
})
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
          <h1 class="text-lg font-semibold">已发布视频</h1>
          <span class="chip text-[11px] bg-surface-tertiary text-text-muted">
            {{ items.length }} 条 · 总播放 {{ totalViews.toLocaleString() }} · 总成本 ¥{{ totalCost.toFixed(2) }}
          </span>
        </div>
        <div class="text-xs text-text-muted">
          数据复盘看板的数据源 —— 手填或导入 Excel/CSV;接 OAuth(#7)后改自动同步
        </div>
      </div>
    </div>

    <div class="px-8 py-5 max-w-[1400px] mx-auto">
      <ErrorBanner :msg="errorMsg" />

      <div class="card p-3 mb-4 flex items-center gap-2 flex-wrap">
        <button class="btn-primary" @click="openCreate">
          <Plus :size="14" /> 添加视频
        </button>
        <button class="btn-ghost" @click="csvOpen = true">
          <Upload :size="14" /> 导入文件
        </button>
        <button class="btn-ghost" :disabled="dedupeBusy || loading" @click="onDedupe">
          <Loader2 v-if="dedupeBusy" :size="14" class="animate-spin" />
          <Sparkles v-else :size="14" />
          {{ dedupeBusy ? '清理中…' : '清理重复' }}
        </button>
        <Loader2 v-if="loading" :size="14" class="animate-spin text-text-muted ml-2" />
      </div>

      <div v-if="!loading && items.length === 0" class="card p-12 text-center text-sm text-text-muted">
        还没有已发布视频 — 点右上「添加视频」录第一条,或「导入文件」批量录入 Excel/CSV
      </div>

      <PublishedVideoTable
        v-else
        :items="items"
        :deleting-id="deletingId"
        @edit="openEdit"
        @delete="onDelete"
      />

      <PublishedVideoEditModal
        v-model:open="dialogOpen"
        :editing="editingItem"
        :scripts="scripts"
        @saved="load"
      />

      <CsvImporter
        v-model:open="csvOpen"
        :scripts="scripts"
        @imported="load"
      />
    </div>
  </div>
</template>
