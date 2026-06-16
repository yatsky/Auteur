<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  ArrowLeft, Download, Film, Image as ImageIcon, Loader2, Music, Pause, Play, Subtitles,
} from 'lucide-vue-next'
import ErrorBanner from '../components/ErrorBanner.vue'
import TimeText from '../components/TimeText.vue'
import BgmPicker from '../components/BgmPicker.vue'
import {
  listImages, listShots, listVoices, listVideos, renderVideoAsync,
} from '../api/scripts'
import { useRunPoll } from '../composables/useRunPoll'
import { extractError, formatDuration } from '../lib/format'
import type {
  ImageAsset, PipelineRunStatus, StoryboardShot, VideoAsset, VoiceAsset,
} from '../types'

const props = defineProps<{ scriptId?: number }>()
const router = useRouter()
const scriptId = computed(() => props.scriptId ?? 1)

const DEFAULT_SHOT_SEC = 5

const shots = ref<StoryboardShot[]>([])
const images = ref<ImageAsset[]>([])
const voices = ref<VoiceAsset[]>([])
const videos = ref<VideoAsset[]>([])
const dataLoading = ref(false)
const dataError = ref<string | null>(null)

const format = ref<'9:16' | '16:9'>('9:16')
const voiceAssetId = ref<number | null>(null)
const markFinal = ref(true)

const runId = ref<number | null>(null)
const runStatus = ref<PipelineRunStatus | null>(null)
const runMsg = ref<string | null>(null)
const rendering = ref(false)

const playing = ref(false)
const playhead = ref(0)
let playTimer: number | null = null

interface TimelineShot {
  id: number
  index: number
  startSec: number
  durationSec: number
  caption: string
  shotType: string
  imageUrl: string | null
  hasImage: boolean
}

const imageByShot = computed<Map<number, ImageAsset | null>>(() => {
  const m = new Map<number, ImageAsset | null>()
  for (const shot of shots.value) {
    const matches = images.value.filter((a) => a.shotId === shot.id)
    if (matches.length === 0) { m.set(shot.id, null); continue }
    const finalLatest = [...matches].reverse().find((a) => a.isFinal)
    m.set(shot.id, finalLatest ?? matches[matches.length - 1])
  }
  return m
})

const timeline = computed<TimelineShot[]>(() => {
  let cursor = 0
  return shots.value.map((s, i) => {
    const dur = s.durationSeconds && s.durationSeconds > 0 ? s.durationSeconds : DEFAULT_SHOT_SEC
    const img = imageByShot.value.get(s.id) ?? null
    const t: TimelineShot = {
      id: s.id,
      index: s.shotIndex ?? (i + 1),
      startSec: cursor,
      durationSec: dur,
      caption: s.promptZh ?? '',
      shotType: s.shotType ?? '-',
      imageUrl: img?.fileUrl ?? null,
      hasImage: !!(img && img.fileUrl),
    }
    cursor += dur
    return t
  })
})

const totalDuration = computed(() => {
  if (timeline.value.length === 0) return 0
  const last = timeline.value[timeline.value.length - 1]
  return last.startSec + last.durationSec
})

const renderableShots = computed(() => timeline.value.filter((s) => s.hasImage).length)

const selectedVoice = computed(() => {
  if (voiceAssetId.value != null) {
    return voices.value.find((v) => v.id === voiceAssetId.value) ?? null
  }
  return voices.value.find((v) => v.isFinal) ?? voices.value[0] ?? null
})

const currentShot = computed(() =>
  timeline.value.find((s) => playhead.value >= s.startSec && playhead.value < s.startSec + s.durationSec) ?? null,
)

function fmt(sec: number): string {
  return formatDuration(sec)
}

function stopPlay() {
  playing.value = false
  if (playTimer != null) { window.clearInterval(playTimer); playTimer = null }
}

function togglePlay() {
  if (totalDuration.value <= 0) return
  if (playing.value) { stopPlay(); return }
  if (playhead.value >= totalDuration.value) playhead.value = 0
  playing.value = true
  playTimer = window.setInterval(() => {
    playhead.value = Math.min(totalDuration.value, playhead.value + 0.1)
    if (playhead.value >= totalDuration.value) stopPlay()
  }, 100)
}

function seekTo(sec: number) {
  playhead.value = Math.max(0, Math.min(totalDuration.value, sec))
}

async function loadAll() {
  dataLoading.value = true
  dataError.value = null
  try {
    const sid = scriptId.value
    const [s, i, v, vd] = await Promise.all([
      listShots(sid), listImages(sid), listVoices(sid), listVideos(sid),
    ])
    shots.value = s
    images.value = i
    voices.value = v
    videos.value = vd
    voiceAssetId.value = null
    playhead.value = 0
  } catch (e: any) {
    dataError.value = extractError(e, 'fetch failed')
  } finally {
    dataLoading.value = false
  }
}

const poll = useRunPoll({
  onUpdate: (r) => { runStatus.value = r.status },
  onDone: async () => {
    rendering.value = false
    videos.value = await listVideos(scriptId.value)
    const latest = videos.value[0]
    const s = latest?.timingStrategy
    const strategyLabel = s === 'PRECISE_BY_SECTION' ? '精确对齐 ✓'
      : s === 'UNIFORM_SCALE' ? '⚠ 等比缩放'
      : s === 'RAW' ? '⚠ LLM估算'
      : ''
    runMsg.value = `渲染 ✓${strategyLabel ? ' · ' + strategyLabel : ''}`
  },
  onFailed: (r) => {
    rendering.value = false
    runMsg.value = `失败:${r.errorMsg ?? r.status}`
  },
})

async function triggerRender() {
  if (renderableShots.value === 0) return
  poll.reset()
  rendering.value = true
  runStatus.value = 'PENDING'
  runMsg.value = null
  try {
    const { runId: rid } = await renderVideoAsync(scriptId.value, {
      voiceAssetId: voiceAssetId.value ?? undefined,
      format: format.value,
      markFinal: markFinal.value,
    })
    runId.value = rid
    await poll.start(rid)
  } catch (e: any) {
    rendering.value = false
    runStatus.value = 'FAILED'
    runMsg.value = `失败:${extractError(e)}`
  }
}

watch(scriptId, () => {
  stopPlay()
  poll.reset()
  runId.value = null
  runStatus.value = null
  runMsg.value = null
  loadAll()
})

onMounted(loadAll)
onBeforeUnmount(stopPlay)
</script>

<template>
  <div class="min-h-full">
    <div class="sticky top-0 z-10 bg-surface-primary border-b border-border-subtle">
      <div class="px-8 py-3 max-w-[1400px] mx-auto flex flex-col gap-1">
        <div class="flex items-center gap-4 flex-wrap">
          <button
            class="flex items-center gap-1.5 text-sm text-text-muted hover:text-text-primary"
            @click="router.push(`/storyboard/${scriptId}`)"
          >
            <ArrowLeft :size="14" /> 返回分镜
          </button>
          <h1 class="text-lg font-semibold">视频组装 · script #{{ scriptId }}</h1>
          <span class="chip text-[11px] bg-surface-tertiary text-text-muted">
            {{ shots.length }} 镜 · 可用 {{ renderableShots }} · {{ fmt(totalDuration) }}
          </span>
        </div>
        <div class="text-xs text-text-muted">
          ffmpeg(libx264 + AAC + libass) · 默认 1080×1920 9:16 · 成片落 /api/files/video/
        </div>
      </div>
    </div>

    <div class="px-8 py-5 max-w-[1400px] mx-auto">
      <ErrorBanner :msg="dataError" />

      <div v-if="dataLoading && shots.length === 0" class="card p-12 text-center">
        <Loader2 :size="20" class="animate-spin text-text-muted mx-auto" />
      </div>

      <template v-else-if="shots.length === 0">
        <div class="card p-12 text-center text-sm text-text-muted">
          script {{ scriptId }} 没有分镜 —— 先去
          <button class="text-accent underline" @click="router.push(`/storyboard/${scriptId}`)">分镜工作台</button>
          生成。
        </div>
      </template>

      <template v-else>
        <div class="grid grid-cols-1 lg:grid-cols-[1fr_360px] gap-4 mb-4">
          <div class="card p-4">
            <div class="aspect-[9/16] max-h-[500px] mx-auto bg-black rounded-lg relative overflow-hidden flex items-center justify-center">
              <div v-if="!currentShot" class="text-text-muted text-sm text-center">
                <Play :size="48" class="mx-auto mb-2 opacity-30" />
                点开始预览
              </div>
              <template v-else>
                <img v-if="currentShot.imageUrl" :src="currentShot.imageUrl" class="absolute inset-0 w-full h-full object-cover opacity-90" />
                <div v-else class="absolute inset-0 bg-gradient-to-b from-purple-900/40 to-black" />
                <div class="absolute inset-x-0 bottom-0 p-6">
                  <div class="text-[10px] uppercase text-purple-300 mb-2 font-mono tracking-wider">
                    {{ currentShot.shotType }} · 镜头 {{ currentShot.index }}
                  </div>
                  <div v-if="currentShot.caption"
                       class="text-white text-base font-medium leading-relaxed bg-black/60 px-3 py-2 rounded-md">
                    {{ currentShot.caption.length > 80 ? currentShot.caption.slice(0, 80) + '…' : currentShot.caption }}
                  </div>
                </div>
              </template>
              <div class="absolute top-3 left-3 right-3 flex items-center justify-between text-xs text-white/80 z-10">
                <span class="font-mono bg-black/40 px-2 py-0.5 rounded">{{ fmt(playhead) }} / {{ fmt(totalDuration) }}</span>
                <span v-if="currentShot && !currentShot.hasImage" class="bg-status-failed/70 px-2 py-0.5 rounded">无图</span>
              </div>
            </div>

            <div class="flex items-center gap-3 mt-3">
              <button class="btn-primary" :disabled="totalDuration === 0" @click="togglePlay">
                <Pause v-if="playing" :size="13" />
                <Play v-else :size="13" />
                {{ playing ? '暂停' : '播放' }}
              </button>
              <input type="range" min="0" :max="totalDuration" step="0.1" v-model.number="playhead"
                     class="flex-1 accent-accent" />
              <span class="text-xs font-mono text-text-muted shrink-0">{{ fmt(playhead) }}</span>
            </div>
          </div>

          <aside class="card p-5 space-y-4 self-start">
            <div>
              <h2 class="text-base font-semibold mb-3 flex items-center gap-2">
                <Film :size="15" class="text-accent" /> 渲染参数
              </h2>
              <div class="space-y-3 text-sm">
                <div>
                  <div class="text-xs text-text-muted mb-1.5">画幅</div>
                  <div class="flex gap-2">
                    <button v-for="f in (['9:16', '16:9'] as const)" :key="f"
                            :class="['chip cursor-pointer text-xs', format === f ? 'bg-accent-soft text-accent' : 'bg-surface-tertiary text-text-secondary']"
                            @click="format = f">
                      {{ f }}
                    </button>
                  </div>
                </div>
                <div>
                  <div class="text-xs text-text-muted mb-1.5">配音</div>
                  <select v-model="voiceAssetId" class="w-full bg-surface-secondary border border-border-subtle rounded px-2 py-1.5 text-sm">
                    <option :value="null">自动(优先 final / 最新)</option>
                    <option v-for="v in voices" :key="v.id" :value="v.id">
                      #{{ v.id }} · {{ v.voiceLabel ?? v.model ?? '-' }}{{ v.isFinal ? ' · final' : '' }}
                    </option>
                  </select>
                  <div v-if="selectedVoice" class="text-xs text-text-muted mt-1">
                    当前 #{{ selectedVoice.id }} · {{ selectedVoice.durationSeconds ?? '-' }}s
                  </div>
                  <div v-else class="text-xs text-status-paused mt-1">
                    没有 voice_asset(成片将无声)
                  </div>
                </div>
                <label class="text-xs text-text-secondary flex items-center gap-1.5 cursor-pointer">
                  <input v-model="markFinal" type="checkbox" class="accent-accent" />
                  设为定稿(替换该 script 的当前定稿)
                </label>
              </div>
            </div>

            <div class="pt-3 border-t border-border-subtle">
              <BgmPicker :script-id="scriptId" />
            </div>

            <button class="btn-primary w-full" :disabled="rendering || renderableShots === 0" @click="triggerRender">
              <Loader2 v-if="rendering" :size="13" class="animate-spin" />
              <Film v-else :size="13" />
              {{ rendering ? `渲染中… run ${runId ?? ''}` : '渲染成片' }}
            </button>
            <p v-if="renderableShots === 0" class="text-xs text-status-failed">
              没有任何镜头有可用图片,先去出图。
            </p>
            <p v-else-if="renderableShots < shots.length" class="text-xs text-status-paused">
              {{ shots.length - renderableShots }} 个镜头无图,渲染时会跳过
            </p>
            <p v-if="runMsg" class="text-sm"
               :class="runMsg.startsWith('失败') ? 'text-status-failed' : 'text-status-done'">
              {{ runMsg }}
            </p>
          </aside>
        </div>

        <div class="card p-5 mb-4">
          <h2 class="text-base font-semibold mb-3 flex items-center gap-2">
            <ImageIcon :size="15" class="text-accent" /> 时间轴
          </h2>
          <div class="overflow-x-auto">
            <div class="relative h-20 bg-surface-secondary rounded-md mb-2"
                 :style="{ width: `${Math.max(totalDuration * 16, 600)}px`, minWidth: '600px' }">
              <div v-for="s in timeline" :key="s.id"
                   class="absolute top-0 bottom-0 border-r border-border-subtle text-[10px] cursor-pointer hover:bg-accent-soft"
                   :class="[
                     currentShot?.id === s.id ? 'bg-accent-soft' : '',
                     !s.hasImage ? 'bg-status-failed/15' : '',
                   ]"
                   :style="{ left: `${s.startSec * 16}px`, width: `${s.durationSec * 16}px` }"
                   @click="seekTo(s.startSec)">
                <div class="px-1 py-0.5 text-text-muted font-mono">{{ s.index }}</div>
                <div class="px-1 text-[9px] truncate">{{ s.shotType }}</div>
              </div>
              <div class="absolute top-0 bottom-0 w-0.5 bg-accent pointer-events-none"
                   :style="{ left: `${playhead * 16}px` }">
                <div class="absolute -top-1 -left-1 w-2 h-2 rounded-full bg-accent" />
              </div>
            </div>
          </div>

          <table class="w-full text-sm mt-4">
            <thead class="text-xs uppercase text-text-muted border-b border-border-subtle">
              <tr>
                <th class="text-left py-2 font-medium w-[40px]">#</th>
                <th class="text-right py-2 font-medium w-[100px]">起止</th>
                <th class="text-left py-2 font-medium w-[60px]">图</th>
                <th class="text-left py-2 font-medium">画面 prompt</th>
                <th class="text-right py-2 font-medium w-[60px]">类型</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="s in timeline" :key="s.id"
                  :class="['border-t border-border-subtle cursor-pointer hover:bg-surface-tertiary/30',
                           currentShot?.id === s.id ? 'bg-accent-soft/30' : '']"
                  @click="seekTo(s.startSec)">
                <td class="py-2 font-mono text-text-muted">{{ s.index }}</td>
                <td class="py-2 text-right font-mono text-text-muted text-xs">
                  {{ fmt(s.startSec) }}-{{ fmt(s.startSec + s.durationSec) }}
                </td>
                <td class="py-2">
                  <span v-if="s.hasImage" class="chip bg-status-done/15 text-status-done">有</span>
                  <span v-else class="chip bg-status-failed/15 text-status-failed">无</span>
                </td>
                <td class="py-2 text-xs truncate max-w-[400px]" :title="s.caption">{{ s.caption || '-' }}</td>
                <td class="py-2 text-right text-xs text-accent">{{ s.shotType }}</td>
              </tr>
            </tbody>
          </table>
        </div>

        <div class="card p-5">
          <div class="flex items-center justify-between mb-3">
            <h2 class="text-base font-semibold flex items-center gap-2">
              <Subtitles :size="15" class="text-accent" /> 历史成片
            </h2>
            <span class="chip text-xs bg-surface-tertiary text-text-muted">{{ videos.length }} 条</span>
          </div>
          <div v-if="videos.length === 0" class="text-sm text-text-muted py-6 text-center">
            还没有成片,点上面「渲染成片」开跑
          </div>
          <ul v-else class="space-y-3">
            <li v-for="v in videos" :key="v.id"
                class="border border-border-subtle rounded-md p-3 text-xs">
              <div class="flex items-center gap-2 mb-2 flex-wrap">
                <span class="font-mono text-text-muted">#{{ v.id }}</span>
                <span v-if="v.isFinal" class="chip bg-status-done/15 text-status-done">final</span>
                <span v-if="v.bgmTrackId" class="chip bg-accent-soft text-accent flex items-center gap-1"
                      :title="`BGM 音量 ${v.bgmVolume != null ? Math.round(Number(v.bgmVolume) * 100) + '%' : '-'}`">
                  <Music :size="10" /> BGM
                </span>
                <span
                  v-if="v.timingStrategy"
                  class="chip text-[10px]"
                  :class="v.timingStrategy === 'PRECISE_BY_SECTION'
                    ? 'bg-status-done/15 text-status-done'
                    : v.timingStrategy === 'UNIFORM_SCALE'
                      ? 'bg-status-paused/15 text-status-paused'
                      : 'bg-status-failed/15 text-status-failed'"
                  :title="v.timingNote ?? ''"
                >
                  {{ v.timingStrategy === 'PRECISE_BY_SECTION' ? '精确对齐'
                     : v.timingStrategy === 'UNIFORM_SCALE' ? '等比缩放'
                     : 'LLM估算' }}
                </span>
                <span class="text-text-muted ml-auto"><TimeText :value="v.createdAt" relative /></span>
              </div>
              <div class="grid grid-cols-2 md:grid-cols-4 gap-3 mb-2">
                <div>
                  <div class="text-text-muted">画幅</div>
                  <div class="font-mono">{{ v.format ?? '-' }} · {{ v.width ?? '?' }}×{{ v.height ?? '?' }}</div>
                </div>
                <div>
                  <div class="text-text-muted">分镜数</div>
                  <div class="font-mono">{{ v.shotCount ?? '-' }}</div>
                </div>
                <div>
                  <div class="text-text-muted">时长</div>
                  <div class="font-mono">{{ v.durationSeconds ?? '-' }}s</div>
                </div>
                <div>
                  <div class="text-text-muted">成本</div>
                  <div class="font-mono">¥{{ v.costYuan != null ? Number(v.costYuan).toFixed(4) : '-' }}</div>
                </div>
              </div>
              <video v-if="v.videoUrl && (v.videoUrl.startsWith('/api/') || v.videoUrl.startsWith('http'))"
                     :src="v.videoUrl" controls preload="none"
                     class="w-full max-w-[360px] mt-1 rounded-md bg-black" />
              <a
                v-if="v.videoUrl"
                :href="v.videoUrl"
                :download="`script-${scriptId}-v${v.id}.mp4`"
                target="_blank"
                rel="noopener noreferrer"
                class="inline-flex items-center gap-1 mt-2 chip cursor-pointer text-xs bg-accent-soft text-accent hover:bg-accent hover:text-white transition-colors"
              >
                <Download :size="12" /> 下载 mp4
              </a>
            </li>
          </ul>
        </div>
      </template>
    </div>
  </div>
</template>
