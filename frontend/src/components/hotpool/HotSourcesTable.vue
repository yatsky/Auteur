<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { Loader2, Pencil, Plus, RefreshCw, Rss, Trash2 } from 'lucide-vue-next'
import { deleteHotSource, listHotSources, triggerSourceFetch } from '../../api/hotpool'
import { extractError } from '../../lib/format'
import TimeText from '../TimeText.vue'
import AddSourceModal from './AddSourceModal.vue'
import type { HotSource } from '../../types'

const sources = ref<HotSource[]>([])
const loading = ref(false)
const errorMsg = ref<string | null>(null)
const successMsg = ref<string | null>(null)
const fetchingId = ref<number | null>(null)
const editing = ref<HotSource | null>(null)
const modalOpen = ref(false)

onMounted(load)

async function load() {
  loading.value = true
  errorMsg.value = null
  try {
    sources.value = await listHotSources()
  } catch (e) {
    errorMsg.value = extractError(e, '加载源列表失败')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editing.value = null
  modalOpen.value = true
}

function openEdit(s: HotSource) {
  editing.value = s
  modalOpen.value = true
}

async function doDelete(s: HotSource) {
  if (!confirm(`确认删除「${s.name}」?该源下所有热点条目会一起删除(级联),不可恢复。`)) return
  try {
    await deleteHotSource(s.id)
    sources.value = sources.value.filter((x) => x.id !== s.id)
    successMsg.value = `已删除「${s.name}」`
  } catch (e) {
    errorMsg.value = extractError(e, '删除失败')
  }
}

async function doFetch(s: HotSource) {
  fetchingId.value = s.id
  errorMsg.value = null
  successMsg.value = null
  try {
    const result = await triggerSourceFetch(s.id)
    if (result.error) {
      errorMsg.value = `${s.name}: ${result.error}`
    } else {
      successMsg.value = `${s.name}: 新增 ${result.inserted} 条 / 跳过 ${result.skipped} 条`
    }
    await load()
  } catch (e) {
    errorMsg.value = extractError(e, '抓取失败')
  } finally {
    fetchingId.value = null
  }
}

function onSaved(saved: HotSource) {
  modalOpen.value = false
  successMsg.value = editing.value ? `已更新「${saved.name}」` : `已添加「${saved.name}」`
  load()
}
</script>

<template>
  <div class="space-y-3">
    <!-- 顶部 -->
    <div class="flex items-center justify-between gap-3">
      <div>
        <h2 class="text-base font-semibold text-text-primary">热点源管理</h2>
        <div class="text-xs text-text-muted mt-0.5">
          所有源都可独立启用 / 禁用,失败的源不会阻塞流水线(本地优先 + 可降级)
        </div>
      </div>
      <button class="btn-primary text-sm" @click="openCreate">
        <Plus :size="14" />添加源
      </button>
    </div>

    <div
      v-if="successMsg"
      class="text-xs text-status-done bg-status-done/10 border border-status-done/30 rounded-md px-3 py-2"
    >{{ successMsg }}</div>
    <div
      v-if="errorMsg"
      class="text-xs text-status-failed bg-status-failed/10 border border-status-failed/30 rounded-md px-3 py-2"
    >{{ errorMsg }}</div>

    <!-- 表格 -->
    <div v-if="loading && sources.length === 0" class="card p-6 text-center text-sm text-text-muted">
      <Loader2 :size="20" class="animate-spin mx-auto mb-2" />加载中…
    </div>
    <div v-else-if="sources.length === 0" class="card p-8 text-center text-sm text-text-muted">
      <Rss :size="28" class="mx-auto mb-3 opacity-40" />
      <div class="font-medium text-text-secondary mb-1">还没有配置任何源</div>
      <div class="text-xs">默认会自动 seed 4 条;若已禁用,可手动添加</div>
    </div>
    <div v-else class="card overflow-hidden">
      <table class="w-full text-sm">
        <thead>
          <tr class="bg-surface-tertiary text-[11px] uppercase tracking-wider text-text-muted">
            <th class="text-left px-4 py-2 font-medium">名称</th>
            <th class="text-left px-4 py-2 font-medium">适配器</th>
            <th class="text-left px-4 py-2 font-medium">URL / 配置</th>
            <th class="text-left px-4 py-2 font-medium">上次抓取</th>
            <th class="text-left px-4 py-2 font-medium">启用</th>
            <th class="text-right px-4 py-2 font-medium">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="s in sources"
            :key="s.id"
            class="border-t border-border-subtle hover:bg-surface-hover/40"
          >
            <td class="px-4 py-3 align-top">
              <div class="font-medium text-text-primary">{{ s.name }}</div>
            </td>
            <td class="px-4 py-3 align-top">
              <span class="chip bg-accent-soft text-accent text-[10px]">{{ s.adapter }}</span>
            </td>
            <td class="px-4 py-3 align-top">
              <div class="text-xs font-mono text-text-secondary truncate max-w-[300px]" :title="s.url">
                {{ s.url }}
              </div>
            </td>
            <td class="px-4 py-3 align-top text-xs">
              <div v-if="s.lastFetchedAt" class="text-text-primary">
                <TimeText :value="s.lastFetchedAt" />
              </div>
              <div v-if="s.lastFetchCount != null" class="text-text-muted">
                获取 {{ s.lastFetchCount }} 条
              </div>
              <div v-if="s.lastFetchError" class="text-status-failed mt-0.5" :title="s.lastFetchError">
                ⚠ {{ s.lastFetchError.length > 30 ? s.lastFetchError.slice(0, 30) + '…' : s.lastFetchError }}
              </div>
              <div v-if="!s.lastFetchedAt" class="text-text-muted">未抓取过</div>
            </td>
            <td class="px-4 py-3 align-top">
              <span
                class="chip text-[10px]"
                :class="s.enabled
                  ? 'bg-status-done/15 text-status-done'
                  : 'bg-surface-tertiary text-text-muted'"
              >{{ s.enabled ? '启用' : '禁用' }}</span>
            </td>
            <td class="px-4 py-3 align-top text-right whitespace-nowrap">
              <button
                class="btn-icon"
                :disabled="!s.enabled || fetchingId === s.id"
                @click="doFetch(s)"
                :title="s.enabled ? '立即抓取这个源' : '已禁用'"
              >
                <Loader2 v-if="fetchingId === s.id" :size="13" class="animate-spin" />
                <RefreshCw v-else :size="13" />
              </button>
              <button class="btn-icon" @click="openEdit(s)" title="编辑">
                <Pencil :size="13" />
              </button>
              <button class="btn-icon hover:text-status-failed" @click="doDelete(s)" title="删除">
                <Trash2 :size="13" />
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <AddSourceModal
      :open="modalOpen"
      :editing="editing"
      @close="modalOpen = false"
      @saved="onSaved"
    />
  </div>
</template>
