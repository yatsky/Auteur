<script setup lang="ts">
// PlatformDetailPage —— sticky 顶栏 + 筛选 + KPI + 图表占位 + Top 视频
// 全 mock，等接口
import { computed, ref } from 'vue'
import { ArrowLeft, AtSign, ExternalLink, Settings as SettingsIcon, TrendingUp } from 'lucide-vue-next'

const props = defineProps<{ platform: string }>()

const SPEC: Record<string, { name: string; color: string; ratio: string; maxDuration: string; hookTip: string; rules: string[] }> = {
  douyin: {
    name: '抖音', color: 'bg-pink-500/15 text-pink-500',
    ratio: '9:16', maxDuration: '180s', hookTip: '前 3s 必须有强钩子',
    rules: ['封面必须 9:16 竖图', '标题 ≤ 28 字，含 1-2 个 # 标签', '严禁出现 "关注我"，使用 "评论你的看法"', '历史敏感词需查 v2.4 词库'],
  },
  bilibili: {
    name: 'B站', color: 'bg-cyan-500/15 text-cyan-500',
    ratio: '16:9', maxDuration: '600s', hookTip: '可上长版本 + 章节标记',
    rules: ['横屏 16:9，封面 1146x717', '简介前 100 字最关键', '上传分章节标记', '标签控制在 8-10 个'],
  },
  xiaohongshu: {
    name: '小红书', color: 'bg-rose-500/15 text-rose-500',
    ratio: '4:5', maxDuration: '60s', hookTip: '强引导评论',
    rules: ['竖屏 4:5 优先', '正文 200-500 字 + 表情符', '加 5-8 个相关 # 话题', '结尾问句引导评论'],
  },
  'wechat-channels': {
    name: '视频号', color: 'bg-emerald-500/15 text-emerald-500',
    ratio: '9:16', maxDuration: '60s', hookTip: '社交分享导向',
    rules: ['9:16，时长尽量 ≤ 60s', '加位置 / 公众号绑定', '@ 1-2 个相关账号增加曝光', '分享话术写「转给历史迷」'],
  },
  kuaishou: {
    name: '快手', color: 'bg-orange-500/15 text-orange-500',
    ratio: '9:16', maxDuration: '300s', hookTip: '草根质感+反转',
    rules: ['封面要素脸 + 字大', '标题反转钩子', '配音用方言版本', '挂老铁互动话题'],
  },
  xigua: {
    name: '西瓜视频', color: 'bg-amber-500/15 text-amber-500',
    ratio: '16:9', maxDuration: '600s', hookTip: '中长视频优势',
    rules: ['16:9 横屏', '同步今日头条', '标题 SEO 关键词靠前', '可投信息流广告（成本看板配置）'],
  },
}

const spec = computed(() => SPEC[props.platform] ?? null)

const range = ref<'7d' | '30d' | '90d'>('7d')

// mock KPI + 账号 + 发布
const mockAccount = {
  handle: '@悬案号',
  followers: 18420,
  bindAt: '2025-12-04',
  status: '已绑定',
}

const mockKpi = {
  totalPublished: 28,
  thisWeek: 4,
  avgViews7d: 12340,
  avgEngagement: '3.8%',
}

const mockPublishes = [
  { id: 1, title: '朱元璋之死的三个版本，史官不敢写', publishedAt: '2026-05-22 09:30', views: 23410, likes: 892, status: '已发布' },
  { id: 2, title: '崇祯遗诏被改了 2 次，真相藏在第 17 字', publishedAt: '2026-05-20 09:30', views: 18002, likes: 612, status: '已发布' },
  { id: 3, title: '武则天的真实死因（待发布）', publishedAt: '2026-05-23 18:00', views: null, likes: null, status: '排程中' },
]
</script>

<template>
  <div class="min-h-full">
    <!-- sticky 顶栏 -->
    <div class="sticky top-0 z-10 bg-surface-primary border-b border-border-subtle">
      <div class="px-8 py-3 max-w-[1400px] mx-auto flex flex-col gap-1">
        <div class="flex items-center gap-4 flex-wrap">
          <button
            class="flex items-center gap-1.5 text-sm text-text-muted hover:text-text-primary"
            @click="$router.push('/distribute')"
          >
            <ArrowLeft :size="14" /> 返回多平台
          </button>
          <h1 class="text-lg font-semibold flex items-center gap-2">
            <span v-if="spec" :class="['w-7 h-7 rounded flex items-center justify-center text-sm font-semibold', spec.color]">
              {{ spec.name[0] }}
            </span>
            {{ spec?.name ?? platform }}
          </h1>
          <span v-if="spec" class="chip text-[11px] bg-surface-tertiary text-text-muted font-mono">
            {{ spec.ratio }} · {{ spec.maxDuration }}
          </span>
        </div>
        <div v-if="spec" class="text-xs text-text-muted">{{ spec.hookTip }} · 平台 key {{ platform }}</div>
      </div>
    </div>

    <div class="px-8 py-5 max-w-[1400px] mx-auto">
      <div v-if="!spec" class="card p-12 text-center text-text-muted text-sm">
        未知平台 key：{{ platform }}
      </div>

      <template v-else>
        <div class="card p-3 mb-4 bg-status-paused/10 border-status-paused/30 text-xs text-status-paused">
          ⚠️ 数据为 mock —— 后端账号 / 发布 API 接通后自动替换
        </div>

        <!-- 时间范围筛选 -->
        <div class="card p-3 mb-4 flex items-center gap-2 flex-wrap">
          <span class="text-xs text-text-muted">时间范围</span>
          <button
            v-for="r in (['7d', '30d', '90d'] as const)" :key="r"
            :class="['chip cursor-pointer text-xs', range === r ? 'bg-accent-soft text-accent' : 'bg-surface-tertiary text-text-secondary']"
            @click="range = r"
          >
            {{ r === '7d' ? '近 7 天' : r === '30d' ? '近 30 天' : '近 90 天' }}
          </button>
        </div>

        <!-- KPI -->
        <div class="grid grid-cols-2 md:grid-cols-4 gap-3 mb-4">
          <div class="card p-4">
            <div class="text-xs text-text-muted mb-1">已发布</div>
            <div class="text-2xl font-mono font-semibold">{{ mockKpi.totalPublished }}</div>
          </div>
          <div class="card p-4">
            <div class="text-xs text-text-muted mb-1">本周</div>
            <div class="text-2xl font-mono font-semibold text-status-running">{{ mockKpi.thisWeek }}</div>
          </div>
          <div class="card p-4">
            <div class="text-xs text-text-muted mb-1">7d 平均播放</div>
            <div class="text-2xl font-mono font-semibold">{{ mockKpi.avgViews7d.toLocaleString() }}</div>
          </div>
          <div class="card p-4">
            <div class="text-xs text-text-muted mb-1">7d 互动率</div>
            <div class="text-2xl font-mono font-semibold text-status-done">{{ mockKpi.avgEngagement }}</div>
          </div>
        </div>

        <!-- 主体:左 Top 视频 + 账号/规则 -->
        <div class="grid grid-cols-1 lg:grid-cols-[1fr_360px] gap-4">
          <div class="card p-5">
            <h2 class="text-base font-semibold mb-3 flex items-center gap-2">
              <TrendingUp :size="16" class="text-accent" /> 最近发布
            </h2>
            <table class="w-full text-sm">
              <thead class="text-xs uppercase text-text-muted border-b border-border-subtle">
                <tr>
                  <th class="text-left py-2 font-medium">标题</th>
                  <th class="text-right py-2 font-medium">播放</th>
                  <th class="text-right py-2 font-medium">点赞</th>
                  <th class="text-right py-2 font-medium">状态</th>
                  <th class="text-right py-2 font-medium">时间</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="p in mockPublishes" :key="p.id" class="border-t border-border-subtle hover:bg-surface-tertiary/30">
                  <td class="py-2 pr-2 truncate max-w-[280px]">{{ p.title }}</td>
                  <td class="py-2 text-right font-mono text-text-secondary">{{ p.views?.toLocaleString() ?? '-' }}</td>
                  <td class="py-2 text-right font-mono text-text-secondary">{{ p.likes ?? '-' }}</td>
                  <td class="py-2 text-right">
                    <span :class="['chip', p.status === '已发布' ? 'bg-status-done/15 text-status-done' : 'bg-status-paused/15 text-status-paused']">
                      {{ p.status }}
                    </span>
                  </td>
                  <td class="py-2 text-right text-xs text-text-muted font-mono">{{ p.publishedAt }}</td>
                </tr>
              </tbody>
            </table>
          </div>

          <div class="space-y-3">
            <div class="card p-5">
              <h2 class="text-base font-semibold mb-3 flex items-center gap-2">
                <AtSign :size="16" class="text-accent" /> 账号
              </h2>
              <div class="flex items-center gap-3 mb-2">
                <div :class="['w-12 h-12 rounded-full flex items-center justify-center text-base font-semibold', spec.color]">
                  {{ spec.name[0] }}
                </div>
                <div class="flex-1 min-w-0">
                  <div class="text-sm font-mono">{{ mockAccount.handle }}</div>
                  <div class="text-xs text-text-muted">{{ mockAccount.followers.toLocaleString() }} 粉丝</div>
                </div>
                <span class="chip bg-status-done/15 text-status-done">{{ mockAccount.status }}</span>
              </div>
              <div class="text-xs text-text-muted">绑定于 {{ mockAccount.bindAt }}</div>
              <button class="btn-ghost mt-3 w-full" disabled>
                <ExternalLink :size="14" /> 重新绑定（API 待接）
              </button>
            </div>

            <div class="card p-5">
              <h2 class="text-base font-semibold mb-3 flex items-center gap-2">
                <SettingsIcon :size="16" class="text-accent" /> 投放规则
              </h2>
              <ul class="space-y-2 text-sm">
                <li v-for="(r, i) in spec.rules" :key="i" class="flex gap-2 text-text-secondary">
                  <span class="text-accent">·</span>
                  <span class="leading-relaxed">{{ r }}</span>
                </li>
              </ul>
              <div class="text-xs text-text-muted mt-3 pt-3 border-t border-border-subtle">
                钩子提示：{{ spec.hookTip }}
              </div>
            </div>
          </div>
        </div>
      </template>
    </div>
  </div>
</template>
