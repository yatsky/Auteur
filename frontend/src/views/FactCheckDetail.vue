<script setup lang="ts">
// 事实核查详情 —— sticky 顶栏 + 操作条 + issue 卡列表
import { onMounted, ref, computed } from 'vue'
import { ArrowLeft, CheckCircle2, Loader2, Pencil, Play, RefreshCw, Wand2, X } from 'lucide-vue-next'
import { listIssues, runFactCheckAsync, applyIssueFix, dismissIssue, type ApplyFixResult } from '../api/scripts'
import { useRunPoll } from '../composables/useRunPoll'
import { extractError } from '../lib/format'
import { useRouter } from 'vue-router'
import ErrorBanner from '../components/ErrorBanner.vue'
import TimeText from '../components/TimeText.vue'
import type { FactCheckIssue } from '../types'
import { SEVERITY_LABELS } from '../types'

const props = defineProps<{ scriptId: number }>()
const router = useRouter()

const issues = ref<FactCheckIssue[]>([])
const loading = ref(false)
const errorMsg = ref<string | null>(null)
const runMsg = ref<string | null>(null)

const issueBusy = ref<Record<number, 'applying' | 'dismissing'>>({})
const fixResults = ref<Record<number, ApplyFixResult>>({})
const issueError = ref<Record<number, string>>({})

const bulkFix = ref<{ done: number; total: number; replaced: number; skipped: number; failed: number } | null>(null)
const bulkRunning = computed(() => bulkFix.value !== null && bulkFix.value.done < bulkFix.value.total)

const unresolvedCount = computed(() => issues.value.filter((x) => !x.resolved).length)
const lastCheckedAt = computed(() => {
  const times = issues.value.map((i) => i.createdAt).filter(Boolean) as string[]
  return times.length === 0 ? null : times.sort().slice(-1)[0]
})

async function load() {
  loading.value = true
  try {
    issues.value = await listIssues(props.scriptId)
    errorMsg.value = null
  } catch (e: any) {
    errorMsg.value = extractError(e, 'fetch failed')
  } finally {
    loading.value = false
  }
}

const poll = useRunPoll({
  onDone: async () => { runMsg.value = '跑完 ✓'; await load() },
  onFailed: (r) => { runMsg.value = `失败:${r.errorMsg ?? r.status}` },
})
const activeRun = poll.run
const isRunning = computed(() => activeRun.value && (activeRun.value.status === 'PENDING' || activeRun.value.status === 'RUNNING'))
const progressText = computed(() => {
  const r = activeRun.value
  if (!r) return ''
  if (r.totalItems == null || r.totalItems === 0) return 'P1 通读疑点中...'
  return `P2 联网核证 ${r.lastCompletedIndex ?? 0}/${r.totalItems}`
})

async function trigger() {
  poll.reset()
  runMsg.value = null
  try {
    const { runId } = await runFactCheckAsync(props.scriptId)
    runMsg.value = `已起 run ${runId},正在轮询进度...`
    await poll.start(runId)
  } catch (e: any) {
    runMsg.value = `失败:${extractError(e)}`
  }
}

async function applyFix(issue: FactCheckIssue) {
  if (issue.resolved) return
  issueBusy.value[issue.id] = 'applying'
  delete issueError.value[issue.id]
  try {
    const r = await applyIssueFix(issue.id)
    fixResults.value[issue.id] = r
    issue.resolved = true
  } catch (e: any) {
    issueError.value[issue.id] = extractError(e, '修复失败')
  } finally {
    delete issueBusy.value[issue.id]
  }
}

async function dismiss(issue: FactCheckIssue) {
  if (issue.resolved) return
  issueBusy.value[issue.id] = 'dismissing'
  delete issueError.value[issue.id]
  try {
    await dismissIssue(issue.id)
    issue.resolved = true
  } catch (e: any) {
    issueError.value[issue.id] = extractError(e, '忽略失败')
  } finally {
    delete issueBusy.value[issue.id]
  }
}

function jumpToScript() {
  router.push(`/scripts/${props.scriptId}`)
}

async function bulkFixAll() {
  const targets = issues.value.filter((x) => !x.resolved)
  if (targets.length === 0) return
  bulkFix.value = { done: 0, total: targets.length, replaced: 0, skipped: 0, failed: 0 }
  for (const issue of targets) {
    issueBusy.value[issue.id] = 'applying'
    delete issueError.value[issue.id]
    try {
      const r = await applyIssueFix(issue.id)
      fixResults.value[issue.id] = r
      issue.resolved = true
      if (r.applied) bulkFix.value.replaced++
      else bulkFix.value.skipped++
    } catch (e: any) {
      issueError.value[issue.id] = extractError(e, '修复失败')
      bulkFix.value.failed++
    } finally {
      delete issueBusy.value[issue.id]
      bulkFix.value.done++
    }
  }
}

const SEVERITY_STYLE: Record<string, string> = {
  CRITICAL: 'bg-status-failed/20 text-status-failed font-semibold',
  MAJOR: 'bg-status-failed/15 text-status-failed',
  HIGH: 'bg-status-failed/15 text-status-failed',
  MEDIUM: 'bg-status-paused/15 text-status-paused',
  MINOR: 'bg-status-paused/15 text-status-paused',
  LOW: 'bg-text-muted/15 text-text-secondary',
}

onMounted(load)
</script>

<template>
  <div class="min-h-full">
    <!-- sticky 顶栏 -->
    <div class="sticky top-0 z-10 bg-surface-primary border-b border-border-subtle">
      <div class="px-8 py-3 max-w-[1200px] mx-auto flex flex-col gap-1">
        <div class="flex items-center gap-4 flex-wrap">
          <button
            class="flex items-center gap-1.5 text-sm text-text-muted hover:text-text-primary"
            @click="$router.push('/factcheck')"
          >
            <ArrowLeft :size="14" /> 返回
          </button>
          <h1 class="text-lg font-semibold">事实核查 · script #{{ scriptId }}</h1>
          <span class="chip text-[11px] bg-surface-tertiary text-text-muted">
            {{ issues.length }} 条问题
            <template v-if="unresolvedCount > 0">· 待处理 {{ unresolvedCount }}</template>
          </span>
        </div>
        <div class="text-xs text-text-muted">
          <template v-if="lastCheckedAt">
            上次核查 <TimeText :value="lastCheckedAt" relative /> · 通读 + 联网核证
          </template>
          <template v-else>
            还没有跑过核查 · 点「重跑核查」开始
          </template>
        </div>
      </div>
    </div>

    <div class="px-8 py-5 max-w-[1200px] mx-auto">
      <ErrorBanner :msg="errorMsg" />

      <!-- 操作条 -->
      <div class="card p-3 mb-4 flex items-center gap-2 flex-wrap">
        <button class="btn-ghost" :disabled="loading" @click="load">
          <RefreshCw :size="13" :class="loading ? 'animate-spin' : ''" /> 刷新
        </button>
        <button class="btn-primary" :disabled="!!isRunning || bulkRunning" @click="trigger">
          <Play :size="13" /> {{ isRunning ? '运行中...' : '重跑核查' }}
        </button>
        <button class="btn-ghost"
                :disabled="bulkRunning || !!isRunning || unresolvedCount === 0"
                @click="bulkFixAll">
          <Loader2 v-if="bulkRunning" :size="13" class="animate-spin" />
          <Wand2 v-else :size="13" />
          {{ bulkRunning ? `修复中 ${bulkFix?.done}/${bulkFix?.total}` : `一键修复全部 (${unresolvedCount})` }}
        </button>
        <span v-if="bulkFix && !bulkRunning" class="text-xs text-text-muted ml-2">
          ✓ 改了 {{ bulkFix.replaced }} 条 · 无需改 {{ bulkFix.skipped }} 条
          <span v-if="bulkFix.failed > 0">· <span class="text-status-failed">失败 {{ bulkFix.failed }} 条</span></span>
        </span>
        <span v-if="isRunning" class="text-xs text-status-running flex items-center gap-1.5 ml-2">
          <Loader2 :size="12" class="animate-spin" />
          {{ progressText }}
          <span v-if="activeRun" class="text-text-muted">· run {{ activeRun.id }}</span>
        </span>
        <span v-if="runMsg && !isRunning" class="text-sm ml-2"
              :class="runMsg.startsWith('失败') ? 'text-status-failed' : 'text-status-done'">
          {{ runMsg }}
        </span>
      </div>

      <div v-if="loading && issues.length === 0" class="card p-12 text-center">
        <Loader2 :size="20" class="animate-spin text-text-muted mx-auto" />
      </div>

      <div v-else-if="issues.length === 0" class="card p-12 text-center text-text-muted text-sm">
        <CheckCircle2 :size="32" class="mx-auto mb-3 text-text-muted opacity-40" />
        没有发现问题(或还没跑过核查)
      </div>

      <ul v-else class="flex flex-col gap-3">
        <li v-for="i in issues" :key="i.id" class="card p-5"
            :class="i.resolved ? 'opacity-60' : ''">
          <header class="flex items-start justify-between gap-3 mb-3">
            <div class="flex items-center gap-2 text-xs flex-wrap">
              <span class="font-mono text-text-muted">#{{ i.id }}</span>
              <span v-if="i.lineNumber" class="text-text-muted">L{{ i.lineNumber }}</span>
              <span v-if="i.issueType" class="chip bg-surface-tertiary text-text-secondary">{{ i.issueType }}</span>
              <span v-if="i.severity" class="chip" :class="SEVERITY_STYLE[i.severity] || 'bg-text-muted/15 text-text-muted'">
                {{ SEVERITY_LABELS[i.severity] || i.severity }}
              </span>
              <span v-if="i.credibility" class="chip bg-accent-soft text-accent">可信度 {{ i.credibility }}</span>
              <span v-if="i.resolved" class="chip bg-status-done/15 text-status-done flex items-center gap-1">
                <CheckCircle2 :size="11" /> 已解决
              </span>
            </div>
            <TimeText :value="i.createdAt" relative class="text-xs text-text-muted shrink-0" />
          </header>

          <div v-if="i.originalText" class="text-sm text-text-secondary border-l-2 border-status-failed/40 pl-3 py-1 mb-2 leading-relaxed">
            {{ i.originalText }}
          </div>
          <div v-if="i.suggestion" class="text-sm text-text-primary leading-relaxed">
            <span class="text-text-muted text-xs">建议:</span>{{ i.suggestion }}
          </div>
          <a v-if="i.sourceUrl" :href="i.sourceUrl" target="_blank"
             class="text-xs text-accent hover:underline mt-2 inline-block">
            查证来源 ↗
          </a>

          <div v-if="fixResults[i.id]" class="mt-3 p-3 rounded-md text-xs"
               :class="fixResults[i.id].applied
                 ? 'bg-status-done/10 border border-status-done/30'
                 : 'bg-text-muted/10 border border-border-subtle'">
            <div class="flex items-center gap-2 mb-1.5">
              <Wand2 :size="12" :class="fixResults[i.id].applied ? 'text-status-done' : 'text-text-muted'" />
              <span class="font-semibold" :class="fixResults[i.id].applied ? 'text-status-done' : 'text-text-secondary'">
                {{ fixResults[i.id].applied ? '已修复' : 'LLM 判定无需改' }}
              </span>
              <span class="text-text-muted">
                · {{ fixResults[i.id].sectionCode }} · {{ fixResults[i.id].rationale }}
              </span>
            </div>
            <template v-if="fixResults[i.id].applied">
              <div class="text-text-muted line-through whitespace-pre-wrap">{{ fixResults[i.id].before }}</div>
              <div class="text-status-done whitespace-pre-wrap mt-1">{{ fixResults[i.id].after }}</div>
            </template>
          </div>

          <div v-if="issueError[i.id]"
               class="mt-2 text-xs text-status-failed bg-status-failed/10 border border-status-failed/30 rounded px-2 py-1.5">
            ⚠ {{ issueError[i.id] }}
          </div>

          <div v-if="!i.resolved" class="flex items-center gap-2 mt-3 pt-3 border-t border-border-subtle">
            <button class="chip cursor-pointer text-xs bg-accent-soft text-accent disabled:opacity-50 flex items-center gap-1"
                    :disabled="!!issueBusy[i.id]" @click="applyFix(i)">
              <Loader2 v-if="issueBusy[i.id] === 'applying'" :size="11" class="animate-spin" />
              <Wand2 v-else :size="11" />
              {{ issueBusy[i.id] === 'applying' ? '正在修…' : '一键修复' }}
            </button>
            <button class="chip cursor-pointer text-xs bg-surface-tertiary text-text-secondary flex items-center gap-1"
                    @click="jumpToScript">
              <Pencil :size="11" /> 手动改
            </button>
            <button class="chip cursor-pointer text-xs bg-surface-tertiary text-text-muted disabled:opacity-50 flex items-center gap-1 ml-auto"
                    :disabled="!!issueBusy[i.id]" @click="dismiss(i)">
              <Loader2 v-if="issueBusy[i.id] === 'dismissing'" :size="11" class="animate-spin" />
              <X v-else :size="11" />
              {{ issueBusy[i.id] === 'dismissing' ? '...' : '忽略' }}
            </button>
          </div>
        </li>
      </ul>
    </div>
  </div>
</template>
