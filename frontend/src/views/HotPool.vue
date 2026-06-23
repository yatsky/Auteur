<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  CheckSquare, Flame, Loader2, RefreshCw, Settings, ShieldAlert, Sparkles,
} from 'lucide-vue-next'
import { fetchHotNow, listHotItems, listHotSources } from '../api/hotpool'
import { listPresets } from '../api/presets'
import type { Preset } from '../api/presets'
import { extractError, splitWords } from '../lib/format'
import { useAsyncLoad } from '../composables/useAsyncLoad'
import HotItemCard from '../components/hotpool/HotItemCard.vue'
import HotItemDetailDrawer from '../components/hotpool/HotItemDetailDrawer.vue'
import PromoteApprovalCard from '../components/hotpool/PromoteApprovalCard.vue'
import ErrorBanner from '../components/ErrorBanner.vue'
import type { HotItem, HotItemStatus, HotSource } from '../types'

// ---------- 数据 ----------
const items = ref<HotItem[]>([])
const sources = ref<HotSource[]>([])
const presets = ref<Preset[]>([])

// ---------- 过滤状态 ----------
const status = ref<HotItemStatus | 'all'>('new')
const selectedSourceIds = ref<number[]>([])
const includeKwInput = ref('')
const excludeKwInput = ref('')
const maxAgeOptions = [
  { value: 24, label: '24h' },
  { value: 48, label: '48h' },
  { value: 24 * 7, label: '7d' },
  { value: null as number | null, label: '全部' },
]
const maxAgeHours = ref<number | null>(48)
const minPopularity = ref(0.3)

// ---------- 选中 ----------
const selectedIds = ref<Set<number>>(new Set())

// ---------- 弹窗 ----------
const detailItem = ref<HotItem | null>(null)
const promoteOpen = ref(false)
const promoteCandidates = ref<HotItem[]>([])

// 抓取状态
const fetching = ref(false)
const fetchMsg = ref<string | null>(null)
const fetchError = ref<string | null>(null)

const sourceById = computed(() => {
  const m = new Map<number, HotSource>()
  sources.value.forEach((s) => m.set(s.id, s))
  return m
})

const includeKeywords = computed(() => splitWords(includeKwInput.value))
const excludeKeywords = computed(() => splitWords(excludeKwInput.value))

// ---------- 加载 ----------
const itemsLoad = useAsyncLoad(async () => {
  items.value = await listHotItems({
    status: status.value,
    sourceIds: selectedSourceIds.value.length ? selectedSourceIds.value : undefined,
    includeKeywords: includeKeywords.value.length ? includeKeywords.value : undefined,
    excludeKeywords: excludeKeywords.value.length ? excludeKeywords.value : undefined,
    maxAgeHours: maxAgeHours.value ?? undefined,
    minPopularity: minPopularity.value > 0 ? minPopularity.value : undefined,
    limit: 100,
  })
}, { errorPrefix: '加载热点失败' })

async function loadSources() {
  sources.value = await listHotSources()
}

async function loadPresets() {
  presets.value = await listPresets()
}

onMounted(async () => {
  await Promise.all([loadSources().catch(() => {}), loadPresets().catch(() => {})])
  await itemsLoad.run()
})

function refresh() {
  selectedIds.value = new Set()
  itemsLoad.run()
}

async function doFetch() {
  fetching.value = true
  fetchMsg.value = null
  fetchError.value = null
  try {
    const enabledIds = selectedSourceIds.value.length
      ? selectedSourceIds.value
      : sources.value.filter((s) => s.enabled).map((s) => s.id)
    const results = await fetchHotNow(enabledIds)
    const total = results.reduce((sum, r) => sum + r.inserted, 0)
    const failed = results.filter((r) => r.error)
    fetchMsg.value = failed.length === 0
      ? `抓取完成,新增 ${total} 条`
      : `抓取完成,新增 ${total} 条;${failed.length} 个源失败(见源管理)`
    await Promise.all([loadSources(), itemsLoad.run()])
  } catch (e) {
    fetchError.value = extractError(e, '抓取失败')
  } finally {
    fetching.value = false
  }
}

// ---------- 选中操作 ----------
function toggleSelect(id: number) {
  if (selectedIds.value.has(id)) selectedIds.value.delete(id)
  else selectedIds.value.add(id)
  // Vue 不能感知 Set 变更,触发响应式刷新
  selectedIds.value = new Set(selectedIds.value)
}

function openPromote(targets: HotItem[]) {
  if (targets.length === 0) return
  promoteCandidates.value = targets
  promoteOpen.value = true
}

function onPromoteDone(topicIds: number[]) {
  promoteOpen.value = false
  selectedIds.value = new Set()
  fetchMsg.value = `已送入 ${topicIds.length} 条选题`
  itemsLoad.run()
}

function onItemPromoted(item: HotItem, topicId: number) {
  detailItem.value = null
  fetchMsg.value = `「${item.title.slice(0, 20)}…」已送入选题 (id=${topicId})`
  itemsLoad.run()
}

function onItemDismissed(item: HotItem) {
  detailItem.value = null
  fetchMsg.value = `「${item.title.slice(0, 20)}…」已忽略`
  itemsLoad.run()
}

function toggleSourceFilter(id: number) {
  const i = selectedSourceIds.value.indexOf(id)
  if (i >= 0) selectedSourceIds.value.splice(i, 1)
  else selectedSourceIds.value.push(id)
  refresh()
}
</script>

<template>
  <div class="min-h-screen flex">
    <!-- 左侧筛选栏 -->
    <aside class="w-60 border-r border-border-subtle bg-surface-secondary p-4 space-y-5 shrink-0">
      <!-- 状态切换 -->
      <div>
        <div class="text-[11px] font-semibold text-text-muted tracking-wide mb-2">状态</div>
        <div class="flex flex-wrap gap-1">
          <button
            v-for="opt in [
              { v: 'new' as const, l: '待处理' },
              { v: 'promoted' as const, l: '已送选题' },
              { v: 'dismissed' as const, l: '已忽略' },
              { v: 'all' as const, l: '全部' },
            ]"
            :key="opt.v"
            class="chip text-[11px]"
            :class="status === opt.v
              ? 'bg-accent text-white'
              : 'bg-surface-tertiary text-text-secondary hover:bg-surface-hover'"
            @click="status = opt.v; refresh()"
          >{{ opt.l }}</button>
        </div>
      </div>

      <!-- 来源 -->
      <div>
        <div class="text-[11px] font-semibold text-text-muted tracking-wide mb-2">来源</div>
        <div class="space-y-1">
          <label
            v-for="s in sources"
            :key="s.id"
            class="flex items-center gap-2 px-2 py-1.5 rounded-md cursor-pointer hover:bg-surface-hover"
            :class="selectedSourceIds.includes(s.id) ? 'bg-accent-soft' : ''"
          >
            <input
              type="checkbox"
              :checked="selectedSourceIds.includes(s.id)"
              @change="toggleSourceFilter(s.id)"
              class="w-3.5 h-3.5 accent-accent"
            />
            <span class="text-xs flex-1 min-w-0 truncate" :class="s.enabled ? 'text-text-primary' : 'text-text-muted'">
              {{ s.name }}
            </span>
            <span v-if="s.lastFetchError" class="w-1.5 h-1.5 rounded-full bg-status-failed" title="上次抓取失败"></span>
          </label>
          <div v-if="sources.length === 0" class="text-xs text-text-muted px-2 py-2">
            未配置源 — 去 <router-link to="/config#hot-sources" class="text-accent hover:underline">系统设置</router-link>
          </div>
        </div>
      </div>

      <!-- 包含关键词 -->
      <div>
        <div class="text-[11px] font-semibold text-text-muted tracking-wide mb-2">包含关键词</div>
        <textarea
          v-model="includeKwInput"
          rows="2"
          placeholder="降息 通胀 房价 ..."
          class="w-full bg-surface-primary border border-border rounded-md px-2 py-1.5 text-xs resize-none focus:outline-none focus:ring-2 focus:ring-accent/30 focus:border-accent"
          @blur="refresh"
        />
      </div>

      <!-- 排除关键词(合规护栏) -->
      <div>
        <div class="flex items-center gap-1 text-[11px] font-semibold text-text-muted tracking-wide mb-2">
          <ShieldAlert :size="12" class="text-status-paused" />
          合规护栏(排除词)
        </div>
        <textarea
          v-model="excludeKwInput"
          rows="2"
          placeholder="股票推荐 必涨 翻倍 ..."
          class="w-full bg-surface-primary border border-status-failed/30 rounded-md px-2 py-1.5 text-xs resize-none focus:outline-none focus:ring-2 focus:ring-status-failed/20 focus:border-status-failed"
          @blur="refresh"
        />
      </div>

      <!-- 时效 -->
      <div>
        <div class="text-[11px] font-semibold text-text-muted tracking-wide mb-2">时效</div>
        <div class="grid grid-cols-4 gap-1">
          <button
            v-for="opt in maxAgeOptions"
            :key="opt.label"
            class="text-[11px] py-1 rounded-md transition-colors"
            :class="maxAgeHours === opt.value
              ? 'bg-accent text-white'
              : 'bg-surface-tertiary text-text-secondary hover:bg-surface-hover'"
            @click="maxAgeHours = opt.value; refresh()"
          >{{ opt.label }}</button>
        </div>
      </div>

      <!-- 最低热度 -->
      <div>
        <div class="flex items-center justify-between text-[11px] font-semibold text-text-muted tracking-wide mb-2">
          <span>最低热度</span>
          <span class="text-text-primary">{{ minPopularity.toFixed(2) }}</span>
        </div>
        <input
          type="range"
          v-model.number="minPopularity"
          min="0"
          max="1"
          step="0.05"
          class="w-full accent-accent"
          @change="refresh"
        />
      </div>
    </aside>

    <!-- 主区 -->
    <main class="flex-1 min-w-0 p-6 space-y-4">
      <!-- 顶栏 -->
      <div class="flex items-center justify-between gap-4">
        <div class="flex items-center gap-2">
          <Flame :size="20" class="text-accent" />
          <h1 class="text-xl font-semibold text-text-primary">热点池</h1>
          <span class="chip bg-surface-tertiary text-text-muted text-[11px]">
            {{ items.length }} 条
          </span>
        </div>
        <div class="flex items-center gap-2">
          <button class="btn-ghost text-sm" :disabled="fetching" @click="doFetch">
            <RefreshCw :size="14" :class="fetching ? 'animate-spin' : ''" />
            {{ fetching ? '抓取中…' : '立即抓取' }}
          </button>
          <router-link to="/config#hot-sources" class="btn-primary text-sm">
            <Settings :size="14" />
            源管理
          </router-link>
        </div>
      </div>

      <!-- 反馈条 -->
      <div v-if="fetchMsg" class="card p-3 text-xs text-text-secondary flex items-center gap-2">
        <span class="w-1.5 h-1.5 rounded-full bg-status-done"></span>
        {{ fetchMsg }}
      </div>
      <ErrorBanner v-if="fetchError" :msg="fetchError" />

      <!-- 工具栏:批量操作 -->
      <div class="flex items-center justify-between gap-3">
        <div class="flex items-center gap-2">
          <div
            v-if="selectedIds.size > 0"
            class="chip bg-accent-soft text-accent text-xs px-3 py-1"
          >
            <CheckSquare :size="13" />
            已选 {{ selectedIds.size }} 条
          </div>
          <button
            v-if="selectedIds.size > 0"
            class="btn-primary text-sm"
            @click="openPromote(items.filter(i => selectedIds.has(i.id)))"
          >
            <Sparkles :size="14" />批量送选题
          </button>
        </div>
      </div>

      <!-- 列表 -->
      <div v-if="itemsLoad.loading.value && items.length === 0" class="flex items-center justify-center py-20 text-text-muted text-sm">
        <Loader2 :size="20" class="animate-spin mr-2" />加载中…
      </div>
      <ErrorBanner v-else-if="itemsLoad.errorMsg.value" :msg="itemsLoad.errorMsg.value" />
      <div
        v-else-if="items.length === 0"
        class="card p-12 text-center text-sm text-text-muted"
      >
        <Flame :size="32" class="mx-auto mb-3 opacity-40" />
        <div class="font-medium text-text-secondary mb-1">暂无符合筛选的热点</div>
        <div class="text-xs">试着放宽时效或排除词,或现在拉取一次</div>
        <button class="btn-primary text-sm mt-4" :disabled="fetching" @click="doFetch">
          <RefreshCw :size="14" :class="fetching ? 'animate-spin' : ''" />立即抓取
        </button>
      </div>
      <div v-else class="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4">
        <HotItemCard
          v-for="it in items"
          :key="it.id"
          :item="it"
          :source="sourceById.get(it.sourceId)"
          :selected="selectedIds.has(it.id)"
          @toggle-select="toggleSelect"
          @open-detail="(i) => (detailItem = i)"
          @promote="(i) => openPromote([i])"
        />
      </div>
    </main>

    <!-- 抽屉 / 弹窗 -->
    <HotItemDetailDrawer
      :open="detailItem !== null"
      :item="detailItem"
      :source="detailItem ? sourceById.get(detailItem.sourceId) : undefined"
      :presets="presets"
      @close="detailItem = null"
      @promoted="onItemPromoted"
      @dismissed="onItemDismissed"
    />
    <PromoteApprovalCard
      :open="promoteOpen"
      :items="promoteCandidates"
      :sources="sources"
      :presets="presets"
      @close="promoteOpen = false"
      @done="onPromoteDone"
    />
  </div>
</template>
