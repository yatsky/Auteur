<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  ArrowLeft, Layers, Loader2, Pencil, Plus, Trash2, Download, Upload,
} from 'lucide-vue-next'
import ErrorBanner from '../components/ErrorBanner.vue'
import TimeText from '../components/TimeText.vue'
import { listPresets, deletePreset, createPreset, type Preset } from '../api/presets'
import { extractError } from '../lib/format'
import { useAsyncLoad } from '../composables/useAsyncLoad'

const router = useRouter()
const items = ref<Preset[]>([])
const deletingId = ref<number | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)

const { loading, errorMsg, run: load } = useAsyncLoad(async () => {
  items.value = await listPresets()
}, { errorPrefix: '加载失败' })

onMounted(load)

function goEdit(id: number) {
  router.push(`/presets/${id}/edit`)
}

function goNew() {
  router.push('/presets/new')
}

async function onDelete(p: Preset) {
  if (!confirm(`确认删除预设「${p.displayName || p.name}」?\n关联的 version 快照和 asset 会一起删除(级联)。`)) return
  deletingId.value = p.id
  try {
    await deletePreset(p.id)
    await load()
  } catch (e: any) {
    errorMsg.value = extractError(e, '删除失败')
  } finally {
    deletingId.value = null
  }
}

function exportPreset(p: Preset) {
  const blob = new Blob([JSON.stringify(p, null, 2)], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `preset-${p.name}-v${p.currentVersion}.json`
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}

function triggerImport() {
  fileInput.value?.click()
}

async function onImportFile(e: Event) {
  const target = e.target as HTMLInputElement
  const file = target.files?.[0]
  if (!file) return
  try {
    const text = await file.text()
    const draft = JSON.parse(text) as Partial<Preset>
    delete draft.id
    delete draft.createdAt
    delete draft.updatedAt
    delete draft.currentVersion
    if (!draft.name) throw new Error('JSON 缺 name 字段')
    // 名字冲突时让用户加后缀
    const existing = items.value.find((x) => x.name === draft.name)
    if (existing) {
      const newName = prompt(`已存在同名预设 "${draft.name}",输入新名字(留空取消):`, `${draft.name}-copy`)
      if (!newName) return
      draft.name = newName
    }
    const created = await createPreset(draft)
    await load()
    router.push(`/presets/${created.id}/edit`)
  } catch (err: any) {
    errorMsg.value = extractError(err, '导入失败:' + (err?.message || err))
  } finally {
    target.value = ''
  }
}
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
          <h1 class="text-lg font-semibold">预设库</h1>
          <span class="chip text-[11px] bg-surface-tertiary text-text-muted">{{ items.length }} 个预设</span>
          <div class="ml-auto flex gap-2">
            <button class="btn" @click="triggerImport">
              <Upload :size="13" /> 导入 JSON
            </button>
            <button class="btn-primary" @click="goNew">
              <Plus :size="13" /> 新建预设
            </button>
          </div>
          <input
            ref="fileInput"
            type="file"
            accept="application/json"
            class="hidden"
            @change="onImportFile"
          />
        </div>
        <div class="text-xs text-text-muted">
          一行预设描述"一种视频形态的所有配方"(input schema + 各 stage prompt + 风格 + 画幅)。
        </div>
      </div>
    </div>

    <div class="px-8 py-5 max-w-[1400px] mx-auto">
      <ErrorBanner :msg="errorMsg" />

      <div v-if="loading" class="card p-3 mb-4 flex items-center gap-2 text-xs text-text-muted">
        <Loader2 :size="14" class="animate-spin" /> 加载中…
      </div>

      <div v-if="!loading && items.length === 0" class="card p-8 text-center text-sm text-text-muted">
        <Layers :size="32" class="mx-auto mb-2 opacity-50" />
        <div>还没有预设</div>
        <div class="mt-3">
          <button class="btn-primary" @click="goNew">新建第一个</button>
        </div>
      </div>

      <div v-else-if="!loading" class="card overflow-hidden">
        <table class="w-full text-sm">
          <thead class="bg-surface-secondary text-text-muted text-xs">
            <tr>
              <th class="text-left px-4 py-2 font-medium">名称</th>
              <th class="text-left px-4 py-2 font-medium">显示名</th>
              <th class="text-left px-4 py-2 font-medium">画幅</th>
              <th class="text-left px-4 py-2 font-medium">composition</th>
              <th class="text-left px-4 py-2 font-medium">版本</th>
              <th class="text-left px-4 py-2 font-medium">更新时间</th>
              <th class="text-right px-4 py-2 font-medium">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="p in items"
              :key="p.id"
              class="border-t border-border-subtle hover:bg-surface-hover"
            >
              <td class="px-4 py-2 font-mono text-xs">{{ p.name }}</td>
              <td class="px-4 py-2">{{ p.displayName || '—' }}</td>
              <td class="px-4 py-2 text-xs text-text-muted">{{ p.formatWidth }}×{{ p.formatHeight }}</td>
              <td class="px-4 py-2 text-xs text-text-muted">{{ p.compositionId }}</td>
              <td class="px-4 py-2 text-xs">v{{ p.currentVersion }}</td>
              <td class="px-4 py-2"><TimeText :value="p.updatedAt" /></td>
              <td class="px-4 py-2">
                <div class="flex gap-1.5 justify-end">
                  <button
                    class="btn-icon"
                    title="导出 JSON"
                    @click="exportPreset(p)"
                  >
                    <Download :size="13" />
                  </button>
                  <button
                    class="btn-icon"
                    title="编辑"
                    @click="goEdit(p.id)"
                  >
                    <Pencil :size="13" />
                  </button>
                  <button
                    class="btn-icon hover:text-status-failed"
                    title="删除"
                    :disabled="deletingId === p.id"
                    @click="onDelete(p)"
                  >
                    <Loader2 v-if="deletingId === p.id" :size="13" class="animate-spin" />
                    <Trash2 v-else :size="13" />
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </div>
</template>
