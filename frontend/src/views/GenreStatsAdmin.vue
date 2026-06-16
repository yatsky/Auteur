<script setup lang="ts">
// 体裁/垂类聚合统计快照管理。
// 抖音「投稿作品.xlsx」按周期导入(周期由 PublishedVideoAdmin 文件导入对话框收集),
// KpiDrift 用最新快照算"体裁基准"。
import { computed, onMounted, ref } from 'vue'
import { ArrowLeft, Loader2, Trash2 } from 'lucide-vue-next'
import ErrorBanner from '../components/ErrorBanner.vue'
import { deleteGenreStat, listGenreStats, type GenreStat } from '../api/genreStats'
import { extractError } from '../lib/format'
import { useAsyncLoad } from '../composables/useAsyncLoad'

const items = ref<GenreStat[]>([])
const deletingId = ref<number | null>(null)
const platformFilter = ref<string>('all')

const { loading, errorMsg, run: load } = useAsyncLoad(async () => {
  items.value = await listGenreStats()
}, { errorPrefix: '加载失败' })

const platforms = computed(() => Array.from(new Set(items.value.map((i) => i.platform))).sort())

const filtered = computed(() =>
  platformFilter.value === 'all'
    ? items.value
    : items.value.filter((i) => i.platform === platformFilter.value),
)

async function onDelete(s: GenreStat) {
  if (!confirm(`确定删除 ${s.periodStart} ~ ${s.periodEnd} · ${s.platform} / ${s.genre} / ${s.vertical}?\nKpiDrift 体裁基准会少这条样本。`)) return
  deletingId.value = s.id
  try {
    await deleteGenreStat(s.id)
    items.value = items.value.filter((x) => x.id !== s.id)
    errorMsg.value = null
  } catch (e: any) {
    errorMsg.value = extractError(e, '删除失败')
  } finally {
    deletingId.value = null
  }
}

function fmtPct(n: number | null) { return n == null ? '-' : n.toFixed(2) + '%' }
function fmtNum(n: number | null) { return n == null ? '-' : n.toLocaleString() }
function fmtSec(n: number | null) { return n == null ? '-' : n.toFixed(1) + 's' }

onMounted(load)
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
          <h1 class="text-lg font-semibold">体裁基准 · 投稿作品聚合</h1>
          <span class="chip text-[11px] bg-surface-tertiary text-text-muted">{{ items.length }} 条快照</span>
          <Loader2 v-if="loading" :size="14" class="animate-spin text-text-muted ml-auto" />
        </div>
        <div class="text-xs text-text-muted">抖音「投稿作品.xlsx」按周期导入 · KpiDrift 体裁基准的数据源(单视频 metric 跟所属垂类的均值比 σ)</div>
      </div>
    </div>

    <div class="px-8 py-5 max-w-[1400px] mx-auto">
      <ErrorBanner :msg="errorMsg" />

      <div class="card p-3 mb-4 flex items-center gap-2 flex-wrap">
        <span class="text-xs text-text-muted">平台</span>
        <button
          v-for="p in (['all', ...platforms] as const)"
          :key="p"
          :class="[
            'chip cursor-pointer text-xs',
            platformFilter === p ? 'bg-accent-soft text-accent' : 'bg-surface-tertiary text-text-secondary',
          ]"
          @click="platformFilter = p as string"
        >
          {{ p === 'all' ? '全部' : p }}
        </button>
        <span class="ml-auto text-xs text-text-muted">
          要导入新快照?去 <a class="text-accent cursor-pointer" @click="$router.push('/published-videos')">已发布视频 → 导入文件</a>,选「投稿作品.xlsx」自动走聚合通道
        </span>
      </div>

      <div class="card overflow-hidden">
        <div class="overflow-x-auto">
          <table class="w-full text-sm">
            <thead class="bg-surface-tertiary text-xs uppercase text-text-muted">
              <tr>
                <th class="text-left px-3 py-2 font-medium w-[60px]">id</th>
                <th class="text-left px-3 py-2 font-medium w-[180px]">周期</th>
                <th class="text-left px-3 py-2 font-medium w-[80px]">平台</th>
                <th class="text-left px-3 py-2 font-medium w-[100px]">体裁</th>
                <th class="text-left px-3 py-2 font-medium w-[100px]">垂类</th>
                <th class="text-right px-3 py-2 font-medium w-[80px]">投稿量</th>
                <th class="text-right px-3 py-2 font-medium w-[90px]">条均点击率</th>
                <th class="text-right px-3 py-2 font-medium w-[90px]">5s完播率</th>
                <th class="text-right px-3 py-2 font-medium w-[90px]">2s跳出率</th>
                <th class="text-right px-3 py-2 font-medium w-[90px]">条均播放</th>
                <th class="text-right px-3 py-2 font-medium w-[100px]">播放中位数</th>
                <th class="text-right px-3 py-2 font-medium w-[60px]">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="s in filtered"
                :key="s.id"
                class="border-t border-border-subtle hover:bg-surface-tertiary/40"
              >
                <td class="px-3 py-2 font-mono text-text-secondary">{{ s.id }}</td>
                <td class="px-3 py-2 font-mono text-xs">{{ s.periodStart }} ~ {{ s.periodEnd }}</td>
                <td class="px-3 py-2 text-text-secondary">{{ s.platform }}</td>
                <td class="px-3 py-2">{{ s.genre }}</td>
                <td class="px-3 py-2">{{ s.vertical }}</td>
                <td class="px-3 py-2 text-right font-mono">{{ s.submissionCount.toLocaleString() }}</td>
                <td class="px-3 py-2 text-right font-mono">{{ fmtPct(s.avgCtrPct) }}</td>
                <td class="px-3 py-2 text-right font-mono">{{ fmtPct(s.avgPlay5sPct) }}</td>
                <td class="px-3 py-2 text-right font-mono">{{ fmtPct(s.avgDrop2sPct) }}</td>
                <td class="px-3 py-2 text-right font-mono">{{ fmtSec(s.avgPlaySeconds) }}</td>
                <td class="px-3 py-2 text-right font-mono">{{ fmtNum(s.medianViews) }}</td>
                <td class="px-3 py-2 text-right">
                  <button
                    class="text-text-muted hover:text-status-failed disabled:opacity-50"
                    title="删除"
                    :disabled="deletingId === s.id"
                    @click="onDelete(s)"
                  >
                    <Loader2 v-if="deletingId === s.id" :size="13" class="animate-spin" />
                    <Trash2 v-else :size="13" />
                  </button>
                </td>
              </tr>
              <tr v-if="!loading && filtered.length === 0">
                <td colspan="12" class="text-center py-12 text-text-muted text-sm">
                  还没有体裁基准数据 — 去
                  <a class="text-accent cursor-pointer mx-1" @click="$router.push('/published-videos')">已发布视频</a>
                  的「导入文件」里拖入「投稿作品.xlsx」开始
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>
  </div>
</template>
