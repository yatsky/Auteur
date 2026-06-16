<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Loader2, Save, X } from 'lucide-vue-next'
import { updateTopic } from '../api/topics'
import { getPreset, type Preset } from '../api/presets'
import { extractError } from '../lib/format'
import DynamicForm from './DynamicForm.vue'

const props = defineProps<{
  open: boolean
  topicId: number
  presetId: number | null
  /** 已有的 preset_input_json(对象或 null) */
  initialInput?: any
}>()

const emit = defineEmits<{
  (e: 'close'): void
  (e: 'saved', input: any): void
}>()

const preset = ref<Preset | null>(null)
const loading = ref(false)
const saving = ref(false)
const error = ref<string | null>(null)
const form = ref<Record<string, any>>({})

const schema = computed<any>(() => {
  const s = preset.value?.inputSchemaJson
  if (!s) return null
  return typeof s === 'string' ? JSON.parse(s) : s
})

watch(
  () => [props.open, props.presetId] as const,
  async ([open, pid]) => {
    if (!open || !pid) return
    loading.value = true
    error.value = null
    try {
      preset.value = await getPreset(pid)
      // initialInput 可能是对象,也可能是后端返回的 JSON 字符串
      const init = props.initialInput
      if (init == null) {
        form.value = {}
      } else if (typeof init === 'string') {
        try {
          form.value = JSON.parse(init) || {}
        } catch {
          form.value = {}
        }
      } else {
        form.value = { ...init }
      }
    } catch (e: any) {
      error.value = e?.message ?? '加载预设失败'
    } finally {
      loading.value = false
    }
  },
  { immediate: true },
)

async function save() {
  saving.value = true
  error.value = null
  try {
    await updateTopic(props.topicId, {
      presetInputJson: JSON.stringify(form.value),
    })
    emit('saved', form.value)
    emit('close')
  } catch (e: any) {
    error.value = extractError(e, '保存失败')
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <Transition
    enter-active-class="transition duration-150"
    enter-from-class="opacity-0"
    enter-to-class="opacity-100"
    leave-active-class="transition duration-100"
    leave-from-class="opacity-100"
    leave-to-class="opacity-0"
  >
    <div
      v-if="open"
      class="fixed inset-0 z-40 bg-black/40"
      @click="emit('close')"
    />
  </Transition>

  <Transition
    enter-active-class="transition duration-200"
    enter-from-class="translate-x-full"
    enter-to-class="translate-x-0"
    leave-active-class="transition duration-150"
    leave-from-class="translate-x-0"
    leave-to-class="translate-x-full"
  >
    <aside
      v-if="open"
      class="fixed top-0 right-0 z-50 h-full w-full md:w-[640px] bg-surface-primary border-l border-border shadow-xl flex flex-col"
    >
      <header class="flex items-center justify-between px-5 py-3 border-b border-border-subtle">
        <div class="flex items-center gap-2">
          <span class="text-lg font-semibold">配置预设输入</span>
          <span v-if="preset" class="chip text-[10px] bg-accent/15 text-accent">
            {{ preset.displayName || preset.name }}
          </span>
        </div>
        <button class="btn-icon" @click="emit('close')"><X :size="18" /></button>
      </header>

      <section class="flex-1 overflow-y-auto px-5 py-4">
        <div v-if="loading" class="flex items-center gap-2 text-sm text-text-muted">
          <Loader2 :size="14" class="animate-spin" /> 加载预设…
        </div>
        <div v-else-if="error" class="card p-3 bg-status-failed/10 border-status-failed/30 text-xs text-status-failed">
          {{ error }}
        </div>
        <div v-else-if="!schema" class="text-sm text-text-muted">
          该预设未配置 input_schema,无字段可编辑。
        </div>
        <DynamicForm
          v-else
          :schema="schema"
          v-model="form"
          :disabled="saving"
        />
      </section>

      <footer class="flex items-center justify-end gap-2 px-5 py-3 border-t border-border-subtle">
        <button class="btn" :disabled="saving" @click="emit('close')">取消</button>
        <button class="btn-primary" :disabled="saving || !preset" @click="save">
          <Loader2 v-if="saving" :size="13" class="animate-spin" />
          <Save v-else :size="13" />
          {{ saving ? '保存中…' : '保存' }}
        </button>
      </footer>
    </aside>
  </Transition>
</template>
