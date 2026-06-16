<script setup lang="ts">
import { computed } from 'vue'
import { Loader2, Pencil, Trash2 } from 'lucide-vue-next'
import TimeText from '../TimeText.vue'
import type { PublishedVideo } from '../../api/publishedVideos'

const props = defineProps<{
  items: PublishedVideo[]
  deletingId: number | null
}>()

defineEmits<{
  (e: 'edit', v: PublishedVideo): void
  (e: 'delete', v: PublishedVideo): void
}>()

const PLATFORM_ORDER = ['抖音', '快手', '小红书', 'B站', '视频号', '西瓜']
interface PlatformGroup {
  platform: string
  videos: PublishedVideo[]
  totalViews: number
}

function sortByPublished(arr: PublishedVideo[]): PublishedVideo[] {
  return [...arr].sort((a, b) => new Date(b.publishedAt).getTime() - new Date(a.publishedAt).getTime())
}
function sumViews(arr: PublishedVideo[]): number {
  return arr.reduce((s, v) => s + (v.views || 0), 0)
}
function pct1(n: number): string {
  return n.toFixed(2)
}

const byPlatform = computed<PlatformGroup[]>(() => {
  const map = new Map<string, PublishedVideo[]>()
  for (const v of props.items) {
    const key = v.platform || '未知'
    if (!map.has(key)) map.set(key, [])
    map.get(key)!.push(v)
  }
  const ordered: PlatformGroup[] = []
  for (const p of PLATFORM_ORDER) {
    if (map.has(p)) {
      ordered.push({ platform: p, videos: sortByPublished(map.get(p)!), totalViews: sumViews(map.get(p)!) })
      map.delete(p)
    }
  }
  Array.from(map.keys()).sort((a, b) => a.localeCompare(b, 'zh-CN')).forEach((p) => {
    ordered.push({ platform: p, videos: sortByPublished(map.get(p)!), totalViews: sumViews(map.get(p)!) })
  })
  return ordered
})
</script>

<template>
  <div class="flex flex-col gap-5">
    <section v-for="g in byPlatform" :key="g.platform" class="card overflow-hidden">
      <header class="flex items-center gap-3 px-5 py-3 border-b border-border-subtle">
        <h2 class="text-base font-semibold text-text-primary">{{ g.platform }}</h2>
        <span class="chip text-[11px] bg-surface-tertiary text-text-secondary font-mono">
          {{ g.videos.length }} 条
        </span>
        <span class="text-xs text-text-muted ml-auto">
          总播放 <span class="font-mono text-text-secondary font-semibold">{{ g.totalViews.toLocaleString() }}</span>
        </span>
      </header>
      <div class="overflow-x-auto">
        <table class="w-full text-sm">
          <thead class="text-[11px] text-text-muted">
            <tr class="border-b border-border-subtle">
              <th class="text-left pl-4 pr-2 py-2.5 font-medium w-[40px]">#</th>
              <th class="text-left px-3 py-2.5 font-medium">项目名 / 标题</th>
              <th class="text-right px-3 py-2.5 font-medium w-[88px]">播放</th>
              <th class="text-right px-3 py-2.5 font-medium w-[96px]" title="点赞 / 点赞率">点赞·率</th>
              <th class="text-right px-3 py-2.5 font-medium w-[64px]">评论</th>
              <th class="text-right px-3 py-2.5 font-medium w-[80px]" title="涨粉 / 掉粉">涨粉</th>
              <th class="text-left px-3 py-2.5 font-medium w-[120px]">完播率</th>
              <th class="text-right px-3 py-2.5 font-medium w-[64px]">成本</th>
              <th class="text-left px-3 py-2.5 font-medium w-[96px]">发布</th>
              <th class="text-right px-4 py-2.5 font-medium w-[72px]"></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="v in g.videos" :key="v.id"
                class="border-b border-border-subtle/60 hover:bg-surface-tertiary/30 group transition-colors">
              <td class="pl-4 pr-2 py-3 font-mono text-xs text-text-muted">{{ v.id }}</td>
              <td class="px-3 py-3">
                <div class="text-text-primary truncate max-w-[340px] leading-snug">{{ v.projectName || v.title }}</div>
                <div v-if="v.title && v.projectName && v.title !== v.projectName"
                     class="text-[11px] text-text-muted truncate max-w-[340px] mt-0.5">{{ v.title }}</div>
              </td>
              <td class="px-3 py-3 text-right font-mono font-semibold tabular-nums">{{ v.views.toLocaleString() }}</td>
              <td class="px-3 py-3 text-right font-mono text-text-secondary tabular-nums">
                <div>{{ v.likes.toLocaleString() }}</div>
                <div v-if="v.likeRate != null" class="text-[10px] text-text-muted mt-0.5">
                  {{ pct1(v.likeRate * 100) }}%
                </div>
              </td>
              <td class="px-3 py-3 text-right font-mono text-text-secondary tabular-nums">{{ v.comments.toLocaleString() }}</td>
              <td class="px-3 py-3 text-right font-mono tabular-nums">
                <template v-if="v.subscribeCount != null || v.unsubscribeCount != null">
                  <span class="text-status-done">+{{ v.subscribeCount ?? 0 }}</span>
                  <span v-if="v.unsubscribeCount" class="text-status-failed text-xs ml-1">−{{ v.unsubscribeCount }}</span>
                </template>
                <span v-else class="text-text-muted text-xs">—</span>
              </td>
              <td class="px-3 py-3">
                <template v-if="v.retentionPct != null">
                  <div class="flex items-center gap-2">
                    <div class="flex-1 h-1.5 rounded-full bg-surface-tertiary overflow-hidden">
                      <div class="h-full rounded-full bg-accent/70"
                           :style="{ width: Math.min(100, Number(v.retentionPct)) + '%' }" />
                    </div>
                    <span class="text-xs font-mono tabular-nums w-10 text-right text-text-secondary">{{ v.retentionPct }}%</span>
                  </div>
                </template>
                <span v-else class="text-text-muted text-xs">—</span>
              </td>
              <td class="px-3 py-3 text-right font-mono text-text-secondary text-xs tabular-nums">
                {{ v.costYuan != null ? '¥' + v.costYuan : '—' }}
              </td>
              <td class="px-3 py-3 text-text-muted text-xs">
                <TimeText :value="v.publishedAt" relative />
              </td>
              <td class="px-4 py-3 text-right">
                <div class="inline-flex items-center gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
                  <button class="p-1 rounded hover:bg-surface-hover text-text-muted hover:text-text-primary"
                          title="编辑" @click="$emit('edit', v)">
                    <Pencil :size="13" />
                  </button>
                  <button class="p-1 rounded hover:bg-status-failed/10 text-text-muted hover:text-status-failed disabled:opacity-50"
                          title="删除"
                          :disabled="deletingId === v.id"
                          @click="$emit('delete', v)">
                    <Loader2 v-if="deletingId === v.id" :size="13" class="animate-spin" />
                    <Trash2 v-else :size="13" />
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
  </div>
</template>
