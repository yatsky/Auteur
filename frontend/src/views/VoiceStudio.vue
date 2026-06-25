<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ArrowLeft, Loader2, Mic, Play, RefreshCw, Subtitles } from 'lucide-vue-next'
import { useRecentScripts } from '../composables/useRecentScripts'
import { useRunPoll } from '../composables/useRunPoll'
import ErrorBanner from '../components/ErrorBanner.vue'
import TimeText from '../components/TimeText.vue'
import AudioPlayer from '../components/AudioPlayer.vue'
import { generateVoiceAsync, listVoices } from '../api/scripts'
import { previewVoice } from '../api/voiceCatalog'
import { extractError } from '../lib/format'
import { defaultVoice, ensureVoiceCatalogLoaded, voiceGroups, voiceLabelOf } from '../lib/voices'
import type { PipelineRunStatus, VoiceAsset } from '../types'

const { items, loading, errorMsg } = useRecentScripts()
const route = useRoute()

const selectedScriptId = ref<number | null>(null)
const selected = computed(() => items.value.find((s) => s.scriptId === selectedScriptId.value) ?? null)

const qsid = Number(route.query.scriptId)
if (Number.isFinite(qsid) && qsid > 0) selectedScriptId.value = qsid

const voiceModel = ref<string>('')  // catalog 加载完前空串,加载后设为 defaultVoice.value
const speed = ref(1.0)
const pitch = ref(0)
const subtitleStyle = ref<'standard' | 'highlight'>('standard')
const markFinal = ref(true)
const voiceSource = ref<'auto_default' | 'manual'>('manual')
const voiceSourceLabel = computed(() =>
  voiceSource.value === 'auto_default' ? `默认音色 · ${voiceLabelOf(defaultVoice.value)}` : '',
)

const voices = ref<VoiceAsset[]>([])
const voicesLoading = ref(false)
const voicesError = ref<string | null>(null)

const runId = ref<number | null>(null)
const runStatus = ref<PipelineRunStatus | null>(null)
const runMsg = ref<string | null>(null)
const generating = ref(false)

async function loadVoices(scriptId: number) {
  voicesLoading.value = true
  voicesError.value = null
  try {
    voices.value = await listVoices(scriptId)
  } catch (e: any) {
    voicesError.value = extractError(e, 'fetch failed')
  } finally {
    voicesLoading.value = false
  }
}

const poll = useRunPoll({
  onUpdate: (r) => { runStatus.value = r.status },
  onDone: async () => {
    generating.value = false
    runMsg.value = '生成 ✓'
    if (selectedScriptId.value != null) await loadVoices(selectedScriptId.value)
  },
  onFailed: (r) => {
    generating.value = false
    runMsg.value = `失败:${r.errorMsg ?? r.status}`
  },
})

watch(selectedScriptId, (sid) => {
  poll.reset()
  runId.value = null
  runStatus.value = null
  runMsg.value = null
  voices.value = []
  if (sid != null) {
    loadVoices(sid)
    resolveVoiceDefaults(sid)
  }
})

/**
 * 进 voice studio 时套 catalog 默认音色。用户改后 voiceSource=manual,提示消失。
 */
async function resolveVoiceDefaults(_scriptId: number) {
  voiceModel.value = defaultVoice.value
  speed.value = 1.0
  voiceSource.value = 'auto_default'
}

// 用户手动改音色后,清掉来源提示
watch(voiceModel, (_, prev) => {
  if (prev !== undefined && voiceSource.value !== 'manual') voiceSource.value = 'manual'
  demoUrl.value = null
})
watch(speed, () => { demoUrl.value = null })

// 进入页面时先 fetch catalog,再用 default。catalog 是单一来源,后端 VolcanoVoiceCatalog 改 → 自动同步。
onMounted(async () => {
  await ensureVoiceCatalogLoaded()
  if (selectedScriptId.value != null) resolveVoiceDefaults(selectedScriptId.value)
})

const demoing = ref(false)
const demoUrl = ref<string | null>(null)
const demoError = ref<string | null>(null)

async function triggerDemo() {
  demoing.value = true
  demoError.value = null
  demoUrl.value = null
  try {
    demoUrl.value = await previewVoice(voiceModel.value, speed.value)
  } catch (e: any) {
    demoError.value = extractError(e, '试听失败')
  } finally {
    demoing.value = false
  }
}

async function triggerGen() {
  if (selectedScriptId.value == null) return
  generating.value = true
  runStatus.value = 'PENDING'
  runMsg.value = null
  try {
    const { runId: rid } = await generateVoiceAsync(selectedScriptId.value, {
      voiceModel: voiceModel.value,
      voiceLabel: voiceLabelOf(voiceModel.value),
      speed: speed.value,
      pitch: pitch.value,
      subtitleStyle: subtitleStyle.value,
      markFinal: markFinal.value,
    })
    runId.value = rid
    await poll.start(rid)
  } catch (e: any) {
    generating.value = false
    runStatus.value = 'FAILED'
    runMsg.value = `失败:${extractError(e)}`
  }
}
</script>

<template>
  <div class="min-h-full">
    <div class="sticky top-0 z-10 bg-surface-primary border-b border-border-subtle">
      <div class="px-8 py-3 max-w-[1400px] mx-auto flex items-center justify-between gap-4">
        <div>
          <div class="text-xs text-text-muted flex items-center gap-1.5">
            <ArrowLeft :size="12" /> 配音字幕
          </div>
          <h1 class="text-lg font-semibold mt-0.5">配音工作台</h1>
        </div>
        <div class="flex items-center gap-2">
          <button class="btn-primary" :disabled="generating || !selected" @click="triggerGen">
            <Loader2 v-if="generating" :size="13" class="animate-spin" />
            <Mic v-else :size="13" />
            {{ generating ? `生成中… run ${runId ?? ''}` : '生成全片配音' }}
          </button>
        </div>
      </div>
    </div>

    <div class="px-8 py-5 max-w-[1400px] mx-auto">
      <ErrorBanner :msg="errorMsg" />

      <div class="card p-3 mb-4 bg-status-paused/10 border-status-paused/30 text-xs text-status-paused">
        ℹ️ provider:火山豆包 TTS。产物 mp3/srt 落本地 /api/files/voice/。
      </div>

      <div class="grid grid-cols-1 lg:grid-cols-[280px_1fr] gap-4">
        <aside class="card p-3 max-h-[680px] overflow-y-auto">
          <div class="text-xs text-text-muted px-2 py-1.5 flex items-center justify-between">
            <span>选 Script</span>
            <button v-if="loading" class="text-text-muted">
              <RefreshCw :size="11" class="animate-spin" />
            </button>
          </div>
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
            左侧选一个 script 开始配音
          </div>

          <div v-else class="grid grid-cols-1 xl:grid-cols-[1fr_320px] gap-4">
            <div class="space-y-4">
              <div class="card p-5">
                <div class="flex items-center justify-between mb-2">
                  <h2 class="text-base font-semibold flex items-center gap-2">
                    <Mic :size="15" class="text-accent" />
                    <span class="font-mono text-sm text-text-muted">#{{ selected.scriptId }}</span>
                    <span v-if="selected.projectName" class="text-text-secondary text-sm">{{ selected.projectName }}</span>
                  </h2>
                  <span v-if="runStatus" class="chip text-xs"
                        :class="{
                          'bg-status-running/15 text-status-running': runStatus === 'PENDING' || runStatus === 'RUNNING',
                          'bg-status-done/15 text-status-done': runStatus === 'DONE',
                          'bg-status-failed/15 text-status-failed': runStatus === 'FAILED' || runStatus === 'CANCELLED',
                        }">
                    {{ runStatus }}
                  </span>
                </div>
                <p v-if="runMsg" class="text-sm"
                   :class="runMsg.startsWith('失败') ? 'text-status-failed' : 'text-status-done'">
                  {{ runMsg }}
                </p>
                <p v-else class="text-xs text-text-muted">
                  右侧调好音色 / 语速 / 音调 → 顶部「生成全片配音」开跑。生成完会自动落到下面的历史产物。
                </p>
              </div>

              <div class="card p-5">
                <h2 class="text-base font-semibold mb-3 flex items-center gap-2">
                  <Subtitles :size="15" class="text-accent" /> 字幕样式
                </h2>
                <div class="flex gap-2 mb-3">
                  <button
                    v-for="s in (['standard', 'highlight'] as const)" :key="s"
                    :class="['chip cursor-pointer text-xs', subtitleStyle === s ? 'bg-accent-soft text-accent' : 'bg-surface-tertiary text-text-secondary']"
                    @click="subtitleStyle = s"
                  >
                    {{ s === 'standard' ? '标准' : '关键词高亮' }}
                  </button>
                </div>
                <div class="bg-surface-secondary rounded p-4 text-center text-sm border border-border-subtle">
                  <span v-if="subtitleStyle === 'standard'">朱元璋的死,藏着一个被改写六百年的秘密</span>
                  <span v-else>朱元璋的死,藏着一个被改写<span class="text-accent font-semibold">六百年</span>的<span class="text-accent font-semibold">秘密</span></span>
                </div>
                <p class="text-xs text-text-muted mt-2 leading-relaxed">
                  ⚠️ CSS 占位预览。TTS 产物只是标准 SRT,「highlight」由后期视频渲染管线烧字幕实现(LLM 抽词 → ASS 模板)。当前字段会落库 voice_asset.subtitle_style 留给视频渲染读取。
                </p>
              </div>

              <div class="card p-5">
                <div class="flex items-center justify-between mb-3">
                  <h2 class="text-base font-semibold">历史产物</h2>
                  <span class="chip text-xs bg-surface-tertiary text-text-muted">{{ voices.length }} 条</span>
                </div>
                <ErrorBanner :msg="voicesError" />
                <div v-if="voicesLoading && voices.length === 0" class="py-6 text-center">
                  <Loader2 :size="18" class="animate-spin text-text-muted mx-auto" />
                </div>
                <div v-else-if="voices.length === 0" class="text-sm text-text-muted py-4 text-center">
                  还没有产物 · 顶部「生成全片配音」开跑
                </div>
                <ul v-else class="space-y-2">
                  <li v-for="v in voices" :key="v.id"
                      class="border border-border-subtle rounded-md p-3 text-xs">
                    <div class="flex items-center justify-between gap-2 mb-2 flex-wrap">
                      <div class="flex items-center gap-2">
                        <span class="font-mono text-text-muted">#{{ v.id }}</span>
                        <span class="text-text-secondary">{{ v.voiceLabel ?? v.model }}</span>
                        <span v-if="v.isFinal" class="chip bg-status-done/15 text-status-done">final</span>
                      </div>
                      <TimeText :value="v.createdAt" relative class="text-text-muted" />
                    </div>
                    <div class="grid grid-cols-2 md:grid-cols-4 gap-3 text-xs">
                      <div>
                        <div class="text-text-muted">语速 / 音调</div>
                        <div class="font-mono">{{ Number(v.speed).toFixed(1) }}× / {{ v.pitch >= 0 ? '+' : '' }}{{ v.pitch }}</div>
                      </div>
                      <div>
                        <div class="text-text-muted">时长</div>
                        <div class="font-mono">{{ v.durationSeconds ?? '-' }}s</div>
                      </div>
                      <div>
                        <div class="text-text-muted">成本</div>
                        <div class="font-mono">¥{{ v.costYuan != null ? Number(v.costYuan).toFixed(4) : '-' }}</div>
                      </div>
                      <div class="min-w-0">
                        <div class="text-text-muted">SRT</div>
                        <div class="font-mono truncate text-text-muted" :title="v.subtitleUrl ?? ''">{{ v.subtitleUrl ?? '-' }}</div>
                      </div>
                    </div>
                    <AudioPlayer v-if="v.audioUrl" :src="v.audioUrl" class="mt-2" />
                  </li>
                </ul>
              </div>
            </div>

            <aside class="card p-5 space-y-4 self-start sticky top-[88px]">
              <h2 class="text-base font-semibold flex items-center gap-2">
                <Play :size="14" class="text-accent" /> 语音参数
              </h2>

              <div>
                <div class="text-xs text-text-muted mb-1.5">音色</div>
                <select v-model="voiceModel" class="w-full bg-surface-secondary border border-border-subtle rounded px-2 py-1.5 text-sm">
                  <optgroup v-for="g in voiceGroups" :key="g.label" :label="g.label">
                    <option v-for="v in g.voices" :key="v.voiceType" :value="v.voiceType">
                      {{ v.label }}{{ v.suit ? ' · ' + v.suit : '' }}
                    </option>
                  </optgroup>
                </select>
                <input v-model.trim="voiceModel" type="text"
                       placeholder="或直接输入 voice ID(覆盖上方选择)"
                       class="w-full mt-1.5 bg-surface-tertiary border border-border-subtle rounded px-2 py-1.5 text-xs font-mono" />
                <p v-if="voiceSourceLabel"
                   class="text-xs mt-1 flex items-center gap-1 text-text-muted">
                  <span>当前默认:</span>
                  <span class="font-medium">{{ voiceSourceLabel }}</span>
                </p>
                <p v-else class="text-xs text-text-muted mt-1">
                  已手动选择 · {{ voiceLabelOf(voiceModel) }}
                </p>

                <button
                  class="mt-2 w-full btn-ghost text-xs flex items-center justify-center gap-1.5"
                  :disabled="demoing"
                  @click="triggerDemo"
                >
                  <Loader2 v-if="demoing" :size="12" class="animate-spin" />
                  <Play v-else :size="12" />
                  {{ demoing ? '合成中…' : '试听' }}
                </button>
                <p v-if="demoError" class="text-xs text-status-failed mt-1.5">{{ demoError }}</p>
                <AudioPlayer v-if="demoUrl" :src="demoUrl" class="mt-2" />
              </div>

              <div>
                <div class="flex items-center justify-between text-xs text-text-muted mb-1.5">
                  <span>语速</span>
                  <span class="font-mono">{{ speed.toFixed(1) }}×</span>
                </div>
                <input v-model.number="speed" type="range" min="0.6" max="1.5" step="0.1" class="w-full accent-accent" />
              </div>

              <div>
                <div class="flex items-center justify-between text-xs text-text-muted mb-1.5">
                  <span>音调</span>
                  <span class="font-mono">{{ pitch >= 0 ? '+' : '' }}{{ pitch }}</span>
                </div>
                <input v-model.number="pitch" type="range" min="-6" max="6" step="1" class="w-full accent-accent" />
              </div>

              <label class="flex items-center gap-2 text-xs text-text-secondary pt-3 border-t border-border-subtle cursor-pointer">
                <input v-model="markFinal" type="checkbox" class="accent-accent" />
                设为定稿(替换该 script 的当前定稿)
              </label>
            </aside>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
