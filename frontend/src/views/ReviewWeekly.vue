<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { ArrowLeft, ArrowRight, Save, Sparkles } from 'lucide-vue-next'
import { useRouter } from 'vue-router'
import { getAnalyticsCompare, type VideoCompare } from '../api/analytics'
import {
  getTopBottom,
  generateWeeklyReview,
  DIMENSION_LABELS,
  type TopBottomReport,
} from '../api/insights'
import { getWeeklyReview, saveWeeklyReview } from '../api/reviews'

interface WeeklyReview {
  weekKey: string
  highlights: string
  lessons: string
  experiments: string
  nextWeek: string
}

function thisWeekKey(): string {
  const d = new Date()
  // ISO 周近似:用年份 + 月份的第几周
  const year = d.getFullYear()
  const start = new Date(year, 0, 1)
  const week = Math.ceil(((d.getTime() - start.getTime()) / 86400000 + start.getDay() + 1) / 7)
  return `${year}-W${String(week).padStart(2, '0')}`
}

function emptyReview(weekKey: string): WeeklyReview {
  return { weekKey, highlights: '', lessons: '', experiments: '', nextWeek: '' }
}

const week = ref(thisWeekKey())
const review = ref<WeeklyReview>(emptyReview(week.value))
const dirty = ref(false)
const saving = ref(false)
const savedAt = ref<Date | null>(null)
const allVideos = ref<VideoCompare[]>([])
const loading = ref(true)
const aiLoading = ref(false)
const insights = ref<TopBottomReport | null>(null)
const router = useRouter()

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
function splitTitle(raw: string): { main: string; tags: string[] } {
  if (!raw) return { main: '', tags: [] }
  const idx = raw.indexOf('#')
  if (idx < 0) return { main: raw.trim(), tags: [] }
  const main = raw.slice(0, idx).trim()
  const tags = raw.slice(idx).split(/\s*#/).map((t) => t.trim()).filter(Boolean)
  return { main: main || raw.trim(), tags }
}

const topCommonText = computed(() => {
  if (!insights.value) return ''
  return Object.entries(insights.value.topCommonality)
    .map(([k, v]) => `${DIMENSION_LABELS[k] || k}=${v}`).join(' · ')
})
const bottomCommonText = computed(() => {
  if (!insights.value) return ''
  return Object.entries(insights.value.bottomCommonality)
    .map(([k, v]) => `${DIMENSION_LABELS[k] || k}=${v}`).join(' · ')
})

function gotoDataDrivenBrainstorm() {
  router.push({ path: '/topics', query: { brainstorm: 'data' } })
}

async function aiGenerate() {
  // 已有内容时确认覆盖,避免误删用户已写的草稿
  const hasExisting = review.value.highlights || review.value.lessons
    || review.value.experiments || review.value.nextWeek
  if (hasExisting && !confirm('当前 4 段已有内容,生成会全部覆盖,确定?')) return
  aiLoading.value = true
  try {
    const r = await generateWeeklyReview(undefined, 7)
    review.value = {
      ...review.value,
      highlights: r.highlights,
      lessons: r.lessons,
      experiments: r.experiments,
      nextWeek: r.nextWeek,
    }
    // 生成成功(非兜底)就顺手落库,防用户关页面丢草稿;兜底文案不入库免污染。
    // 等深 watch 把 dirty 置 true 后再 save,save 内部会把 dirty 重置回 false。
    if (!r.fallback) {
      await nextTick()
      await save()
    }
  } catch (e) {
    alert('生成失败:' + ((e as Error).message || e))
  } finally {
    aiLoading.value = false
  }
}

// 从后端拉某一周的复盘文本;远端无记录时填空。
// 拉完后 nextTick 把 dirty 改回 false,避免 deep watch 把 reload 当成"用户修改"
async function reloadFromApi(weekKey: string) {
  try {
    const view = await getWeeklyReview(weekKey)
    review.value = {
      weekKey: view.weekCode,
      highlights: view.highlights || '',
      lessons: view.lessons || '',
      experiments: view.experiments || '',
      nextWeek: view.nextWeek || '',
    }
    savedAt.value = view.updatedAt ? new Date(view.updatedAt) : null
  } catch (e) {
    console.error('[ReviewWeekly] load failed', e)
    review.value = emptyReview(weekKey)
    savedAt.value = null
  } finally {
    await nextTick()
    dirty.value = false
  }
}

watch(review, () => { dirty.value = true }, { deep: true })

watch(week, (w) => {
  reloadFromApi(w)
})

async function save() {
  saving.value = true
  try {
    const view = await saveWeeklyReview(week.value, {
      highlights: review.value.highlights,
      lessons: review.value.lessons,
      experiments: review.value.experiments,
      nextWeek: review.value.nextWeek,
    })
    savedAt.value = view.updatedAt ? new Date(view.updatedAt) : new Date()
    dirty.value = false
  } catch (e) {
    alert('保存失败:' + ((e as Error).message || e))
  } finally {
    saving.value = false
  }
}

onMounted(async () => {
  try {
    const [vs, tb] = await Promise.all([
      getAnalyticsCompare(),
      getTopBottom(undefined, 7, 5).catch(() => null),
      reloadFromApi(week.value),
    ])
    allVideos.value = vs
    insights.value = tb
  } finally {
    loading.value = false
  }
})

// 取最近 7 天发布的视频;不足 5 条就取最近 5 条
const weekVideos = computed(() => {
  const sevenDaysAgo = Date.now() - 7 * 24 * 60 * 60 * 1000
  const recent = allVideos.value.filter((v) => new Date(v.publishedAt).getTime() >= sevenDaysAgo)
  if (recent.length >= 5) return recent
  return allVideos.value.slice(0, 5)
})

const weekStats = computed(() => {
  const totalViews = weekVideos.value.reduce((s, v) => s + v.views, 0)
  const totalCost = weekVideos.value.reduce((s, v) => s + (v.costYuan ?? 0), 0)
  const retSum = weekVideos.value.reduce((s, v) => s + (v.retentionPct ?? 0), 0)
  const retCount = weekVideos.value.filter((v) => v.retentionPct != null).length
  return {
    count: weekVideos.value.length,
    views: totalViews,
    cost: totalCost,
    retention: retCount > 0 ? retSum / retCount : 0,
  }
})

const PROMPTS: Record<keyof Omit<WeeklyReview, 'weekKey'>, { title: string; placeholder: string }> = {
  highlights: { title: '本周亮点 🎯', placeholder: '哪些做对了?哪条数据破纪录?哪个钩子模板复用成功?' },
  lessons: { title: '教训 ⚠️', placeholder: '哪些失误?哪条视频不达预期?为什么?' },
  experiments: { title: '试错 🧪', placeholder: '本周做了哪些 A/B?结果如何?' },
  nextWeek: { title: '下周改进 🚀', placeholder: '具体的 3 个动作 / 2 个目标 / 1 个停做' },
}
</script>

<template>
  <div class="min-h-full">
    <div class="sticky top-0 z-10 bg-surface-primary border-b border-border-subtle">
      <div class="px-8 py-3 max-w-[1400px] mx-auto flex flex-col gap-1">
        <div class="flex items-center gap-4 flex-wrap">
          <button
            class="flex items-center gap-1.5 text-sm text-text-muted hover:text-text-primary"
            @click="$router.push('/dashboard')"
          >
            <ArrowLeft :size="14" /> 返回数据看板
          </button>
          <h1 class="text-lg font-semibold">周复盘</h1>
          <span class="chip text-[11px] bg-accent-soft text-accent font-mono">{{ week }}</span>
          <span class="chip text-[11px] bg-surface-tertiary text-text-muted">本周 {{ weekStats.count }} 条视频</span>
          <span v-if="savedAt" class="ml-auto text-xs text-status-done">
            已保存于 {{ savedAt.toLocaleTimeString() }}
          </span>
          <span v-if="dirty" :class="[savedAt ? '' : 'ml-auto', 'text-xs text-status-paused']">未保存</span>
        </div>
        <div class="text-xs text-text-muted">本周完成 + 4 大块复盘输入 · 数据已自动落库</div>
      </div>
    </div>

    <div class="px-8 py-5 max-w-[1400px] mx-auto">
      <div class="card p-3 mb-4 flex items-center gap-3 flex-wrap">
        <label class="text-sm flex items-center gap-2">
          <span class="text-text-muted text-xs">周</span>
          <input
            v-model="week" type="text" placeholder="2026-W21"
            class="bg-surface-tertiary border border-border-subtle rounded px-2 py-1 font-mono text-sm w-32"
          />
        </label>
        <button class="btn-primary ml-auto" :disabled="!dirty || saving" @click="save">
          <Save :size="14" /> {{ saving ? '保存中…' : '保存' }}
        </button>
        <button class="btn-primary" :disabled="aiLoading" @click="aiGenerate">
          <Sparkles :size="14" /> {{ aiLoading ? '生成中…' : 'AI 生成复盘' }}
        </button>
        <button class="btn-primary" @click="gotoDataDrivenBrainstorm">
          <Sparkles :size="14" /> 基于数据生成下周选题
        </button>
      </div>

      <div class="grid grid-cols-2 md:grid-cols-4 gap-3 mb-4">
        <div class="card p-4">
          <div class="text-xs text-text-muted mb-1">视频数</div>
          <div class="text-2xl font-mono font-semibold">{{ weekStats.count }}</div>
        </div>
        <div class="card p-4">
          <div class="text-xs text-text-muted mb-1">总播放</div>
          <div class="text-2xl font-mono font-semibold">{{ weekStats.views.toLocaleString() }}</div>
        </div>
        <div class="card p-4">
          <div class="text-xs text-text-muted mb-1">本周成本</div>
          <div class="text-2xl font-mono font-semibold text-status-done">¥{{ weekStats.cost.toFixed(2) }}</div>
        </div>
        <div class="card p-4">
          <div class="text-xs text-text-muted mb-1">平均完播率</div>
          <div class="text-2xl font-mono font-semibold text-accent">{{ weekStats.retention.toFixed(1) }}%</div>
        </div>
      </div>

      <div class="card p-4 mb-4 text-sm">
        <div class="flex items-center gap-2 mb-2">
          <Sparkles :size="14" class="text-accent" />
          <span class="font-medium">本周维度洞察</span>
          <a class="text-xs text-accent ml-auto cursor-pointer hover:underline"
             @click="$router.push('/insights')">查看完整数据洞察 <ArrowRight :size="11" class="inline" /></a>
        </div>
        <ul class="space-y-1 text-xs text-text-secondary">
          <li>
            <span class="text-text-muted w-24 inline-block">Top 5 共性:</span>
            <span class="text-status-done">{{ topCommonText || '— 样本不足或无显著共性' }}</span>
          </li>
          <li>
            <span class="text-text-muted w-24 inline-block">Bottom 5 共性:</span>
            <span class="text-status-failed">{{ bottomCommonText || '— 样本不足或无显著共性' }}</span>
          </li>
        </ul>
      </div>

      <div class="card p-5 mb-4">
        <h2 class="text-base font-semibold mb-3">本周视频</h2>
        <div v-if="loading" class="text-sm text-text-muted py-4 text-center">加载中…</div>
        <div v-else-if="weekVideos.length === 0" class="text-sm text-text-muted py-4 text-center">
          暂无视频 — <a class="text-accent cursor-pointer" @click="$router.push('/published-videos')">去录入</a>
        </div>
        <div v-else class="overflow-x-auto">
          <table class="w-full text-sm">
            <thead class="text-[11px] text-text-muted">
              <tr class="border-b border-border-subtle">
                <th class="text-left py-2.5 pl-2 font-medium w-[64px]">平台</th>
                <th class="text-left px-3 py-2.5 font-medium">项目名 / 标题</th>
                <th class="text-right px-3 py-2.5 font-medium w-[88px]">播放</th>
                <th class="text-left px-3 py-2.5 font-medium w-[140px]">完播率</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="v in weekVideos" :key="v.id"
                class="border-b border-border-subtle/60 hover:bg-surface-tertiary/30 cursor-pointer transition-colors"
                @click="$router.push(`/review/${v.id}`)"
              >
                <td class="pl-2 py-3">
                  <span class="chip text-[10px] font-semibold" :class="platformChip(v.platform)">
                    {{ v.platform }}
                  </span>
                </td>
                <td class="px-3 py-3">
                  <div class="text-text-primary leading-snug truncate max-w-[420px]">
                    {{ splitTitle(v.title).main || v.title }}
                  </div>
                  <div v-if="splitTitle(v.title).tags.length || v.hookTemplate"
                       class="flex items-center gap-2 mt-1 text-[11px] text-text-muted flex-wrap">
                    <span v-if="splitTitle(v.title).tags.length" class="truncate max-w-[320px]">
                      #{{ splitTitle(v.title).tags.slice(0, 4).join(' #') }}
                    </span>
                    <span v-if="v.hookTemplate" class="chip text-[10px] bg-accent-soft text-accent">
                      钩子 · {{ v.hookTemplate }}
                    </span>
                  </div>
                </td>
                <td class="px-3 py-3 text-right font-mono font-semibold tabular-nums">{{ v.views.toLocaleString() }}</td>
                <td class="px-3 py-3">
                  <template v-if="v.retentionPct != null">
                    <div class="flex items-center gap-2">
                      <div class="flex-1 h-1.5 rounded-full bg-surface-tertiary overflow-hidden">
                        <div class="h-full rounded-full bg-accent/70"
                             :style="{ width: Math.min(100, Number(v.retentionPct)) + '%' }" />
                      </div>
                      <span class="text-xs font-mono tabular-nums w-12 text-right text-text-secondary">
                        {{ Number(v.retentionPct).toFixed(1) }}%
                      </span>
                    </div>
                  </template>
                  <span v-else class="text-text-muted text-xs">—</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
        <div
          v-for="key in (Object.keys(PROMPTS) as Array<keyof typeof PROMPTS>)" :key="key"
          class="card p-5"
        >
          <h2 class="text-base font-semibold mb-2">{{ PROMPTS[key].title }}</h2>
          <textarea
            v-model="review[key]"
            :placeholder="PROMPTS[key].placeholder"
            rows="6"
            class="w-full bg-surface-tertiary border border-border-subtle rounded p-3 text-sm text-text-primary font-sans resize-y"
          />
        </div>
      </div>
    </div>
  </div>
</template>
