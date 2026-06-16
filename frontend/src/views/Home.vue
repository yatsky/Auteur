<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowRight, ArrowUp, ArrowDown, Minus, Sparkles, FileText, Film, LayoutDashboard, Lightbulb, Layers } from 'lucide-vue-next'
import ErrorBanner from '../components/ErrorBanner.vue'
import TimeText from '../components/TimeText.vue'
import { listTopics } from '../api/topics'
import { listPublishedVideos, type PublishedVideo } from '../api/publishedVideos'
import { useAsyncLoad } from '../composables/useAsyncLoad'
import {
  ALL_TOPIC_STATUSES,
  TOPIC_STATUS_LABELS,
  type Topic,
  type TopicStatus,
} from '../types'

const router = useRouter()

const statusCounts = ref<Record<TopicStatus, number>>({
  DRAFT: 0, SCHEDULED: 0, PRODUCED: 0, PUBLISHED: 0, ARCHIVED: 0,
})
const recentDraftTopics = ref<Topic[]>([])
const publishedVideos = ref<PublishedVideo[]>([])

const { loading, errorMsg, run: load } = useAsyncLoad(async () => {
  const [topicResults, videos] = await Promise.all([
    Promise.all(ALL_TOPIC_STATUSES.map((s) => listTopics(s, 0, s === 'DRAFT' ? 5 : 1))),
    listPublishedVideos().catch(() => [] as PublishedVideo[]),
  ])
  ALL_TOPIC_STATUSES.forEach((s, i) => {
    statusCounts.value[s] = topicResults[i].page.totalElements
  })
  recentDraftTopics.value = topicResults[0].content
  publishedVideos.value = videos
})

onMounted(load)

const SEVEN_DAYS_MS = 7 * 86400_000
const THIRTY_DAYS_MS = 30 * 86400_000

function inWindow(iso: string, ms: number): boolean {
  return Date.now() - new Date(iso).getTime() <= ms
}

function isThisMonth(iso: string): boolean {
  const d = new Date(iso)
  const now = new Date()
  return d.getFullYear() === now.getFullYear() && d.getMonth() === now.getMonth()
}

function isLastMonth(iso: string): boolean {
  const d = new Date(iso)
  const now = new Date()
  const last = new Date(now.getFullYear(), now.getMonth() - 1, 1)
  return d.getFullYear() === last.getFullYear() && d.getMonth() === last.getMonth()
}

const recentTopicCount = computed(() =>
  recentDraftTopics.value.filter((t) => inWindow(t.createdAt, SEVEN_DAYS_MS)).length,
)

const inFlightCount = computed(() => statusCounts.value.SCHEDULED + statusCounts.value.PRODUCED)

const monthlyVideoCount = computed(() =>
  publishedVideos.value.filter((v) => isThisMonth(v.publishedAt)).length,
)
const lastMonthVideoCount = computed(() =>
  publishedVideos.value.filter((v) => isLastMonth(v.publishedAt)).length,
)
const monthlyVideoDiff = computed(() => {
  const cur = monthlyVideoCount.value
  const prev = lastMonthVideoCount.value
  if (prev === 0) return null
  return ((cur - prev) / prev) * 100
})

const totalViews30d = computed(() =>
  publishedVideos.value
    .filter((v) => inWindow(v.publishedAt, THIRTY_DAYS_MS))
    .reduce((s, v) => s + (v.views || 0), 0),
)
const prevTotalViews30d = computed(() => {
  const start = Date.now() - 60 * 86400_000
  const end = Date.now() - 30 * 86400_000
  return publishedVideos.value.reduce((s, v) => {
    const t = new Date(v.publishedAt).getTime()
    return t >= start && t < end ? s + (v.views || 0) : s
  }, 0)
})
const totalViewsDiff = computed(() => {
  const prev = prevTotalViews30d.value
  if (prev === 0) return null
  return ((totalViews30d.value - prev) / prev) * 100
})

function fmtNum(n: number): string {
  if (n >= 100_000_000) return (n / 100_000_000).toFixed(1) + '亿'
  if (n >= 10_000) return (n / 10_000).toFixed(1) + '万'
  return n.toLocaleString()
}

const totalTopics = computed(() =>
  ALL_TOPIC_STATUSES.reduce((s, k) => s + statusCounts.value[k], 0),
)

const statusBars = computed(() =>
  ALL_TOPIC_STATUSES.map((s, i) => ({
    key: s,
    label: TOPIC_STATUS_LABELS[s],
    count: statusCounts.value[s],
    pct: totalTopics.value > 0 ? (statusCounts.value[s] / totalTopics.value) * 100 : 0,
    opacity: [0.85, 0.7, 0.55, 0.4, 0.25][i],
  })),
)

interface ActivityItem {
  kind: 'topic' | 'video'
  id: number
  title: string
  meta: string
  at: string
  to: string
  color: string
}

const activityFeed = computed<ActivityItem[]>(() => {
  const items: ActivityItem[] = []
  for (const t of recentDraftTopics.value) {
    items.push({
      kind: 'topic',
      id: t.id,
      title: t.title,
      meta: `新建选题${t.dynasty ? ' · ' + t.dynasty : ''}${t.genre ? ' · ' + t.genre : ''}`,
      at: t.createdAt,
      to: `/topics/${t.id}`,
      color: 'bg-accent',
    })
  }
  for (const v of publishedVideos.value.slice(0, 5)) {
    items.push({
      kind: 'video',
      id: v.id,
      title: v.title,
      meta: `${v.platform} · ${fmtNum(v.views)} 播放`,
      at: v.publishedAt,
      to: `/review/${v.id}`,
      color: 'bg-status-done',
    })
  }
  return items
    .sort((a, b) => new Date(b.at).getTime() - new Date(a.at).getTime())
    .slice(0, 6)
})

function gotoBrainstorm() {
  router.push({ path: '/topics', query: { brainstorm: 'data' } })
}
</script>

<template>
  <div class="min-h-full">
    <div class="sticky top-0 z-10 bg-surface-primary border-b border-border-subtle">
      <div class="px-8 py-3 max-w-[1400px] mx-auto flex flex-col gap-1">
        <div class="flex items-center gap-4 flex-wrap">
          <h1 class="text-lg font-semibold flex items-center gap-2">
            <LayoutDashboard :size="16" class="text-accent" /> 工作台
          </h1>
          <span class="chip text-[11px] bg-surface-tertiary text-text-muted">{{ totalTopics }} 选题 · {{ publishedVideos.length }} 视频</span>
          <div class="ml-auto flex gap-2">
            <button class="btn text-sm" @click="router.push('/presets')">
              <Layers :size="13" /> 预设库
            </button>
            <button class="btn-primary text-sm" @click="gotoBrainstorm">
              <Sparkles :size="13" /> AI 生成选题
            </button>
          </div>
        </div>
        <div class="text-xs text-text-muted">今日生产线一览 · AI 数据驱动选题</div>
      </div>
    </div>

    <div class="px-8 py-5 max-w-[1400px] mx-auto">
      <ErrorBanner :msg="errorMsg" />

    <div class="card p-4 mb-4 flex items-center gap-4 bg-accent-soft border-accent/30">
      <div class="w-9 h-9 rounded-lg bg-surface-primary flex items-center justify-center shrink-0">
        <Sparkles :size="16" class="text-accent" />
      </div>
      <div class="flex-1 min-w-0">
        <div class="text-sm font-semibold">基于近 30 天数据生成新选题</div>
        <div class="text-xs text-text-muted mt-0.5 truncate">
          抽取 Top 视频共性维度,投喂给 LLM 反向出题 —— 一次产 5-20 条
        </div>
      </div>
      <button class="btn-ghost bg-surface-primary border border-border-subtle" @click="gotoBrainstorm">
        立即生成 <ArrowRight :size="13" />
      </button>
    </div>

    <div class="grid grid-cols-2 md:grid-cols-4 gap-3 mb-4">
      <div class="card p-4">
        <div class="text-xs text-text-muted flex items-center gap-1.5">
          <Lightbulb :size="12" /> 本周新增选题
        </div>
        <div class="text-2xl font-mono mt-1">{{ recentTopicCount }}</div>
        <div class="text-xs text-text-muted mt-2">DRAFT 总 {{ statusCounts.DRAFT }} 条</div>
      </div>

      <div class="card p-4">
        <div class="text-xs text-text-muted flex items-center gap-1.5">
          <FileText :size="12" /> 流水线进行中
        </div>
        <div class="text-2xl font-mono mt-1">{{ inFlightCount }}</div>
        <div class="text-xs text-text-muted mt-2">
          已排期 {{ statusCounts.SCHEDULED }} · 已制作 {{ statusCounts.PRODUCED }}
        </div>
      </div>

      <div class="card p-4">
        <div class="text-xs text-text-muted flex items-center gap-1.5">
          <Film :size="12" /> 本月发布视频
        </div>
        <div class="text-2xl font-mono mt-1">{{ monthlyVideoCount }}</div>
        <div class="text-xs flex items-center gap-1 mt-2"
             :class="monthlyVideoDiff == null ? 'text-text-muted'
                      : monthlyVideoDiff >= 0 ? 'text-status-done' : 'text-status-failed'">
          <ArrowUp v-if="monthlyVideoDiff != null && monthlyVideoDiff >= 0" :size="11" />
          <ArrowDown v-else-if="monthlyVideoDiff != null" :size="11" />
          <Minus v-else :size="11" />
          {{ monthlyVideoDiff != null ? `${Math.abs(monthlyVideoDiff).toFixed(1)}% vs 上月` : '上月无数据' }}
        </div>
      </div>

      <div class="card p-4">
        <div class="text-xs text-text-muted">近 30 天总曝光</div>
        <div class="text-2xl font-mono mt-1 text-accent">{{ fmtNum(totalViews30d) }}</div>
        <div class="text-xs flex items-center gap-1 mt-2"
             :class="totalViewsDiff == null ? 'text-text-muted'
                      : totalViewsDiff >= 0 ? 'text-status-done' : 'text-status-failed'">
          <ArrowUp v-if="totalViewsDiff != null && totalViewsDiff >= 0" :size="11" />
          <ArrowDown v-else-if="totalViewsDiff != null" :size="11" />
          <Minus v-else :size="11" />
          {{ totalViewsDiff != null ? `${Math.abs(totalViewsDiff).toFixed(1)}% vs 前 30 天` : '前 30 天无数据' }}
        </div>
      </div>
    </div>

    <div class="grid grid-cols-1 lg:grid-cols-[1fr_400px] gap-4">
      <div class="card p-5">
        <div class="flex items-center justify-between mb-3">
          <div>
            <h2 class="text-base font-semibold">选题状态分布</h2>
            <div class="text-xs text-text-muted mt-0.5">总计 {{ totalTopics }} 条</div>
          </div>
          <button class="chip bg-surface-tertiary text-text-secondary cursor-pointer hover:text-text-primary"
                  @click="router.push('/topics')">
            选题池 <ArrowRight :size="11" />
          </button>
        </div>
        <div class="grid grid-cols-2 sm:grid-cols-5 gap-2">
          <div v-for="b in statusBars" :key="b.key"
               class="bg-surface-secondary rounded-lg p-3 cursor-pointer hover:bg-surface-tertiary transition-colors"
               @click="router.push({ path: '/topics', query: { status: b.key } })">
            <div class="text-xs text-text-muted">{{ b.label }}</div>
            <div class="text-xl font-mono mt-1">{{ b.count }}</div>
            <div class="h-1 rounded mt-2 bg-surface-tertiary">
              <div class="h-1 rounded bg-accent" :style="{ width: `${b.pct}%`, opacity: b.opacity }" />
            </div>
          </div>
        </div>

        <h3 class="text-sm font-semibold mt-5 mb-2">最近 DRAFT 选题</h3>
        <div v-if="loading" class="text-xs text-text-muted py-4 text-center">加载中…</div>
        <div v-else-if="recentDraftTopics.length === 0" class="text-xs text-text-muted py-4 text-center">
          还没有 DRAFT 选题 —
          <a class="text-accent cursor-pointer hover:underline" @click="gotoBrainstorm">去生成</a>
        </div>
        <ul v-else class="divide-y divide-border-subtle">
          <li v-for="t in recentDraftTopics" :key="t.id" class="py-2 first:pt-0 last:pb-0">
            <router-link :to="`/topics/${t.id}`" class="block hover:text-accent">
              <div class="text-sm truncate">{{ t.title }}</div>
              <div class="text-xs text-text-muted mt-0.5 flex items-center gap-2">
                <span v-if="t.dynasty">{{ t.dynasty }}</span>
                <span v-if="t.genre">· {{ t.genre }}</span>
                <span v-if="t.potentialScore != null">· {{ t.potentialScore }} 分</span>
                <span class="ml-auto"><TimeText :value="t.createdAt" relative /></span>
              </div>
            </router-link>
          </li>
        </ul>
      </div>

      <div class="card p-5">
        <div class="flex items-center justify-between mb-3">
          <h2 class="text-base font-semibold">近期活动</h2>
          <button class="chip bg-surface-tertiary text-text-secondary cursor-pointer hover:text-text-primary"
                  @click="router.push('/dashboard')">
            数据看板 <ArrowRight :size="11" />
          </button>
        </div>
        <div v-if="loading" class="text-xs text-text-muted py-4 text-center">加载中…</div>
        <div v-else-if="activityFeed.length === 0" class="text-xs text-text-muted py-4 text-center">
          暂无活动
        </div>
        <ul v-else class="divide-y divide-border-subtle">
          <li v-for="(a, i) in activityFeed" :key="`${a.kind}-${a.id}-${i}`" class="py-2.5 first:pt-0 last:pb-0">
            <router-link :to="a.to" class="flex items-start gap-3 hover:text-accent">
              <span class="w-2 h-2 rounded-full mt-1.5 shrink-0" :class="a.color" />
              <div class="flex-1 min-w-0">
                <div class="text-sm truncate">{{ a.title }}</div>
                <div class="text-xs text-text-muted mt-0.5 flex items-center gap-2">
                  <span class="truncate">{{ a.meta }}</span>
                  <span class="ml-auto shrink-0"><TimeText :value="a.at" relative /></span>
                </div>
              </div>
            </router-link>
          </li>
        </ul>
      </div>
    </div>
    </div>
  </div>
</template>
