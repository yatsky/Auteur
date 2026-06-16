<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowLeft, Loader2, Save, Cpu, RotateCcw } from 'lucide-vue-next'
import { listAppConfig, updateAppConfig, type AppConfigItem } from '../api/appConfig'
import { extractError } from '../lib/format'
import ErrorBanner from '../components/ErrorBanner.vue'

const router = useRouter()
const items = ref<AppConfigItem[]>([])
const loading = ref(false)
const saving = ref(false)
const errorMsg = ref<string | null>(null)
const successMsg = ref<string | null>(null)

/** key → 用户改后的新值。key 在表里 = 用户改过这一项 */
const edits = ref<Record<string, string>>({})

const GROUPS: Array<{
  id: string
  label: string
  hint?: string
  presetOverridable?: boolean
  steps: string[]
}> = [
  {
    id: 'script',
    label: '脚本流程',
    hint: '若对应预设里 *PromptYaml 顶层指定了 model,则以预设为准;此处为全局兜底。',
    presetOverridable: true,
    steps: ['brainstorm', 'script', 'script_critic', 'storyboard'],
  },
  {
    id: 'analysis',
    label: 'BGM / 钩子 / 事实核查 / 复盘',
    steps: ['bgm_mood', 'hook_extract', 'factcheck', 'factcheck_verify', 'factcheck_apply', 'video_attribution', 'weekly_review'],
  },
  {
    id: 'image',
    label: '分镜 prompt / 图像审核',
    steps: ['shot_prompt_refine', 'shot_prompt_desensitize', 'image_audit'],
  },
  {
    id: 'imagegen',
    label: '图像生成',
    hint: 'image_primary:预设里 imageConfig.model 指定了就以预设为准。image_fallback:主模型失败强制走这个,不可被预设覆盖。',
    presetOverridable: true,
    steps: ['image_primary', 'image_fallback'],
  },
  {
    id: 'agent',
    label: 'Agent',
    hint: '新建 Agent 会话不传 model 时落库就用这一项。已有会话不会被改。',
    steps: ['agent_default'],
  },
]

const KEY_PREFIX = 'auteur.model.'

const byStep = computed<Record<string, AppConfigItem>>(() => {
  const m: Record<string, AppConfigItem> = {}
  for (const it of items.value) {
    if (it.category !== 'model') continue
    if (!it.configKey.startsWith(KEY_PREFIX)) continue
    m[it.configKey.slice(KEY_PREFIX.length)] = it
  }
  return m
})

async function load() {
  loading.value = true
  errorMsg.value = null
  try {
    items.value = await listAppConfig()
    edits.value = {}
  } catch (e: any) {
    errorMsg.value = extractError(e, '加载失败')
  } finally {
    loading.value = false
  }
}

onMounted(load)

function valueOrEdit(item: AppConfigItem): string {
  if (item.configKey in edits.value) return edits.value[item.configKey]
  return item.displayValue
}

function isDirty(key: string): boolean {
  return key in edits.value
}

function onInput(key: string, value: string) {
  edits.value[key] = value
}

function resetField(item: AppConfigItem) {
  // 恢复成 DB 当前值 = 取消改动
  delete edits.value[item.configKey]
}

async function save() {
  const dirty = { ...edits.value }
  if (Object.keys(dirty).length === 0) {
    successMsg.value = '没有改动'
    return
  }
  saving.value = true
  errorMsg.value = null
  successMsg.value = null
  try {
    const r = await updateAppConfig(dirty)
    successMsg.value = `已保存 ${r.updated} 项;LLM 中转客户端是 @PostConstruct 启动期构造的,模型 ID 改完立即生效,无需重启。`
    await load()
  } catch (e: any) {
    errorMsg.value = extractError(e, '保存失败')
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="min-h-full">
    <div class="sticky top-0 z-10 bg-surface-primary border-b border-border-subtle">
      <div class="px-8 py-3 max-w-[1100px] mx-auto flex items-center gap-3">
        <button class="flex items-center gap-1.5 text-sm text-text-muted hover:text-text-primary"
                @click="router.push('/')">
          <ArrowLeft :size="14" /> 返回
        </button>
        <h1 class="text-lg font-semibold flex items-center gap-2">
          <Cpu :size="16" class="text-accent" />
          AI 模型
        </h1>
        <Loader2 v-if="loading" :size="14" class="animate-spin text-text-muted" />
        <div class="ml-auto flex items-center gap-2">
          <button class="btn" :disabled="saving || loading" @click="load">
            <RotateCcw :size="13" /> 重新加载
          </button>
          <button class="btn-primary" :disabled="saving || loading || Object.keys(edits).length === 0"
                  @click="save">
            <Loader2 v-if="saving" :size="13" class="animate-spin" />
            <Save v-else :size="13" />
            {{ saving ? '保存中…' : `保存${Object.keys(edits).length ? ` (${Object.keys(edits).length})` : ''}` }}
          </button>
        </div>
      </div>
    </div>

    <div class="px-8 py-5 max-w-[1100px] mx-auto space-y-5">
      <ErrorBanner :msg="errorMsg" />
      <div v-if="successMsg" class="card p-3 text-sm text-status-done bg-status-done/10 border-status-done/30">
        {{ successMsg }}
      </div>

      <div class="card p-4 bg-accent-soft border-accent/30">
        <div class="text-sm text-text-secondary leading-relaxed space-y-1.5">
          <div>
            流水线每一步用到的 LLM / 图像 / Agent 模型都在这里集中管理,落到 <code class="font-mono text-xs">app_config</code>
            表(<code class="font-mono text-xs">category='model'</code>)。代码层面已禁止在 <code class="font-mono text-xs">prompts/*.yaml</code>
            或 Java 字面量里硬编码模型 ID,所有运行时调用都从这里读。
          </div>
          <div>
            <strong>预设可覆盖</strong>:脚本流程 / 图像生成 这两组,如果当前 topic 用的预设里(brainstormPromptYaml /
            scriptPromptYaml / criticPromptYaml / storyboardPromptYaml / imageConfig.model)显式指定了模型,就以预设为准;
            本页只作"预设没填时的全局默认"。
          </div>
          <div>
            <strong>无法覆盖</strong>:其它步骤(BGM mood / 钩子 / 事实核查 / 分镜 prompt / 图像审核 / 视频归因 /
            周复盘 / 图像降级 / Agent 默认)直接读本页的值。
          </div>
        </div>
      </div>

      <div v-for="g in GROUPS" :key="g.id" class="card p-5 space-y-4">
        <div class="space-y-1">
          <h2 class="text-base font-semibold flex items-center gap-2">
            {{ g.label }}
            <span v-if="g.presetOverridable"
                  class="chip text-[10px] bg-amber-500/15 text-amber-700">预设可覆盖</span>
          </h2>
          <div v-if="g.hint" class="text-xs text-text-muted">{{ g.hint }}</div>
        </div>

        <div v-for="step in g.steps" :key="step" class="space-y-1.5">
          <template v-if="byStep[step]">
            <label class="text-sm text-text-primary block flex items-center gap-2">
              <span class="font-mono text-xs">{{ step }}</span>
              <span v-if="byStep[step].hasDbValue" class="chip text-[9px] bg-status-done/15 text-status-done">已配置</span>
              <span v-else class="chip text-[9px] bg-status-failed/15 text-status-failed">未配置</span>
              <span v-if="isDirty(byStep[step].configKey)" class="chip text-[9px] bg-amber-500/15 text-amber-700">已改</span>
            </label>
            <div v-if="byStep[step].description" class="text-xs text-text-muted">{{ byStep[step].description }}</div>
            <div class="flex gap-2 items-center">
              <input
                type="text"
                :value="valueOrEdit(byStep[step])"
                placeholder="模型 ID,如 claude-opus-4-7 / DeepSeek-V3.2"
                class="form-input font-mono flex-1"
                @input="onInput(byStep[step].configKey, ($event.target as HTMLInputElement).value)"
              />
              <button v-if="isDirty(byStep[step].configKey)"
                      class="btn-icon" title="撤销改动"
                      @click="resetField(byStep[step])">
                <RotateCcw :size="12" />
              </button>
            </div>
          </template>
          <template v-else>
            <!-- DB 里没这一行(V8 迁移没跑成功 / 被人手动删了)-->
            <div class="text-xs text-status-failed">
              缺失 <code class="font-mono">auteur.model.{{ step }}</code> —— 检查 V8 迁移是否执行成功
            </div>
          </template>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.form-input {
  @apply w-full px-3 py-1.5 text-sm bg-surface-primary border border-border rounded-md
         focus:outline-none focus:border-accent disabled:opacity-50 disabled:cursor-not-allowed;
}
</style>
