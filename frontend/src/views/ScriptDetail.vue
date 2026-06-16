<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  ArrowLeft, ChevronRight, Film, Image as ImageIcon, Info, Loader2, Mic,
  Palette, Pencil, RotateCw, Save, Search, UserCheck, Workflow, X,
} from 'lucide-vue-next'
import { getScript, regenerateScriptAsync, updateScriptSection } from '../api/scripts'
import { getTopic } from '../api/topics'
import { useRunPoll } from '../composables/useRunPoll'
import { extractError, formatDuration } from '../lib/format'
import ErrorBanner from '../components/ErrorBanner.vue'
import TimeText from '../components/TimeText.vue'
import type { Script, ScriptSection, Topic } from '../types'
import { SCRIPT_STATUS_LABELS } from '../types'

const props = defineProps<{ id: number }>()

const script = ref<Script | null>(null)
const sections = ref<ScriptSection[]>([])
const topic = ref<Topic | null>(null)
const loading = ref(false)
const errorMsg = ref<string | null>(null)

function sectionLabel(_idx: number, code: string): string {
  return `[${code}]`
}

const editingId = ref<number | null>(null)
const draftText = ref('')
const draftTitle = ref('')
const saving = ref(false)

const regenOpen = ref(false)
const regenAnchor = ref('')
const regenSubmitting = ref(false)
const regenMsg = ref<string | null>(null)

async function load() {
  loading.value = true
  try {
    const resp = await getScript(props.id)
    script.value = resp.script
    sections.value = resp.sections ?? []
    errorMsg.value = null
    if (resp.script?.topicId) {
      try {
        topic.value = await getTopic(resp.script.topicId)
      } catch (e: any) {
        console.warn('[script-detail topic-lookup]', e?.message)
      }
    }
  } catch (e: any) {
    errorMsg.value = extractError(e, 'fetch failed')
  } finally {
    loading.value = false
  }
}

function startEdit(s: ScriptSection) {
  editingId.value = s.id
  draftText.value = s.textContent || ''
  draftTitle.value = s.title || ''
}
function cancelEdit() {
  editingId.value = null
}
async function saveSection() {
  if (editingId.value == null) return
  const s = sections.value.find((x) => x.id === editingId.value)
  if (!s) return
  saving.value = true
  try {
    const updated = await updateScriptSection(props.id, s.id, {
      textContent: draftText.value,
      title: draftTitle.value || null,
    })
    s.textContent = updated.textContent ?? draftText.value
    s.title = updated.title ?? null
    editingId.value = null
    errorMsg.value = null
  } catch (e: any) {
    errorMsg.value = extractError(e, 'save failed')
  } finally {
    saving.value = false
  }
}

const regenPoll = useRunPoll({
  onDone: async () => {
    regenMsg.value = '重写完成,刷新中…'
    try {
      await load()
      setTimeout(() => {
        regenOpen.value = false
        regenSubmitting.value = false
      }, 600)
    } catch {
      regenMsg.value = '后端跑完但页面刷新失败,请手动刷新'
      regenSubmitting.value = false
    }
  },
  onFailed: (r) => {
    regenMsg.value = `失败:${r.errorMsg ?? r.status}`
    regenSubmitting.value = false
  },
})
const regenRun = regenPoll.run

function openRegen() {
  regenAnchor.value = ''
  regenMsg.value = null
  regenPoll.reset()
  regenOpen.value = true
}

function closeRegen() {
  if (regenSubmitting.value && regenRun.value && regenRun.value.status !== 'DONE'
      && regenRun.value.status !== 'FAILED' && regenRun.value.status !== 'CANCELLED') {
    return
  }
  regenPoll.stop()
  regenOpen.value = false
}

async function submitRegen() {
  if (regenSubmitting.value) return
  regenSubmitting.value = true
  regenMsg.value = null
  regenPoll.reset()
  try {
    const { runId } = await regenerateScriptAsync(props.id, regenAnchor.value || null)
    regenMsg.value = `已起 run ${runId},LLM 重写中…(script ≈ 27s,opus 锚点重写可能更长)`
    await regenPoll.start(runId)
  } catch (e: any) {
    regenMsg.value = `提交失败:${extractError(e)}`
    regenSubmitting.value = false
  }
}

onMounted(load)

const STATUS_COLOR: Record<string, string> = {
  DRAFT: 'bg-status-paused/15 text-status-paused',
  REVIEWING: 'bg-accent-soft text-accent',
  APPROVED: 'bg-status-done/15 text-status-done',
  ARCHIVED: 'bg-surface-tertiary text-text-muted',
}

function fmtDur(secs: number | null | undefined): string {
  return formatDuration(secs)
}

const reviewColor = computed(() => {
  const r = script.value?.reviewScore
  if (r == null) return 'text-text-muted'
  if (r >= 80) return 'text-status-done'
  if (r >= 60) return 'text-text-primary'
  return 'text-status-paused'
})

interface DownstreamStep {
  key: string
  icon: any
  label: string
  desc: string
  to: string
}
const downstream = computed<DownstreamStep[]>(() => script.value ? [
  { key: 'factcheck', icon: Search, label: '事实核查', desc: '校对史料锚点', to: `/factcheck/${script.value.id}` },
  { key: 'voice', icon: Mic, label: '配音字幕', desc: '生成 SRT(分镜依赖)', to: `/voice?scriptId=${script.value.id}` },
  { key: 'storyboard', icon: Film, label: '分镜', desc: '按 SRT 切镜头', to: `/storyboard/${script.value.id}` },
  { key: 'image', icon: ImageIcon, label: '生图', desc: '基于分镜出图', to: `/images/${script.value.id}` },
  { key: 'assembly', icon: Film, label: '组装预览', desc: '把图 + 配音组装成视频', to: `/assembly/${script.value.id}` },
  { key: 'cover', icon: Palette, label: '封面', desc: '可独立做', to: `/scripts/${script.value.id}/cover` },
] : [])
</script>

<template>
  <div class="min-h-full">
    <div class="sticky top-0 z-10 bg-surface-primary border-b border-border-subtle">
      <div class="px-8 py-3 max-w-[1400px] mx-auto flex items-center gap-4 flex-wrap">
        <button
          class="flex items-center gap-1.5 text-sm text-text-muted hover:text-text-primary"
          @click="$router.push('/scripts')"
        >
          <ArrowLeft :size="14" /> 返回脚本列表
        </button>
        <span v-if="script" class="text-sm text-text-secondary">
          脚本 #{{ script.id }} · v{{ script.version }} ·
          <a class="text-text-secondary hover:text-accent hover:underline cursor-pointer"
             @click="$router.push(`/topics/${script.topicId}`)">
            topic #{{ script.topicId }}
          </a>
        </span>
        <span v-if="script" class="chip text-[11px] font-semibold" :class="STATUS_COLOR[script.status] ?? STATUS_COLOR.DRAFT">
          {{ SCRIPT_STATUS_LABELS[script.status] }}
        </span>
        <span v-if="topic" class="chip text-[11px] font-semibold flex items-center gap-1 bg-accent-soft text-accent"
              title="该脚本所属预设(P3 后由 preset.name 决定)">
          <UserCheck :size="11" />
          {{ topic.presetId ? `preset #${topic.presetId}` : '无预设' }}
        </span>
        <div class="ml-auto flex items-center gap-2">
          <button v-if="script" class="btn-primary" @click="openRegen">
            <RotateCw :size="13" /> 重新生成
          </button>
        </div>
      </div>
    </div>

    <div class="px-8 py-5 max-w-[1400px] mx-auto">
      <ErrorBanner :msg="errorMsg" />

      <div v-if="loading" class="card p-12 text-center text-text-muted">
        <Loader2 :size="20" class="animate-spin mx-auto" />
      </div>

      <template v-else-if="script">
        <div class="grid grid-cols-1 lg:grid-cols-[1fr_340px] gap-4">
          <div class="flex flex-col gap-4 min-w-0">
            <div class="card p-5 flex items-stretch gap-5 flex-wrap">
              <div class="min-w-[100px]">
                <div class="text-xs text-text-muted">字数</div>
                <div class="mt-1 flex items-baseline gap-1">
                  <span class="text-2xl font-mono font-bold leading-none">{{ script.wordCount ?? '—' }}</span>
                  <span class="text-xs text-text-muted">字</span>
                </div>
              </div>
              <div class="w-px bg-border-subtle" />
              <div class="min-w-[100px]">
                <div class="text-xs text-text-muted">预计时长</div>
                <div class="mt-1 text-2xl font-mono font-bold leading-none">{{ fmtDur(script.durationSeconds) }}</div>
              </div>
              <div class="w-px bg-border-subtle" />
              <div class="min-w-[140px] flex-1">
                <div class="text-xs text-text-muted">模型</div>
                <div class="mt-1 text-sm font-medium truncate">{{ script.modelUsed || '—' }}</div>
              </div>
              <div class="w-px bg-border-subtle" />
              <div class="min-w-[100px]">
                <div class="text-xs text-text-muted">复审分</div>
                <div class="mt-1 flex items-baseline gap-1">
                  <span class="text-2xl font-mono font-bold leading-none" :class="reviewColor">
                    {{ script.reviewScore ?? '—' }}
                  </span>
                  <span class="text-xs text-text-muted">/100</span>
                </div>
              </div>
            </div>

            <div class="card p-5">
              <div class="flex items-center justify-between mb-4 flex-wrap gap-2">
                <div class="flex items-center gap-2">
                  <h2 class="text-base font-semibold">脚本分段</h2>
                  <span class="chip bg-surface-tertiary text-text-muted text-[11px]">{{ sections.length }} 段</span>
                </div>
                <span class="text-xs text-text-muted flex items-center gap-1">
                  <Pencil :size="11" /> hover 各段编辑
                </span>
              </div>

              <div v-if="sections.length === 0" class="text-sm text-text-muted py-4 text-center">
                没有 section 数据
              </div>
              <ol v-else class="flex flex-col gap-4">
                <li
                  v-for="(s, idx) in sections" :key="s.id"
                  class="flex gap-4 group"
                >
                  <span class="w-[3px] rounded shrink-0"
                        :class="s.isGoldenLine ? 'bg-status-paused' : 'bg-accent/40'" />
                  <div class="flex-1 min-w-0">
                    <div class="flex items-center gap-2 flex-wrap">
                      <span class="font-mono text-accent text-xs font-bold">{{ sectionLabel(idx, s.sectionCode) }}</span>
                      <span v-if="s.title" class="text-sm font-semibold">{{ s.title }}</span>
                      <span v-if="s.startSeconds != null" class="text-xs text-text-muted">
                        {{ s.startSeconds }}s – {{ s.endSeconds ?? '?' }}s
                      </span>
                      <span v-if="s.isGoldenLine"
                            class="chip bg-status-paused/15 text-status-paused text-[10px]">
                        金句
                      </span>
                      <button v-if="editingId !== s.id"
                              class="ml-auto opacity-0 group-hover:opacity-100 text-text-muted hover:text-accent text-xs flex items-center gap-1"
                              @click="startEdit(s)">
                        <Pencil :size="12" /> 编辑
                      </button>
                    </div>

                    <div v-if="editingId === s.id" class="mt-2">
                      <input v-model="draftTitle" type="text" placeholder="段落标题(可空)"
                             class="bg-surface-tertiary border border-border-subtle rounded px-2 py-1 text-xs mb-2 w-full" />
                      <textarea v-model="draftText" rows="6"
                                class="w-full bg-surface-tertiary border border-border-subtle rounded p-2 text-sm font-sans resize-y" />
                      <div class="flex items-center gap-2 mt-2">
                        <button class="chip cursor-pointer text-xs bg-surface-tertiary text-text-secondary"
                                :disabled="saving" @click="cancelEdit">
                          <X :size="11" /> 取消
                        </button>
                        <button class="chip cursor-pointer text-xs bg-accent-soft text-accent disabled:opacity-50"
                                :disabled="saving" @click="saveSection">
                          <Save :size="11" /> {{ saving ? '保存中…' : '保存' }}
                        </button>
                      </div>
                    </div>
                    <template v-else>
                      <div class="text-sm text-text-secondary mt-1.5 leading-[1.7] whitespace-pre-wrap"
                           :class="{ 'text-text-primary font-medium': s.isGoldenLine }">
                        {{ s.textContent }}
                      </div>
                      <div v-if="s.directorNote"
                           class="mt-2 text-xs text-text-muted italic whitespace-pre-wrap border-l-2 border-border-subtle pl-2">
                        导演备注:{{ s.directorNote }}
                      </div>
                    </template>
                  </div>
                </li>
              </ol>
            </div>

            <details v-if="script.fullText">
              <summary class="cursor-pointer text-sm text-text-muted hover:text-text-primary">
                展开脚本全文
              </summary>
              <pre class="mt-3 p-4 card text-xs text-text-secondary whitespace-pre-wrap font-sans">{{ script.fullText }}</pre>
            </details>
          </div>

          <div class="flex flex-col gap-4">
            <div class="card p-4">
              <div class="flex items-center gap-2 mb-3">
                <Workflow :size="14" class="text-text-secondary" />
                <h3 class="text-sm font-semibold">下游流程</h3>
              </div>
              <ul class="flex flex-col gap-1">
                <li v-for="step in downstream" :key="step.key">
                  <button class="w-full text-left flex items-center gap-3 px-3 py-2 rounded-lg
                                 hover:bg-surface-tertiary group transition-colors"
                          @click="$router.push(step.to)">
                    <component :is="step.icon" :size="14" class="text-text-secondary shrink-0" />
                    <div class="flex-1 min-w-0">
                      <div class="text-sm font-medium">{{ step.label }}</div>
                      <div class="text-[11px] text-text-muted">{{ step.desc }}</div>
                    </div>
                    <ChevronRight :size="13" class="text-text-muted opacity-0 group-hover:opacity-100" />
                  </button>
                </li>
              </ul>
            </div>

            <div class="card p-3.5 flex items-start gap-2.5"
                 style="background: rgba(245, 158, 11, 0.06); border-color: rgba(245, 158, 11, 0.3);">
              <Info :size="14" class="text-status-paused mt-0.5 shrink-0" />
              <p class="text-xs leading-relaxed text-text-secondary">
                分镜会读取 voice SRT 把每个 shot 对齐到真实音频,务必先生成配音字幕。
              </p>
            </div>

            <div class="card p-4">
              <h3 class="text-sm font-semibold mb-3">版本与时间</h3>
              <dl class="flex flex-col gap-2 text-xs">
                <div class="flex items-center justify-between">
                  <dt class="text-text-muted">版本</dt>
                  <dd class="font-medium font-mono">v{{ script.version }}</dd>
                </div>
                <div class="flex items-center justify-between">
                  <dt class="text-text-muted">创建于</dt>
                  <dd class="font-medium"><TimeText :value="script.createdAt" relative /></dd>
                </div>
                <div class="flex items-center justify-between">
                  <dt class="text-text-muted">最后更新</dt>
                  <dd class="font-medium"><TimeText :value="script.updatedAt" relative /></dd>
                </div>
                <div class="flex items-center justify-between">
                  <dt class="text-text-muted">脚本 ID</dt>
                  <dd class="font-mono text-text-muted">#{{ script.id }}</dd>
                </div>
              </dl>
            </div>
          </div>
        </div>
      </template>
    </div>

    <div v-if="regenOpen"
         class="fixed inset-0 z-50 bg-black/60 flex items-center justify-center p-4"
         @click.self="closeRegen">
      <div class="card p-6 max-w-[560px] w-full">
        <header class="flex items-center justify-between mb-3">
          <h2 class="text-lg font-semibold flex items-center gap-2">
            <RotateCw :size="18" class="text-accent" />
            重新生成脚本
          </h2>
          <button class="text-text-muted hover:text-text-primary"
                  :disabled="regenSubmitting && !!regenRun && regenRun.status !== 'DONE' && regenRun.status !== 'FAILED' && regenRun.status !== 'CANCELLED'"
                  @click="closeRegen">
            <X :size="20" />
          </button>
        </header>

        <div class="text-xs text-text-muted mb-3 leading-relaxed">
          基于当前选题用 LLM 在<strong class="text-status-failed">原脚本上</strong>重写一份(保留脚本 ID 与
          v{{ script?.version ?? 1 }},不创建新版本)。
          <br/>
          <strong class="text-status-failed">⚠️ 已生成的分镜 / 图片 / 语音 / 视频 / 封面 / 事实核查问题 / 系列钩子会被一并清空</strong> ——
          重写后需要重新跑一遍下游。
          <br/>
          下面这段「锚点」会被原样塞进 prompt 作为本次的额外要求(留空 = 等价于普通重写)。
        </div>

        <label class="flex flex-col gap-1 text-sm mb-3">
          <span class="text-xs text-text-muted">锚点 / 自由指令(可空)</span>
          <textarea v-model="regenAnchor" rows="5" maxlength="800"
                    :disabled="regenSubmitting"
                    placeholder="例:把开场改成马皇后视角的回忆;D 段金句保留原意但换更口语化的说法;整体降低悬疑感,多带烟火气。"
                    class="bg-surface-tertiary border border-border-subtle rounded p-2 text-sm font-sans resize-y" />
          <span class="text-[10px] text-text-muted self-end">{{ regenAnchor.length }} / 800</span>
        </label>

        <div v-if="regenMsg"
             :class="['card p-3 mb-3 text-xs',
                      regenRun?.status === 'FAILED' || regenRun?.status === 'CANCELLED'
                        ? 'bg-status-failed/10 border-status-failed/30 text-status-failed'
                        : regenRun?.status === 'DONE'
                          ? 'bg-status-paused/10 border-status-paused/30 text-status-paused'
                          : 'bg-surface-tertiary text-text-secondary']">
          <div>{{ regenMsg }}</div>
          <div v-if="regenRun" class="mt-1 font-mono text-[10px] text-text-muted">
            run #{{ regenRun.id }} · {{ regenRun.status }}
          </div>
        </div>

        <footer class="flex items-center justify-end gap-2">
          <button class="btn-ghost"
                  :disabled="regenSubmitting && !!regenRun && regenRun.status !== 'DONE' && regenRun.status !== 'FAILED' && regenRun.status !== 'CANCELLED'"
                  @click="closeRegen">
            取消
          </button>
          <button class="btn-primary disabled:opacity-50"
                  :disabled="regenSubmitting"
                  @click="submitRegen">
            <Loader2 v-if="regenSubmitting" :size="14" class="animate-spin" />
            <RotateCw v-else :size="14" />
            {{ regenSubmitting ? '生成中…' : '提交重新生成' }}
          </button>
        </footer>
      </div>
    </div>
  </div>
</template>
