<script setup lang="ts">
// 跨视频对比 —— 多选视频 + 维度选 + 横向柱状对比 + 钩子模板排行。吃 /api/analytics/compare
import { computed, onMounted, ref } from 'vue'
import { ArrowLeft, GitCompare, Trophy } from 'lucide-vue-next'
import { getAnalyticsCompare, type VideoCompare } from '../api/analytics'

type Metric = 'views' | 'retentionPct' | 'hookCtr' | 'cost' | 'engagement'
              | 'drop2sPct' | 'play5sPct' | 'avgPlayRatioPct' | 'favoriteRatePct'
              | 'avgPlaySeconds' | 'dislikeRatePct'

const METRICS: { key: Metric; label: string; format: (v: number) => string; color: string }[] = [
  { key: 'views', label: '播放', format: (v) => v.toLocaleString(), color: '#A855F7' },
  { key: 'retentionPct', label: '完播率 (%)', format: (v) => v.toFixed(1) + '%', color: '#34D399' },
  { key: 'hookCtr', label: '钩子 CTR (%)', format: (v) => v.toFixed(1) + '%', color: '#FBBF24' },
  { key: 'engagement', label: '互动率 (%)', format: (v) => v.toFixed(2) + '%', color: '#60A5FA' },
  { key: 'cost', label: '成本 (¥)', format: (v) => '¥' + v.toFixed(2), color: '#F87171' },
  { key: 'avgPlaySeconds', label: '平均播放时长 (s)', format: (v) => v.toFixed(1) + 's', color: '#22D3EE' },
  { key: 'drop2sPct', label: '2s 跳出率 (%)', format: (v) => v.toFixed(2) + '%', color: '#F472B6' },
  { key: 'play5sPct', label: '5s 完播率 (%)', format: (v) => v.toFixed(2) + '%', color: '#A3E635' },
  { key: 'avgPlayRatioPct', label: '平均播放占比 (%)', format: (v) => v.toFixed(2) + '%', color: '#818CF8' },
  { key: 'favoriteRatePct', label: '收藏率 (%)', format: (v) => v.toFixed(2) + '%', color: '#FB923C' },
  { key: 'dislikeRatePct', label: '不感兴趣率 (%)', format: (v) => v.toFixed(2) + '%', color: '#EF4444' },
]

const videos = ref<VideoCompare[]>([])
const loading = ref(true)
const selectedIds = ref<Set<number>>(new Set())
const metric = ref<Metric>('views')

function toggle(id: number) {
  if (selectedIds.value.has(id)) selectedIds.value.delete(id)
  else selectedIds.value.add(id)
  selectedIds.value = new Set(selectedIds.value)
}

function getValue(v: VideoCompare, m: Metric): number {
  if (m === 'engagement') return v.engagementPct
  if (m === 'retentionPct') return v.retentionPct ?? 0
  if (m === 'hookCtr') return v.hookCtr ?? 0
  if (m === 'cost') return v.costYuan ?? 0
  if (m === 'avgPlaySeconds') return v.avgPlaySeconds ?? 0
  if (m === 'drop2sPct') return v.drop2sPct ?? 0
  if (m === 'play5sPct') return v.play5sPct ?? 0
  if (m === 'avgPlayRatioPct') return v.avgPlayRatioPct ?? 0
  if (m === 'favoriteRatePct') return v.favoriteRatePct ?? 0
  if (m === 'dislikeRatePct') return v.dislikeRatePct ?? 0
  return v[m] as number
}

const compared = computed(() =>
  videos.value
    .filter((v) => selectedIds.value.has(v.id))
    .map((v) => ({ ...v, value: getValue(v, metric.value) })),
)

const maxValue = computed(() => Math.max(...compared.value.map((v) => v.value), 1))

const currentMetric = computed(() => METRICS.find((m) => m.key === metric.value)!)

const hookRanking = computed(() => {
  const map = new Map<string, { count: number; views: number; retention: number; retCount: number }>()
  for (const v of videos.value) {
    if (!v.hookTemplate) continue
    const cur = map.get(v.hookTemplate) || { count: 0, views: 0, retention: 0, retCount: 0 }
    cur.count += 1
    cur.views += v.views
    if (v.retentionPct != null) {
      cur.retention += v.retentionPct
      cur.retCount += 1
    }
    map.set(v.hookTemplate, cur)
  }
  return Array.from(map.entries())
    .map(([name, s]) => ({
      name,
      count: s.count,
      avgViews: Math.round(s.views / s.count),
      avgRetention: s.retCount > 0 ? s.retention / s.retCount : 0,
    }))
    .sort((a, b) => b.avgViews - a.avgViews)
})

onMounted(async () => {
  try {
    videos.value = await getAnalyticsCompare()
    // 默认选前 4 条作为示例
    selectedIds.value = new Set(videos.value.slice(0, 4).map((v) => v.id))
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="min-h-full">
    <!-- sticky 顶栏 -->
    <div class="sticky top-0 z-10 bg-surface-primary border-b border-border-subtle">
      <div class="px-8 py-3 max-w-[1400px] mx-auto flex flex-col gap-1">
        <div class="flex items-center gap-4 flex-wrap">
          <button
            class="flex items-center gap-1.5 text-sm text-text-muted hover:text-text-primary"
            @click="$router.push('/dashboard')"
          >
            <ArrowLeft :size="14" /> 返回数据看板
          </button>
          <h1 class="text-lg font-semibold flex items-center gap-2">
            <GitCompare :size="16" class="text-accent" /> 跨视频对比
          </h1>
          <span class="chip text-[11px] bg-surface-tertiary text-text-muted">
            已选 {{ selectedIds.size }} / {{ videos.length }}
          </span>
        </div>
        <div class="text-xs text-text-muted">选若干视频横向对比 + 钩子模板排行</div>
      </div>
    </div>

    <div class="px-8 py-5 max-w-[1400px] mx-auto">
      <div v-if="loading" class="card p-6 text-center text-text-muted">加载中…</div>

      <div v-else-if="videos.length === 0" class="card p-8 text-center">
        <div class="text-text-muted mb-3">还没有已发布视频</div>
        <button class="chip bg-accent-soft text-accent cursor-pointer" @click="$router.push('/published-videos')">
          去录入数据 →
        </button>
      </div>

      <template v-else>
        <div class="card p-4 mb-4">
          <div class="text-xs text-text-muted mb-2">选要对比的视频(已选 {{ selectedIds.size }})</div>
          <div class="flex flex-wrap gap-2">
            <button
              v-for="v in videos" :key="v.id"
              :class="['chip cursor-pointer text-xs max-w-[260px] truncate',
                       selectedIds.has(v.id) ? 'bg-accent-soft text-accent border border-accent/40' : 'bg-surface-tertiary text-text-secondary']"
              @click="toggle(v.id)"
            >
              <span class="font-mono mr-1">{{ v.id }}</span> {{ v.projectName || v.title }}
            </button>
          </div>
        </div>

        <div class="card p-3 mb-4 flex items-center gap-2 flex-wrap">
          <span class="text-xs text-text-muted">维度</span>
          <button
            v-for="m in METRICS" :key="m.key"
            :class="['chip cursor-pointer text-xs', metric === m.key ? 'bg-accent-soft text-accent' : 'bg-surface-tertiary text-text-secondary']"
            @click="metric = m.key"
          >
            {{ m.label }}
          </button>
        </div>

        <div class="card p-5 mb-4">
          <h2 class="text-base font-semibold mb-3 flex items-center gap-2">
            <GitCompare :size="16" class="text-accent" /> {{ currentMetric.label }} 对比
          </h2>
          <div v-if="compared.length === 0" class="text-sm text-text-muted py-6 text-center">
            请先勾选视频
          </div>
          <ul v-else class="space-y-3">
            <li v-for="v in compared" :key="v.id" class="text-sm">
              <div class="flex items-center justify-between mb-1">
                <span class="truncate flex-1 mr-3">
                  <span class="font-mono text-text-muted text-xs mr-2">{{ v.id }}</span>
                  {{ v.projectName || v.title }}
                </span>
                <span class="font-mono text-text-secondary shrink-0">
                  {{ currentMetric.format(v.value) }}
                </span>
              </div>
              <div class="bg-surface-tertiary h-2.5 rounded">
                <div
                  class="h-2.5 rounded transition-all"
                  :style="{ width: `${(v.value / maxValue) * 100}%`, background: currentMetric.color }"
                />
              </div>
            </li>
          </ul>
        </div>

        <div class="card p-5">
          <h2 class="text-base font-semibold mb-3 flex items-center gap-2">
            <Trophy :size="16" class="text-accent" /> 钩子模板排行(按平均播放)
          </h2>
          <div v-if="hookRanking.length === 0" class="text-sm text-text-muted py-4 text-center">
            暂无钩子模板数据 — 录入视频时填写"钩子模板"字段后会出现在这里
          </div>
          <table v-else class="w-full text-sm">
            <thead class="text-xs uppercase text-text-muted">
              <tr>
                <th class="text-left py-2 font-medium w-[40px]">#</th>
                <th class="text-left py-2 font-medium">模板</th>
                <th class="text-right py-2 font-medium w-[80px]">视频数</th>
                <th class="text-right py-2 font-medium w-[120px]">平均播放</th>
                <th class="text-right py-2 font-medium w-[100px]">平均完播率</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(h, i) in hookRanking" :key="h.name" class="border-t border-border-subtle">
                <td class="py-2 font-mono text-text-muted">{{ i + 1 }}</td>
                <td class="py-2">{{ h.name }}</td>
                <td class="py-2 text-right font-mono">{{ h.count }}</td>
                <td class="py-2 text-right font-mono text-text-secondary">{{ h.avgViews.toLocaleString() }}</td>
                <td class="py-2 text-right font-mono text-accent">{{ h.avgRetention.toFixed(1) }}%</td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>
    </div>
  </div>
</template>
