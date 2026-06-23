<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Loader2, Play, X } from 'lucide-vue-next'
import { extractError } from '../../lib/format'
import {
  createHotSource, listHotAdapters, testFetchHotSource, updateHotSource,
  type HotSourceDraft,
} from '../../api/hotpool'
import type { HotItem, HotSource } from '../../types'

const props = defineProps<{
  open: boolean
  editing: HotSource | null
}>()

const emit = defineEmits<{
  (e: 'close'): void
  (e: 'saved', source: HotSource): void
}>()

const form = ref<HotSourceDraft>({
  name: '',
  adapter: 'rss',
  url: '',
  configJson: null,
  defaultTagsJson: null,
  enabled: true,
})

const adapters = ref<string[]>(['rss', 'http_json'])
const testing = ref(false)
const testResults = ref<HotItem[] | null>(null)
const testError = ref<string | null>(null)
const saving = ref(false)
const saveError = ref<string | null>(null)
const defaultTagsInput = ref('')

watch(
  () => props.open,
  async (op) => {
    if (!op) return
    testResults.value = null
    testError.value = null
    saveError.value = null
    if (props.editing) {
      form.value = {
        name: props.editing.name,
        adapter: props.editing.adapter,
        url: props.editing.url,
        configJson: props.editing.configJson,
        defaultTagsJson: props.editing.defaultTagsJson,
        enabled: props.editing.enabled,
      }
      defaultTagsInput.value = parseTagsToInput(props.editing.defaultTagsJson)
    } else {
      form.value = {
        name: '',
        adapter: 'rss',
        url: '',
        configJson: null,
        defaultTagsJson: null,
        enabled: true,
      }
      defaultTagsInput.value = ''
    }
    try {
      adapters.value = await listHotAdapters()
    } catch {
      // 沿用默认
    }
  },
  { immediate: true },
)

function parseTagsToInput(json: string | null): string {
  if (!json) return ''
  try {
    const arr = JSON.parse(json)
    return Array.isArray(arr) ? arr.join(' ') : ''
  } catch {
    return ''
  }
}

function tagsInputToJson(input: string): string | null {
  const arr = input.split(/[,，\s\n]+/).map((w) => w.trim()).filter(Boolean)
  return arr.length === 0 ? null : JSON.stringify(arr)
}

const canSubmit = computed(
  () => !!form.value.name.trim() && !!form.value.url.trim() && !!form.value.adapter && !saving.value,
)

async function doTest() {
  testing.value = true
  testError.value = null
  testResults.value = null
  try {
    const draft = buildDraft()
    const items = await testFetchHotSource(draft)
    testResults.value = items
    if (items.length === 0) testError.value = '抓取成功但返回 0 条 — 检查 URL / 配置'
  } catch (e) {
    testError.value = extractError(e, '测试抓取失败')
  } finally {
    testing.value = false
  }
}

function buildDraft(): HotSourceDraft {
  return {
    name: form.value.name.trim(),
    adapter: form.value.adapter,
    url: form.value.url.trim(),
    configJson: form.value.configJson?.trim() || null,
    defaultTagsJson: tagsInputToJson(defaultTagsInput.value),
    enabled: form.value.enabled,
  }
}

async function save() {
  saving.value = true
  saveError.value = null
  try {
    const draft = buildDraft()
    const saved = props.editing
      ? await updateHotSource(props.editing.id, draft)
      : await createHotSource(draft)
    emit('saved', saved)
  } catch (e) {
    saveError.value = extractError(e, '保存失败')
  } finally {
    saving.value = false
  }
}

const isHttpJson = computed(() => form.value.adapter === 'http_json')
</script>

<template>
  <Teleport to="body">
    <Transition name="modal">
      <div
        v-if="open"
        class="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4"
        @click.self="emit('close')"
      >
        <div class="card bg-surface-secondary w-[600px] max-w-full max-h-[90vh] flex flex-col">
          <div class="p-5 border-b border-border-subtle flex items-start justify-between gap-3">
            <div>
              <h2 class="text-base font-semibold text-text-primary">
                {{ editing ? '编辑热点源' : '添加热点源' }}
              </h2>
              <div class="text-xs text-text-muted mt-0.5">
                {{ editing ? '改完先「测试抓取」确认拿得到再保存' : '配置一个新的抓取适配器' }}
              </div>
            </div>
            <button class="btn-icon" @click="emit('close')"><X :size="16" /></button>
          </div>

          <div class="flex-1 overflow-y-auto p-5 space-y-4">
            <!-- 适配器类型 -->
            <div>
              <label class="text-xs font-medium text-text-primary block mb-1.5">适配器类型</label>
              <select
                v-model="form.adapter"
                class="w-full bg-surface-primary border border-border rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-accent/30 focus:border-accent"
              >
                <option v-for="a in adapters" :key="a" :value="a">
                  {{ a === 'rss' ? 'RSS 订阅源' : a === 'http_json' ? 'HTTP JSON API' : a }}
                </option>
              </select>
            </div>

            <!-- 名称 -->
            <div>
              <label class="text-xs font-medium text-text-primary block mb-1.5">名称</label>
              <input
                v-model="form.name"
                type="text"
                placeholder="36 氪 快讯"
                class="w-full bg-surface-primary border border-border rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-accent/30 focus:border-accent"
              />
            </div>

            <!-- URL -->
            <div>
              <label class="text-xs font-medium text-text-primary block mb-1.5">URL</label>
              <input
                v-model="form.url"
                type="text"
                placeholder="https://example.com/feed"
                class="w-full bg-surface-primary border border-border rounded-md px-3 py-2 text-sm font-mono focus:outline-none focus:ring-2 focus:ring-accent/30 focus:border-accent"
              />
            </div>

            <!-- 适配器配置(http_json 必填) -->
            <div v-if="isHttpJson">
              <label class="text-xs font-medium text-text-primary block mb-1.5">
                JSON 抽字段配置
                <span class="text-text-muted font-normal">— 用 JSON Pointer (RFC 6901)</span>
              </label>
              <textarea
                v-model="form.configJson"
                rows="8"
                placeholder='{
  "itemsPointer": "/result/data",
  "titlePointer": "/title",
  "urlPointer": "/url",
  "summaryPointer": "/intro",
  "publishedPointer": "/ctime",
  "publishedFormat": "epoch_seconds",
  "limit": 50
}'
                class="w-full bg-surface-primary border border-border rounded-md px-3 py-2 text-xs font-mono resize-y focus:outline-none focus:ring-2 focus:ring-accent/30 focus:border-accent"
              />
            </div>

            <!-- 默认 tag -->
            <div>
              <label class="text-xs font-medium text-text-primary block mb-1.5">
                默认 tag
                <span class="text-text-muted font-normal">— 空格 / 逗号分隔,该源所有条目自动打上</span>
              </label>
              <input
                v-model="defaultTagsInput"
                type="text"
                placeholder="财经 创投"
                class="w-full bg-surface-primary border border-border rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-accent/30 focus:border-accent"
              />
            </div>

            <!-- 启用 toggle -->
            <label class="flex items-center justify-between p-3 bg-surface-tertiary/40 rounded-md cursor-pointer">
              <div>
                <div class="text-sm font-medium text-text-primary">启用</div>
                <div class="text-xs text-text-muted mt-0.5">
                  启用后该源进入「生成选题」和「立即抓取」的可选范围;禁用时手动触发会跳过
                </div>
              </div>
              <input
                v-model="form.enabled"
                type="checkbox"
                class="w-4 h-4 accent-accent"
              />
            </label>

            <!-- 测试结果 -->
            <div v-if="testResults" class="card p-3 bg-status-done/5 border-status-done/30">
              <div class="text-xs font-semibold text-status-done mb-2">✓ 抓取成功 · 前 {{ testResults.length }} 条预览</div>
              <ul class="space-y-1.5 text-xs">
                <li v-for="(it, i) in testResults" :key="i" class="text-text-secondary">
                  <span class="text-text-muted">{{ i + 1 }}.</span> {{ it.title }}
                </li>
              </ul>
            </div>

            <div
              v-if="testError"
              class="text-xs text-status-failed bg-status-failed/10 border border-status-failed/30 rounded-md px-3 py-2"
            >{{ testError }}</div>
            <div
              v-if="saveError"
              class="text-xs text-status-failed bg-status-failed/10 border border-status-failed/30 rounded-md px-3 py-2"
            >{{ saveError }}</div>
          </div>

          <div class="px-5 py-4 border-t border-border-subtle flex items-center justify-end gap-2">
            <button class="btn-ghost text-sm" @click="emit('close')" :disabled="saving">取消</button>
            <button class="btn-ghost text-sm" :disabled="!canSubmit || testing" @click="doTest">
              <Loader2 v-if="testing" :size="13" class="animate-spin" />
              <Play v-else :size="13" />测试抓取
            </button>
            <button class="btn-primary text-sm" :disabled="!canSubmit" @click="save">
              {{ saving ? '保存中…' : (editing ? '保存' : '保存并启用') }}
            </button>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.modal-enter-active,
.modal-leave-active {
  transition: opacity 0.15s ease;
}
.modal-enter-from,
.modal-leave-to {
  opacity: 0;
}
</style>
