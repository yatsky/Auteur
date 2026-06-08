<script setup lang="ts">
import {
  Layers,
  LayoutDashboard, Lightbulb, FileText, Search, Film, Image,
  Mic, Share2, BarChart3,
  FileSpreadsheet, GitCompare,
  Sparkles,
  Layers as LayersIcon, Film as FilmIcon,
  Wand2,
  Settings,
  Sun, Moon,
  ShieldCheck,
} from 'lucide-vue-next'
import { useRoute } from 'vue-router'
import { useTheme } from '../composables/useTheme'
import { adminMode, ownerName } from '../lib/admin'

const { theme, toggle } = useTheme()
const route = useRoute()

interface NavItem {
  to?: string
  icon: any
  label: string
  hint?: string
  matchPaths?: string[]  // 哪些 path 前缀算 active
  adminOnly?: boolean    // 仅 admin 模式可见
}

interface NavGroup {
  title: string
  items: NavItem[]
}

// 做减法之后只剩两组:创作 + 复盘。工具/配置/其它整组移除。
const navGroups: NavGroup[] = [
  {
    title: '创作',
    items: [
      { to: '/home', icon: LayoutDashboard, label: '工作台' },
      { to: '/topics', icon: Lightbulb, label: '选题池', matchPaths: ['/topics'] },
      { to: '/scripts', icon: FileText, label: '脚本工作台', matchPaths: ['/scripts'] },
      { to: '/factcheck', icon: Search, label: '事实核查', matchPaths: ['/factcheck'] },
      { to: '/voice', icon: Mic, label: '配音字幕' },
      { to: '/storyboard', icon: Film, label: '分镜工作台', matchPaths: ['/storyboard'] },
      { to: '/images', icon: Image, label: '生图工作台', matchPaths: ['/images'] },
      { to: '/assembly', icon: FilmIcon, label: '视频组装', matchPaths: ['/assembly'] },
      { to: '/cover', icon: Wand2, label: '封面生成', matchPaths: ['/cover'] },
      { to: '/distribute', icon: Share2, label: '多平台分发', matchPaths: ['/distribute'] },
    ],
  },
  {
    title: '复盘',
    items: [
      { to: '/insights', icon: Sparkles, label: '数据洞察', matchPaths: ['/insights'], hint: 'AI' },
      { to: '/published-videos', icon: FileSpreadsheet, label: '已发布视频', matchPaths: ['/published-videos'] },
      { to: '/genre-stats', icon: BarChart3, label: '体裁基准', matchPaths: ['/genre-stats'] },
      { to: '/dashboard', icon: BarChart3, label: '数据看板' },
      { to: '/review/weekly', icon: FileSpreadsheet, label: '周复盘', matchPaths: ['/review/weekly'] },
      { to: '/review/compare', icon: GitCompare, label: '跨视频对比', matchPaths: ['/review/compare'] },
      { to: '/series', icon: LayersIcon, label: '系列视图' },
    ],
  },
  // 配置组(预设管理),公开预设始终可见 — 私有预设需 admin 模式才能编辑/新建
  {
    title: '配置',
    items: [
      { to: '/presets', icon: Layers, label: '预设库', matchPaths: ['/presets'] },
      { to: '/config', icon: Settings, label: '系统设置', matchPaths: ['/config'] },
    ],
  },
]

function isActive(item: NavItem): boolean {
  if (!item.to) return false
  if (route.path === item.to) return true
  if (item.matchPaths) {
    return item.matchPaths.some((p) => route.path.startsWith(p + '/') || route.path === p)
  }
  return false
}
</script>

<template>
  <div class="flex h-full">
    <aside class="w-60 shrink-0 bg-surface-secondary border-r border-border-subtle flex flex-col">
      <div class="px-5 py-5 border-b border-border-subtle flex items-center gap-2">
        <div class="w-8 h-8 rounded-md bg-accent flex items-center justify-center">
          <Layers :size="18" class="text-white" />
        </div>
        <div class="flex-1 min-w-0">
          <div class="text-base font-semibold text-text-primary leading-tight">Auteur</div>
          <div class="text-xs text-text-muted">控制台</div>
        </div>
        <!-- 全局 admin 标记。任何页面都能看到当前是否启用 admin 模式 -->
        <a
          v-if="adminMode"
          href="/admin"
          @click.prevent="$router.push('/admin')"
          class="chip text-[10px] bg-accent-soft text-accent flex items-center gap-1 cursor-pointer hover:opacity-80"
          :title="`admin 模式已启用 (owner: ${ownerName || '我'}) — 点击进入开关页`"
        >
          <ShieldCheck :size="10" /> admin
        </a>
        <a
          v-else
          href="/admin"
          @click.prevent="$router.push('/admin')"
          class="chip text-[10px] bg-surface-tertiary text-text-muted cursor-pointer hover:text-text-primary"
          title="点击启用 admin 模式"
        >
          仅访客
        </a>
      </div>

      <nav class="flex-1 px-3 py-4 overflow-y-auto">
        <div v-for="(group, gIdx) in navGroups" :key="group.title" :class="gIdx > 0 ? 'mt-5' : ''">
          <div class="px-3 mb-1.5 text-[10px] uppercase tracking-wider text-text-muted">
            {{ group.title }}
          </div>
          <div class="space-y-0.5">
            <template v-for="(item, idx) in group.items" :key="idx">
              <a
                v-if="item.to"
                :href="item.to"
                @click.prevent="$router.push(item.to)"
                :class="[
                  'flex items-center gap-3 px-3 py-2 rounded-md text-sm cursor-pointer transition-colors',
                  isActive(item)
                    ? 'bg-accent-soft text-accent'
                    : 'text-text-secondary hover:bg-surface-tertiary hover:text-text-primary',
                ]"
              >
                <component :is="item.icon" :size="16" />
                <span class="flex-1">{{ item.label }}</span>
                <span v-if="item.hint" class="text-[9px] uppercase text-text-muted">{{ item.hint }}</span>
              </a>
            </template>
          </div>
        </div>
      </nav>

      <div class="px-3 py-3 border-t border-border-subtle space-y-2">
        <button
          class="w-full flex items-center gap-2 px-3 py-2 rounded-md text-sm text-text-secondary hover:bg-surface-tertiary hover:text-text-primary transition-colors"
          :title="theme === 'dark' ? '切到浅色模式' : '切到深色模式'"
          @click="toggle"
        >
          <Sun v-if="theme === 'dark'" :size="16" />
          <Moon v-else :size="16" />
          <span class="flex-1 text-left">{{ theme === 'dark' ? '浅色模式' : '深色模式' }}</span>
          <span class="text-[9px] uppercase text-text-muted">{{ theme }}</span>
        </button>
      </div>
    </aside>

    <main class="flex-1 overflow-auto">
      <router-view />
    </main>
  </div>
</template>
