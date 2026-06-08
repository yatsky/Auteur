<script setup lang="ts">
// 数据看板 —— KPI(带 sparkline + 环比) + 趋势 + 平台分布 + Top 视频 + 平台×完播率对比表
// 数据源:/api/analytics/compare(单条视频明细) + /api/analytics/daily-trend(日序列,只有 views/engagementPct)
import { computed, onMounted, ref } from 'vue'
import { ArrowDown, ArrowLeft, ArrowUp, BarChart3, Minus, RefreshCw } from 'lucide-vue-next'
import MiniLineChart from '../components/MiniLineChart.vue'
import { getAnalyticsCompare, getDailyTrend, type VideoCompare, type DailyTrendPoint } from '../api/analytics'

const range = ref<'7d' | '30d' | '90d'>('30d')
const platform = ref<string>('all')

// 跟 Insights.vue 同款的平台 chip 配色 —— status palette /15 半透明,色相微差识别平台,克制不扎眼
const PLATFORM_CHIP: Record<string, string> = {
  '抖音':   'bg-status-failed/15 text-status-failed',
  '快手':   'bg-status-paused/15 text-status-paused',
  '小红书': 'bg-status-failed/15 text-status-failed',
  'B站':    'bg-status-running/15 text-status-running',
  '视频号': 'bg-status-done/15 text-status-done',
  '西瓜':   'bg-status-paused/15 text-status-paused',
}
function platformChip(p: string): string {
  return PLATFORM_CHIP[p] ?? 'bg-surface-tertiary text-text-secondary'
}
const trendMetric = ref<'views' | 'engagementPct'>('views')
const loading = ref(true)
const refreshing = ref(false)
const errMsg = ref('')
const lastLoaded = ref<Date | null>(null)

const videos = ref<VideoCompare[]>([])
const trend = ref<DailyTrendPoint[]>([])

const platforms = computed(() => {
  const set = new Set<string>(videos.value.map((v) => v.platform))
  return ['all', ...Array.from(set)]
})

// 按 range 过滤已发布视频(publishedAt 落在窗口内)
const rangeDays = computed(() => (range.value === '7d' ? 7 : range.value === '30d' ? 30 : 90))

const filtered = computed(() => {
  const cutoff = Date.now() - rangeDays.value * 86400_000
  return videos.value.filter((v) => {
    const okPlat = platform.value === 'all' || v.platform === platform.value
    const okTime = new Date(v.publishedAt).getTime() >= cutoff
    return okPlat && okTime
  })
})

// 当前 range 的 daily trend 切片(后端默认返回 30 天,这里再按 rangeDays 截尾)
const trendInRange = computed(() => trend.value.slice(-rangeDays.value))

// KPI 当前值
const kpi = computed(() => {
  const totalViews = filtered.value.reduce((s, v) => s + v.views, 0)
  const totalLikes = filtered.value.reduce((s, v) => s + v.likes, 0)
  const totalComments = filtered.value.reduce((s, v) => s + v.comments, 0)
  const engagement = totalViews > 0 ? ((totalLikes + totalComments) / totalViews) * 100 : 0
  const retVids = filtered.value.filter((v) => v.retentionPct != null)
  const avgRetention = retVids.length > 0
    ? retVids.reduce((s, v) => s + Number(v.retentionPct), 0) / retVids.length
    : 0
  return {
    totalViews,
    totalLikes,
    engagement,
    avgRetention,
    videoCount: filtered.value.length,
  }
})

// 环比 trend:按 daily-trend 拆前后两半算 views 的环比;engagement 用本期 vs 上期窗口同口径
// daily-trend 没有 likes/retention 字段,所以 totalLikes / avgRetention 显示 — 不计算环比
function diffPct(prev: number, cur: number): number | null {
  if (prev === 0) return null
  return ((cur - prev) / prev) * 100
}

const trendVs = computed(() => {
  const half = Math.floor(trendInRange.value.length / 2)
  if (half === 0) return { views: null, engagement: null }
  const a = trendInRange.value.slice(0, half)
  const b = trendInRange.value.slice(half)
  const sumA = a.reduce((s, p) => s + p.views, 0)
  const sumB = b.reduce((s, p) => s + p.views, 0)
  const engA = a.reduce((s, p) => s + Number(p.engagementPct), 0) / Math.max(a.length, 1)
  const engB = b.reduce((s, p) => s + Number(p.engagementPct), 0) / Math.max(b.length, 1)
  return {
    views: diffPct(sumA, sumB),
    engagement: diffPct(engA, engB),
  }
})

// sparkline 数据 — 只对 views / engagement 有日序列;另外两张卡用空数据
const sparkViews = computed(() => trendInRange.value.map((d) => ({ x: d.date.slice(5), y: d.views })))
const sparkEngagement = computed(() =>
  trendInRange.value.map((d) => ({ x: d.date.slice(5), y: Number(d.engagementPct) })),
)

// 平台分布(按 views 占比)
const platformDistribution = computed(() => {
  const map = new Map<string, number>()
  for (const v of filtered.value) map.set(v.platform, (map.get(v.platform) || 0) + v.views)
  const total = Array.from(map.values()).reduce((s, n) => s + n, 0)
  const colors = ['#A855F7', '#60A5FA', '#34D399', '#FBBF24', '#F87171', '#94A3B8']
  return Array.from(map.entries())
    .sort((a, b) => b[1] - a[1])
    .map(([name, views], i) => ({
      name,
      views,
      pct: total > 0 ? (views / total) * 100 : 0,
      color: colors[i % colors.length],
    }))
})

// 平台 × 关键指标 对比表(已发布数 / 总播放 / 互动率 / 平均完播率)
const platformCompare = computed(() => {
  const map = new Map<string, { count: number; views: number; likes: number; comments: number; retSum: number; retCnt: number }>()
  for (const v of filtered.value) {
    const cur = map.get(v.platform) || { count: 0, views: 0, likes: 0, comments: 0, retSum: 0, retCnt: 0 }
    cur.count++
    cur.views += v.views
    cur.likes += v.likes
    cur.comments += v.comments
    if (v.retentionPct != null) { cur.retSum += Number(v.retentionPct); cur.retCnt++ }
    map.set(v.platform, cur)
  }
  return Array.from(map.entries())
    .map(([name, s]) => ({
      name,
      count: s.count,
      views: s.views,
      engagement: s.views > 0 ? ((s.likes + s.comments) / s.views) * 100 : 0,
      retention: s.retCnt > 0 ? s.retSum / s.retCnt : null,
    }))
    .sort((a, b) => b.views - a.views)
})

const topVideos = computed(() =>
  [...filtered.value].sort((a, b) => b.views - a.views).slice(0, 5),
)

const trendData = computed(() => {
  if (trendMetric.value === 'views') return sparkViews.value
  return sparkEngagement.value
})

async function load(isRefresh = false) {
  if (isRefresh) refreshing.value = true
  else loading.value = true
  errMsg.value = ''
  try {
    const [compare, daily] = await Promise.all([getAnalyticsCompare(), getDailyTrend()])
    videos.value = compare
    trend.value = daily
    lastLoaded.value = new Date()
  } catch (e: any) {
    errMsg.value = e?.message || '加载分析数据失败'
  } finally {
    loading.value = false
    refreshing.value = false
  }
}

onMounted(() => load(false))

const subtitle = computed(() => {
  const platTxt = platform.value === 'all' ? '全网' : platform.value
  if (!lastLoaded.value) return platTxt
  const t = lastLoaded.value
  const hh = String(t.getHours()).padStart(2, '0')
  const mm = String(t.getMinutes()).padStart(2, '0')
  const ss = String(t.getSeconds()).padStart(2, '0')
  return `${platTxt} · 上次加载 ${hh}:${mm}:${ss}`
})

function fmtNum(n: number): string {
  if (n >= 100_000_000) return (n / 100_000_000).toFixed(1) + '亿'
  if (n >= 10_000) return (n / 10_000).toFixed(1) + '万'
  return n.toLocaleString()
}
</script>

<template>
  <div class="min-h-full">
    <!-- sticky 顶栏 -->
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
            <BarChart3 :size="16" class="text-accent" /> 数据看板
          </h1>
          <span v-if="kpi.videoCount" class="chip text-[11px] bg-surface-tertiary text-text-muted font-mono">
            {{ kpi.videoCount }} 条 · 近 {{ rangeDays }} 天
          </span>
          <div class="ml-auto flex items-center gap-2">
            <button
              v-for="r in (['7d', '30d', '90d'] as const)" :key="r"
              :class="['chip cursor-pointer text-xs', range === r ? 'bg-accent-soft text-accent' : 'bg-surface-tertiary text-text-secondary']"
              @click="range = r"
            >
              近 {{ r }}
            </button>
            <button class="btn-ghost text-sm" :disabled="refreshing" @click="load(true)">
              <RefreshCw :size="14" :class="refreshing ? 'animate-spin' : ''" />
              {{ refreshing ? '刷新中…' : '刷新' }}
            </button>
          </div>
        </div>
        <div class="text-xs text-text-muted">{{ subtitle }}</div>
      </div>
    </div>

    <div class="px-8 py-5 max-w-[1400px] mx-auto">
      <div v-if="loading" class="card p-12 text-center text-text-muted text-sm">加载中…</div>
      <div v-else-if="errMsg" class="card p-3 mb-4 bg-status-failed/10 border-status-failed/30 text-xs text-status-failed">
        {{ errMsg }}
      </div>

      <div v-else-if="videos.length === 0" class="card p-8 text-center">
        <div class="text-text-muted mb-3">还没有已发布视频数据</div>
        <button class="chip bg-accent-soft text-accent cursor-pointer" @click="$router.push('/published-videos')">
          去录入数据 →
        </button>
      </div>

      <template v-else>
        <!-- 平台过滤(独立行) -->
        <div class="card p-2.5 mb-4 flex items-center gap-2 flex-wrap">
        <span class="text-xs text-text-muted ml-1">平台</span>
        <button
          v-for="p in platforms" :key="p"
          :class="['chip cursor-pointer text-xs', platform === p ? 'bg-accent-soft text-accent' : 'bg-surface-tertiary text-text-secondary']"
          @click="platform = p"
        >
          {{ p === 'all' ? '全平台' : p }}
        </button>
      </div>

      <!-- KPI 4 卡:数值 + 环比 trend + sparkline -->
      <div class="grid grid-cols-2 md:grid-cols-4 gap-3 mb-4">
        <div class="card p-4">
          <div class="text-xs text-text-muted">总播放</div>
          <div class="text-2xl font-mono mt-1">{{ fmtNum(kpi.totalViews) }}</div>
          <div class="flex items-center justify-between mt-2 gap-1">
            <span class="text-xs flex items-center gap-1"
                  :class="trendVs.views == null ? 'text-text-muted'
                           : trendVs.views >= 0 ? 'text-status-done' : 'text-status-failed'">
              <ArrowUp v-if="trendVs.views != null && trendVs.views >= 0" :size="11" />
              <ArrowDown v-else-if="trendVs.views != null" :size="11" />
              <Minus v-else :size="11" />
              {{ trendVs.views != null ? Math.abs(trendVs.views).toFixed(1) + '%' : '—' }}
            </span>
            <div class="flex-1 h-7 ml-2">
              <MiniLineChart v-if="sparkViews.length" :data="sparkViews" :width="120" :height="28" stroke="#A855F7" fill="rgba(168,85,247,0.18)" compact class="!h-7" />
            </div>
          </div>
        </div>

        <div class="card p-4">
          <div class="text-xs text-text-muted">总点赞</div>
          <div class="text-2xl font-mono mt-1">{{ fmtNum(kpi.totalLikes) }}</div>
          <div class="flex items-center justify-between mt-2 gap-1">
            <span class="text-xs text-text-muted flex items-center gap-1"><Minus :size="11" /> —</span>
            <span class="text-xs text-text-muted">无日序列</span>
          </div>
        </div>

        <div class="card p-4">
          <div class="text-xs text-text-muted">互动率</div>
          <div class="text-2xl font-mono mt-1">{{ kpi.engagement.toFixed(2) }}%</div>
          <div class="flex items-center justify-between mt-2 gap-1">
            <span class="text-xs flex items-center gap-1"
                  :class="trendVs.engagement == null ? 'text-text-muted'
                           : trendVs.engagement >= 0 ? 'text-status-done' : 'text-status-failed'">
              <ArrowUp v-if="trendVs.engagement != null && trendVs.engagement >= 0" :size="11" />
              <ArrowDown v-else-if="trendVs.engagement != null" :size="11" />
              <Minus v-else :size="11" />
              {{ trendVs.engagement != null ? Math.abs(trendVs.engagement).toFixed(1) + '%' : '—' }}
            </span>
            <div class="flex-1 h-7 ml-2">
              <MiniLineChart v-if="sparkEngagement.length" :data="sparkEngagement" :width="120" :height="28" stroke="#34D399" fill="rgba(52,211,153,0.18)" compact class="!h-7" />
            </div>
          </div>
        </div>

        <div class="card p-4">
          <div class="text-xs text-text-muted">平均完播率</div>
          <div class="text-2xl font-mono mt-1 text-accent">{{ kpi.avgRetention.toFixed(1) }}%</div>
          <div class="flex items-center justify-between mt-2 gap-1">
            <span class="text-xs text-text-muted">{{ kpi.videoCount }} 条视频</span>
            <span class="text-xs text-text-muted">无日序列</span>
          </div>
        </div>
      </div>

      <!-- 趋势图 + 平台分布 -->
      <div class="grid grid-cols-1 lg:grid-cols-[1fr_360px] gap-4 mb-4">
        <div class="card p-5">
          <div class="flex items-center gap-2 mb-3 flex-wrap">
            <BarChart3 :size="16" class="text-accent" />
            <h2 class="text-base font-semibold">近 {{ rangeDays }} 天趋势</h2>
            <div class="ml-auto flex items-center gap-1">
              <button
                :class="['chip cursor-pointer text-xs', trendMetric === 'views' ? 'bg-accent-soft text-accent' : 'bg-surface-tertiary text-text-secondary']"
                @click="trendMetric = 'views'"
              >播放</button>
              <button
                :class="['chip cursor-pointer text-xs', trendMetric === 'engagementPct' ? 'bg-accent-soft text-accent' : 'bg-surface-tertiary text-text-secondary']"
                @click="trendMetric = 'engagementPct'"
              >互动率</button>
            </div>
          </div>
          <div v-if="trendInRange.length === 0" class="text-sm text-text-muted py-12 text-center">暂无近 {{ rangeDays }} 天数据</div>
          <MiniLineChart v-else
            :data="trendData" :height="220"
            :stroke="trendMetric === 'views' ? '#A855F7' : '#34D399'"
            :fill="trendMetric === 'views' ? 'rgba(168,85,247,0.15)' : 'rgba(52,211,153,0.15)'" />
        </div>

        <div class="card p-5">
          <h2 class="text-base font-semibold mb-3">平台分布</h2>
          <ul v-if="platformDistribution.length" class="space-y-2.5">
            <li v-for="p in platformDistribution" :key="p.name">
              <div class="flex items-center justify-between mb-1 text-sm">
                <span class="chip text-[10px] font-semibold" :class="platformChip(p.name)">
                  {{ p.name }}
                </span>
                <span class="text-xs text-text-muted font-mono">{{ p.pct.toFixed(1) }}%</span>
              </div>
              <div class="bg-surface-tertiary h-2 rounded">
                <div class="h-2 rounded" :style="{ width: `${p.pct}%`, background: p.color }" />
              </div>
              <div class="text-xs text-text-muted mt-0.5 font-mono">{{ p.views.toLocaleString() }} 播放</div>
            </li>
          </ul>
          <div v-else class="text-sm text-text-muted py-6 text-center">无数据</div>
        </div>
      </div>

      <!-- Top 5 视频 + 平台对比表 -->
      <div class="grid grid-cols-1 lg:grid-cols-[1fr_440px] gap-4">
        <div class="card p-5">
          <h2 class="text-base font-semibold mb-3">Top 5 视频</h2>
          <table class="w-full text-sm">
            <thead class="text-xs uppercase text-text-muted">
              <tr>
                <th class="text-left py-2 font-medium">标题</th>
                <th class="text-left py-2 font-medium w-[80px]">平台</th>
                <th class="text-right py-2 font-medium w-[100px]">播放</th>
                <th class="text-right py-2 font-medium w-[80px]">互动率</th>
                <th class="text-right py-2 font-medium w-[80px]">完播率</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="v in topVideos" :key="v.id"
                class="border-t border-border-subtle hover:bg-surface-tertiary/30 cursor-pointer"
                @click="$router.push(`/review/${v.id}`)"
              >
                <td class="py-2 pr-2 truncate max-w-[400px]">{{ v.title }}</td>
                <td class="py-2">
                  <span class="chip text-[10px] font-semibold" :class="platformChip(v.platform)">
                    {{ v.platform }}
                  </span>
                </td>
                <td class="py-2 text-right font-mono text-text-secondary">{{ fmtNum(v.views) }}</td>
                <td class="py-2 text-right font-mono">{{ v.engagementPct.toFixed(2) }}%</td>
                <td class="py-2 text-right font-mono text-accent">
                  {{ v.retentionPct != null ? Number(v.retentionPct).toFixed(1) + '%' : '—' }}
                </td>
              </tr>
              <tr v-if="topVideos.length === 0">
                <td colspan="5" class="py-4 text-center text-text-muted text-sm">无数据</td>
              </tr>
            </tbody>
          </table>
        </div>

        <div class="card p-5">
          <h2 class="text-base font-semibold mb-3">平台 · 完播率对比</h2>
          <table v-if="platformCompare.length" class="w-full text-sm">
            <thead class="text-xs uppercase text-text-muted">
              <tr>
                <th class="text-left py-2 font-medium">平台</th>
                <th class="text-right py-2 font-medium">条数</th>
                <th class="text-right py-2 font-medium">播放</th>
                <th class="text-right py-2 font-medium">完播</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="p in platformCompare" :key="p.name" class="border-t border-border-subtle">
                <td class="py-2">
                  <span class="chip text-[10px] font-semibold" :class="platformChip(p.name)">
                    {{ p.name }}
                  </span>
                </td>
                <td class="py-2 text-right font-mono text-text-secondary">{{ p.count }}</td>
                <td class="py-2 text-right font-mono text-text-secondary">{{ fmtNum(p.views) }}</td>
                <td class="py-2 text-right font-mono"
                    :class="p.retention == null ? 'text-text-muted'
                             : p.retention >= 50 ? 'text-status-done'
                             : p.retention >= 30 ? 'text-status-paused'
                             : 'text-status-failed'">
                  {{ p.retention != null ? p.retention.toFixed(1) + '%' : '—' }}
                </td>
              </tr>
            </tbody>
          </table>
          <div v-else class="text-sm text-text-muted py-6 text-center">无数据</div>
        </div>
      </div>
    </template>
    </div>
  </div>
</template>
