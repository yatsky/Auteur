<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { Flame, Info, ShieldAlert } from 'lucide-vue-next'
import { listHotSources } from '../../api/hotpool'
import { splitWords } from '../../lib/format'
import {
  DEFAULT_HOT_SOURCE_CONFIG,
  type HotSource,
  type HotSourceConfig,
} from '../../types'

const props = defineProps<{
  /** preset.hotSourceConfigJson — 后端 @JsonRawValue,可能是字符串或 null */
  modelValue: string | null | undefined
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: string | null): void
}>()

const sources = ref<HotSource[]>([])
const cfg = ref<HotSourceConfig>({ ...DEFAULT_HOT_SOURCE_CONFIG })
const includeKwInput = ref('')
const excludeKwInput = ref('')

// 是否抑制内部 watch 触发回写(同步外部数据时用)
let suppressEmit = false

watch(
  () => props.modelValue,
  (raw) => {
    suppressEmit = true
    cfg.value = parseConfig(raw)
    includeKwInput.value = cfg.value.includeKeywords.join(' ')
    excludeKwInput.value = cfg.value.excludeKeywords.join(' ')
    // 下一个 tick 才打开 emit,避免 watch 同步触发回写
    queueMicrotask(() => (suppressEmit = false))
  },
  { immediate: true },
)

onMounted(async () => {
  try {
    sources.value = await listHotSources()
  } catch {
    // 静默 — 前端有 ErrorBanner 在外层,这里加载失败不阻塞预设保存
  }
})

function parseConfig(raw: string | null | undefined): HotSourceConfig {
  if (!raw) return { ...DEFAULT_HOT_SOURCE_CONFIG }
  try {
    const parsed = typeof raw === 'string' ? JSON.parse(raw) : raw
    return {
      ...DEFAULT_HOT_SOURCE_CONFIG,
      ...parsed,
      sourceIds: parsed.sourceIds ?? [],
      includeKeywords: parsed.includeKeywords ?? [],
      excludeKeywords: parsed.excludeKeywords ?? [],
      includeTags: parsed.includeTags ?? [],
    }
  } catch {
    return { ...DEFAULT_HOT_SOURCE_CONFIG }
  }
}

function commit() {
  if (suppressEmit) return
  cfg.value.includeKeywords = splitWords(includeKwInput.value)
  cfg.value.excludeKeywords = splitWords(excludeKwInput.value)
  // 真正的「未配置」 = enabled=false 且没有任何用户调过的字段。
  // 仅靠 enabled+sourceIds+keywords 判断会把 minPopularity / maxAgeHours / includeTags 调过又关订阅的用户的 tuning 抹掉。
  const isAllDefault =
    !cfg.value.enabled
    && cfg.value.sourceIds.length === 0
    && cfg.value.includeKeywords.length === 0
    && cfg.value.excludeKeywords.length === 0
    && (cfg.value.includeTags?.length ?? 0) === 0
    && cfg.value.maxAgeHours === DEFAULT_HOT_SOURCE_CONFIG.maxAgeHours
    && cfg.value.minPopularity === DEFAULT_HOT_SOURCE_CONFIG.minPopularity
  if (isAllDefault) {
    emit('update:modelValue', null)
  } else {
    emit('update:modelValue', JSON.stringify(cfg.value))
  }
}

watch(cfg, commit, { deep: true })
watch([includeKwInput, excludeKwInput], commit)

function toggleSource(id: number) {
  const i = cfg.value.sourceIds.indexOf(id)
  if (i >= 0) cfg.value.sourceIds.splice(i, 1)
  else cfg.value.sourceIds.push(id)
}

const maxAgeOptions = [
  { value: 24, label: '24h' },
  { value: 48, label: '48h' },
  { value: 24 * 7, label: '7d' },
  { value: 24 * 30, label: '30d' },
]
</script>

<template>
  <div class="space-y-4">
    <!-- 启用开关 -->
    <div class="card p-4 flex items-center justify-between gap-4">
      <div>
        <div class="text-sm font-semibold text-text-primary flex items-center gap-2">
          <Flame :size="14" class="text-accent" />
          启用热点订阅
        </div>
        <div class="text-xs text-text-muted mt-0.5">
          启动后,brainstorm 时按下方规则拉取一批热点作为种子(LLM 上下文里多一段 hot_items_context)
        </div>
      </div>
      <input
        type="checkbox"
        v-model="cfg.enabled"
        class="w-4 h-4 accent-accent"
      />
    </div>

    <div v-if="cfg.enabled" class="space-y-4">
      <!-- 订阅来源 -->
      <div class="card p-4 space-y-3">
        <div>
          <div class="text-sm font-semibold text-text-primary">订阅来源</div>
          <div class="text-xs text-text-muted mt-0.5">勾选用于本预设的源。不勾任何源 = 跑所有 enabled 源</div>
        </div>
        <div v-if="sources.length === 0" class="text-xs text-text-muted">
          还没有配置源 — 去
          <router-link to="/config#hot-sources" class="text-accent hover:underline">系统设置</router-link>
          添加
        </div>
        <div v-else class="grid grid-cols-2 gap-2">
          <label
            v-for="s in sources"
            :key="s.id"
            class="flex items-center gap-2 px-3 py-2 rounded-md cursor-pointer transition-colors"
            :class="cfg.sourceIds.includes(s.id)
              ? 'bg-accent-soft border border-accent'
              : 'bg-surface-tertiary border border-transparent hover:bg-surface-hover'"
          >
            <input
              type="checkbox"
              :checked="cfg.sourceIds.includes(s.id)"
              @change="toggleSource(s.id)"
              class="w-3.5 h-3.5 accent-accent"
            />
            <span class="text-xs flex-1 min-w-0 truncate"
                  :class="s.enabled ? 'text-text-primary' : 'text-text-muted'">
              {{ s.name }}
            </span>
            <span v-if="!s.enabled" class="text-[10px] text-text-muted">已禁用</span>
          </label>
        </div>
      </div>

      <!-- 筛选规则 -->
      <div class="card p-4 space-y-4">
        <div class="text-sm font-semibold text-text-primary">筛选规则</div>

        <div class="grid grid-cols-2 gap-4">
          <div>
            <label class="text-xs font-medium text-text-primary block mb-1.5">
              包含关键词
              <span class="text-text-muted font-normal">— 任一命中即纳入</span>
            </label>
            <textarea
              v-model="includeKwInput"
              rows="3"
              placeholder="降息 通胀 房价 ..."
              class="form-input font-mono text-xs resize-none"
            />
          </div>

          <div>
            <label class="text-xs font-medium text-text-primary block mb-1.5 flex items-center gap-1">
              <ShieldAlert :size="11" class="text-status-paused" />
              合规护栏(排除词)
              <span class="text-text-muted font-normal">— 任一命中即排除</span>
            </label>
            <textarea
              v-model="excludeKwInput"
              rows="3"
              placeholder="股票推荐 必涨 翻倍 内幕 ..."
              class="form-input font-mono text-xs resize-none !border-status-failed/30 focus:!border-status-failed"
            />
          </div>
        </div>

        <div class="grid grid-cols-2 gap-4">
          <div>
            <label class="text-xs font-medium text-text-primary block mb-1.5">时效窗口</label>
            <div class="grid grid-cols-4 gap-1">
              <button
                v-for="opt in maxAgeOptions"
                :key="opt.label"
                type="button"
                class="text-[11px] py-1.5 rounded-md transition-colors"
                :class="cfg.maxAgeHours === opt.value
                  ? 'bg-accent text-white'
                  : 'bg-surface-tertiary text-text-secondary hover:bg-surface-hover'"
                @click="cfg.maxAgeHours = opt.value"
              >{{ opt.label }}</button>
            </div>
          </div>

          <div>
            <label class="text-xs font-medium text-text-primary block mb-1.5 flex items-center justify-between">
              <span>最低热度</span>
              <span class="font-mono">{{ (cfg.minPopularity ?? 0).toFixed(2) }}</span>
            </label>
            <input
              type="range"
              v-model.number="cfg.minPopularity"
              min="0"
              max="1"
              step="0.05"
              class="w-full accent-accent"
            />
          </div>
        </div>
      </div>

      <!-- 触发说明 -->
      <div class="card p-3 bg-accent-soft/40 border-accent/20 flex items-start gap-2">
        <Info :size="13" class="text-accent mt-0.5 shrink-0" />
        <div class="text-xs text-text-secondary leading-relaxed">
          <strong>无需调度。</strong>每次「生成选题」(brainstorm)时按上方规则手动拉取一批热点;
          也可在「热点池」页随时点立即抓取。
          <br />
          在 brainstorm prompt 里用
          <code v-pre class="bg-surface-tertiary px-1 py-0.5 rounded text-[10px]">{{ hot_items_context }}</code>
          引用拼好的素材段。
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.form-input {
  @apply w-full px-3 py-1.5 text-sm bg-surface-primary border border-border rounded-md
         focus:outline-none focus:border-accent;
}
</style>
