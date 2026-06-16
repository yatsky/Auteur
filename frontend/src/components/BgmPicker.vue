<script setup lang="ts">
// preset.bgmLocked = true 时整组件 v-if 隐藏,不调任何 BGM 接口,后端按 preset 兜底逻辑出曲。
import { computed, onMounted, ref, watch } from 'vue'
import { Loader2, Music, Plus, RefreshCcw, Volume2 } from 'lucide-vue-next'
import ErrorBanner from './ErrorBanner.vue'
import {
  type BgmChoice, type BgmTrack,
  getBgmChoice, loadMoreBgm, recommendBgm, selectBgm,
} from '../api/bgm'
import { getScript } from '../api/scripts'
import { extractError, formatDuration } from '../lib/format'

const props = defineProps<{ scriptId: number }>()

const tracks = ref<BgmTrack[]>([])
const choice = ref<BgmChoice | null>(null)
const offset = ref(0)
const volume = ref(0.25)

const loadingInit = ref(false)
const loadingMore = ref(false)
const selecting = ref<number | null>(null)
const errorMsg = ref<string | null>(null)
const bgmLocked = ref(false)

// LLM 给出的 mood key,从返回的曲目里读(全 3 条 moodTag 一致)
const moodTag = computed<string | null>(() => {
  if (choice.value && tracks.value.length > 0) {
    const matched = tracks.value.find((t) => t.id === choice.value!.bgmTrackId)
    if (matched?.moodTag) return matched.moodTag
  }
  return tracks.value[0]?.moodTag ?? null
})

const MOOD_LABELS: Record<string, string> = {
  dark_suspense: '暗调悬疑',
  ancient_solemn: '古风庄重',
  tense_thriller: '紧张刺激',
  melancholic: '哀伤怀古',
  epic_documentary: '史诗纪录片',
  mysterious_ambient: '神秘氛围',
}

function moodLabel(key: string | null): string {
  if (!key) return '-'
  return MOOD_LABELS[key] ?? key
}

function fmtDuration(sec: number | null): string {
  if (sec == null || sec <= 0) return '-'
  return formatDuration(sec)
}

async function loadInitial() {
  loadingInit.value = true
  errorMsg.value = null
  try {
    // 先读已选;有就只显示已选,推荐按需触发(避免每次进页面都打 LLM + Jamendo)
    const existing = await getBgmChoice(props.scriptId)
    choice.value = existing
    if (existing) {
      volume.value = existing.volume
    } else {
      const top = await recommendBgm(props.scriptId)
      tracks.value = top
      offset.value = top.length
    }
  } catch (e: any) {
    errorMsg.value = extractError(e, '获取 BGM 推荐失败')
  } finally {
    loadingInit.value = false
  }
}

async function showOtherRecommendations() {
  loadingInit.value = true
  errorMsg.value = null
  try {
    const top = await recommendBgm(props.scriptId)
    tracks.value = top
    offset.value = top.length
  } catch (e: any) {
    errorMsg.value = extractError(e, '获取 BGM 推荐失败')
  } finally {
    loadingInit.value = false
  }
}

async function fetchMore() {
  loadingMore.value = true
  errorMsg.value = null
  try {
    const more = await loadMoreBgm(props.scriptId, offset.value)
    if (more.length === 0) {
      errorMsg.value = 'Jamendo 这个 mood 没更多曲了,试试换 mood 或保留当前选择'
    } else {
      tracks.value = [...tracks.value, ...more]
      offset.value += more.length
    }
  } catch (e: any) {
    errorMsg.value = extractError(e, '加载更多失败')
  } finally {
    loadingMore.value = false
  }
}

async function pick(track: BgmTrack) {
  selecting.value = track.id
  errorMsg.value = null
  try {
    choice.value = await selectBgm(props.scriptId, track.id, volume.value)
  } catch (e: any) {
    errorMsg.value = extractError(e, '选曲失败(可能是 mp3 下载失败)')
  } finally {
    selecting.value = null
  }
}

async function onVolumeChange() {
  // 同 trackId 重新提交一次,后端只 update volume
  if (!choice.value) return
  try {
    choice.value = await selectBgm(props.scriptId, choice.value.bgmTrackId, volume.value)
  } catch (e: any) {
    errorMsg.value = extractError(e, '音量更新失败')
  }
}

function clearChoice() {
  // 后端没有 DELETE 端点(选择必有一首);把列表展开让用户换一首
  showOtherRecommendations()
}

watch(() => props.scriptId, () => {
  tracks.value = []
  choice.value = null
  offset.value = 0
  errorMsg.value = null
  bootstrap()
})

async function bootstrap() {
  try {
    const detail = await getScript(props.scriptId)
    bgmLocked.value = !!detail.bgmLocked
  } catch (e: any) {
    // getScript 失败不算 BGM 故障,降级当作未锁定处理
    bgmLocked.value = false
  }
  if (bgmLocked.value) return
  await loadInitial()
}

onMounted(bootstrap)
</script>

<template>
  <div v-if="bgmLocked"
       class="rounded border border-border-subtle bg-surface-tertiary/40 p-2.5 text-xs text-text-muted flex items-start gap-2">
    <Music :size="14" class="text-accent flex-shrink-0 mt-0.5" />
    <div class="flex-1">
      <div class="text-text-primary text-[11px] font-medium mb-0.5">BGM 已统一锁定</div>
      <div>当前预设统一用同一首主题曲(由后端默认兜底),无需选曲。</div>
    </div>
  </div>

  <div v-else class="space-y-3">
    <div class="flex items-center justify-between">
      <h3 class="text-sm font-medium flex items-center gap-2">
        <Music :size="14" class="text-accent" /> BGM
      </h3>
      <span v-if="moodTag" class="chip bg-accent-soft text-accent text-[10px]">
        mood: {{ moodLabel(moodTag) }}
      </span>
    </div>

    <ErrorBanner :msg="errorMsg" />

    <div v-if="choice"
         class="rounded border border-status-done/40 bg-status-done/5 p-2.5 text-xs space-y-2">
      <div class="flex items-start justify-between gap-2">
        <div class="flex-1 min-w-0">
          <div class="text-status-done text-[10px] uppercase mb-0.5">当前已选</div>
          <div class="font-medium truncate" :title="choice.trackName">《{{ choice.trackName }}》</div>
          <div class="text-text-muted truncate">{{ choice.trackArtist ?? '-' }}</div>
        </div>
        <button v-if="tracks.length === 0"
                class="text-[11px] text-text-muted hover:text-accent flex items-center gap-1"
                title="拉推荐换一首"
                :disabled="loadingInit"
                @click="clearChoice">
          <RefreshCcw :size="11" /> 换一首
        </button>
      </div>
      <div class="flex items-center gap-2">
        <Volume2 :size="12" class="text-text-muted" />
        <input type="range" min="0.05" max="0.6" step="0.01"
               v-model.number="volume" @change="onVolumeChange"
               class="flex-1 accent-accent" />
        <span class="font-mono text-text-muted w-9 text-right">{{ Math.round(volume * 100) }}%</span>
      </div>
    </div>

    <div v-if="loadingInit && tracks.length === 0"
         class="flex items-center justify-center py-6 text-text-muted text-xs gap-2">
      <Loader2 :size="14" class="animate-spin" /> LLM 打 mood + 拉 Jamendo…
    </div>

    <ul v-else-if="tracks.length > 0" class="space-y-2">
      <li v-for="t in tracks" :key="t.id"
          :class="['rounded border p-2 text-xs',
                    choice?.bgmTrackId === t.id
                      ? 'border-accent bg-accent-soft/40'
                      : 'border-border-subtle hover:border-accent/50']">
        <div class="flex items-start gap-2">
          <img v-if="t.albumImageUrl" :src="t.albumImageUrl"
               class="w-12 h-12 rounded object-cover flex-shrink-0 bg-surface-tertiary"
               loading="lazy" />
          <div v-else class="w-12 h-12 rounded bg-surface-tertiary flex items-center justify-center flex-shrink-0">
            <Music :size="18" class="text-text-muted" />
          </div>
          <div class="flex-1 min-w-0">
            <div class="font-medium truncate" :title="t.name">{{ t.name }}</div>
            <div class="text-text-muted truncate">
              {{ t.artistName ?? '-' }} · {{ fmtDuration(t.durationSeconds) }}
            </div>
            <audio :src="t.audioUrl" controls preload="none"
                   class="w-full mt-1 h-7" />
          </div>
        </div>
        <button class="btn-primary w-full mt-2 text-xs py-1"
                :disabled="selecting !== null || choice?.bgmTrackId === t.id"
                @click="pick(t)">
          <Loader2 v-if="selecting === t.id" :size="12" class="animate-spin" />
          <span v-if="selecting === t.id">下载中…</span>
          <span v-else-if="choice?.bgmTrackId === t.id">已选 ✓</span>
          <span v-else>选这首</span>
        </button>
      </li>
    </ul>

    <div v-else class="text-text-muted text-xs py-2 text-center">
      没有推荐结果(可能 client_id 未配置或 Jamendo 暂时不可达)
    </div>

    <button v-if="tracks.length > 0"
            class="w-full text-xs py-1.5 rounded border border-border-subtle text-text-muted hover:text-text-primary hover:border-accent/50 flex items-center justify-center gap-1"
            :disabled="loadingMore"
            @click="fetchMore">
      <Loader2 v-if="loadingMore" :size="12" class="animate-spin" />
      <Plus v-else-if="tracks.length === 0" :size="12" />
      <RefreshCcw v-else :size="12" />
      {{ loadingMore ? '加载中…' : `加载更多 (+3)` }}
    </button>
  </div>
</template>
