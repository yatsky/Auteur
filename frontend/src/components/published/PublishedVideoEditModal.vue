<script setup lang="ts">
// 已发布视频 单行编辑/新增对话框。父用 v-model:open 控制开关、传 editing 决定模式。
import { computed, ref, watch } from 'vue'
import { Loader2, X } from 'lucide-vue-next'
import {
  createPublishedVideo, updatePublishedVideo,
  type PublishedVideo, type PublishedVideoUpsert,
} from '../../api/publishedVideos'
import { getTopic } from '../../api/topics'
import { extractError } from '../../lib/format'
import type { ScriptListItem } from '../../types'

const props = defineProps<{
  open: boolean
  editing: PublishedVideo | null  // null = 新建模式
  scripts: ScriptListItem[]
}>()

const emit = defineEmits<{
  (e: 'update:open', value: boolean): void
  (e: 'saved'): void
}>()

const form = ref<PublishedVideoUpsert>(emptyForm())
const formError = ref<string | null>(null)
const saving = ref(false)

function emptyForm(): PublishedVideoUpsert {
  return {
    title: '', platform: '抖音', publishedAt: nowLocalForInput(),
    projectName: '', platformVideoId: '', scriptId: null, topicId: null,
    durationSeconds: null, views: 0, likes: 0, comments: 0, shares: 0,
    retentionPct: null,
    avgPlaySeconds: null, drop2sPct: null, play5sPct: null,
    avgPlayRatioPct: null, favoriteRatePct: null, dislikeRatePct: null,
    hookCtr: null, costYuan: null, hookTemplate: '', notes: '',
  }
}

function nowLocalForInput(): string {
  const d = new Date()
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
}

// 父切换 open / editing 时重新填表
watch(
  () => [props.open, props.editing] as const,
  ([open, v]) => {
    if (!open) return
    formError.value = null
    if (v == null) {
      form.value = emptyForm()
    } else {
      form.value = {
        title: v.title,
        platform: v.platform,
        publishedAt: v.publishedAt.slice(0, 16),
        projectName: v.projectName || '',
        platformVideoId: v.platformVideoId || '',
        scriptId: v.scriptId,
        topicId: v.topicId,
        durationSeconds: v.durationSeconds,
        views: v.views,
        likes: v.likes,
        comments: v.comments,
        shares: v.shares,
        retentionPct: v.retentionPct,
        avgPlaySeconds: v.avgPlaySeconds,
        drop2sPct: v.drop2sPct,
        play5sPct: v.play5sPct,
        avgPlayRatioPct: v.avgPlayRatioPct,
        favoriteRatePct: v.favoriteRatePct,
        dislikeRatePct: v.dislikeRatePct,
        hookCtr: v.hookCtr,
        costYuan: v.costYuan,
        hookTemplate: v.hookTemplate || '',
        notes: v.notes || '',
      }
    }
  },
  { immediate: true },
)

// dropdown 选中或清空时联动 topicId / projectName / durationSeconds(只在用户没填的时候)
async function onScriptPick(idRaw: string | number | null) {
  const id = idRaw === '' || idRaw == null ? null : Number(idRaw)
  form.value.scriptId = id
  if (id == null) return
  const s = props.scripts.find((x) => x.id === id)
  if (!s) return
  if (s.topicId != null && form.value.topicId == null) form.value.topicId = s.topicId
  if (s.projectName && !form.value.projectName) form.value.projectName = s.projectName
  if (s.durationSeconds != null && form.value.durationSeconds == null) form.value.durationSeconds = s.durationSeconds
  if (s.topicId != null && !form.value.hookTemplate) {
    try {
      const t = await getTopic(s.topicId)
      if (t.hookType) form.value.hookTemplate = t.hookType
    } catch { /* topic 拉不到就静默,允许手填 */ }
  }
}

// 平均播放占比 = 平均播放时长 / 时长 × 100,2 位小数;只在两个源都有值时覆盖
watch(
  () => [form.value.avgPlaySeconds, form.value.durationSeconds] as const,
  ([avg, dur]) => {
    if (avg != null && dur != null && dur > 0) {
      form.value.avgPlayRatioPct = Math.round((avg / dur) * 10000) / 100
    }
  },
)

// 编辑老数据时,scriptId 可能不在最近列表里;前置一个"历史"选项
const scriptOptions = computed(() => {
  const list = props.scripts.slice()
  const cur = form.value.scriptId
  if (cur != null && !list.some((s) => s.id === cur)) {
    list.unshift({
      id: cur,
      topicId: null,
      projectName: '(历史 / 已不在最近列表)',
      version: 0,
      modelUsed: null,
      wordCount: null,
      durationSeconds: null,
      status: 'DONE' as any,
      reviewScore: null,
      createdAt: '',
      updatedAt: '',
      lastRunStage: null,
      lastRunStatus: null,
      lastRunAt: null,
    })
  }
  return list
})

function scriptLabel(s: ScriptListItem): string {
  const name = s.projectName || `script #${s.id}`
  const dur = s.durationSeconds != null ? ` · ${s.durationSeconds}s` : ''
  const stage = s.lastRunStage ? ` · ${s.lastRunStage}` : ''
  return `#${s.id} · ${name}${dur}${stage}`
}

async function onSave() {
  const projectName = form.value.projectName?.trim() || ''
  if (!projectName) { formError.value = '项目名必填(同时作为标题)'; return }
  if (!form.value.platform?.trim()) { formError.value = '平台必填'; return }
  if (!form.value.publishedAt) { formError.value = '发布时间必填'; return }
  saving.value = true
  formError.value = null
  try {
    const payload = { ...form.value }
    payload.projectName = projectName
    if (!payload.title?.trim()) payload.title = projectName
    if (payload.publishedAt && payload.publishedAt.length === 16) payload.publishedAt += ':00'
    if (props.editing == null) {
      await createPublishedVideo(payload)
    } else {
      await updatePublishedVideo(props.editing.id, payload)
    }
    emit('saved')
    emit('update:open', false)
  } catch (e: any) {
    formError.value = extractError(e, '保存失败')
  } finally {
    saving.value = false
  }
}

function close() {
  if (saving.value) return
  emit('update:open', false)
}
</script>

<template>
  <div v-if="open"
       class="fixed inset-0 z-50 bg-black/60 flex items-center justify-center p-4"
       @click.self="close">
    <div class="card p-7 max-w-[820px] w-full max-h-[92vh] overflow-y-auto">
      <header class="flex items-start justify-between mb-5">
        <div>
          <h2 class="text-lg font-semibold">{{ editing == null ? '添加已发布视频' : `编辑 #${editing.id}` }}</h2>
          <p class="text-xs text-text-muted mt-1">录入手填指标行 · 跨视频对比和钩子复盘的数据源同步</p>
        </div>
        <button class="text-text-muted hover:text-text-primary" :disabled="saving" @click="close">
          <X :size="20" />
        </button>
      </header>

      <div v-if="formError" class="card p-3 mb-4 bg-status-failed/10 border-status-failed/30 text-xs text-status-failed">
        {{ formError }}
      </div>

      <div class="space-y-5 text-sm">
        <!-- 基本信息 -->
        <section>
          <h3 class="text-[13px] font-semibold mb-3">基本信息</h3>
          <div class="space-y-3">
            <label class="flex flex-col gap-1.5">
              <span class="text-[11px] text-text-muted">项目名 <span class="text-status-failed">*</span><span class="text-text-muted/70 ml-1">(同时作为标题, ≤40 字)</span></span>
              <input v-model="form.projectName" type="text" maxlength="40" :disabled="saving"
                     placeholder="如:朱元璋之死 / 崇祯遗诏"
                     class="bg-surface-tertiary border border-border-subtle rounded-lg px-3 py-2" />
            </label>
            <div class="grid grid-cols-1 md:grid-cols-2 gap-3">
              <label class="flex flex-col gap-1.5">
                <span class="text-[11px] text-text-muted">平台 <span class="text-status-failed">*</span></span>
                <select v-model="form.platform" :disabled="saving"
                        class="bg-surface-tertiary border border-border-subtle rounded-lg px-3 py-2">
                  <option value="抖音">抖音</option>
                  <option value="B站">B站</option>
                  <option value="小红书">小红书</option>
                  <option value="视频号">视频号</option>
                  <option value="快手">快手</option>
                  <option value="西瓜">西瓜</option>
                  <option value="知乎">知乎</option>
                </select>
              </label>
              <label class="flex flex-col gap-1.5">
                <span class="text-[11px] text-text-muted">发布时间 <span class="text-status-failed">*</span></span>
                <input v-model="form.publishedAt" type="datetime-local" :disabled="saving"
                       class="bg-surface-tertiary border border-border-subtle rounded-lg px-3 py-2 font-mono" />
              </label>
            </div>
            <div class="grid grid-cols-1 md:grid-cols-2 gap-3">
              <label class="flex flex-col gap-1.5">
                <span class="text-[11px] text-text-muted">关联脚本 <span class="text-text-muted/70">(从「视频组装」列表里选 · 选中后联动 topic / 项目名 / 时长)</span></span>
                <select :value="form.scriptId ?? ''" :disabled="saving"
                        class="bg-surface-tertiary border border-border-subtle rounded-lg px-3 py-2"
                        @change="onScriptPick(($event.target as HTMLSelectElement).value)">
                  <option value="">— 不关联 —</option>
                  <option v-for="s in scriptOptions" :key="s.id" :value="s.id">
                    {{ scriptLabel(s) }}
                  </option>
                </select>
              </label>
              <label class="flex flex-col gap-1.5">
                <span class="text-[11px] text-text-muted">平台 vid <span class="text-text-muted/70">(可选 · 全局唯一去重键)</span></span>
                <input v-model="form.platformVideoId" type="text" maxlength="120" :disabled="saving"
                       placeholder="7368291045…"
                       class="bg-surface-tertiary border border-border-subtle rounded-lg px-3 py-2 font-mono" />
              </label>
            </div>
          </div>
        </section>

        <!-- 核心曝光数据 -->
        <section>
          <h3 class="text-[13px] font-semibold mb-3">核心曝光数据</h3>
          <div class="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-5 gap-3">
            <label class="flex flex-col gap-1.5">
              <span class="text-[11px] text-text-muted">时长 (s)</span>
              <input v-model.number="form.durationSeconds" type="number" min="0" :disabled="saving"
                     class="bg-surface-tertiary border border-border-subtle rounded-lg px-3 py-2 font-mono" />
            </label>
            <label class="flex flex-col gap-1.5">
              <span class="text-[11px] text-text-muted">播放</span>
              <input v-model.number="form.views" type="number" min="0" :disabled="saving"
                     class="bg-surface-tertiary border border-border-subtle rounded-lg px-3 py-2 font-mono" />
            </label>
            <label class="flex flex-col gap-1.5">
              <span class="text-[11px] text-text-muted">点赞</span>
              <input v-model.number="form.likes" type="number" min="0" :disabled="saving"
                     class="bg-surface-tertiary border border-border-subtle rounded-lg px-3 py-2 font-mono" />
            </label>
            <label class="flex flex-col gap-1.5">
              <span class="text-[11px] text-text-muted">评论</span>
              <input v-model.number="form.comments" type="number" min="0" :disabled="saving"
                     class="bg-surface-tertiary border border-border-subtle rounded-lg px-3 py-2 font-mono" />
            </label>
            <label class="flex flex-col gap-1.5">
              <span class="text-[11px] text-text-muted">转发</span>
              <input v-model.number="form.shares" type="number" min="0" :disabled="saving"
                     class="bg-surface-tertiary border border-border-subtle rounded-lg px-3 py-2 font-mono" />
            </label>
          </div>
        </section>

        <!-- 留存 / 钩子细分 -->
        <section>
          <h3 class="text-[13px] font-semibold mb-3">留存 / 钩子细分</h3>
          <div class="grid grid-cols-2 md:grid-cols-3 gap-3">
            <label class="flex flex-col gap-1.5">
              <span class="text-[11px] text-text-muted">完播率 (%)</span>
              <input v-model.number="form.retentionPct" type="number" min="0" max="100" step="0.1" :disabled="saving"
                     class="bg-surface-tertiary border border-border-subtle rounded-lg px-3 py-2 font-mono" />
            </label>
            <label class="flex flex-col gap-1.5">
              <span class="text-[11px] text-text-muted">平均播放时长 (s)</span>
              <input v-model.number="form.avgPlaySeconds" type="number" min="0" step="0.1" :disabled="saving"
                     class="bg-surface-tertiary border border-border-subtle rounded-lg px-3 py-2 font-mono" />
            </label>
            <label class="flex flex-col gap-1.5">
              <span class="text-[11px] text-text-muted">钩子 CTR (%)</span>
              <input v-model.number="form.hookCtr" type="number" min="0" max="100" step="0.1" :disabled="saving"
                     class="bg-surface-tertiary border border-border-subtle rounded-lg px-3 py-2 font-mono" />
            </label>
            <label class="flex flex-col gap-1.5">
              <span class="text-[11px] text-text-muted">2s 跳出率 (%) <span class="text-status-failed/80">&gt;30 = 钩子失败</span></span>
              <input v-model.number="form.drop2sPct" type="number" min="0" max="100" step="0.01" :disabled="saving"
                     class="bg-surface-tertiary border border-border-subtle rounded-lg px-3 py-2 font-mono" />
            </label>
            <label class="flex flex-col gap-1.5">
              <span class="text-[11px] text-text-muted">5s 完播率 (%) <span class="text-status-failed/80">&lt;40 = 钩子拉胯</span></span>
              <input v-model.number="form.play5sPct" type="number" min="0" max="100" step="0.01" :disabled="saving"
                     class="bg-surface-tertiary border border-border-subtle rounded-lg px-3 py-2 font-mono" />
            </label>
            <label class="flex flex-col gap-1.5">
              <span class="text-[11px] text-text-muted">平均播放占比 (%) <span class="text-text-muted/70">(自动算 = 平均播放时长 ÷ 时长)</span></span>
              <input :value="form.avgPlayRatioPct ?? ''" type="number" readonly disabled
                     placeholder="先填时长 + 平均播放时长"
                     class="bg-surface-tertiary/40 border border-border-subtle rounded-lg px-3 py-2 font-mono text-text-muted cursor-not-allowed" />
            </label>
          </div>
        </section>

        <!-- 互动 / 成本 -->
        <section>
          <h3 class="text-[13px] font-semibold mb-3">互动 / 成本</h3>
          <div class="grid grid-cols-2 md:grid-cols-3 gap-3">
            <label class="flex flex-col gap-1.5">
              <span class="text-[11px] text-text-muted">收藏率 (%)</span>
              <input v-model.number="form.favoriteRatePct" type="number" min="0" max="100" step="0.01" :disabled="saving"
                     class="bg-surface-tertiary border border-border-subtle rounded-lg px-3 py-2 font-mono" />
            </label>
            <label class="flex flex-col gap-1.5">
              <span class="text-[11px] text-text-muted">不感兴趣率 (%) <span class="text-status-failed/80">&gt;1 = 触发负反馈</span></span>
              <input v-model.number="form.dislikeRatePct" type="number" min="0" max="100" step="0.01" :disabled="saving"
                     class="bg-surface-tertiary border border-border-subtle rounded-lg px-3 py-2 font-mono" />
            </label>
            <label class="flex flex-col gap-1.5">
              <span class="text-[11px] text-text-muted">单条总成本 (¥)</span>
              <input v-model.number="form.costYuan" type="number" min="0" step="0.01" :disabled="saving"
                     class="bg-surface-tertiary border border-border-subtle rounded-lg px-3 py-2 font-mono" />
            </label>
          </div>
        </section>

        <!-- 元数据 -->
        <section>
          <h3 class="text-[13px] font-semibold mb-3">元数据</h3>
          <label class="flex flex-col gap-1.5">
            <span class="text-[11px] text-text-muted">钩子模板 <span class="text-text-muted/70">(关联脚本时自动从话题钩子类型填,可手改)</span></span>
            <input v-model="form.hookTemplate" type="text" maxlength="100" :disabled="saving"
                   placeholder="反转型 / 密信型 / 问句型 / 时间线型 / 清单型 …"
                   class="bg-surface-tertiary border border-border-subtle rounded-lg px-3 py-2" />
          </label>
        </section>

        <label class="flex flex-col gap-1.5">
          <span class="text-[11px] text-text-muted">备注</span>
          <textarea v-model="form.notes" rows="3" :disabled="saving"
                    placeholder="周三晚 21:30 发,流量好,准备做兄弟篇 …"
                    class="bg-surface-tertiary border border-border-subtle rounded-lg px-3 py-2 resize-y" />
        </label>
      </div>

      <div class="border-t border-border-subtle mt-6 pt-4 flex items-center justify-end gap-2">
        <button class="chip cursor-pointer bg-surface-tertiary text-text-secondary text-sm px-4 py-2"
                :disabled="saving" @click="close">取消</button>
        <button class="btn-primary px-5 py-2" :disabled="saving" @click="onSave">
          <Loader2 v-if="saving" :size="14" class="animate-spin" />
          {{ saving ? '保存中…' : (editing == null ? '保存' : '保存') }}
        </button>
      </div>
    </div>
  </div>
</template>
