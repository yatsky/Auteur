<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ArrowLeft, MessageCircle, Sparkles, TrendingDown } from 'lucide-vue-next'
import { getPublishedVideo, type PublishedVideo } from '../api/publishedVideos'
import { generateVideoAttribution, type VideoAttributionResult } from '../api/insights'

const props = defineProps<{ videoId: number }>()

const video = ref<PublishedVideo | null>(null)
const loading = ref(true)
const errMsg = ref('')

const attribution = ref<VideoAttributionResult | null>(null)
const attrLoading = ref(false)
const attrError = ref('')

onMounted(async () => {
  try {
    video.value = await getPublishedVideo(props.videoId)
  } catch (e: any) {
    errMsg.value = e?.message || '视频不存在或加载失败'
  } finally {
    loading.value = false
  }
})

function fmtPct(n: number | null): string {
  return n != null ? n.toFixed(1) + '%' : '—'
}

function fmtPct2(n: number | null | undefined): string {
  return n != null ? Number(n).toFixed(2) + '%' : '—'
}

function fmtSec(n: number | null | undefined): string {
  return n != null ? Number(n).toFixed(1) + ' s' : '—'
}

// drop2sPct > 30 = 钩子失败; dislikeRatePct > 1 = 触发负反馈; play5sPct < 40 = 钩子拉胯
const fineGrainHasData = computed(() => {
  const v = video.value
  if (!v) return false
  return v.avgPlaySeconds != null || v.drop2sPct != null || v.play5sPct != null
      || v.avgPlayRatioPct != null || v.favoriteRatePct != null || v.dislikeRatePct != null
})

function dropClass(n: number | null | undefined): string {
  if (n == null) return ''
  if (n > 30) return 'text-status-failed'
  if (n > 20) return 'text-status-paused'
  return 'text-status-done'
}
function play5sClass(n: number | null | undefined): string {
  if (n == null) return ''
  if (n < 40) return 'text-status-failed'
  if (n < 60) return 'text-status-paused'
  return 'text-status-done'
}
function dislikeClass(n: number | null | undefined): string {
  if (n == null) return ''
  if (n > 1) return 'text-status-failed'
  if (n > 0.3) return 'text-status-paused'
  return 'text-text-secondary'
}

async function runAttribution() {
  attrLoading.value = true
  attrError.value = ''
  try {
    attribution.value = await generateVideoAttribution(props.videoId)
  } catch (e: any) {
    attrError.value = e?.message || '归因失败'
    attribution.value = null
  } finally {
    attrLoading.value = false
  }
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
          <h1 class="text-lg font-semibold truncate max-w-[640px]">
            {{ video?.title ?? `视频 #${videoId}` }}
          </h1>
          <span v-if="video" class="chip text-[11px] bg-surface-tertiary text-text-muted font-mono">
            #{{ video.id }} · {{ video.platform }}{{ video.durationSeconds ? ' · ' + video.durationSeconds + 's' : '' }}
          </span>
        </div>
        <div v-if="video" class="text-xs text-text-muted">
          发布于 {{ video.publishedAt.replace('T', ' ').slice(0, 16) }}
        </div>
      </div>
    </div>

    <div class="px-8 py-5 max-w-[1400px] mx-auto">
      <div v-if="loading" class="card p-12 text-center text-text-muted text-sm">加载中…</div>

      <div v-else-if="errMsg || !video" class="card p-12 text-center text-text-muted text-sm">
        {{ errMsg || `没有找到视频 ${videoId}` }}
      </div>

      <template v-else>
        <div class="grid grid-cols-2 md:grid-cols-4 gap-3 mb-4">
          <div class="card p-4">
            <div class="text-xs text-text-muted mb-1">播放</div>
            <div class="text-2xl font-mono font-semibold">{{ video.views.toLocaleString() }}</div>
          </div>
          <div class="card p-4">
            <div class="text-xs text-text-muted mb-1">点赞</div>
            <div class="text-2xl font-mono font-semibold">{{ video.likes.toLocaleString() }}</div>
          </div>
          <div class="card p-4">
            <div class="text-xs text-text-muted mb-1">完播率</div>
            <div class="text-2xl font-mono font-semibold text-accent">
              {{ fmtPct(video.retentionPct != null ? Number(video.retentionPct) : null) }}
            </div>
          </div>
          <div class="card p-4">
            <div class="text-xs text-text-muted mb-1">钩子 CTR</div>
            <div class="text-2xl font-mono font-semibold text-status-done">
              {{ fmtPct(video.hookCtr != null ? Number(video.hookCtr) : null) }}
            </div>
          </div>
        </div>

        <div class="grid grid-cols-1 md:grid-cols-2 gap-3 mb-4">
          <div class="card p-4">
            <div class="text-xs text-text-muted mb-1">评论 / 转发</div>
            <div class="text-base font-mono">
              {{ video.comments.toLocaleString() }} 评论 · {{ video.shares.toLocaleString() }} 转发
            </div>
            <div class="text-xs text-text-muted mt-1">
              互动率: {{ video.views > 0 ? (((video.likes + video.comments) / video.views) * 100).toFixed(2) + '%' : '—' }}
            </div>
          </div>
          <div class="card p-4">
            <div class="text-xs text-text-muted mb-1">钩子模板 / 成本</div>
            <div class="text-base">{{ video.hookTemplate || '—' }}</div>
            <div class="text-xs text-text-muted mt-1 font-mono">
              {{ video.costYuan != null ? '¥' + Number(video.costYuan).toFixed(2) : '成本未填' }}
              {{ video.scriptId ? '· script #' + video.scriptId : '' }}
            </div>
          </div>
        </div>

        <div v-if="fineGrainHasData" class="card p-4 mb-4">
          <div class="text-xs text-text-muted mb-3">细分留存 / 互动率
            <span class="ml-2 text-text-muted/70">2s 跳出率 &gt; 30 = 钩子失败 · 5s 完播率 &lt; 40 = 钩子拉胯 · 不感兴趣率 &gt; 1 = 触发负反馈</span>
          </div>
          <div class="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-6 gap-3 text-sm">
            <div>
              <div class="text-xs text-text-muted">平均播放时长</div>
              <div class="text-base font-mono mt-0.5">{{ fmtSec(video.avgPlaySeconds) }}</div>
            </div>
            <div>
              <div class="text-xs text-text-muted">2s 跳出率</div>
              <div class="text-base font-mono mt-0.5" :class="dropClass(video.drop2sPct)">{{ fmtPct2(video.drop2sPct) }}</div>
            </div>
            <div>
              <div class="text-xs text-text-muted">5s 完播率</div>
              <div class="text-base font-mono mt-0.5" :class="play5sClass(video.play5sPct)">{{ fmtPct2(video.play5sPct) }}</div>
            </div>
            <div>
              <div class="text-xs text-text-muted">平均播放占比</div>
              <div class="text-base font-mono mt-0.5">{{ fmtPct2(video.avgPlayRatioPct) }}</div>
            </div>
            <div>
              <div class="text-xs text-text-muted">收藏率</div>
              <div class="text-base font-mono mt-0.5">{{ fmtPct2(video.favoriteRatePct) }}</div>
            </div>
            <div>
              <div class="text-xs text-text-muted">不感兴趣率</div>
              <div class="text-base font-mono mt-0.5" :class="dislikeClass(video.dislikeRatePct)">{{ fmtPct2(video.dislikeRatePct) }}</div>
            </div>
          </div>
        </div>

        <div v-if="video.notes" class="card p-4 mb-4">
          <div class="text-xs text-text-muted mb-1">备注</div>
          <div class="text-sm text-text-secondary whitespace-pre-wrap">{{ video.notes }}</div>
        </div>

        <div class="card p-5 mb-4">
          <div class="flex items-center gap-2 mb-3">
            <Sparkles :size="16" class="text-accent" />
            <h2 class="text-base font-semibold">AI 归因分析</h2>
            <button class="btn-primary ml-auto text-sm" :disabled="attrLoading" @click="runAttribution">
              <Sparkles :size="13" />
              {{ attrLoading ? '生成中…' : (attribution ? '重新生成' : '生成归因') }}
            </button>
          </div>

          <div v-if="attrError" class="text-sm text-status-failed">{{ attrError }}</div>

          <div v-else-if="attrLoading" class="text-sm text-text-muted py-4 text-center">
            AI 正在分析这条视频的 KPI / 维度 / 30 天均值基线…
          </div>

          <div v-else-if="!attribution" class="text-sm text-text-muted py-2">
            点击「生成归因」让 AI 基于本条 KPI + 维度 + 30 天均值给出 4 段分析:定性结论、做对的地方、做差的地方、3 条可执行建议。结果不入库,可重新生成。
          </div>

          <div v-else>
            <div class="text-sm font-medium mb-3 text-text-primary leading-relaxed">
              {{ attribution.verdict }}
            </div>
            <div class="grid grid-cols-1 md:grid-cols-2 gap-3 text-sm">
              <div class="p-3 rounded bg-status-done/5 border border-status-done/20">
                <div class="text-xs text-status-done font-semibold mb-1">做对的 ✓</div>
                <pre class="whitespace-pre-wrap font-sans text-text-secondary leading-relaxed">{{ attribution.whatWorked }}</pre>
              </div>
              <div class="p-3 rounded bg-status-failed/5 border border-status-failed/20">
                <div class="text-xs text-status-failed font-semibold mb-1">做差的 ✗</div>
                <pre class="whitespace-pre-wrap font-sans text-text-secondary leading-relaxed">{{ attribution.whatFailed }}</pre>
              </div>
            </div>
            <div class="mt-3 p-3 rounded bg-accent/5 border border-accent/20">
              <div class="text-xs text-accent font-semibold mb-1">下次怎么做 →</div>
              <pre class="whitespace-pre-wrap font-sans text-text-secondary leading-relaxed">{{ attribution.recommendations }}</pre>
            </div>
            <div v-if="attribution.fallback" class="text-xs text-text-muted mt-2">
              ⚠️ AI 解析失败,以上为兜底文案。可点「重新生成」重试。
            </div>
          </div>
        </div>

        <div class="grid grid-cols-1 lg:grid-cols-[1fr_1fr] gap-4">
          <div class="card p-5 bg-status-paused/5 border-status-paused/20">
            <h2 class="text-base font-semibold mb-2 flex items-center gap-2">
              <TrendingDown :size="16" class="text-status-paused" /> 分钟级留存曲线
            </h2>
            <div class="text-sm text-text-muted">
              📊 该指标需对接平台 OAuth(#7)后才能填充 —— 暂未接通
            </div>
            <div class="text-xs text-text-muted mt-2">
              手填的总完播率已显示在上方 KPI;分钟级衰减只有 B 站给得齐,需走平台 open API。
            </div>
          </div>

          <div class="card p-5 bg-status-paused/5 border-status-paused/20">
            <h2 class="text-base font-semibold mb-2 flex items-center gap-2">
              <MessageCircle :size="16" class="text-status-paused" /> 评论关键词
            </h2>
            <div class="text-sm text-text-muted">
              📊 该指标需对接平台 OAuth(#7) + 评论 NLP 后才能填充 —— 暂未接通
            </div>
            <div class="text-xs text-text-muted mt-2">
              需要先爬评论再走 NLP 关键词,本期不在范围内。
            </div>
          </div>
        </div>
      </template>
    </div>
  </div>
</template>
