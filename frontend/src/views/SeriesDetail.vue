<script setup lang="ts">
// 系列详情:展示系列元信息 + 该系列下所有 topic;支持 inline 编辑系列字段。
// /series/:id。从 /series 列表的行点击进来,或从 TopicDetail 的"系列"链接(后续接)。
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowLeft, Layers, Loader2, Pencil, Save, Trash2, X } from 'lucide-vue-next'
import {
  deleteSeries, getSeries, listTopicsInSeries, updateSeries,
  type Series, type SeriesUpsert,
} from '../api/series'
import { extractError } from '../lib/format'
import type { Topic } from '../types'
import { TOPIC_STATUS_LABELS } from '../types'
import ErrorBanner from '../components/ErrorBanner.vue'
import TimeText from '../components/TimeText.vue'

const props = defineProps<{ id: number }>()
const router = useRouter()

const series = ref<Series | null>(null)
const topics = ref<Topic[]>([])
const loading = ref(false)
const errorMsg = ref<string | null>(null)

const editing = ref(false)
const editForm = ref<SeriesUpsert | null>(null)
const saving = ref(false)
const saveError = ref<string | null>(null)
const deleting = ref(false)

const topicCountByStatus = computed(() => {
  const m: Record<string, number> = {}
  for (const t of topics.value) m[t.status] = (m[t.status] || 0) + 1
  return m
})

async function load() {
  loading.value = true
  errorMsg.value = null
  try {
    const [s, ts] = await Promise.all([
      getSeries(props.id),
      listTopicsInSeries(props.id),
    ])
    series.value = s
    topics.value = ts
  } catch (e: any) {
    if (e?.response?.status === 404) {
      errorMsg.value = `系列 ${props.id} 不存在`
    } else {
      errorMsg.value = extractError(e, '加载失败')
    }
  } finally {
    loading.value = false
  }
}

function startEdit() {
  if (!series.value) return
  editForm.value = {
    name: series.value.name,
    slug: series.value.slug,
    description: series.value.description || '',
    coverUrl: series.value.coverUrl || '',
    status: series.value.status,
  }
  saveError.value = null
  editing.value = true
}

function cancelEdit() {
  editing.value = false
  editForm.value = null
  saveError.value = null
}

async function saveEdit() {
  if (!series.value || !editForm.value) return
  if (!editForm.value.name?.trim()) { saveError.value = '名称必填'; return }
  if (!editForm.value.slug?.trim()) { saveError.value = 'slug 必填'; return }
  saving.value = true
  saveError.value = null
  try {
    series.value = await updateSeries(series.value.id, editForm.value)
    editing.value = false
    editForm.value = null
  } catch (e: any) {
    saveError.value = extractError(e, '保存失败')
  } finally {
    saving.value = false
  }
}

async function onDelete() {
  if (!series.value) return
  if (topics.value.length > 0) {
    alert(`无法删除:系列下还有 ${topics.value.length} 条选题。\n\n请先把它们改到别的系列或清空 series_id(在选题详情页编辑)。`)
    return
  }
  if (!confirm(`确定删除系列「${series.value.name}」?`)) return
  deleting.value = true
  try {
    await deleteSeries(series.value.id)
    router.push('/series')
  } catch (e: any) {
    const msg = extractError(e, '删除失败')
    alert(`删除失败:${msg}`)
  } finally {
    deleting.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="min-h-full">
    <!-- sticky 顶栏 -->
    <div class="sticky top-0 z-10 bg-surface-primary border-b border-border-subtle">
      <div class="px-8 py-3 max-w-[1400px] mx-auto flex flex-col gap-1">
        <div class="flex items-center gap-4 flex-wrap">
          <button
            class="flex items-center gap-1.5 text-sm text-text-muted hover:text-text-primary"
            @click="router.push('/series')"
          >
            <ArrowLeft :size="14" /> 返回系列列表
          </button>
          <h1 class="text-lg font-semibold flex items-center gap-2">
            <Layers :size="16" class="text-accent" />
            {{ series?.name ?? `系列 #${id}` }}
          </h1>
          <span v-if="series" class="chip text-[11px] bg-surface-tertiary text-text-muted font-mono">
            #{{ series.id }} · {{ series.slug }}
          </span>
          <span v-if="series" :class="['chip text-[11px]',
                          series.status === 'ACTIVE'
                            ? 'bg-status-done/15 text-status-done'
                            : 'bg-surface-tertiary text-text-muted']">
            {{ series.status }}
          </span>
          <div v-if="series && !editing" class="ml-auto flex items-center gap-2">
            <button class="chip cursor-pointer bg-surface-tertiary text-text-secondary text-sm"
                    @click="startEdit">
              <Pencil :size="13" /> 编辑
            </button>
            <button class="chip cursor-pointer bg-status-failed/15 text-status-failed text-sm"
                    :disabled="deleting || topics.length > 0"
                    :title="topics.length > 0 ? '系列下有选题,无法删除' : ''"
                    @click="onDelete">
              <Trash2 :size="13" /> {{ deleting ? '删除中…' : '删除' }}
            </button>
          </div>
        </div>
        <div v-if="series" class="text-xs text-text-muted">
          {{ topics.length }} 集 · 更新 {{ new Date(series.updatedAt).toLocaleDateString() }}
        </div>
      </div>
    </div>

    <div class="px-8 py-5 max-w-[1400px] mx-auto">
      <ErrorBanner :msg="errorMsg" />

      <div v-if="loading" class="card p-12 text-center text-text-muted">
        <Loader2 :size="20" class="animate-spin mx-auto" />
      </div>

      <template v-else-if="series">
        <!-- KPI strip -->
        <div class="grid grid-cols-2 md:grid-cols-4 gap-3 mb-4">
          <div class="card p-4">
            <div class="text-xs text-text-muted mb-1">总选题数</div>
            <div class="text-2xl font-mono font-semibold">{{ topics.length }}</div>
          </div>
          <div class="card p-4">
            <div class="text-xs text-text-muted mb-1">已发布</div>
            <div class="text-2xl font-mono font-semibold text-status-done">{{ topicCountByStatus['PUBLISHED'] ?? 0 }}</div>
          </div>
          <div class="card p-4">
            <div class="text-xs text-text-muted mb-1">进行中</div>
            <div class="text-2xl font-mono font-semibold text-status-running">
              {{ (topicCountByStatus['DRAFT'] ?? 0) + (topicCountByStatus['IN_PROGRESS'] ?? 0) }}
            </div>
          </div>
          <div class="card p-4">
            <div class="text-xs text-text-muted mb-1">已归档</div>
            <div class="text-2xl font-mono font-semibold text-text-muted">{{ topicCountByStatus['ARCHIVED'] ?? 0 }}</div>
          </div>
        </div>

        <!-- 描述 / 元信息 -->
        <div class="card p-5 mb-4">
          <template v-if="!editing">
            <div v-if="series.description" class="text-sm text-text-secondary whitespace-pre-wrap mb-4 leading-relaxed">
              {{ series.description }}
            </div>
            <div v-else class="text-sm text-text-muted italic mb-4">未填写描述</div>
            <div class="grid grid-cols-2 md:grid-cols-3 gap-4 text-sm pt-3 border-t border-border-subtle">
              <div>
                <div class="text-xs text-text-muted mb-1">封面</div>
                <div class="font-mono text-xs truncate" :title="series.coverUrl || ''">
                  {{ series.coverUrl || '-' }}
                </div>
              </div>
              <div>
                <div class="text-xs text-text-muted mb-1">创建</div>
                <div><TimeText :value="series.createdAt" relative /></div>
              </div>
              <div>
                <div class="text-xs text-text-muted mb-1">更新</div>
                <div><TimeText :value="series.updatedAt" relative /></div>
              </div>
            </div>
          </template>

          <template v-else-if="editForm">
            <div v-if="saveError" class="card p-3 mb-3 bg-status-failed/10 border-status-failed/30 text-xs text-status-failed">
              {{ saveError }}
            </div>
            <div class="grid grid-cols-1 md:grid-cols-2 gap-3 text-sm">
              <label class="flex flex-col gap-1">
                <span class="text-xs text-text-muted">名称 *</span>
                <input v-model="editForm.name" type="text" maxlength="120"
                       :disabled="saving"
                       class="bg-surface-tertiary border border-border-subtle rounded px-2 py-1.5" />
              </label>
              <label class="flex flex-col gap-1">
                <span class="text-xs text-text-muted">slug *</span>
                <input v-model="editForm.slug" type="text" maxlength="120"
                       :disabled="saving"
                       class="bg-surface-tertiary border border-border-subtle rounded px-2 py-1.5 font-mono" />
              </label>
              <label class="flex flex-col gap-1 md:col-span-2">
                <span class="text-xs text-text-muted">描述</span>
                <textarea v-model="editForm.description" rows="3" maxlength="1000"
                          :disabled="saving"
                          class="bg-surface-tertiary border border-border-subtle rounded px-2 py-1.5 resize-y" />
              </label>
              <label class="flex flex-col gap-1 md:col-span-2">
                <span class="text-xs text-text-muted">封面 URL</span>
                <input v-model="editForm.coverUrl" type="text" maxlength="500"
                       :disabled="saving"
                       class="bg-surface-tertiary border border-border-subtle rounded px-2 py-1.5 font-mono text-xs" />
              </label>
              <label class="flex flex-col gap-1">
                <span class="text-xs text-text-muted">状态</span>
                <select v-model="editForm.status" :disabled="saving"
                        class="bg-surface-tertiary border border-border-subtle rounded px-2 py-1.5 w-32">
                  <option value="ACTIVE">ACTIVE</option>
                  <option value="ARCHIVED">ARCHIVED</option>
                </select>
              </label>
            </div>
            <div class="flex items-center gap-2 mt-4">
              <button class="chip cursor-pointer bg-surface-tertiary text-text-secondary text-sm"
                      :disabled="saving" @click="cancelEdit">
                <X :size="13" /> 取消
              </button>
              <button class="btn-primary ml-auto" :disabled="saving" @click="saveEdit">
                <Loader2 v-if="saving" :size="14" class="animate-spin" />
                <Save v-else :size="14" />
                {{ saving ? '保存中…' : '保存' }}
              </button>
            </div>
          </template>
        </div>

        <!-- 系列下选题 -->
        <div class="card overflow-hidden">
          <header class="flex items-center gap-2 px-5 py-3 border-b border-border-subtle">
            <Layers :size="14" class="text-accent" />
            <span class="text-sm font-semibold">系列下选题</span>
            <span class="text-xs text-text-muted">{{ topics.length }} 集 · 按 id desc</span>
          </header>
          <table class="w-full text-sm">
            <thead class="bg-surface-tertiary text-xs uppercase text-text-muted">
              <tr>
                <th class="text-left px-4 py-2 font-medium w-[60px]">id</th>
                <th class="text-left px-4 py-2 font-medium">标题 / 项目名</th>
                <th class="text-left px-4 py-2 font-medium w-[80px]">朝代</th>
                <th class="text-left px-4 py-2 font-medium w-[80px]">题材</th>
                <th class="text-left px-4 py-2 font-medium w-[100px]">状态</th>
                <th class="text-left px-4 py-2 font-medium w-[90px]">来源</th>
                <th class="text-left px-4 py-2 font-medium w-[110px]">创建</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="t in topics" :key="t.id"
                  class="border-t border-border-subtle hover:bg-surface-tertiary/40 cursor-pointer"
                  @click="router.push(`/topics/${t.id}`)">
                <td class="px-4 py-2 font-mono text-text-secondary">{{ t.id }}</td>
                <td class="px-4 py-2">
                  <div class="text-text-primary truncate max-w-[420px]">{{ t.title }}</div>
                  <div v-if="t.projectName" class="text-xs text-text-muted truncate">{{ t.projectName }}</div>
                </td>
                <td class="px-4 py-2 text-text-secondary">{{ t.dynasty || '-' }}</td>
                <td class="px-4 py-2 text-text-secondary">{{ t.genre || '-' }}</td>
                <td class="px-4 py-2">
                  <span class="chip text-xs bg-surface-tertiary text-text-secondary">
                    {{ TOPIC_STATUS_LABELS[t.status] }}
                  </span>
                </td>
                <td class="px-4 py-2 text-xs">
                  <span v-if="t.sourceHookId" class="text-accent">钩子</span>
                  <span v-else class="text-text-muted">{{ t.source }}</span>
                </td>
                <td class="px-4 py-2"><TimeText :value="t.createdAt" relative /></td>
              </tr>
              <tr v-if="topics.length === 0">
                <td colspan="7" class="text-center py-12 text-text-muted text-sm">
                  这个系列还没有选题 — 在选题详情页里把 seriesId 改到这里(#{{ series.id }})就接进来了
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>
    </div>
  </div>
</template>
