<script setup lang="ts">
// 多平台改造工作台
// 左：script 列表，右：6 个平台卡片，点进 PlatformDetail
import { computed, ref } from 'vue'
import { ArrowLeft, Loader2, Share2, Upload } from 'lucide-vue-next'
import { useRecentScripts } from '../composables/useRecentScripts'
import ErrorBanner from '../components/ErrorBanner.vue'
import TimeText from '../components/TimeText.vue'

const { items, loading, errorMsg } = useRecentScripts()

const selectedScriptId = ref<number | null>(null)
const selected = computed(() => items.value.find((s) => s.scriptId === selectedScriptId.value) ?? null)

interface PlatformSpec {
  key: string
  name: string
  color: string
  ratio: string
  maxDuration: string
  hookTip: string
  status: 'PENDING' | 'GENERATED' | 'PUBLISHED'
}

const PLATFORMS: PlatformSpec[] = [
  { key: 'douyin', name: '抖音', color: 'bg-pink-500/15 text-pink-500', ratio: '9:16', maxDuration: '180s', hookTip: '前 3s 必须有强钩子', status: 'GENERATED' },
  { key: 'bilibili', name: 'B站', color: 'bg-cyan-500/15 text-cyan-500', ratio: '16:9', maxDuration: '600s', hookTip: '可上长版本 + 章节标记', status: 'PENDING' },
  { key: 'xiaohongshu', name: '小红书', color: 'bg-rose-500/15 text-rose-500', ratio: '4:5', maxDuration: '60s', hookTip: '强引导评论', status: 'PENDING' },
  { key: 'wechat-channels', name: '视频号', color: 'bg-emerald-500/15 text-emerald-500', ratio: '9:16', maxDuration: '60s', hookTip: '社交分享导向', status: 'PUBLISHED' },
  { key: 'kuaishou', name: '快手', color: 'bg-orange-500/15 text-orange-500', ratio: '9:16', maxDuration: '300s', hookTip: '草根质感+反转', status: 'PENDING' },
  { key: 'xigua', name: '西瓜视频', color: 'bg-amber-500/15 text-amber-500', ratio: '16:9', maxDuration: '600s', hookTip: '中长视频优势', status: 'PENDING' },
]

const STATUS_LABEL = {
  PENDING: '未改造',
  GENERATED: '已生成',
  PUBLISHED: '已发布',
}
const STATUS_STYLE = {
  PENDING: 'bg-text-muted/15 text-text-muted',
  GENERATED: 'bg-status-done/15 text-status-done',
  PUBLISHED: 'bg-accent-soft text-accent',
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
          <h1 class="text-lg font-semibold">多平台改造工作台</h1>
          <span v-if="selected" class="chip text-[11px] bg-accent-soft text-accent">
            #{{ selected.scriptId }}{{ selected.projectName ? ' · ' + selected.projectName : '' }}
          </span>
        </div>
        <div class="text-xs text-text-muted">
          把同一个视频按各平台的尺寸 / 时长 / 调性二次改造
        </div>
      </div>
    </div>

    <div class="px-8 py-5 max-w-[1400px] mx-auto">
      <ErrorBanner :msg="errorMsg" />

      <div class="card p-3 mb-4 bg-status-paused/10 border-status-paused/30 text-xs text-status-paused">
        ⚠️ 后端剪辑接口暂未实现 —— 平台状态为 mock 演示用；接通后会调
        POST /api/scripts/&#123;id&#125;/platforms/adapt
      </div>

      <div class="grid grid-cols-1 lg:grid-cols-[280px_1fr] gap-4">
        <aside class="card p-3 max-h-[680px] overflow-y-auto">
          <div class="text-xs text-text-muted px-2 py-1.5">选 Script</div>
          <div v-if="loading && items.length === 0" class="py-8 text-center">
            <Loader2 :size="18" class="animate-spin text-text-muted mx-auto" />
          </div>
          <div v-else-if="items.length === 0" class="py-8 text-center text-xs text-text-muted">
            没有可用脚本
          </div>
          <ul v-else class="space-y-0.5">
            <li
              v-for="h in items" :key="h.scriptId"
              :class="['flex items-center justify-between px-2 py-2 rounded text-sm cursor-pointer transition-colors gap-2',
                       selectedScriptId === h.scriptId
                         ? 'bg-accent-soft text-accent'
                         : 'hover:bg-surface-tertiary text-text-secondary']"
              @click="selectedScriptId = h.scriptId"
            >
              <span class="min-w-0 truncate">
                <span class="font-mono">#{{ h.scriptId }}</span>
                <span v-if="h.projectName" class="ml-1.5">{{ h.projectName }}</span>
              </span>
              <TimeText :value="h.lastRunAt" relative class="text-xs text-text-muted shrink-0" />
            </li>
          </ul>
        </aside>

        <div>
          <div v-if="!selected" class="card p-12 text-center text-text-muted text-sm">
            <Share2 :size="24" class="mx-auto mb-2 opacity-50" />
            左侧选一个 script 查看各平台改造状态
          </div>

          <template v-else>
            <div class="card p-3 mb-4 flex items-center justify-between flex-wrap gap-3">
              <div class="text-sm flex items-center gap-2">
                <Share2 :size="14" class="text-accent" />
                <span class="text-text-secondary">
                  script #{{ selected.scriptId }} 在 {{ PLATFORMS.length }} 个平台的版本
                </span>
              </div>
              <button class="btn-primary"
                      @click="$router.push(`/distribute/batch?scriptId=${selected.scriptId}`)">
                <Upload :size="13" /> 一键发布到 6 平台
              </button>
            </div>

            <div class="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-3">
              <article
                v-for="p in PLATFORMS" :key="p.key"
                class="card p-4 hover:border-accent/50 cursor-pointer transition-colors"
                @click="$router.push(`/distribute/platforms/${p.key}`)"
              >
                <div class="flex items-center justify-between mb-3">
                  <div :class="['w-10 h-10 rounded-md flex items-center justify-center text-base font-semibold', p.color]">
                    {{ p.name[0] }}
                  </div>
                  <span :class="['chip text-xs', STATUS_STYLE[p.status]]">{{ STATUS_LABEL[p.status] }}</span>
                </div>
                <div class="text-sm font-semibold text-text-primary mb-2">{{ p.name }}</div>
                <div class="grid grid-cols-2 gap-2 text-xs text-text-muted mb-2">
                  <div>
                    <div>比例</div>
                    <div class="text-text-secondary mt-0.5 font-mono">{{ p.ratio }}</div>
                  </div>
                  <div>
                    <div>限时</div>
                    <div class="text-text-secondary mt-0.5 font-mono">{{ p.maxDuration }}</div>
                  </div>
                </div>
                <div class="text-xs text-text-muted leading-snug">{{ p.hookTip }}</div>
              </article>
            </div>

            <p class="text-xs text-text-muted mt-3">
              💡 点击平台卡片查看账号 / 最近发布 / 投放规则。
            </p>
          </template>
        </div>
      </div>
    </div>
  </div>
</template>
