<script setup lang="ts">
// FulfillHookDialog —— 钩子兑现编辑器。点 banner「填坑」打开,预填 LLM 建议,
// 用户改完点确认 → 调 fulfillHook → emit confirmed(topic)。
//
// 设计要点:
//   - title 必填,LLM 偶尔脑补(过度解读"意外火灾"为"自焚"等),给用户改的机会
//   - dynasty 预填 LLM 建议,用户可改;不限制必填(钩子可能跨朝代/灵异)
//   - hookType 5 个固定值(对齐 brainstorm.yaml):反逻辑 / 数字冲击 / 时间地点反常 / 未解之谜 / 反差身份
//   - durationMinutes 默认 6;historicalReference 留给用户填史料锚点(可空)
//   - hookText 全文展示在顶部供用户参考钩子原话,不允许编辑(改钩子内容会破坏溯源)
import { computed, ref, watch } from 'vue'
import { Loader2, Sparkles, X } from 'lucide-vue-next'
import { fulfillHook } from '../api/seriesHooks'
import { extractError } from '../lib/format'
import type { SeriesHook, Topic } from '../types'

const props = defineProps<{ hook: SeriesHook | null }>()
const emit = defineEmits<{
  (e: 'close'): void
  (e: 'confirmed', topic: Topic, autoGen: boolean): void
}>()

const HOOK_TYPES = ['反逻辑', '数字冲击', '时间地点反常', '未解之谜', '反差身份']

const title = ref('')
const dynasty = ref('')
const hookType = ref('')
const durationMinutes = ref<number>(6)
const historicalReference = ref('')
// 确认后立即触发后端 generateScript 异步链路 —— 默认勾选,
// 用户审完直接出脚本,省去手动跑回详情页点按钮的两步跳转。
const autoGenerateScript = ref(true)

const busy = ref(false)
const error = ref<string | null>(null)

const open = computed(() => props.hook !== null)

// 打开时预填 LLM 建议
watch(() => props.hook, (h) => {
  if (!h) return
  title.value = h.suggestedTitle || ''
  dynasty.value = h.suggestedDynasty || ''
  hookType.value = ''
  durationMinutes.value = 6
  historicalReference.value = ''
  autoGenerateScript.value = true
  error.value = null
}, { immediate: true })

async function submit() {
  const h = props.hook
  if (!h) return
  if (!title.value.trim()) {
    error.value = '标题必填'
    return
  }
  busy.value = true
  error.value = null
  try {
    const resp = await fulfillHook(h.id, {
      title: title.value.trim(),
      dynasty: dynasty.value.trim() || null,
      hookType: hookType.value || null,
      durationMinutes: durationMinutes.value || null,
      historicalReference: historicalReference.value.trim() || null,
    })
    emit('confirmed', resp.topic, autoGenerateScript.value)
  } catch (e: any) {
    error.value = extractError(e, '建 topic 失败')
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div
    v-if="open"
    class="fixed inset-0 z-50 bg-black/60 flex items-center justify-center p-4"
    @click.self="!busy && emit('close')"
  >
    <div class="card p-6 max-w-[640px] w-full max-h-[90vh] overflow-y-auto">
      <header class="flex items-center justify-between mb-4">
        <h2 class="text-lg font-semibold flex items-center gap-2">
          <Sparkles :size="18" class="text-accent" /> 兑现钩子,建下一集 topic
        </h2>
        <button class="text-text-muted hover:text-text-primary"
                :disabled="busy" @click="emit('close')">
          <X :size="20" />
        </button>
      </header>

      <div v-if="hook" class="card p-3 mb-4 bg-surface-tertiary text-xs">
        <div class="text-text-muted mb-1">钩子原文(来自 {{ hook.fromScriptId }} 的 E 段):</div>
        <div class="text-text-secondary leading-relaxed line-clamp-4">{{ hook.hookText }}</div>
        <div v-if="hook.nextEpisodeHint" class="text-text-muted mt-2">
          LLM 总结:{{ hook.nextEpisodeHint }}
        </div>
      </div>

      <div v-if="error" class="card p-3 mb-3 bg-status-failed/10 border-status-failed/30 text-xs text-status-failed">
        {{ error }}
      </div>

      <div class="grid grid-cols-1 gap-3 text-sm">
        <label class="flex flex-col gap-1">
          <span class="text-xs text-text-muted">标题 *(LLM 偶尔脑补,审一下)</span>
          <input v-model="title" type="text" maxlength="200"
                 :disabled="busy"
                 placeholder="例:方皇后与端妃旧账,及她死于火灾的真相"
                 class="bg-surface-tertiary border border-border-subtle rounded px-2 py-1.5" />
        </label>

        <div class="grid grid-cols-2 gap-3">
          <label class="flex flex-col gap-1">
            <span class="text-xs text-text-muted">朝代</span>
            <input v-model="dynasty" type="text" maxlength="40"
                   :disabled="busy"
                   placeholder="例:明 / 清 / 灵异"
                   class="bg-surface-tertiary border border-border-subtle rounded px-2 py-1.5" />
          </label>
          <label class="flex flex-col gap-1">
            <span class="text-xs text-text-muted">钩子类型</span>
            <select v-model="hookType" :disabled="busy"
                    class="bg-surface-tertiary border border-border-subtle rounded px-2 py-1.5">
              <option value="">— 不指定 —</option>
              <option v-for="t in HOOK_TYPES" :key="t" :value="t">{{ t }}</option>
            </select>
          </label>
        </div>

        <label class="flex flex-col gap-1">
          <span class="text-xs text-text-muted">时长(分钟)</span>
          <input v-model.number="durationMinutes" type="number" min="1" max="20"
                 :disabled="busy"
                 class="bg-surface-tertiary border border-border-subtle rounded px-2 py-1.5 font-mono w-32" />
        </label>

        <label class="flex flex-col gap-1">
          <span class="text-xs text-text-muted">史料锚点(可选,贴一段原文 LLM 写脚本时引用)</span>
          <textarea v-model="historicalReference" rows="3"
                    :disabled="busy"
                    placeholder="留空也行,稍后在 topic 详情里补"
                    class="bg-surface-tertiary border border-border-subtle rounded px-2 py-1.5 font-sans resize-y" />
        </label>

        <label class="flex items-center gap-2 mt-1">
          <input v-model="autoGenerateScript" type="checkbox" :disabled="busy" />
          <span class="text-xs text-text-secondary">
            确认后立即生成脚本(异步,跳详情页看进度。LLM ~30s)
          </span>
        </label>
      </div>

      <div class="flex items-center gap-2 mt-5">
        <button class="chip cursor-pointer bg-surface-tertiary text-text-secondary text-sm"
                :disabled="busy" @click="emit('close')">
          取消
        </button>
        <button class="btn-primary ml-auto" :disabled="busy" @click="submit">
          <Loader2 v-if="busy" :size="14" class="animate-spin" />
          <Sparkles v-else :size="14" />
          {{ busy ? '建 topic 中…' : '确认兑现' }}
        </button>
      </div>
    </div>
  </div>
</template>
