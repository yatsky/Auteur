<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ArrowLeft, ArrowRight, ArrowUpDown, Calendar, Lightbulb, Loader2, RotateCcw, Search,
  Sparkles, Trash2, X,
} from 'lucide-vue-next'
import { brainstormTopics, deleteTopic, listTopics } from '../api/topics'
import { dismissHook, listUnresolvedHooks } from '../api/seriesHooks'
import { extractError } from '../lib/format'
import ErrorBanner from '../components/ErrorBanner.vue'
import TimeText from '../components/TimeText.vue'
import PendingHooksBanner from '../components/PendingHooksBanner.vue'
import FulfillHookDialog from '../components/FulfillHookDialog.vue'
import DismissedHooksDialog from '../components/DismissedHooksDialog.vue'
import PresetSelector from '../components/PresetSelector.vue'
import { useListFilter } from '../composables/useListFilter'
import { useAsyncLoad } from '../composables/useAsyncLoad'
import type { SeriesHook, Topic, TopicStatus } from '../types'
import { ALL_TOPIC_STATUSES, TOPIC_STATUS_LABELS } from '../types'

const router = useRouter()
const route = useRoute()

const status = ref<TopicStatus>('DRAFT')
const page = ref(0)
const pageSize = 30
const items = ref<Topic[]>([])
const total = ref(0)

const hooks = ref<SeriesHook[]>([])
const hookFulfilling = ref<SeriesHook | null>(null)
const dismissedDialogOpen = ref(false)

const brainstormOpen = ref(false)
const brainstormHint = ref('')
const brainstormAvoidDup = ref(true)
const brainstormBusy = ref(false)
const brainstormError = ref<string | null>(null)
const brainstormResultMsg = ref<string | null>(null)
const brainstormDataDriven = ref(true)
const brainstormPlatform = ref<string>('')
const brainstormWindowDays = ref<number>(30)
const brainstormPresetId = ref<number | null>(null)
const PLATFORM_OPTIONS = ['', '抖音', 'B站', '视频号', '小红书', '西瓜'] as const
const WINDOW_OPTIONS = [7, 30, 90] as const

const SORT_LABELS = {
  createdAt: '按创建时间',
  potentialScore: '按 AI 评分',
  title: '按标题',
} as const

const { keyword: search, sortKey: sortBy, filtered: filteredItems } = useListFilter<Topic>({
  source: items,
  searchableText: (t) => [t.title, t.dynasty ?? '', t.genre ?? '', t.notes ?? ''],
  sortOptions: {
    createdAt: (a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime(),
    potentialScore: (a, b) => (b.potentialScore ?? -1) - (a.potentialScore ?? -1),
    title: (a, b) => a.title.localeCompare(b.title, 'zh-CN'),
  },
  defaultSort: 'createdAt',
})

const recentTitles = computed(() => items.value.map((t) => t.title).filter(Boolean))

async function load() {
  const resp = await listTopics(status.value, page.value, pageSize)
  items.value = resp.content
  total.value = resp.page.totalElements
}
const { loading, errorMsg, run: runLoad } = useAsyncLoad(load)

async function loadHooks() {
  try {
    hooks.value = await listUnresolvedHooks(true)
  } catch {
    hooks.value = []
  }
}

onMounted(() => {
  runLoad()
  loadHooks()
  if (typeof route.query.status === 'string'
    && (ALL_TOPIC_STATUSES as readonly string[]).includes(route.query.status)) {
    status.value = route.query.status as TopicStatus
  }
  if (route.query.brainstorm === 'data') {
    brainstormDataDriven.value = true
    if (typeof route.query.platform === 'string') brainstormPlatform.value = route.query.platform
    brainstormOpen.value = true
  }
})
watch([status, page], () => { runLoad() })

async function onDismissHook(id: number) {
  const idx = hooks.value.findIndex((h) => h.id === id)
  if (idx >= 0) hooks.value.splice(idx, 1)
  try {
    await dismissHook(id)
  } catch {
    await loadHooks()
  }
}

function onFulfillClick(hook: SeriesHook) {
  hookFulfilling.value = hook
}

function onDismissedRestored(_id: number) {
  loadHooks()
}

function onFulfillConfirmed(topic: Topic, autoGen: boolean) {
  const fulfilledId = hookFulfilling.value?.id
  hookFulfilling.value = null
  if (fulfilledId != null) {
    const idx = hooks.value.findIndex((h) => h.id === fulfilledId)
    if (idx >= 0) hooks.value.splice(idx, 1)
  }
  router.push({ path: `/topics/${topic.id}`, query: autoGen ? { autoGen: '1' } : {} })
}

function setStatus(s: TopicStatus) {
  status.value = s
  page.value = 0
}

const deletingId = ref<number | null>(null)
async function onDeleteTopic(t: Topic, evt: Event) {
  evt.stopPropagation()
  if (!confirm(`确定删除「${t.title}」?\n\n真删,不可恢复。`)) return
  deletingId.value = t.id
  try {
    await deleteTopic(t.id)
    const idx = items.value.findIndex((x) => x.id === t.id)
    if (idx >= 0) items.value.splice(idx, 1)
    total.value = Math.max(0, total.value - 1)
    errorMsg.value = null
  } catch (e: any) {
    errorMsg.value = extractError(e, '删除失败')
  } finally {
    deletingId.value = null
  }
}

function openBrainstorm() {
  brainstormOpen.value = true
  brainstormError.value = null
  brainstormResultMsg.value = null
}

async function runBrainstorm() {
  if (!brainstormPresetId.value) {
    brainstormError.value = '请先选择一个预设'
    return
  }
  brainstormBusy.value = true
  brainstormError.value = null
  brainstormResultMsg.value = null
  try {
    const doneTopics = brainstormAvoidDup.value && recentTitles.value.length
      ? recentTitles.value.slice(0, 30).join(' / ')
      : undefined
    const userHint = brainstormHint.value.trim()
    const created = await brainstormTopics({
      presetId: brainstormPresetId.value,
      n: 1,
      archiveHint: userHint || undefined,
      doneTopics,
      useDataDriven: brainstormDataDriven.value || undefined,
      platform: brainstormDataDriven.value && brainstormPlatform.value ? brainstormPlatform.value : undefined,
      windowDays: brainstormDataDriven.value ? brainstormWindowDays.value : undefined,
    })
    brainstormResultMsg.value = `已生成 ${created.length} 条候选选题(状态=草稿)`
    if (status.value !== 'DRAFT') {
      status.value = 'DRAFT'
      page.value = 0
    } else {
      page.value = 0
      await runLoad()
    }
    setTimeout(() => { brainstormOpen.value = false }, 1500)
  } catch (e: any) {
    brainstormError.value = extractError(e, '调用失败')
  } finally {
    brainstormBusy.value = false
  }
}

const STATUS_BADGE: Record<TopicStatus, string> = {
  DRAFT: 'bg-status-paused/15 text-status-paused',
  SCHEDULED: 'bg-accent-soft text-accent',
  PRODUCED: 'bg-status-done/15 text-status-done',
  PUBLISHED: 'bg-status-done/15 text-status-done',
  ARCHIVED: 'bg-surface-tertiary text-text-muted',
}

function ctaText(s: TopicStatus): string {
  if (s === 'DRAFT') return '查看详情'
  if (s === 'SCHEDULED') return '去脚本'
  if (s === 'PRODUCED') return '去发布'
  if (s === 'PUBLISHED') return '去复盘'
  return '查看详情'
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
          <h1 class="text-lg font-semibold flex items-center gap-2">
            <Lightbulb :size="16" class="text-accent" /> 选题池
          </h1>
          <span class="chip text-[11px] bg-surface-tertiary text-text-muted">
            {{ total }} 条 · {{ TOPIC_STATUS_LABELS[status] }}
          </span>
          <Loader2 v-if="loading" :size="14" class="animate-spin text-text-muted" />
          <button class="ml-auto btn-primary text-sm" @click="openBrainstorm">
            <Sparkles :size="13" /> AI 生成选题
          </button>
        </div>
        <div class="text-xs text-text-muted">候选选题 · 状态推进 · AI 头脑风暴</div>
      </div>
    </div>

    <div class="px-8 py-5 max-w-[1400px] mx-auto">
      <ErrorBanner :msg="errorMsg" />

      <PendingHooksBanner :hooks="hooks" @dismiss="onDismissHook" @fulfill="onFulfillClick" />

      <div class="flex justify-end -mt-2 mb-4">
        <button
          class="text-xs text-text-muted hover:text-text-primary flex items-center gap-1"
          @click="dismissedDialogOpen = true"
        >
          <RotateCcw :size="12" /> 查看已忽略的钩子
        </button>
      </div>

      <div class="card p-3 mb-4 flex items-center gap-2 flex-wrap">
        <div class="flex items-center gap-1 flex-wrap">
          <button
            v-for="s in ALL_TOPIC_STATUSES" :key="s"
            class="chip cursor-pointer text-xs"
            :class="status === s ? 'bg-accent-soft text-accent' : 'bg-surface-tertiary text-text-secondary hover:text-text-primary'"
            @click="setStatus(s)"
          >{{ TOPIC_STATUS_LABELS[s] }}</button>
        </div>
        <div class="flex items-center gap-2 ml-auto flex-wrap">
          <label class="flex items-center gap-1.5 bg-surface-tertiary border border-border-subtle rounded px-2 py-1.5">
            <Search :size="13" class="text-text-muted" />
            <input v-model="search" placeholder="搜索标题 / 朝代 / 题材"
                   class="bg-transparent outline-none text-xs w-48" />
          </label>
          <label class="flex items-center gap-1.5 bg-surface-tertiary border border-border-subtle rounded px-2 py-1.5">
            <ArrowUpDown :size="13" class="text-text-muted" />
            <select v-model="sortBy" class="bg-transparent outline-none text-xs cursor-pointer">
              <option v-for="(label, key) in SORT_LABELS" :key="key" :value="key">{{ label }}</option>
            </select>
          </label>
        </div>
      </div>

    <div v-if="!loading && filteredItems.length === 0" class="card p-12 text-center text-sm text-text-muted">
      没有 {{ TOPIC_STATUS_LABELS[status] }} 的选题
      <span v-if="status === 'DRAFT'"> — 试试右上角「AI 生成选题」</span>
    </div>

    <div v-else class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-3">
      <article
        v-for="t in filteredItems" :key="t.id"
        class="card p-4 flex flex-col gap-3 cursor-pointer hover:border-accent/40 transition-colors group"
        @click="$router.push(`/topics/${t.id}`)"
      >
        <header class="flex items-center justify-between gap-2">
          <div class="flex items-center gap-1.5 flex-wrap">
            <span class="chip text-[10px] font-semibold" :class="STATUS_BADGE[t.status]">
              {{ TOPIC_STATUS_LABELS[t.status] }}
            </span>
          </div>
          <div class="flex items-center gap-2">
            <span v-if="t.potentialScore != null" class="flex items-center gap-1 text-xs text-accent font-semibold">
              <Sparkles :size="11" /> AI {{ t.potentialScore }}
            </span>
            <button
              v-if="status === 'DRAFT'"
              class="text-text-muted hover:text-status-failed disabled:opacity-50 opacity-0 group-hover:opacity-100 transition-opacity"
              title="删除草稿"
              :disabled="deletingId === t.id"
              @click="onDeleteTopic(t, $event)"
            >
              <Loader2 v-if="deletingId === t.id" :size="13" class="animate-spin" />
              <Trash2 v-else :size="13" />
            </button>
          </div>
        </header>

        <h3 class="text-[15px] font-semibold leading-snug line-clamp-2">{{ t.title }}</h3>

        <div v-if="t.dynasty || t.genre || t.hookType" class="flex items-center gap-1.5 flex-wrap">
          <span v-if="t.dynasty" class="chip text-[11px] bg-surface-secondary text-text-secondary">#{{ t.dynasty }}</span>
          <span v-if="t.genre" class="chip text-[11px] bg-surface-secondary text-text-secondary">#{{ t.genre }}</span>
          <span v-if="t.hookType" class="chip text-[11px] bg-surface-secondary text-text-secondary">#{{ t.hookType }}</span>
        </div>

        <p v-if="t.notes" class="text-xs text-text-secondary leading-relaxed line-clamp-3">
          {{ t.notes }}
        </p>
        <p v-else-if="t.historicalReference" class="text-xs text-text-secondary leading-relaxed line-clamp-3">
          {{ t.historicalReference }}
        </p>

        <footer class="mt-auto pt-2.5 border-t border-border-subtle flex items-center justify-between text-xs">
          <span class="flex items-center gap-1.5 text-text-muted">
            <Calendar :size="11" />
            <TimeText :value="t.createdAt" relative />
          </span>
          <a
            v-if="t.latestScriptId"
            class="chip cursor-pointer bg-accent-soft text-accent flex items-center gap-1"
            @click.stop="$router.push(`/scripts/${t.latestScriptId}`)"
          >
            脚本 {{ t.latestScriptId }} <ArrowRight :size="11" />
          </a>
          <span v-else class="chip bg-surface-secondary text-text-secondary flex items-center gap-1">
            {{ ctaText(t.status) }} <ArrowRight :size="11" />
          </span>
        </footer>
      </article>
    </div>

    <div class="flex items-center justify-between mt-4 text-sm text-text-muted">
      <div>第 {{ total === 0 ? 0 : page + 1 }} / {{ Math.max(1, Math.ceil(total / pageSize)) }} 页</div>
      <div class="flex gap-2">
        <button class="btn-ghost" :disabled="page === 0 || loading" @click="page = page - 1">上一页</button>
        <button class="btn-ghost" :disabled="(page + 1) * pageSize >= total || loading" @click="page = page + 1">下一页</button>
      </div>
    </div>

    <FulfillHookDialog
      :hook="hookFulfilling"
      @close="hookFulfilling = null"
      @confirmed="onFulfillConfirmed"
    />

    <DismissedHooksDialog
      v-if="dismissedDialogOpen"
      @close="dismissedDialogOpen = false"
      @restored="onDismissedRestored"
    />

    <div v-if="brainstormOpen"
         class="fixed inset-0 z-50 bg-black/60 flex items-center justify-center p-4"
         @click.self="!brainstormBusy && (brainstormOpen = false)">
      <div class="card p-6 max-w-[560px] w-full">
        <header class="flex items-center justify-between mb-4">
          <h2 class="text-lg font-semibold flex items-center gap-2">
            <Sparkles :size="18" class="text-accent" /> AI 头脑风暴
          </h2>
          <button class="text-text-muted hover:text-text-primary"
                  :disabled="brainstormBusy" @click="brainstormOpen = false">
            <X :size="20" />
          </button>
        </header>

        <p class="text-xs text-text-muted mb-4">
          调 LLM 生成 1 条候选选题,按所选预设的 brainstorm prompt 渲染。同步等 10-30s,别关页面。
        </p>

        <div v-if="brainstormError" class="card p-3 mb-3 bg-status-failed/10 border-status-failed/30 text-xs text-status-failed">
          {{ brainstormError }}
        </div>
        <div v-if="brainstormResultMsg" class="card p-3 mb-3 bg-status-done/10 border-status-done/30 text-xs text-status-done">
          {{ brainstormResultMsg }}
        </div>

        <div class="grid grid-cols-1 gap-3 text-sm">
          <div class="space-y-1.5">
            <span class="text-xs text-text-muted">预设</span>
            <PresetSelector v-model="brainstormPresetId" brainstorm-capable />
          </div>

          <label class="flex flex-col gap-1">
            <span class="text-xs text-text-muted">提示词 / 主题方向(可选)</span>
            <textarea v-model="brainstormHint" rows="3"
                      :disabled="brainstormBusy"
                      placeholder="例:大厂程序员 / 宋代汴京书贩 / 北京胡同流浪猫 …(对应所选预设的输入语义)"
                      class="bg-surface-tertiary border border-border-subtle rounded px-2 py-1.5 font-sans resize-y" />
          </label>
          <label class="flex items-center gap-2">
            <input v-model="brainstormAvoidDup" type="checkbox" :disabled="brainstormBusy" />
            <span class="text-xs text-text-secondary">
              自动避开当前列表里的标题(把前 30 条作为 doneTopics 传给 LLM)
            </span>
          </label>

          <div class="border-t border-border-subtle pt-3 mt-1">
            <label class="flex items-start gap-2">
              <input v-model="brainstormDataDriven" type="checkbox" :disabled="brainstormBusy" class="mt-0.5" />
              <div class="flex-1">
                <div class="text-xs text-text-secondary flex items-center gap-1">
                  📊 数据驱动 <span class="chip text-[9px] bg-accent-soft text-accent">推荐</span>
                </div>
                <div class="text-[10px] text-text-muted mt-0.5">
                  用本号已发布数据(published_video)动态替代 yaml 里写死的权重表;同时把完播率 Top 5 / Bottom 5 视频特征喂给 LLM。
                </div>
              </div>
            </label>
            <div v-if="brainstormDataDriven" class="grid grid-cols-2 gap-3 mt-3 pl-6">
              <label class="flex flex-col gap-1">
                <span class="text-[11px] text-text-muted">平台口径</span>
                <select v-model="brainstormPlatform" :disabled="brainstormBusy"
                        class="bg-surface-tertiary border border-border-subtle rounded px-2 py-1.5 text-xs">
                  <option v-for="p in PLATFORM_OPTIONS" :key="p || 'all'" :value="p">{{ p || '全部' }}</option>
                </select>
              </label>
              <label class="flex flex-col gap-1">
                <span class="text-[11px] text-text-muted">统计窗口</span>
                <select v-model.number="brainstormWindowDays" :disabled="brainstormBusy"
                        class="bg-surface-tertiary border border-border-subtle rounded px-2 py-1.5 text-xs">
                  <option v-for="d in WINDOW_OPTIONS" :key="d" :value="d">{{ d }} 天</option>
                </select>
              </label>
            </div>
          </div>
        </div>

        <div class="flex items-center gap-2 mt-5">
          <button class="chip cursor-pointer bg-surface-tertiary text-text-secondary text-sm"
                  :disabled="brainstormBusy" @click="brainstormOpen = false">
            取消
          </button>
          <button class="btn-primary ml-auto" :disabled="brainstormBusy" @click="runBrainstorm">
            <Loader2 v-if="brainstormBusy" :size="14" class="animate-spin" />
            <Sparkles v-else :size="14" />
            {{ brainstormBusy ? '生成中(LLM 慢,可能 30s+)…' : '生成 1 条「人生采样」' }}
          </button>
        </div>
      </div>
    </div>
    </div>
  </div>
</template>
