<script setup lang="ts">
/**
 * 通用 JSON-Schema 驱动表单。
 *
 * 支持的 schema 形态(够用主义,不追求 ajv 全规格):
 *   - { type: 'object', required: [...], properties: { key: <field> } }
 *   - field 类型:string / integer / number / boolean / array<string> / object(嵌套递归)
 *   - 字段可选属性:title / description / enum / default / minimum / maximum
 *
 * 对象嵌套:递归用同组件渲染 sub-schema。
 * 数组:目前只支持 items.type=string(每行一个 input + 增删按钮);更复杂的 array<object>(嵌套 nodes 等)
 *      用 textarea 让用户填 JSON 字符串,提交时 parse 回数组。
 */
import { computed, watch } from 'vue'
import { Plus, Trash2 } from 'lucide-vue-next'

interface Props {
  schema: any
  modelValue: Record<string, any>
  /** 嵌套递归时用 — 不显示 description block */
  nested?: boolean
  disabled?: boolean
}

const props = defineProps<Props>()
const emit = defineEmits<{
  (e: 'update:modelValue', v: Record<string, any>): void
}>()

const requiredKeys = computed<Set<string>>(() => new Set(props.schema?.required || []))
const propEntries = computed<Array<[string, any]>>(() => {
  const p = props.schema?.properties || {}
  return Object.entries(p)
})

function setField(key: string, value: any) {
  emit('update:modelValue', { ...props.modelValue, [key]: value })
}

function fieldDefault(field: any): any {
  if (field.default !== undefined) return field.default
  switch (field.type) {
    case 'integer':
    case 'number':
      return null
    case 'boolean':
      return false
    case 'array':
      return []
    case 'object':
      return {}
    default:
      return ''
  }
}

// 首次挂载时,缺省字段填默认值(避免 v-model 取 undefined 报错)
watch(
  () => props.schema,
  () => {
    const next = { ...props.modelValue }
    let dirty = false
    for (const [key, field] of propEntries.value) {
      if (next[key] === undefined) {
        next[key] = fieldDefault(field)
        dirty = true
      }
    }
    if (dirty) emit('update:modelValue', next)
  },
  { immediate: true },
)

function addArrayItem(key: string) {
  const arr = [...(props.modelValue[key] || []), '']
  setField(key, arr)
}

function removeArrayItem(key: string, idx: number) {
  const arr = [...(props.modelValue[key] || [])]
  arr.splice(idx, 1)
  setField(key, arr)
}

function updateArrayItem(key: string, idx: number, value: string) {
  const arr = [...(props.modelValue[key] || [])]
  arr[idx] = value
  setField(key, arr)
}

function tryParseObjectArray(key: string, raw: string) {
  if (!raw.trim()) {
    setField(key, [])
    return
  }
  try {
    const parsed = JSON.parse(raw)
    if (Array.isArray(parsed)) {
      setField(key, parsed)
    }
  } catch {
    // 留着原文本不动,等用户改对再 parse
  }
}

function objectArrayText(value: any): string {
  if (!Array.isArray(value)) return ''
  return JSON.stringify(value, null, 2)
}
</script>

<template>
  <div class="space-y-4">
    <div v-for="[key, field] in propEntries" :key="key" class="space-y-1.5">
      <label class="text-sm text-text-primary block">
        {{ field.title || key }}
        <span v-if="requiredKeys.has(key)" class="text-status-failed">*</span>
      </label>
      <div v-if="field.description" class="text-xs text-text-muted">{{ field.description }}</div>

      <!-- enum 下拉 -->
      <select
        v-if="field.enum && Array.isArray(field.enum)"
        :value="props.modelValue[key] ?? ''"
        :disabled="props.disabled"
        class="form-input"
        @change="setField(key, ($event.target as HTMLSelectElement).value)"
      >
        <option value="">--</option>
        <option v-for="opt in field.enum" :key="opt" :value="opt">{{ opt }}</option>
      </select>

      <!-- string 输入框 / 多行 -->
      <textarea
        v-else-if="field.type === 'string'"
        :value="props.modelValue[key] ?? ''"
        :disabled="props.disabled"
        :placeholder="field.placeholder || ''"
        rows="2"
        class="form-input font-sans"
        @input="setField(key, ($event.target as HTMLTextAreaElement).value)"
      />

      <!-- integer / number -->
      <input
        v-else-if="field.type === 'integer' || field.type === 'number'"
        type="number"
        :value="props.modelValue[key] ?? ''"
        :min="field.minimum"
        :max="field.maximum"
        :disabled="props.disabled"
        class="form-input w-32"
        @input="setField(key, ($event.target as HTMLInputElement).value === ''
          ? null
          : (field.type === 'integer'
              ? parseInt(($event.target as HTMLInputElement).value, 10)
              : parseFloat(($event.target as HTMLInputElement).value)))"
      />

      <!-- boolean -->
      <input
        v-else-if="field.type === 'boolean'"
        type="checkbox"
        :checked="!!props.modelValue[key]"
        :disabled="props.disabled"
        @change="setField(key, ($event.target as HTMLInputElement).checked)"
      />

      <!-- array<string> -->
      <div v-else-if="field.type === 'array' && (!field.items || field.items.type === 'string')" class="space-y-1.5">
        <div v-for="(item, idx) in ((props.modelValue[key] || []) as string[])" :key="idx" class="flex gap-2">
          <input
            type="text"
            :value="item"
            :disabled="props.disabled"
            class="form-input flex-1"
            @input="updateArrayItem(key, Number(idx), ($event.target as HTMLInputElement).value)"
          />
          <button
            type="button"
            class="btn-icon"
            :disabled="props.disabled"
            @click="removeArrayItem(key, Number(idx))"
          >
            <Trash2 :size="14" />
          </button>
        </div>
        <button
          type="button"
          class="btn text-xs"
          :disabled="props.disabled"
          @click="addArrayItem(key)"
        >
          <Plus :size="12" /> 加一项
        </button>
      </div>

      <!-- array<object>(嵌套结构):用 JSON textarea 让用户直接编辑 -->
      <textarea
        v-else-if="field.type === 'array'"
        :value="objectArrayText(props.modelValue[key])"
        :disabled="props.disabled"
        rows="10"
        class="form-input font-mono text-xs"
        :placeholder="'JSON 数组,例:\n[\n  { ' + (field.items?.required?.[0] || 'key') + ': &quot;...&quot; }\n]'"
        @input="tryParseObjectArray(key, ($event.target as HTMLTextAreaElement).value)"
      />

      <!-- 嵌套对象:递归 -->
      <div v-else-if="field.type === 'object'" class="pl-3 border-l-2 border-border-subtle">
        <DynamicForm
          :schema="field"
          :model-value="props.modelValue[key] || {}"
          :disabled="props.disabled"
          nested
          @update:model-value="(v: Record<string, any>) => setField(key, v)"
        />
      </div>

      <!-- 兜底:不识别的类型 → JSON 字符串 -->
      <textarea
        v-else
        :value="JSON.stringify(props.modelValue[key] ?? null)"
        :disabled="props.disabled"
        rows="3"
        class="form-input font-mono text-xs"
        @input="setField(key, ($event.target as HTMLTextAreaElement).value)"
      />
    </div>
  </div>
</template>

<style scoped>
.form-input {
  @apply w-full px-3 py-1.5 text-sm bg-surface-primary border border-border rounded-md
         focus:outline-none focus:border-accent disabled:opacity-50 disabled:cursor-not-allowed;
}
</style>
