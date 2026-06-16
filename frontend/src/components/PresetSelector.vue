<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { listPresets, type Preset } from '../api/presets'

const props = defineProps<{
  modelValue: number | null
  brainstormCapable?: boolean
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', v: number | null): void
}>()

const presets = ref<Preset[]>([])
const loading = ref(false)
const error = ref<string | null>(null)

const filtered = computed(() => {
  if (props.brainstormCapable) {
    return presets.value.filter((p) => p.brainstormPromptYaml && p.brainstormPromptYaml.trim().length > 0)
  }
  return presets.value
})

const selected = computed(() => filtered.value.find((p) => p.id === props.modelValue) || null)

onMounted(async () => {
  loading.value = true
  try {
    presets.value = await listPresets()
    if (props.modelValue == null && filtered.value.length > 0) {
      // 优先 freeform(开箱即用示例),否则首条
      const ff = filtered.value.find((p) => p.name === 'freeform')
      emit('update:modelValue', (ff ?? filtered.value[0]).id)
    }
  } catch (e: any) {
    error.value = e?.message ?? '加载预设失败'
  } finally {
    loading.value = false
  }
})

watch(
  () => props.brainstormCapable,
  () => {
    if (props.modelValue != null && !filtered.value.find((p) => p.id === props.modelValue)) {
      emit('update:modelValue', filtered.value[0]?.id ?? null)
    }
  },
)

function onChange(e: Event) {
  const v = (e.target as HTMLSelectElement).value
  emit('update:modelValue', v ? Number(v) : null)
}
</script>

<template>
  <div class="space-y-1.5">
    <select
      :value="props.modelValue ?? ''"
      :disabled="loading"
      class="form-input"
      @change="onChange"
    >
      <option value="" disabled>-- 选择预设 --</option>
      <option v-for="p in filtered" :key="p.id" :value="p.id">
        {{ p.displayName || p.name }}
        <template v-if="p.visibility === 'private'">(私有)</template>
      </option>
    </select>
    <div v-if="selected?.description" class="text-xs text-text-muted">
      {{ selected.description }}
    </div>
    <div v-if="error" class="text-xs text-status-failed">{{ error }}</div>
    <div v-else-if="!loading && filtered.length === 0" class="text-xs text-text-muted">
      没有可用预设。<router-link to="/presets" class="text-accent">前往预设库新建</router-link>
    </div>
  </div>
</template>

<style scoped>
.form-input {
  @apply w-full px-3 py-1.5 text-sm bg-surface-primary border border-border rounded-md
         focus:outline-none focus:border-accent disabled:opacity-50 disabled:cursor-not-allowed;
}
</style>
