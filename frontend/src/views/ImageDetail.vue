<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ArrowLeft, Loader2, Play, RefreshCw, ShieldCheck } from 'lucide-vue-next'
import {
  auditImageAsync,
  auditImagesAsync,
  generateImagesAsync,
  getScript,
  listImages,
  listShots,
  regenerateImageForShotAsync,
  selectImageAsFinal,
  updateShotPrompt,
} from '../api/scripts'
import { startRunPoll, useRunPoll } from '../composables/useRunPoll'
import { extractError } from '../lib/format'
import ErrorBanner from '../components/ErrorBanner.vue'
import RunProgress from '../components/RunProgress.vue'
import ImageCard from '../components/image/ImageCard.vue'
import type { ImageAsset, PipelineRun, StoryboardShot } from '../types'

const props = defineProps<{ scriptId: number }>()

const images = ref<ImageAsset[]>([])
const shotsMap = ref<Record<number, StoryboardShot>>({})  // shotId → StoryboardShot,提供 promptZh
const protagonistRefAssetId = ref<number | null>(null)
const loading = ref(false)
const triggering = ref(false)
const auditing = ref(false)
const limit = ref<number | null>(null)
const force = ref(false)
const errorMsg = ref<string | null>(null)
const runMsg = ref<string | null>(null)

type ShotRunState = { runId: number; status: PipelineRun['status']; msg?: string }
const shotRuns = ref<Record<number, ShotRunState>>({})
const shotPollStops: Record<number, () => void> = {}

type AssetRunState = { runId: number; status: PipelineRun['status']; msg?: string }
const assetRuns = ref<Record<number, AssetRunState>>({})
const assetPollStops: Record<number, () => void> = {}

const finalCount = computed(() => images.value.filter((i) => i.isFinal).length)
const shotHasFinalMap = computed(() => {
  const map: Record<number, boolean> = {}
  for (const img of images.value) { if (img.isFinal) map[img.shotId] = true }
  return map
})
const failedCount = computed(
  () => images.value.filter((i) => i.reviewDecision && (i.reviewDecision === 'FAIL' || i.reviewDecision === 'REJECT')).length,
)
const avgScore = computed(() => {
  const scored = images.value.map((i) => i.reviewScore).filter((s): s is number => typeof s === 'number')
  if (scored.length === 0) return null
  return Math.round((scored.reduce((a, b) => a + b, 0) / scored.length) * 10) / 10
})
const modelLabel = computed(() => {
  const m = images.value.map((i) => i.model).filter(Boolean) as string[]
  if (m.length === 0) return ''
  const counts: Record<string, number> = {}
  for (const x of m) counts[x] = (counts[x] || 0) + 1
  return Object.entries(counts).sort((a, b) => b[1] - a[1])[0][0]
})

async function load() {
  loading.value = true
  try {
    const [imgs, detail, shots] = await Promise.all([
      listImages(props.scriptId),
      getScript(props.scriptId).catch(() => null),
      listShots(props.scriptId).catch(() => [] as StoryboardShot[]),
    ])
    images.value = imgs
    protagonistRefAssetId.value = detail?.script?.protagonistRefAssetId ?? null
    const map: Record<number, StoryboardShot> = {}
    for (const s of shots) map[s.id] = s
    shotsMap.value = map
    errorMsg.value = null
  } catch (e: any) {
    errorMsg.value = extractError(e, 'fetch failed')
  } finally {
    loading.value = false
  }
}

// 某张图片对应 shot 的 prompt 编辑 —— 微调后可选"仅保存"或"保存并重生"
const editingShotId = ref<number | null>(null)
const draftPrompt = ref('')
const promptSaving = ref(false)
const promptError = ref<string | null>(null)

function startEditPrompt(shotId: number) {
  editingShotId.value = shotId
  draftPrompt.value = shotsMap.value[shotId]?.promptZh ?? ''
  promptError.value = null
}
function cancelEditPrompt() {
  editingShotId.value = null
  promptError.value = null
}

async function savePromptOnly() {
  if (editingShotId.value == null) return
  await persistPromptDraft()
}

async function savePromptAndRegen() {
  if (editingShotId.value == null) return
  const sid = editingShotId.value
  const ok = await persistPromptDraft()
  if (ok) await regenerateShot(sid)
}

/** 把当前 draft 提交到后端,成功后关闭编辑器并 patch 本地 shotsMap。返回是否成功。 */
async function persistPromptDraft(): Promise<boolean> {
  if (editingShotId.value == null) return false
  const sid = editingShotId.value
  promptSaving.value = true
  promptError.value = null
  try {
    const updated = await updateShotPrompt(sid, { promptZh: draftPrompt.value })
    shotsMap.value = { ...shotsMap.value, [sid]: updated }
    editingShotId.value = null
    return true
  } catch (e: any) {
    promptError.value = extractError(e, '保存失败')
    return false
  } finally {
    promptSaving.value = false
  }
}

async function triggerGen() {
  triggering.value = true
  runMsg.value = null
  try {
    const res = await generateImagesAsync(
      props.scriptId,
      limit.value ?? undefined,
      force.value,
    )
    runMsg.value = `生图任务已提交 · run ${res.runId}`
    await genPoll.start(res.runId)
  } catch (e: any) {
    runMsg.value = `失败:${extractError(e)}`
  } finally {
    triggering.value = false
  }
}

async function triggerAudit() {
  auditing.value = true
  runMsg.value = null
  try {
    const res = await auditImagesAsync(props.scriptId)
    runMsg.value = `图审任务已提交 · run ${res.runId}`
    await auditPoll.start(res.runId)
  } catch (e: any) {
    runMsg.value = `失败:${extractError(e)}`
  } finally {
    auditing.value = false
  }
}

// 整片生图 / 整片图审 进度轮询(per-shot 重生 / 单图重审走 startRunPoll 各自一份)
const genPoll = useRunPoll({
  onDone: async () => {
    runMsg.value = '生图完成 ✓'
    try { images.value = await listImages(props.scriptId) } catch { /* ignore */ }
  },
  onFailed: (r) => { runMsg.value = `生图失败:${r.errorMsg ?? r.status}` },
})
const auditPoll = useRunPoll({
  onDone: async () => {
    runMsg.value = '图审完成 ✓'
    try { images.value = await listImages(props.scriptId) } catch { /* ignore */ }
  },
  onFailed: (r) => { runMsg.value = `图审失败:${r.errorMsg ?? r.status}` },
})
const genRun = genPoll.run
const auditRun = auditPoll.run

function stopShotPoll(shotId: number) {
  const stop = shotPollStops[shotId]
  if (stop) { stop(); delete shotPollStops[shotId] }
}

async function regenerateShot(shotId: number) {
  const sameShot = images.value.filter((i) => i.shotId === shotId)
  const hasFinal = sameShot.some((i) => i.isFinal)
  const hasIssues = sameShot.some((i) => i.reviewIssues && i.reviewIssues.trim() !== '')
  const refineHint = hasIssues
    ? '\n后端会自动结合上一轮审图扣分点优化 prompt 再生图。'
    : ''
  const ok = window.confirm(
    `重生 shot ${shotId} 这一镜 → 会删掉这个 shot 下面 ${sameShot.length} 张图${hasFinal ? '(含已定稿 final)' : ''},然后重新生成。${refineHint}\n继续吗?`,
  )
  if (!ok) return
  stopShotPoll(shotId)
  try {
    const { runId } = await regenerateImageForShotAsync(shotId)
    shotRuns.value = { ...shotRuns.value, [shotId]: { runId, status: 'RUNNING' } }
    shotPollStops[shotId] = startRunPoll(runId, {
      onUpdate: (r) => {
        const cur = shotRuns.value[shotId]
        if (cur) cur.status = r.status
      },
      onDone: async () => {
        const cur = shotRuns.value[shotId]
        if (cur) cur.msg = '重生 ✓'
        try { images.value = await listImages(props.scriptId) } catch { /* ignore */ }
        window.setTimeout(() => { delete shotRuns.value[shotId] }, 1500)
      },
      onFailed: (r) => {
        const cur = shotRuns.value[shotId]
        if (cur) cur.msg = `失败:${r.errorMsg ?? r.status}`
        window.setTimeout(() => { delete shotRuns.value[shotId] }, 4000)
      },
    })
  } catch (e: any) {
    shotRuns.value = {
      ...shotRuns.value,
      [shotId]: { runId: 0, status: 'FAILED', msg: `失败:${extractError(e)}` },
    }
    window.setTimeout(() => { delete shotRuns.value[shotId] }, 4000)
  }
}

function stopAssetPoll(assetId: number) {
  const stop = assetPollStops[assetId]
  if (stop) { stop(); delete assetPollStops[assetId] }
}

async function reauditAsset(assetId: number) {
  stopAssetPoll(assetId)
  try {
    const { runId } = await auditImageAsync(assetId)
    assetRuns.value = { ...assetRuns.value, [assetId]: { runId, status: 'RUNNING' } }
    assetPollStops[assetId] = startRunPoll(runId, {
      onUpdate: (r) => {
        const cur = assetRuns.value[assetId]
        if (cur) cur.status = r.status
      },
      onDone: async () => {
        const cur = assetRuns.value[assetId]
        if (cur) cur.msg = '重审 ✓'
        try { images.value = await listImages(props.scriptId) } catch { /* ignore */ }
        window.setTimeout(() => { delete assetRuns.value[assetId] }, 1500)
      },
      onFailed: (r) => {
        const cur = assetRuns.value[assetId]
        if (cur) cur.msg = `失败:${r.errorMsg ?? r.status}`
        window.setTimeout(() => { delete assetRuns.value[assetId] }, 4000)
      },
    })
  } catch (e: any) {
    assetRuns.value = {
      ...assetRuns.value,
      [assetId]: { runId: 0, status: 'FAILED', msg: `失败:${extractError(e)}` },
    }
    window.setTimeout(() => { delete assetRuns.value[assetId] }, 4000)
  }
}

async function selectAsFinal(assetId: number) {
  try {
    await selectImageAsFinal(assetId)
    images.value = await listImages(props.scriptId)
  } catch (e: any) {
    console.warn('[selectAsFinal]', e?.message)
  }
}

onMounted(load)
onBeforeUnmount(() => {
  for (const shotId of Object.keys(shotPollStops)) stopShotPoll(Number(shotId))
  for (const assetId of Object.keys(assetPollStops)) stopAssetPoll(Number(assetId))
})
</script>

<template>
  <div class="min-h-full">
    <div class="sticky top-0 z-10 bg-surface-primary border-b border-border-subtle">
      <div class="px-8 py-3 max-w-[1400px] mx-auto flex flex-col gap-1">
        <div class="flex items-center gap-4 flex-wrap">
          <button
            class="flex items-center gap-1.5 text-sm text-text-muted hover:text-text-primary"
            @click="$router.push('/images')"
          >
            <ArrowLeft :size="14" /> 返回
          </button>
          <h1 class="text-lg font-semibold">图片资产 · script #{{ scriptId }}</h1>
        </div>
        <div class="text-xs text-text-muted truncate">
          {{ images.length }} 张 · 已选定 {{ finalCount }}
          <template v-if="modelLabel"> · {{ modelLabel }}</template>
        </div>
      </div>
    </div>

    <div class="px-8 py-5 max-w-[1400px] mx-auto">
      <ErrorBanner :msg="errorMsg" />

      <div class="grid grid-cols-2 md:grid-cols-4 gap-3 mb-4">
        <div class="card p-4">
          <div class="text-xs text-text-muted mb-1">图片总数</div>
          <div class="text-2xl font-mono font-semibold">{{ images.length }}</div>
        </div>
        <div class="card p-4">
          <div class="text-xs text-text-muted mb-1">已选定</div>
          <div class="text-2xl font-mono font-semibold text-status-done">{{ finalCount }}</div>
        </div>
        <div class="card p-4">
          <div class="text-xs text-text-muted mb-1">审图未通过</div>
          <div class="text-2xl font-mono font-semibold" :class="failedCount > 0 ? 'text-status-failed' : ''">{{ failedCount }}</div>
        </div>
        <div class="card p-4">
          <div class="text-xs text-text-muted mb-1">平均审图分</div>
          <div class="text-2xl font-mono font-semibold">{{ avgScore ?? '—' }}</div>
        </div>
      </div>

      <div class="card p-3 mb-4 flex items-center gap-2 flex-wrap">
        <button class="btn-ghost" :disabled="loading" @click="load">
          <RefreshCw :size="13" :class="loading ? 'animate-spin' : ''" /> 刷新
        </button>
        <button class="btn-primary" :disabled="triggering || !!genRun" @click="triggerGen">
          <Play :size="13" /> {{ triggering ? '提交中...' : '触发生图' }}
        </button>
        <button class="btn-ghost" :disabled="auditing || !!auditRun" @click="triggerAudit">
          <ShieldCheck :size="13" /> {{ auditing ? '提交中...' : '触发图审' }}
        </button>
        <label class="text-xs text-text-muted flex items-center gap-1.5 ml-2">
          limit
          <input
            v-model.number="limit" type="number" min="1" max="200" placeholder="全部"
            class="bg-surface-tertiary border border-border-subtle rounded px-2 py-1 w-16 text-text-primary text-xs"
          />
        </label>
        <label class="text-xs text-text-muted flex items-center gap-1.5">
          <input v-model="force" type="checkbox" class="accent-accent" />
          force 重生
        </label>
        <span v-if="runMsg && !genRun && !auditRun"
              class="text-xs ml-2"
              :class="runMsg.includes('失败') ? 'text-status-failed' : 'text-status-done'">
          {{ runMsg }}
        </span>
      </div>

      <div v-if="genRun || auditRun" class="card p-3 mb-4 space-y-2">
        <RunProgress v-if="genRun" :run="genRun" label="生图中" unit="张" />
        <RunProgress v-if="auditRun" :run="auditRun" label="图审中" unit="张" />
      </div>

      <div v-if="loading && images.length === 0" class="card p-12 text-center">
        <Loader2 :size="20" class="animate-spin text-text-muted mx-auto" />
      </div>

      <div v-else-if="images.length === 0" class="card p-12 text-center text-text-muted text-sm">
        还没有图片(点上面「触发生图」开始)
      </div>

      <div v-else class="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-4 gap-3">
        <ImageCard
          v-for="img in images" :key="img.id"
          :img="img"
          :protagonist-ref-asset-id="protagonistRefAssetId"
          :shot-has-final="!!shotHasFinalMap[img.shotId]"
          :shot-run="shotRuns[img.shotId]"
          :asset-run="assetRuns[img.id]"
          :is-editing-prompt="editingShotId === img.shotId"
          :draft-prompt="draftPrompt"
          :prompt-saving="promptSaving"
          :prompt-error="promptError"
          @select-as-final="selectAsFinal"
          @regenerate-shot="regenerateShot"
          @reaudit-asset="reauditAsset"
          @start-edit-prompt="startEditPrompt"
          @cancel-edit-prompt="cancelEditPrompt"
          @update:draft-prompt="draftPrompt = $event"
          @save-prompt-only="savePromptOnly"
          @save-prompt-and-regen="savePromptAndRegen"
        />
      </div>
    </div>
  </div>
</template>
