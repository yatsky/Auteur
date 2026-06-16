<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ArrowLeft, Layers, Loader2, Pencil, Plus, Trash2, X } from 'lucide-vue-next'
import { useRouter } from 'vue-router'
import ErrorBanner from '../components/ErrorBanner.vue'
import TimeText from '../components/TimeText.vue'
import {
  createSeries, deleteSeries, listSeries, updateSeries,
  type Series, type SeriesUpsert,
} from '../api/series'
import { extractError } from '../lib/format'
import { useAsyncLoad } from '../composables/useAsyncLoad'

const router = useRouter()

const items = ref<Series[]>([])

const dialogOpen = ref(false)
const editingId = ref<number | null>(null)  // null=新建,数字=编辑该 id
const form = ref<SeriesUpsert>({ name: '', slug: '', description: '', coverUrl: '', status: 'ACTIVE' })
const formError = ref<string | null>(null)
const saving = ref(false)
const deletingId = ref<number | null>(null)

const { loading, errorMsg, run: load } = useAsyncLoad(async () => {
  items.value = await listSeries()
}, { errorPrefix: '加载失败' })

function openCreate() {
  editingId.value = null
  form.value = { name: '', slug: '', description: '', coverUrl: '', status: 'ACTIVE' }
  formError.value = null
  dialogOpen.value = true
}

function openEdit(s: Series, evt: Event) {
  evt.stopPropagation()
  editingId.value = s.id
  form.value = {
    name: s.name,
    slug: s.slug,
    description: s.description || '',
    coverUrl: s.coverUrl || '',
    status: s.status,
  }
  formError.value = null
  dialogOpen.value = true
}

async function onSave() {
  if (!form.value.name.trim()) { formError.value = '名称必填'; return }
  if (!form.value.slug.trim()) { formError.value = 'slug 必填(URL 友好,小写英文/数字/-)'; return }
  saving.value = true
  formError.value = null
  try {
    if (editingId.value == null) {
      await createSeries(form.value)
    } else {
      await updateSeries(editingId.value, form.value)
    }
    dialogOpen.value = false
    await load()
  } catch (e: any) {
    formError.value = extractError(e, '保存失败')
  } finally {
    saving.value = false
  }
}

async function onDelete(s: Series, evt: Event) {
  evt.stopPropagation()
  if (!confirm(`确定删除系列「${s.name}」?\n\n该系列下若还有选题会被后端 409 拒绝,需先迁移 topic.seriesId。`)) return
  deletingId.value = s.id
  try {
    await deleteSeries(s.id)
    items.value = items.value.filter((x) => x.id !== s.id)
    errorMsg.value = null
  } catch (e: any) {
    errorMsg.value = extractError(e, '删除失败')
  } finally {
    deletingId.value = null
  }
}

onMounted(load)
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
          <h1 class="text-lg font-semibold">系列管理</h1>
          <span class="chip text-[11px] bg-surface-tertiary text-text-muted">{{ items.length }} 个系列</span>
          <button class="btn-primary ml-auto" @click="openCreate">
            <Plus :size="13" /> 新建系列
          </button>
        </div>
        <div class="text-xs text-text-muted">给跨集相关的选题分组 · Topic.seriesId 关联到这里</div>
      </div>
    </div>

    <div class="px-8 py-5 max-w-[1400px] mx-auto">
      <ErrorBanner :msg="errorMsg" />

      <div v-if="loading" class="card p-3 mb-4 flex items-center gap-2 text-xs text-text-muted">
        <Loader2 :size="14" class="animate-spin" /> 加载中…
      </div>

      <div class="card overflow-hidden">
        <table class="w-full text-sm">
          <thead class="bg-surface-tertiary text-xs uppercase text-text-muted">
            <tr>
              <th class="text-left px-4 py-3 font-medium w-[60px]">id</th>
              <th class="text-left px-4 py-3 font-medium">名称 / slug</th>
              <th class="text-left px-4 py-3 font-medium">描述</th>
              <th class="text-left px-4 py-3 font-medium w-[80px]">集数</th>
              <th class="text-left px-4 py-3 font-medium w-[100px]">状态</th>
              <th class="text-left px-4 py-3 font-medium w-[110px]">更新</th>
              <th class="text-right px-4 py-3 font-medium w-[100px]">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="s in items" :key="s.id"
              class="border-t border-border-subtle hover:bg-surface-tertiary/40 cursor-pointer"
              @click="router.push(`/series/${s.id}`)"
            >
              <td class="px-4 py-3 font-mono text-text-secondary">{{ s.id }}</td>
              <td class="px-4 py-3">
                <div class="flex items-center gap-2">
                  <Layers :size="13" class="text-accent shrink-0" />
                  <div class="min-w-0">
                    <div class="text-text-primary truncate">{{ s.name }}</div>
                    <div class="text-xs text-text-muted font-mono truncate">{{ s.slug }}</div>
                  </div>
                </div>
              </td>
              <td class="px-4 py-3 text-text-secondary text-xs line-clamp-2 max-w-[360px]">
                {{ s.description || '-' }}
              </td>
              <td class="px-4 py-3 font-mono">
                <span :class="s.topicCount > 0 ? 'text-accent' : 'text-text-muted'">
                  {{ s.topicCount }}
                </span>
              </td>
              <td class="px-4 py-3">
                <span :class="['chip text-xs',
                                s.status === 'ACTIVE'
                                  ? 'bg-status-done/15 text-status-done'
                                  : 'bg-surface-tertiary text-text-muted']">
                  {{ s.status }}
                </span>
              </td>
              <td class="px-4 py-3"><TimeText :value="s.updatedAt" relative /></td>
              <td class="px-4 py-3 text-right">
                <button class="text-text-muted hover:text-text-primary mr-2"
                        title="编辑"
                        @click="openEdit(s, $event)">
                  <Pencil :size="14" />
                </button>
                <button class="text-text-muted hover:text-status-failed disabled:opacity-50"
                        title="删除"
                        :disabled="deletingId === s.id"
                        @click="onDelete(s, $event)">
                  <Loader2 v-if="deletingId === s.id" :size="14" class="animate-spin" />
                  <Trash2 v-else :size="14" />
                </button>
              </td>
            </tr>
            <tr v-if="!loading && items.length === 0">
              <td colspan="7" class="text-center py-12 text-text-muted text-sm">
                还没有系列 — 点右上「新建系列」开始
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div v-if="dialogOpen"
           class="fixed inset-0 z-50 bg-black/60 flex items-center justify-center p-4"
           @click.self="!saving && (dialogOpen = false)">
        <div class="card p-6 max-w-[520px] w-full">
          <header class="flex items-center justify-between mb-4">
            <h2 class="text-lg font-semibold flex items-center gap-2">
              <Layers :size="18" class="text-accent" />
              {{ editingId == null ? '新建系列' : '编辑系列' }}
            </h2>
            <button class="text-text-muted hover:text-text-primary"
                    :disabled="saving" @click="dialogOpen = false">
              <X :size="20" />
            </button>
          </header>

          <div v-if="formError" class="card p-3 mb-3 bg-status-failed/10 border-status-failed/30 text-xs text-status-failed">
            {{ formError }}
          </div>

          <div class="grid grid-cols-1 gap-3 text-sm">
            <label class="flex flex-col gap-1">
              <span class="text-xs text-text-muted">名称 *</span>
              <input v-model="form.name" type="text" maxlength="120"
                     :disabled="saving"
                     class="bg-surface-tertiary border border-border-subtle rounded px-2 py-1.5" />
            </label>
            <label class="flex flex-col gap-1">
              <span class="text-xs text-text-muted">slug * <span class="text-text-muted/70">(URL 友好,唯一,小写英文/数字/-)</span></span>
              <input v-model="form.slug" type="text" maxlength="120"
                     :disabled="saving"
                     placeholder="ming-qi-an"
                     class="bg-surface-tertiary border border-border-subtle rounded px-2 py-1.5 font-mono" />
            </label>
            <label class="flex flex-col gap-1">
              <span class="text-xs text-text-muted">描述</span>
              <textarea v-model="form.description" rows="3" maxlength="1000"
                        :disabled="saving"
                        class="bg-surface-tertiary border border-border-subtle rounded px-2 py-1.5 resize-y" />
            </label>
            <label class="flex flex-col gap-1">
              <span class="text-xs text-text-muted">封面 URL(可选)</span>
              <input v-model="form.coverUrl" type="text" maxlength="500"
                     :disabled="saving"
                     class="bg-surface-tertiary border border-border-subtle rounded px-2 py-1.5 font-mono text-xs" />
            </label>
            <label class="flex flex-col gap-1">
              <span class="text-xs text-text-muted">状态</span>
              <select v-model="form.status" :disabled="saving"
                      class="bg-surface-tertiary border border-border-subtle rounded px-2 py-1.5 w-32">
                <option value="ACTIVE">ACTIVE</option>
                <option value="ARCHIVED">ARCHIVED</option>
              </select>
            </label>
          </div>

          <div class="flex items-center gap-2 mt-5">
            <button class="chip cursor-pointer bg-surface-tertiary text-text-secondary text-sm"
                    :disabled="saving" @click="dialogOpen = false">取消</button>
            <button class="btn-primary ml-auto" :disabled="saving" @click="onSave">
              <Loader2 v-if="saving" :size="14" class="animate-spin" />
              {{ saving ? '保存中…' : (editingId == null ? '创建' : '保存') }}
            </button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
