<script setup lang="ts">
// 选题详情 —— 左主区(标题/标签/描述 + 编辑表单 + 史料/备注) + 右栏(AI 评分 + 流转记录 + 生成脚本)
import { onMounted, ref, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ArrowLeft, ArrowRight, Calendar, CheckCircle2, ChevronRight, Clock, FileText, Info, Lightbulb, Link2, Loader2,
  Pencil, Save, Sparkles, Tag, Trash2, UserCheck, X,
} from 'lucide-vue-next'
import { deleteTopic, generateScriptAsync, getTopic, getTopicLineage, updateTopic } from '../api/topics'
import type { LineageNode } from '../api/topics'
import { listScripts } from '../api/scripts'
import { listSeries, type Series } from '../api/series'
import { useRunPoll } from '../composables/useRunPoll'
import { extractError } from '../lib/format'
import ErrorBanner from '../components/ErrorBanner.vue'
import DirectorNoteDrawer from '../components/DirectorNoteDrawer.vue'
import PresetInputDrawer from '../components/PresetInputDrawer.vue'
import TimeText from '../components/TimeText.vue'
import type { Topic, TopicStatus } from '../types'
import { ALL_TOPIC_STATUSES, RUN_STATUS_LABELS, TOPIC_STATUS_LABELS } from '../types'

const props = defineProps<{ id: number }>()
const router = useRouter()
const route = useRoute()

const topic = ref<Topic | null>(null)
const lineage = ref<LineageNode[]>([])
const loading = ref(false)
const errorMsg = ref<string | null>(null)
const generateMsg = ref<string | null>(null)
const generatedScriptId = ref<number | null>(null)

interface EditState {
  title: string
  projectName: string
  dynasty: string
  genre: string
  protagonist: string
  hookType: string
  emotion: string
  durationMinutes: number | null
  potentialScore: number | null
  seriesId: number | null
  historicalReference: string
  notes: string
}
const editing = ref(false)
const editForm = ref<EditState | null>(null)
const saving = ref(false)
const saveError = ref<string | null>(null)
const seriesOptions = ref<Series[]>([])

const directorNoteDrawerOpen = ref(false)
const presetInputDrawerOpen = ref(false)
const directorNoteConfigured = computed(() => !!topic.value?.directorNote)
const presetInputConfigured = computed(() => {
  const v = topic.value?.presetInputJson
  if (v == null) return false
  if (typeof v === 'string') return v.trim().length > 0 && v.trim() !== '{}'
  if (typeof v === 'object') return Object.keys(v).length > 0
  return false
})
const needsPresetInput = computed(() => !presetInputConfigured.value)

function onPresetInputSaved(input: any) {
  if (topic.value) topic.value = { ...topic.value, presetInputJson: input }
}

const statusBusy = ref(false)
const statusError = ref<string | null>(null)
const deleting = ref(false)

const poll = useRunPoll({
  onDone: (r) => {
    if (r.scriptId != null) {
      generatedScriptId.value = r.scriptId
      generateMsg.value = `脚本 ${r.scriptId} 已生成`
    } else {
      generateMsg.value = '后端跑完但没回填 scriptId,去脚本工作台看看'
    }
  },
  onFailed: (r) => { generateMsg.value = `失败:${r.errorMsg ?? r.status}` },
})
const activeRun = poll.run
const isRunning = computed(() => activeRun.value && (activeRun.value.status === 'PENDING' || activeRun.value.status === 'RUNNING'))

async function load() {
  loading.value = true
  errorMsg.value = null
  try {
    topic.value = await getTopic(props.id)
  } catch (e: any) {
    if (e?.response?.status === 404) {
      errorMsg.value = `选题 ${props.id} 不存在`
    } else {
      errorMsg.value = extractError(e, 'fetch failed')
    }
  } finally {
    loading.value = false
  }
}

function startEdit() {
  if (!topic.value) return
  editForm.value = {
    title: topic.value.title,
    projectName: topic.value.projectName || '',
    dynasty: topic.value.dynasty || '',
    genre: topic.value.genre || '',
    protagonist: topic.value.protagonist || '',
    hookType: topic.value.hookType || '',
    emotion: topic.value.emotion || '',
    durationMinutes: topic.value.durationMinutes,
    potentialScore: topic.value.potentialScore,
    seriesId: topic.value.seriesId,
    historicalReference: topic.value.historicalReference || '',
    notes: topic.value.notes || '',
  }
  editing.value = true
  saveError.value = null
  if (seriesOptions.value.length === 0) {
    listSeries().then((s) => { seriesOptions.value = s })
      .catch((e) => console.warn('[topic-detail series-options]', e?.message))
  }
}

function cancelEdit() {
  editing.value = false
  editForm.value = null
  saveError.value = null
}

async function saveEdit() {
  if (!topic.value || !editForm.value) return
  if (!editForm.value.title.trim()) {
    saveError.value = '标题必填'
    return
  }
  saving.value = true
  saveError.value = null
  try {
    const f = editForm.value
    topic.value = await updateTopic(topic.value.id, {
      title: f.title.trim(),
      projectName: f.projectName.trim() || null,
      dynasty: f.dynasty,
      genre: f.genre,
      protagonist: f.protagonist,
      hookType: f.hookType,
      emotion: f.emotion,
      durationMinutes: f.durationMinutes,
      potentialScore: f.potentialScore,
      seriesId: f.seriesId,
      historicalReference: f.historicalReference,
      notes: f.notes,
    })
    editing.value = false
    editForm.value = null
  } catch (e: any) {
    saveError.value = extractError(e, '保存失败')
  } finally {
    saving.value = false
  }
}

async function changeStatus(s: TopicStatus) {
  if (!topic.value || statusBusy.value || topic.value.status === s) return
  if (s === 'ARCHIVED' || s === 'PUBLISHED') {
    if (!confirm(`确认把状态改为「${TOPIC_STATUS_LABELS[s]}」?`)) return
  }
  statusBusy.value = true
  statusError.value = null
  try {
    topic.value = await updateTopic(topic.value.id, { status: s })
  } catch (e: any) {
    statusError.value = extractError(e, '切换失败')
  } finally {
    statusBusy.value = false
  }
}

async function onDelete() {
  if (!topic.value) return
  if (!confirm(`真删除选题「${topic.value.title}」?\n如果已生成过脚本会被后端拒绝(409),建议走「归档」。`)) return
  deleting.value = true
  try {
    await deleteTopic(topic.value.id)
    router.push('/topics')
  } catch (e: any) {
    const status = e?.response?.status
    const msg = extractError(e, '删除失败')
    if (status === 409) {
      alert(`无法删除:${msg}\n\n建议:点击下方状态栏「已归档」做软删。`)
    } else {
      alert(`删除失败:${msg}`)
    }
  } finally {
    deleting.value = false
  }
}

async function onGenerateScript() {
  if (!topic.value) return
  poll.reset()
  generateMsg.value = null
  generatedScriptId.value = null
  try {
    const { runId } = await generateScriptAsync(topic.value.id)
    generateMsg.value = `已起 run ${runId},正在轮询进度...`
    await poll.start(runId)
  } catch (e: any) {
    generateMsg.value = `失败:${extractError(e)}`
  }
}

onMounted(async () => {
  await load()
  try {
    const sp = await listScripts({ topicId: props.id, size: 1 })
    if (sp.content.length > 0) generatedScriptId.value = sp.content[0].id
  } catch (e: any) {
    console.warn('[topic-detail latest-script lookup]', e?.message)
  }
  if (topic.value?.sourceHook) {
    try {
      const chain = await getTopicLineage(props.id)
      lineage.value = chain.length >= 2 ? chain : []
    } catch (e: any) {
      console.warn('[topic-detail lineage]', e?.message)
    }
  }
  if (route.query.autoGen === '1' && topic.value && !generatedScriptId.value) {
    router.replace({ path: route.path, query: {} })
    await onGenerateScript()
  }
})

const STATUS_BADGE: Record<TopicStatus, string> = {
  DRAFT: 'bg-status-paused/15 text-status-paused',
  SCHEDULED: 'bg-accent-soft text-accent',
  PRODUCED: 'bg-status-done/15 text-status-done',
  PUBLISHED: 'bg-status-done/15 text-status-done',
  ARCHIVED: 'bg-surface-tertiary text-text-muted',
}

const scoreColor = computed(() => {
  if (!topic.value?.potentialScore) return 'text-text-muted'
  const s = topic.value.potentialScore
  if (s >= 80) return 'text-status-done'
  if (s >= 60) return 'text-accent'
  return 'text-status-paused'
})
</script>

<template>
  <div class="min-h-full">
    <div class="sticky top-0 z-10 bg-surface-primary border-b border-border-subtle">
      <div class="px-8 py-3 max-w-[1400px] mx-auto flex flex-col gap-1">
        <div class="flex items-center gap-4 flex-wrap">
          <button
            class="flex items-center gap-1.5 text-sm text-text-muted hover:text-text-primary"
            @click="$router.push('/topics')"
          >
            <ArrowLeft :size="14" /> 选题池
          </button>
          <h1 class="text-lg font-semibold flex items-center gap-2">
            <Lightbulb :size="16" class="text-accent" />
            <span class="truncate max-w-[480px]">{{ topic?.title || `选题 #${id}` }}</span>
          </h1>
          <span v-if="topic" class="chip text-[11px] font-semibold" :class="STATUS_BADGE[topic.status]">
            {{ TOPIC_STATUS_LABELS[topic.status] }}
          </span>
          <span v-if="topic" class="chip text-[11px] bg-surface-tertiary text-text-muted font-mono">#{{ topic.id }}</span>
          <Loader2 v-if="loading" :size="14" class="animate-spin text-text-muted ml-auto" />
          <button
            v-if="topic && !editing"
            class="ml-auto btn-primary text-sm"
            :disabled="!!isRunning || needsPresetInput"
            :title="needsPresetInput ? '请先配置预设输入' : ''"
            @click="onGenerateScript"
          >
            <Loader2 v-if="isRunning" :size="13" class="animate-spin" />
            <Sparkles v-else :size="13" />
            {{ isRunning ? '生成中…' : '生成脚本' }}
          </button>
        </div>
        <div class="text-xs text-text-muted">
          <span v-if="topic?.dynasty">{{ topic.dynasty }}</span>
          <span v-if="topic?.genre"> · {{ topic.genre }}</span>
          <span v-if="topic?.hookType"> · 钩子 {{ topic.hookType }}</span>
          <span v-else-if="!topic">选题详情 · 编辑 / 状态推进 / 生成脚本</span>
        </div>
      </div>
    </div>

    <div class="px-8 py-5 max-w-[1400px] mx-auto">
      <ErrorBanner :msg="errorMsg" />

      <div v-if="loading" class="card p-12 text-center text-text-muted">
        <Loader2 :size="20" class="animate-spin mx-auto" />
      </div>

      <div v-else-if="topic" class="grid grid-cols-1 lg:grid-cols-[1fr_360px] gap-4">
      <div class="flex flex-col gap-4 min-w-0">
        <div class="card p-6">
          <div class="flex items-center gap-2 flex-wrap mb-3">
            <span class="chip text-[10px] font-semibold" :class="STATUS_BADGE[topic.status]">
              {{ TOPIC_STATUS_LABELS[topic.status] }}
            </span>
            <span v-if="topic.dynasty" class="chip text-[11px] bg-surface-secondary text-text-secondary">#{{ topic.dynasty }}</span>
            <span v-if="topic.genre" class="chip text-[11px] bg-surface-secondary text-text-secondary">#{{ topic.genre }}</span>
            <span v-if="topic.hookType" class="chip text-[11px] bg-surface-secondary text-text-secondary">#{{ topic.hookType }}</span>
            <span class="text-xs text-text-muted ml-auto font-mono">#{{ topic.id }}</span>
          </div>

          <h1 v-if="!editing" class="text-2xl font-bold leading-snug mb-3">{{ topic.title }}</h1>

          <template v-if="!editing">
            <p v-if="topic.notes" class="text-sm text-text-secondary leading-relaxed whitespace-pre-wrap">{{ topic.notes }}</p>
            <p v-else-if="topic.historicalReference" class="text-sm text-text-secondary leading-relaxed line-clamp-4">{{ topic.historicalReference }}</p>
            <p v-else class="text-sm text-text-muted italic">暂无描述</p>

            <div class="grid grid-cols-2 md:grid-cols-4 gap-4 mt-5 pt-4 border-t border-border-subtle text-sm">
              <div>
                <div class="text-xs text-text-muted flex items-center gap-1.5"><Tag :size="11" /> 主角</div>
                <div class="mt-0.5 truncate flex items-center gap-2 flex-wrap">
                  <span class="truncate">{{ topic.protagonist || '-' }}</span>
                  <button
                    class="chip cursor-pointer text-[10px] flex items-center gap-1 shrink-0"
                    :class="presetInputConfigured
                      ? 'bg-status-done/15 text-status-done'
                      : 'bg-status-failed/15 text-status-failed'"
                    @click="presetInputDrawerOpen = true"
                  >
                    <UserCheck :size="10" />
                    {{ presetInputConfigured ? '预设输入已配置' : '配置预设输入' }}
                  </button>
                  <button
                    class="chip cursor-pointer text-[10px] flex items-center gap-1 shrink-0"
                    :class="directorNoteConfigured
                      ? 'bg-status-done/15 text-status-done'
                      : 'bg-amber-500/15 text-amber-700'"
                    @click="directorNoteDrawerOpen = true"
                    title="总导演产出全片 vision"
                  >
                    🎬
                    {{ directorNoteConfigured ? '导演笔记' : '配置导演笔记' }}
                  </button>
                </div>
              </div>
              <div>
                <div class="text-xs text-text-muted flex items-center gap-1.5">情绪</div>
                <div class="mt-0.5">{{ topic.emotion || '-' }}</div>
              </div>
              <div>
                <div class="text-xs text-text-muted flex items-center gap-1.5"><Clock :size="11" /> 时长</div>
                <div class="mt-0.5 font-mono">{{ topic.durationMinutes != null ? topic.durationMinutes + ' 分钟' : '-' }}</div>
              </div>
              <div>
                <div class="text-xs text-text-muted">系列</div>
                <div class="mt-0.5">
                  <button v-if="topic.seriesId"
                          class="text-accent hover:underline font-mono"
                          @click="$router.push(`/series/${topic.seriesId}`)">
                    #{{ topic.seriesId }}
                  </button>
                  <span v-else-if="topic.aiSuggestedSeries" class="text-text-secondary text-xs">
                    {{ topic.aiSuggestedSeries }}
                    <span class="text-[10px] text-text-muted ml-1">(AI 建议)</span>
                  </span>
                  <span v-else class="text-text-muted">-</span>
                </div>
              </div>
            </div>

            <div class="flex items-center gap-2 mt-5">
              <button class="chip cursor-pointer bg-surface-tertiary text-text-secondary text-sm" @click="startEdit">
                <Pencil :size="13" /> 编辑
              </button>
              <button class="chip cursor-pointer bg-status-failed/15 text-status-failed text-sm"
                      :disabled="deleting"
                      @click="onDelete">
                <Trash2 :size="13" /> {{ deleting ? '删除中…' : '删除' }}
              </button>
              <span class="text-xs text-text-muted ml-auto">来源 {{ topic.source }}</span>
            </div>
          </template>

          <template v-else-if="editForm">
            <div v-if="saveError" class="card p-3 mb-3 bg-status-failed/10 border-status-failed/30 text-xs text-status-failed">
              {{ saveError }}
            </div>

            <div class="grid grid-cols-1 md:grid-cols-2 gap-3 text-sm">
              <label class="flex flex-col gap-1 md:col-span-2">
                <span class="text-xs text-text-muted">标题 *</span>
                <input v-model="editForm.title" type="text" maxlength="200"
                       class="bg-surface-tertiary border border-border-subtle rounded px-2 py-1.5" />
              </label>
              <label class="flex flex-col gap-1 md:col-span-2">
                <span class="text-xs text-text-muted">
                  项目名
                  <span class="text-text-muted/70">(留空则自动取标题前 10 字)</span>
                </span>
                <input v-model="editForm.projectName" type="text" maxlength="40"
                       class="bg-surface-tertiary border border-border-subtle rounded px-2 py-1.5" />
              </label>
              <label class="flex flex-col gap-1">
                <span class="text-xs text-text-muted">朝代</span>
                <input v-model="editForm.dynasty" type="text" maxlength="40"
                       class="bg-surface-tertiary border border-border-subtle rounded px-2 py-1.5" />
              </label>
              <label class="flex flex-col gap-1">
                <span class="text-xs text-text-muted">题材</span>
                <input v-model="editForm.genre" type="text" maxlength="40"
                       class="bg-surface-tertiary border border-border-subtle rounded px-2 py-1.5" />
              </label>
              <label class="flex flex-col gap-1">
                <span class="text-xs text-text-muted">主角</span>
                <input v-model="editForm.protagonist" type="text" maxlength="120"
                       class="bg-surface-tertiary border border-border-subtle rounded px-2 py-1.5" />
              </label>
              <label class="flex flex-col gap-1">
                <span class="text-xs text-text-muted">钩子类型</span>
                <input v-model="editForm.hookType" type="text" maxlength="40"
                       placeholder="反逻辑 / 数字冲击 / 时间地点反常 / 未解之谜 / 反差身份"
                       class="bg-surface-tertiary border border-border-subtle rounded px-2 py-1.5" />
              </label>
              <label class="flex flex-col gap-1">
                <span class="text-xs text-text-muted">情绪</span>
                <input v-model="editForm.emotion" type="text" maxlength="40"
                       class="bg-surface-tertiary border border-border-subtle rounded px-2 py-1.5" />
              </label>
              <label class="flex flex-col gap-1">
                <span class="text-xs text-text-muted">时长(分钟,1-60)</span>
                <input v-model.number="editForm.durationMinutes" type="number" min="1" max="60"
                       class="bg-surface-tertiary border border-border-subtle rounded px-2 py-1.5 font-mono" />
              </label>
              <label class="flex flex-col gap-1">
                <span class="text-xs text-text-muted">潜力分(0-100)</span>
                <input v-model.number="editForm.potentialScore" type="number" min="0" max="100" step="0.1"
                       class="bg-surface-tertiary border border-border-subtle rounded px-2 py-1.5 font-mono" />
              </label>
              <label class="flex flex-col gap-1 md:col-span-2">
                <span class="text-xs text-text-muted">系列</span>
                <select v-model="editForm.seriesId"
                        class="bg-surface-tertiary border border-border-subtle rounded px-2 py-1.5">
                  <option :value="null">— 不归属任何系列 —</option>
                  <option v-for="s in seriesOptions" :key="s.id" :value="s.id">
                    #{{ s.id }} · {{ s.name }} ({{ s.slug }})
                  </option>
                </select>
              </label>
              <label class="flex flex-col gap-1 md:col-span-2">
                <span class="text-xs text-text-muted">史料引用</span>
                <textarea v-model="editForm.historicalReference" rows="3"
                          class="bg-surface-tertiary border border-border-subtle rounded px-2 py-1.5 font-sans resize-y" />
              </label>
              <label class="flex flex-col gap-1 md:col-span-2">
                <span class="text-xs text-text-muted">备注</span>
                <textarea v-model="editForm.notes" rows="3"
                          class="bg-surface-tertiary border border-border-subtle rounded px-2 py-1.5 font-sans resize-y" />
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

        <div v-if="!editing" class="card p-3 flex items-center gap-2 flex-wrap">
          <span class="text-xs text-text-muted px-1">状态切换</span>
          <button v-for="s in ALL_TOPIC_STATUSES" :key="s"
                  :class="['chip text-xs cursor-pointer', topic.status === s
                    ? 'bg-accent-soft text-accent'
                    : 'bg-surface-tertiary text-text-secondary hover:text-text-primary']"
                  :disabled="statusBusy"
                  @click="changeStatus(s)">
            {{ TOPIC_STATUS_LABELS[s] }}
          </button>
          <Loader2 v-if="statusBusy" :size="12" class="animate-spin text-text-muted ml-1" />
          <span v-if="statusError" class="text-xs text-status-failed ml-2">{{ statusError }}</span>
        </div>

        <div v-if="lineage.length >= 2" class="card p-4 border-accent/20 bg-accent-soft/20">
          <div class="text-xs text-text-muted mb-2 flex items-center gap-1.5">
            <Link2 :size="12" class="text-accent" />
            系列追溯 · {{ lineage.length }} 集
          </div>
          <div class="flex items-center gap-1.5 flex-wrap text-xs">
            <template v-for="(n, idx) in lineage" :key="n.topicId">
              <button
                :class="[
                  'chip px-2 py-1 cursor-pointer flex items-center gap-1',
                  n.topicId === topic.id
                    ? 'bg-accent text-white'
                    : 'bg-surface-tertiary text-text-secondary hover:text-text-primary'
                ]"
                :title="n.title"
                @click="n.topicId !== topic.id && $router.push(`/topics/${n.topicId}`)"
              >
                <span class="font-mono">{{ n.topicId }}</span>
                <span class="truncate max-w-[140px]">{{ n.projectName || n.title }}</span>
              </button>
              <ChevronRight v-if="idx < lineage.length - 1" :size="12" class="text-text-muted shrink-0" />
            </template>
          </div>
        </div>

        <div v-if="!editing && topic.historicalReference" class="card p-5">
          <h2 class="text-sm font-semibold mb-2 flex items-center gap-2">
            <FileText :size="14" class="text-text-muted" /> 史料引用
          </h2>
          <p class="text-sm text-text-secondary leading-relaxed whitespace-pre-wrap">{{ topic.historicalReference }}</p>
        </div>

        <div v-if="topic.sourceHook" class="card p-4 border-accent/30 bg-accent-soft/30">
          <div class="flex items-center gap-2 text-sm font-medium mb-2">
            <Link2 :size="14" class="text-accent" />
            来自上一集钩子
          </div>
          <div class="text-xs text-text-muted mb-2">
            上一集脚本:
            <button class="text-accent hover:underline font-mono"
                    @click="$router.push(`/scripts/${topic.sourceHook.fromScriptId}`)">
              {{ topic.sourceHook.fromScriptId }}
            </button>
          </div>
          <div class="text-sm text-text-secondary leading-relaxed line-clamp-3">
            "{{ topic.sourceHook.hookText }}"
          </div>
          <div v-if="topic.sourceHook.nextEpisodeHint" class="text-xs text-text-muted mt-2">
            LLM 总结:{{ topic.sourceHook.nextEpisodeHint }}
          </div>
        </div>
      </div>

      <div class="flex flex-col gap-4">
        <div class="card p-5">
          <div class="flex items-center gap-2 mb-3">
            <Sparkles :size="14" class="text-accent" />
            <span class="text-sm font-semibold">AI 潜力分</span>
          </div>
          <div class="flex items-baseline gap-1">
            <span :class="['text-5xl font-bold leading-none font-mono', scoreColor]">
              {{ topic.potentialScore ?? '—' }}
            </span>
            <span class="text-sm text-text-muted">/ 100</span>
          </div>
          <div class="mt-3 p-2.5 bg-surface-secondary rounded text-xs text-text-secondary leading-relaxed flex gap-1.5">
            <Info :size="12" class="text-text-muted shrink-0 mt-0.5" />
            <span>brainstorm 阶段 LLM 给的潜力评估,80+ 强烈推荐立项</span>
          </div>
        </div>

        <div class="card p-5">
          <div class="flex items-center gap-2 mb-3">
            <FileText :size="14" class="text-accent" />
            <span class="text-sm font-semibold">生成脚本</span>
          </div>
          <p class="text-xs text-text-muted mb-3 leading-relaxed">
            异步触发 generateScript,前端每 3s 轮询。LLM 通常 25-40s 出结果。
          </p>
          <div v-if="needsPresetInput"
               class="card p-2.5 mb-3 bg-status-failed/10 border-status-failed/30 flex items-start gap-2">
            <Info :size="13" class="text-status-failed shrink-0 mt-0.5" />
            <div class="text-[11px] text-status-failed leading-relaxed">
              生成脚本需要先填好预设输入字段
              <button class="underline ml-1 hover:text-text-primary" @click="presetInputDrawerOpen = true">立即配置 →</button>
            </div>
          </div>
          <button class="btn-primary w-full justify-center" :disabled="!!isRunning || editing || needsPresetInput" @click="onGenerateScript">
            <Loader2 v-if="isRunning" :size="14" class="animate-spin" />
            <Sparkles v-else :size="14" />
            {{ isRunning ? '生成中…' : '生成脚本' }}
          </button>

          <div v-if="isRunning"
               class="mt-3 p-2.5 rounded-md bg-status-running/10 border border-status-running/30 flex items-center gap-2">
            <Loader2 :size="13" class="text-status-running animate-spin shrink-0" />
            <span class="text-[11px] text-status-running flex-1">
              run #{{ activeRun?.id }} · {{ activeRun ? RUN_STATUS_LABELS[activeRun.status] : '' }}
            </span>
          </div>

          <div v-else-if="generateMsg && generateMsg.startsWith('失败')"
               class="mt-3 p-2.5 rounded-md bg-status-failed/10 border border-status-failed/30 flex items-start gap-2">
            <Info :size="13" class="text-status-failed shrink-0 mt-0.5" />
            <span class="text-[11px] text-status-failed flex-1 leading-relaxed">{{ generateMsg }}</span>
          </div>

          <div v-else-if="generatedScriptId"
               class="mt-3 p-2.5 rounded-md bg-accent-soft/40 border border-accent/20 flex items-center gap-2">
            <CheckCircle2 :size="14" class="text-accent shrink-0" />
            <div class="flex items-baseline gap-1.5 flex-1 min-w-0">
              <span class="text-[11px] text-text-secondary">脚本</span>
              <span class="font-mono text-sm font-semibold text-accent">#{{ generatedScriptId }}</span>
              <span class="text-[11px] text-text-muted">{{ generateMsg ? '已生成' : '已就绪' }}</span>
            </div>
            <button class="chip cursor-pointer bg-accent text-white hover:bg-accent-hover text-[11px] flex items-center gap-1 shrink-0"
                    @click="$router.push(`/scripts/${generatedScriptId}`)">
              打开 <ArrowRight :size="11" />
            </button>
          </div>
        </div>

        <div v-if="topic.hookType || topic.emotion" class="card p-5">
          <div class="flex items-center gap-2 mb-3">
            <Lightbulb :size="14" class="text-status-paused" />
            <span class="text-sm font-semibold">写作要点</span>
          </div>
          <ul class="space-y-2 text-xs text-text-secondary">
            <li v-if="topic.hookType" class="flex gap-2">
              <span class="w-4 h-4 rounded-full bg-accent-soft flex items-center justify-center text-[10px] text-accent shrink-0">1</span>
              <span>钩子类型「{{ topic.hookType }}」—— 开场 3 秒抛出最大悬念</span>
            </li>
            <li v-if="topic.emotion" class="flex gap-2">
              <span class="w-4 h-4 rounded-full bg-accent-soft flex items-center justify-center text-[10px] text-accent shrink-0">2</span>
              <span>主导情绪「{{ topic.emotion }}」—— 节奏与音乐层服务这一情绪</span>
            </li>
            <li v-if="topic.durationMinutes" class="flex gap-2">
              <span class="w-4 h-4 rounded-full bg-accent-soft flex items-center justify-center text-[10px] text-accent shrink-0">3</span>
              <span>建议时长 {{ topic.durationMinutes }} 分钟,密度 1 个钩子 / 分钟</span>
            </li>
          </ul>
        </div>

        <div class="card p-5">
          <h3 class="text-sm font-semibold mb-3">流转记录</h3>
          <ul class="space-y-3">
            <li class="flex gap-2.5">
              <span class="w-2 h-2 rounded-full bg-status-done mt-1.5 shrink-0" />
              <div class="flex-1 min-w-0">
                <div class="text-xs">最近更新</div>
                <div class="text-xs text-text-muted mt-0.5"><TimeText :value="topic.updatedAt" relative /></div>
              </div>
            </li>
            <li class="flex gap-2.5">
              <span class="w-2 h-2 rounded-full bg-text-muted mt-1.5 shrink-0" />
              <div class="flex-1 min-w-0">
                <div class="text-xs flex items-center gap-1.5"><Calendar :size="11" class="text-text-muted" /> 创建</div>
                <div class="text-xs text-text-muted mt-0.5"><TimeText :value="topic.createdAt" relative /></div>
              </div>
            </li>
          </ul>
        </div>
      </div>
    </div>
    </div>

    <DirectorNoteDrawer
      v-if="topic"
      :open="directorNoteDrawerOpen"
      :topic-id="topic.id"
      :initial-note="topic.directorNote ?? null"
      @close="directorNoteDrawerOpen = false; load()"
      @saved="() => load()"
    />

    <PresetInputDrawer
      v-if="topic"
      :open="presetInputDrawerOpen"
      :topic-id="topic.id"
      :preset-id="topic.presetId ?? null"
      :initial-input="topic.presetInputJson ?? null"
      @close="presetInputDrawerOpen = false; load()"
      @saved="onPresetInputSaved"
    />
  </div>
</template>
