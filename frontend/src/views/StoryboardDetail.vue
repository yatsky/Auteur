<script setup lang="ts">
// 分镜工作台 —— master-detail:左网格选镜,右编辑 prompt + 重生
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import {
  ArrowLeft, Film, Loader2, Play, RefreshCw,
} from 'lucide-vue-next'
import {
  generateStoryboardAsync, listImages, listShots,
  regenerateImageForShotAsync, selectImageAsFinal, updateShotPrompt,
} from '../api/scripts'
import { startRunPoll, useRunPoll } from '../composables/useRunPoll'
import { extractError } from '../lib/format'
import ErrorBanner from '../components/ErrorBanner.vue'
import RunProgress from '../components/RunProgress.vue'
import ShotGridCard from '../components/storyboard/ShotGridCard.vue'
import ShotEditPanel from '../components/storyboard/ShotEditPanel.vue'
import type { ImageAsset, PipelineRun, StoryboardShot } from '../types'

const props = defineProps<{ scriptId: number }>()

const shots = ref<StoryboardShot[]>([])
const images = ref<ImageAsset[]>([])
const loading = ref(false)
const errorMsg = ref<string | null>(null)
const runMsg = ref<string | null>(null)

// 单镜重生轮询
const shotRuns = ref<Record<number, PipelineRun>>({})
const shotRunMsg = ref<Record<number, string>>({})
const shotPollStops: Record<number, () => void> = {}

// 当前选中的 shot id（master-detail 选中态）+ 编辑草稿 + 保存中标记
const selectedShotId = ref<number | null>(null)
const draft = ref({ promptZh: '', promptEn: '', negativePrompt: '' })
const draftDirty = ref(false)
const savingPrompt = ref(false)

// 左侧筛选 chip:全部 / 已生图 / 未生图 / 敏感拦截
type Filter = 'all' | 'withImg' | 'noImg' | 'blocked'
const filter = ref<Filter>('all')

// shotId -> 该镜最新一张 asset(按 id 升序后取末尾,因为后端就是按 id 自增插入的)
const latestAssetByShot = computed(() => {
  const map: Record<number, ImageAsset> = {}
  for (const img of images.value) map[img.shotId] = img
  return map
})
const shotsWithImage = computed(() => {
  const set = new Set<number>()
  for (const img of images.value) if (img.fileUrl) set.add(img.shotId)
  return set
})
const missingCount = computed(() => shots.value.filter((s) => !shotsWithImage.value.has(s.id)).length)
const blockedCount = computed(
  () => shots.value.filter((s) => latestAssetByShot.value[s.id]?.reviewDecision === 'SENSITIVE_BLOCKED').length,
)

const filteredShots = computed(() => {
  if (filter.value === 'all') return shots.value
  if (filter.value === 'withImg') return shots.value.filter((s) => shotsWithImage.value.has(s.id))
  if (filter.value === 'noImg') return shots.value.filter((s) => !shotsWithImage.value.has(s.id))
  return shots.value.filter((s) => latestAssetByShot.value[s.id]?.reviewDecision === 'SENSITIVE_BLOCKED')
})

const selectedShot = computed(() => shots.value.find((s) => s.id === selectedShotId.value) ?? null)
const selectedLatestAsset = computed(
  () => (selectedShot.value ? latestAssetByShot.value[selectedShot.value.id] : null) ?? null,
)
const selectedLinkedImages = computed(() =>
  selectedShot.value ? images.value.filter((img) => img.shotId === selectedShot.value!.id) : [],
)
const selectedIsRegenerating = computed(() => {
  const id = selectedShotId.value
  if (id == null) return false
  const r = shotRuns.value[id]
  return !!r && (r.status === 'PENDING' || r.status === 'RUNNING')
})

function selectShot(s: StoryboardShot) {
  selectedShotId.value = s.id
  draft.value = {
    promptZh: s.promptZh ?? '',
    promptEn: s.promptEn ?? '',
    negativePrompt: s.negativePrompt ?? '',
  }
  draftDirty.value = false
}

watch(draft, () => { draftDirty.value = true }, { deep: true })

function stopShotPoll(shotId: number) {
  const stop = shotPollStops[shotId]
  if (stop) { stop(); delete shotPollStops[shotId] }
}
function stopAllShotPolls() {
  for (const id of Object.keys(shotPollStops)) stopShotPoll(Number(id))
}

async function load() {
  loading.value = true
  try {
    const [s, i] = await Promise.all([
      listShots(props.scriptId),
      listImages(props.scriptId).catch(() => [] as ImageAsset[]),
    ])
    shots.value = s
    images.value = i
    errorMsg.value = null
    // 默认选中:优先第一个被审查拦截的;否则第一个
    if (selectedShotId.value == null && s.length > 0) {
      const blocked = s.find((x) => latestAssetByShot.value[x.id]?.reviewDecision === 'SENSITIVE_BLOCKED')
      selectShot(blocked ?? s[0])
    } else if (selectedShotId.value != null) {
      // 远端可能改写了 prompt(脱敏成功路径会写回),刷新草稿(只在不脏时)
      const cur = s.find((x) => x.id === selectedShotId.value)
      if (cur && !draftDirty.value) {
        draft.value = {
          promptZh: cur.promptZh ?? '',
          promptEn: cur.promptEn ?? '',
          negativePrompt: cur.negativePrompt ?? '',
        }
        draftDirty.value = false
      }
    }
  } catch (e: any) {
    errorMsg.value = extractError(e, 'fetch failed')
  } finally {
    loading.value = false
  }
}

async function reloadImages() {
  try {
    images.value = await listImages(props.scriptId)
  } catch (e: any) {
    console.warn('[reloadImages]', e?.message)
  }
}

// 点击图片卡片 = 选定为本镜 final(同 shot 下其他 asset 自动 unset)。
// 比"重生"更直接 — 历史 asset 都是合格候选,选哪张用哪张。
async function selectAsFinal(assetId: number) {
  try {
    await selectImageAsFinal(assetId)
    await reloadImages()
  } catch (e: any) {
    console.warn('[selectAsFinal]', e?.message)
  }
}

async function regenSelected() {
  const s = selectedShot.value
  if (!s) return
  stopShotPoll(s.id)
  shotRunMsg.value = { ...shotRunMsg.value, [s.id]: '' }
  try {
    const { runId } = await regenerateImageForShotAsync(s.id)
    shotRunMsg.value = { ...shotRunMsg.value, [s.id]: `run ${runId}` }
    shotPollStops[s.id] = startRunPoll(runId, {
      onUpdate: (r) => { shotRuns.value = { ...shotRuns.value, [s.id]: r } },
      onDone: async () => {
        shotRunMsg.value = { ...shotRunMsg.value, [s.id]: '✓' }
        try { shots.value = await listShots(props.scriptId) } catch (e: any) { console.warn('[reloadShots]', e?.message) }
        await reloadImages()
      },
      onFailed: (r) => {
        shotRunMsg.value = { ...shotRunMsg.value, [s.id]: `失败:${r.errorMsg ?? r.status}` }
      },
    })
  } catch (e: any) {
    shotRunMsg.value = { ...shotRunMsg.value, [s.id]: `失败:${extractError(e)}` }
  }
}

async function savePromptOnly() {
  const s = selectedShot.value
  if (!s) return
  savingPrompt.value = true
  try {
    const updated = await updateShotPrompt(s.id, {
      promptZh: draft.value.promptZh,
      promptEn: draft.value.promptEn,
      negativePrompt: draft.value.negativePrompt,
    })
    shots.value = shots.value.map((x) => (x.id === s.id ? updated : x))
    draftDirty.value = false
    shotRunMsg.value = { ...shotRunMsg.value, [s.id]: 'prompt 已保存' }
  } catch (e: any) {
    shotRunMsg.value = { ...shotRunMsg.value, [s.id]: `保存失败:${extractError(e)}` }
  } finally {
    savingPrompt.value = false
  }
}

async function saveAndRegen() {
  const s = selectedShot.value
  if (!s) return
  savingPrompt.value = true
  try {
    const updated = await updateShotPrompt(s.id, {
      promptZh: draft.value.promptZh,
      promptEn: draft.value.promptEn,
      negativePrompt: draft.value.negativePrompt,
    })
    shots.value = shots.value.map((x) => (x.id === s.id ? updated : x))
    draftDirty.value = false
  } catch (e: any) {
    shotRunMsg.value = { ...shotRunMsg.value, [s.id]: `保存失败:${extractError(e)}` }
    savingPrompt.value = false
    return
  }
  savingPrompt.value = false
  await regenSelected()
}

function cancelEdit() {
  const s = selectedShot.value
  if (!s) return
  draft.value = {
    promptZh: s.promptZh ?? '',
    promptEn: s.promptEn ?? '',
    negativePrompt: s.negativePrompt ?? '',
  }
  draftDirty.value = false
}

// 整片生成 / 重生 —— 单实例 poll
const poll = useRunPoll({
  onDone: async (r) => {
    runMsg.value = `生成 ${r.totalItems ?? '?'} 个分镜 ✓`
    await load()
  },
  onFailed: (r) => { runMsg.value = `失败:${r.errorMsg ?? r.status}` },
})
const activeRun = poll.run
const isRunning = computed(
  () => !!activeRun.value && (activeRun.value.status === 'PENDING' || activeRun.value.status === 'RUNNING'),
)

// 单按钮智能触发:无分镜直接生成;有分镜则二次确认 force=true 全重生(级联删图)
async function trigger() {
  const hasShots = shots.value.length > 0
  if (hasShots) {
    const ok = window.confirm(
      `已有 ${shots.value.length} 个分镜,重生会先删除所有旧分镜,以及级联删除其下所有已生成图片。确定继续吗?`,
    )
    if (!ok) return
  }
  poll.reset()
  runMsg.value = null
  try {
    const { runId } = await generateStoryboardAsync(props.scriptId, hasShots)
    runMsg.value = `已起 run ${runId},正在轮询进度...`
    await poll.start(runId)
  } catch (e: any) {
    runMsg.value = `失败:${extractError(e)}`
  }
}

onMounted(load)
onBeforeUnmount(stopAllShotPolls)
</script>

<template>
  <div class="min-h-full">
    <!-- sticky 顶栏 -->
    <div class="sticky top-0 z-10 bg-surface-primary border-b border-border-subtle">
      <div class="px-8 py-3 max-w-[1400px] mx-auto flex flex-col gap-1">
        <div class="flex items-center gap-4 flex-wrap">
          <button
            class="flex items-center gap-1.5 text-sm text-text-muted hover:text-text-primary"
            @click="$router.push('/storyboard')"
          >
            <ArrowLeft :size="14" /> 分镜列表
          </button>
          <h1 class="text-lg font-semibold flex items-center gap-2">
            <Film :size="16" class="text-accent" /> 分镜 · script {{ scriptId }}
          </h1>
          <span v-if="shots.length" class="chip text-[11px] bg-surface-tertiary text-text-muted">
            {{ shots.length }} 个镜头 · 已生图 {{ shots.length - missingCount }} / {{ shots.length }}
          </span>
          <span v-if="missingCount > 0" class="chip text-[11px] bg-status-paused/15 text-status-paused">
            {{ missingCount }} 张未生图
          </span>
          <span v-if="blockedCount > 0" class="chip text-[11px] bg-status-failed/15 text-status-failed">
            {{ blockedCount }} 张敏感拦截
          </span>
          <div class="ml-auto flex items-center gap-2">
            <button class="btn-ghost text-sm" :disabled="loading" @click="load">
              <RefreshCw :size="14" :class="loading ? 'animate-spin' : ''" /> 刷新
            </button>
            <button class="btn-primary text-sm" :disabled="!!isRunning" @click="trigger">
              <Play :size="14" /> {{ isRunning ? '生成中...' : (shots.length > 0 ? '重生分镜' : '生成分镜') }}
            </button>
          </div>
        </div>
        <div class="text-xs text-text-muted flex items-center gap-3 flex-wrap">
          <RunProgress :run="activeRun" label="生成分镜中" unit="镜" class="max-w-[420px]" />
          <span v-if="!activeRun && runMsg" :class="runMsg.startsWith('失败') ? 'text-status-failed' : 'text-status-done'">
            {{ runMsg }}
          </span>
          <span v-else-if="!activeRun">master-detail · 左侧选镜 · 右侧编辑 prompt 与重生</span>
        </div>
      </div>
    </div>

    <div class="px-8 py-5 max-w-[1400px] mx-auto">
      <ErrorBanner :msg="errorMsg" />

      <div v-if="loading && shots.length === 0" class="card p-12 text-center">
        <Loader2 :size="20" class="animate-spin text-text-muted mx-auto" />
      </div>

      <div v-else-if="shots.length === 0" class="card p-12 text-center text-text-muted text-sm">
        还没有分镜(点上面按钮生成)
      </div>

      <div v-else class="grid grid-cols-1 lg:grid-cols-[1fr_440px] gap-4 items-start">
      <!-- 左:筛选 + 卡片网格 -->
      <div>
        <div class="flex items-center gap-2 mb-3 flex-wrap text-xs">
          <button
            v-for="f in (['all', 'withImg', 'noImg', 'blocked'] as Filter[])" :key="f"
            class="chip cursor-pointer"
            :class="filter === f ? 'bg-accent-soft text-accent' : 'bg-surface-tertiary text-text-secondary'"
            @click="filter = f"
          >
            {{ f === 'all' ? `全部 ${shots.length}`
               : f === 'withImg' ? `已生图 ${shots.length - missingCount}`
               : f === 'noImg' ? `未生图 ${missingCount}`
               : `敏感拦截 ${blockedCount}` }}
          </button>
        </div>

        <div class="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-3">
          <ShotGridCard
            v-for="s in filteredShots" :key="s.id"
            :shot="s"
            :selected="selectedShotId === s.id"
            :latest-asset="latestAssetByShot[s.id] ?? null"
            :has-image="shotsWithImage.has(s.id)"
            @select="selectShot(s)"
          />
        </div>
      </div>

      <!-- 右:编辑面板(sticky) -->
      <ShotEditPanel
        :selected-shot="selectedShot"
        :selected-latest-asset="selectedLatestAsset"
        :selected-linked-images="selectedLinkedImages"
        :draft="draft"
        :draft-dirty="draftDirty"
        :saving-prompt="savingPrompt"
        :is-regenerating="selectedIsRegenerating"
        :shot-run-msg="selectedShot ? shotRunMsg[selectedShot.id] : undefined"
        @update:draft="draft = $event"
        @cancel-edit="cancelEdit"
        @save-prompt-only="savePromptOnly"
        @save-and-regen="saveAndRegen"
        @select-as-final="selectAsFinal"
      />
    </div>
    </div>
  </div>
</template>
