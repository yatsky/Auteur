<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ArrowLeft, RefreshCw, Sparkles, TrendingDown, TrendingUp, Zap } from 'lucide-vue-next'
import {
  getDimensionWeights,
  getTopBottom,
  recomputePotentialScores,
  DIMENSION_LABELS,
  DIMENSION_COEFFICIENTS,
  type DimensionWeightReport,
  type TopBottomReport,
  type VideoFeature,
} from '../api/insights'

const PLATFORMS = ['', '抖音', 'B站', '视频号', '小红书', '西瓜'] as const
const WINDOWS = [7, 30, 90] as const

const platform = ref<string>('')
const days = ref<number>(30)
const weightReport = ref<DimensionWeightReport | null>(null)
const tbReport = ref<TopBottomReport | null>(null)
const loading = ref(false)
const errMsg = ref('')
const recomputing = ref(false)
const lastAction = ref<string>('')

const dimensionOrder = ['dynasty', 'genre', 'hookType', 'emotion', 'durationMinutes']

async function loadAll() {
  loading.value = true
  errMsg.value = ''
  try {
    const [w, tb] = await Promise.all([
      getDimensionWeights(platform.value || undefined, days.value, 5),
      getTopBottom(platform.value || undefined, days.value, 5),
    ])
    weightReport.value = w
    tbReport.value = tb
  } catch (e: unknown) {
    errMsg.value = (e as { message?: string })?.message || '加载失败'
  } finally {
    loading.value = false
  }
}

function maxWeight(key: string): number {
  const list = weightReport.value?.weights[key] || []
  let m = 0
  for (const v of list) {
    if (v.avgRetention != null && Number(v.avgRetention) > m) m = Number(v.avgRetention)
  }
  return m || 1
}

function pct(v: number | null | string): string {
  if (v == null) return '—'
  return Number(v).toFixed(2)
}

// 标题里常带一长串 #标签,展示时拆开:正文一行,标签做 muted 副标
function splitTitle(raw: string): { main: string; tags: string[] } {
  if (!raw) return { main: '', tags: [] }
  const idx = raw.indexOf('#')
  if (idx < 0) return { main: raw.trim(), tags: [] }
  const main = raw.slice(0, idx).trim()
  const tags = raw.slice(idx).split(/\s*#/).map((t) => t.trim()).filter(Boolean)
  return { main: main || raw.trim(), tags }
}

// 平台 chip 配色 —— 都用系统 status palette 的 /15 半透明,克制不刺眼,但靠色相微差让平台一眼分得出
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

// Top 列表多视角切换。每个视角用不同字段排序+不同的"展示数值".
type TopMetric = 'retention' | 'likeRate' | 'shareRate' | 'subscribe' | 'coverCtr'
interface TopMetricDef {
  key: TopMetric
  label: string         // tab 文字
  unit: string          // 数字后缀(% / 个 ...)
  /** 从 VideoFeature 取该指标原值,null 表示未抓到 */
  pick: (v: VideoFeature) => number | null
  /** 展示值(已经按单位转换过,直接拼 unit 即可) */
  display: (v: VideoFeature) => string
  /** 进度条相对宽度 0~100,用于横向直观对比 */
  bar: (v: VideoFeature) => number
}
const TOP_METRICS: TopMetricDef[] = [
  {
    key: 'retention', label: '完播率', unit: '%',
    pick: (v) => v.retentionPct,
    display: (v) => v.retentionPct == null ? '—' : Number(v.retentionPct).toFixed(2),
    bar: (v) => Math.min(100, Number(v.retentionPct) || 0),
  },
  {
    key: 'likeRate', label: '点赞率', unit: '%',
    pick: (v) => v.likeRate,
    display: (v) => v.likeRate == null ? '—' : (Number(v.likeRate) * 100).toFixed(2),
    // 点赞率 1~5% 已经很高,bar 用 ×20 拉伸到 0~100 视觉上能看出差异
    bar: (v) => Math.min(100, (Number(v.likeRate) || 0) * 100 * 20),
  },
  {
    key: 'shareRate', label: '分享率', unit: '%',
    pick: (v) => v.shareRate,
    display: (v) => v.shareRate == null ? '—' : (Number(v.shareRate) * 100).toFixed(2),
    bar: (v) => Math.min(100, (Number(v.shareRate) || 0) * 100 * 50),
  },
  {
    key: 'subscribe', label: '涨粉', unit: '',
    pick: (v) => v.subscribeCount,
    display: (v) => v.subscribeCount == null ? '—' : `+${v.subscribeCount}`,
    // 用本批最大涨粉数做相对刻度;后面 computed 里动态算
    bar: () => 0,  // 占位,真正的 bar 宽度在模板里基于 maxSubscribe 算
  },
  {
    key: 'coverCtr', label: '封面 CTR', unit: '%',
    pick: (v) => v.coverCtr,
    display: (v) => v.coverCtr == null ? '—' : Number(v.coverCtr).toFixed(2),
    bar: (v) => Math.min(100, Number(v.coverCtr) || 0),
  },
]
const topMetric = ref<TopMetric>('retention')
const currentTopMetricDef = computed(() => TOP_METRICS.find((m) => m.key === topMetric.value)!)
const currentTopList = computed<VideoFeature[]>(() => {
  if (!tbReport.value) return []
  switch (topMetric.value) {
    case 'retention': return tbReport.value.top
    case 'likeRate':  return tbReport.value.topByLikeRate ?? []
    case 'shareRate': return tbReport.value.topByShareRate ?? []
    case 'subscribe': return tbReport.value.topBySubscribe ?? []
    case 'coverCtr':  return tbReport.value.topByCoverCtr ?? []
  }
})
// 涨粉视角下用本批最大值做进度条相对刻度
const maxSubscribeInList = computed(() => {
  const list = currentTopList.value
  let m = 0
  for (const v of list) {
    const s = v.subscribeCount ?? 0
    if (s > m) m = s
  }
  return m || 1
})
function metricBarWidth(v: VideoFeature): number {
  if (topMetric.value === 'subscribe') {
    return Math.min(100, ((v.subscribeCount ?? 0) / maxSubscribeInList.value) * 100)
  }
  return currentTopMetricDef.value.bar(v)
}

function dimSummary(dims: Record<string, string>): string {
  const parts: string[] = []
  for (const k of dimensionOrder) {
    if (dims[k]) parts.push(`${DIMENSION_LABELS[k]}=${dims[k]}`)
  }
  return parts.join(' · ')
}

const commonalityTopChips = computed(() => {
  if (!tbReport.value) return [] as { label: string; value: string }[]
  return Object.entries(tbReport.value.topCommonality).map(([k, v]) => ({ label: DIMENSION_LABELS[k] || k, value: v }))
})

const commonalityBottomChips = computed(() => {
  if (!tbReport.value) return [] as { label: string; value: string }[]
  return Object.entries(tbReport.value.bottomCommonality).map(([k, v]) => ({ label: DIMENSION_LABELS[k] || k, value: v }))
})

async function onRecompute() {
  recomputing.value = true
  try {
    const r = await recomputePotentialScores(platform.value || undefined, days.value)
    lastAction.value = `已用近 ${days.value} 天数据重算 DRAFT 选题潜力分:更新 ${r.updated} 条`
  } catch (e: unknown) {
    lastAction.value = `重算失败:${(e as { message?: string })?.message || '未知错误'}`
  } finally {
    recomputing.value = false
  }
}

onMounted(loadAll)
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
            <Sparkles :size="16" class="text-accent" /> 数据洞察 · 反向选题
          </h1>
          <span v-if="weightReport" class="chip text-[11px] bg-surface-tertiary text-text-muted font-mono">
            {{ weightReport.totalSample }} 条样本 · 近 {{ days }} 天
          </span>
          <button class="ml-auto btn-primary text-sm" :disabled="recomputing" @click="onRecompute">
            <Zap :size="14" /> {{ recomputing ? '重算中…' : '重算 DRAFT 潜力分' }}
          </button>
        </div>
        <div class="text-xs text-text-muted">把已发布数据反推回选题侧 · 维度权重 / Top-Bottom 共性</div>
      </div>
    </div>

    <div class="px-8 py-5 max-w-[1400px] mx-auto">
      <div class="card p-3 mb-4 flex flex-wrap items-center gap-2">
        <span class="text-xs text-text-muted">平台</span>
        <button v-for="p in PLATFORMS" :key="p || 'all'"
                :class="['chip cursor-pointer text-xs', platform === p ? 'bg-accent-soft text-accent' : 'bg-surface-tertiary text-text-secondary']"
                @click="platform = p; loadAll()">
          {{ p || '全部' }}
        </button>
        <span class="text-xs text-text-muted ml-3">窗口</span>
        <button v-for="d in WINDOWS" :key="d"
                :class="['chip cursor-pointer text-xs', days === d ? 'bg-accent-soft text-accent' : 'bg-surface-tertiary text-text-secondary']"
                @click="days = d; loadAll()">
          {{ d }} 天
        </button>
        <button class="ml-auto chip cursor-pointer bg-surface-tertiary text-text-secondary text-xs" @click="loadAll">
          <RefreshCw :size="12" /> 刷新
        </button>
      </div>

      <div v-if="lastAction" class="card p-2.5 mb-4 text-xs text-status-done bg-status-done/10 border-status-done/30">
        {{ lastAction }}
      </div>
      <div v-if="errMsg" class="card p-2.5 mb-4 text-xs text-status-failed bg-status-failed/10 border-status-failed/30">
        ⚠️ {{ errMsg }}
      </div>

      <div v-if="weightReport" class="grid grid-cols-2 md:grid-cols-3 gap-3 mb-4 text-sm">
        <div class="card p-4">
          <div class="text-xs text-text-muted mb-1">样本视频</div>
          <div class="text-2xl font-mono font-semibold">{{ weightReport.totalSample }}</div>
        </div>
        <div class="card p-4">
          <div class="text-xs text-text-muted mb-1">全局完播率</div>
          <div class="text-2xl font-mono font-semibold text-accent">{{ pct(weightReport.globalAvgRetention) }}%</div>
        </div>
        <div class="card p-4">
          <div class="text-xs text-text-muted mb-1">最低样本阈值</div>
          <div class="text-2xl font-mono font-semibold">{{ weightReport.minSamples }}</div>
          <div class="text-[10px] text-text-muted mt-1">少于此条数的值标记为不可信</div>
        </div>
      </div>

      <section class="card p-5 mb-4">
        <h2 class="text-base font-semibold mb-3 flex items-center gap-2">
          <Sparkles :size="16" class="text-accent" /> 维度权重 · 实测完播率
        </h2>
        <p class="text-xs text-text-muted mb-3">
          每行一个维度,横条长度按完播率归一(同维度内最高 = 100%)。灰底/带"?"表示样本 &lt; {{ weightReport?.minSamples ?? 5 }},估值不可信会回退全局均值。
          系数对应潜力分公式:dynasty×0.20 + genre×0.30 + hookType×0.25 + emotion×0.15 + duration×0.10。
        </p>
        <div class="space-y-4">
          <div v-for="key in dimensionOrder" :key="key">
            <div class="flex items-center justify-between mb-1.5">
              <div class="text-sm font-medium">{{ DIMENSION_LABELS[key] }}
                <span class="text-xs text-text-muted ml-1">系数 {{ DIMENSION_COEFFICIENTS[key] }}</span>
              </div>
              <div class="text-xs text-text-muted">{{ (weightReport?.weights[key] || []).length }} 个值</div>
            </div>
            <div v-if="!(weightReport?.weights[key] || []).length" class="text-xs text-text-muted italic">无样本</div>
            <div v-else class="space-y-1">
              <div v-for="v in weightReport?.weights[key]" :key="v.value"
                   class="flex items-center gap-2 text-xs">
                <div class="w-20 truncate" :class="!v.credible ? 'text-text-muted' : 'text-text-primary'">
                  {{ v.value }}
                </div>
                <div class="flex-1 h-3.5 bg-surface-tertiary rounded overflow-hidden relative">
                  <div class="h-full transition-all"
                       :class="v.credible ? 'bg-accent' : 'bg-text-muted/40'"
                       :style="{ width: ((Number(v.avgRetention) || 0) / maxWeight(key) * 100) + '%' }"></div>
                  <div class="absolute inset-0 flex items-center justify-end pr-1.5 text-[10px] font-mono"
                       :class="v.credible ? 'text-text-primary' : 'text-text-muted'">
                    {{ pct(v.avgRetention) }}%
                  </div>
                </div>
                <div class="w-14 text-right text-text-muted">n={{ v.count }}{{ v.credible ? '' : ' ⚠' }}</div>
              </div>
            </div>
          </div>
        </div>
      </section>

      <div class="grid grid-cols-1 lg:grid-cols-2 gap-4">
        <section class="card p-5">
          <h2 class="text-base font-semibold mb-2 flex items-center gap-2">
            <TrendingUp :size="16" class="text-status-done" /> Top {{ tbReport?.n || 5 }} · 爆款基因
          </h2>
          <div class="flex items-center gap-1 mb-3 flex-wrap">
            <button v-for="m in TOP_METRICS" :key="m.key"
                    class="chip cursor-pointer text-[11px] transition-colors"
                    :class="topMetric === m.key
                      ? 'bg-status-done/15 text-status-done font-semibold'
                      : 'bg-surface-tertiary text-text-muted hover:text-text-primary'"
                    @click="topMetric = m.key">
              {{ m.label }}
            </button>
          </div>
          <div v-if="topMetric === 'retention' && commonalityTopChips.length" class="flex flex-wrap gap-1.5 mb-3">
            <span v-for="c in commonalityTopChips" :key="c.label"
                  class="chip text-xs bg-status-done/15 text-status-done">
              {{ c.label }}: {{ c.value }}
            </span>
          </div>
          <ol class="flex flex-col">
            <li v-for="(v, idx) in currentTopList" :key="v.id"
                class="flex items-start gap-3 py-2.5 border-b border-border-subtle/60 last:border-0">
              <span class="font-mono text-xs text-text-muted w-5 text-right shrink-0 mt-0.5">{{ idx + 1 }}</span>
              <div class="flex-1 min-w-0">
                <div class="text-sm text-text-primary leading-snug truncate">{{ splitTitle(v.title).main }}</div>
                <div class="flex items-center gap-2 mt-1.5 text-[11px] text-text-muted flex-wrap">
                  <span class="chip text-[10px] font-semibold" :class="platformChip(v.platform)">
                    {{ v.platform }}
                  </span>
                  <span v-if="splitTitle(v.title).tags.length" class="truncate max-w-[260px]">
                    #{{ splitTitle(v.title).tags.slice(0, 4).join(' #') }}
                  </span>
                  <span v-if="dimSummary(v.dimensions)" class="truncate">· {{ dimSummary(v.dimensions) }}</span>
                </div>
              </div>
              <div class="flex items-center gap-2 shrink-0 w-[140px]">
                <div class="flex-1 h-1.5 rounded-full bg-surface-tertiary overflow-hidden">
                  <div class="h-full rounded-full bg-status-done/70"
                       :style="{ width: metricBarWidth(v) + '%' }" />
                </div>
                <span class="text-xs font-mono tabular-nums text-status-done w-14 text-right">
                  {{ currentTopMetricDef.display(v) }}{{ currentTopMetricDef.unit }}
                </span>
              </div>
            </li>
            <li v-if="!currentTopList.length" class="text-xs text-text-muted italic py-3">
              暂无数据{{ topMetric !== 'retention' ? '(该指标需抖音插件抓取后才有)' : '' }}
            </li>
          </ol>
        </section>

        <section class="card p-5">
          <h2 class="text-base font-semibold mb-2 flex items-center gap-2">
            <TrendingDown :size="16" class="text-status-failed" /> Bottom {{ tbReport?.n || 5 }} · 失败模式
          </h2>
          <div v-if="commonalityBottomChips.length" class="flex flex-wrap gap-1.5 mb-3">
            <span v-for="c in commonalityBottomChips" :key="c.label"
                  class="chip text-xs bg-status-failed/15 text-status-failed">
              {{ c.label }}: {{ c.value }}
            </span>
          </div>
          <ol class="flex flex-col">
            <li v-for="(v, idx) in (tbReport?.bottom || [])" :key="v.id"
                class="flex items-start gap-3 py-2.5 border-b border-border-subtle/60 last:border-0">
              <span class="font-mono text-xs text-text-muted w-5 text-right shrink-0 mt-0.5">{{ idx + 1 }}</span>
              <div class="flex-1 min-w-0">
                <div class="text-sm text-text-primary leading-snug truncate">{{ splitTitle(v.title).main }}</div>
                <div class="flex items-center gap-2 mt-1.5 text-[11px] text-text-muted flex-wrap">
                  <span class="chip text-[10px] font-semibold" :class="platformChip(v.platform)">
                    {{ v.platform }}
                  </span>
                  <span v-if="splitTitle(v.title).tags.length" class="truncate max-w-[260px]">
                    #{{ splitTitle(v.title).tags.slice(0, 4).join(' #') }}
                  </span>
                  <span v-if="dimSummary(v.dimensions)" class="truncate">· {{ dimSummary(v.dimensions) }}</span>
                </div>
              </div>
              <div class="flex items-center gap-2 shrink-0 w-[140px]">
                <div class="flex-1 h-1.5 rounded-full bg-surface-tertiary overflow-hidden">
                  <div class="h-full rounded-full bg-status-failed/70"
                       :style="{ width: Math.min(100, Number(v.retentionPct) || 0) + '%' }" />
                </div>
                <span class="text-xs font-mono tabular-nums text-status-failed w-12 text-right">{{ pct(v.retentionPct) }}%</span>
              </div>
            </li>
            <li v-if="!(tbReport?.bottom || []).length" class="text-xs text-text-muted italic py-3">暂无数据</li>
          </ol>
        </section>
      </div>
    </div>
  </div>
</template>
