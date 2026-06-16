<script setup lang="ts">
import { ref, watch } from 'vue'
import { FileSpreadsheet, Loader2, Upload, X } from 'lucide-vue-next'
import { useRouter } from 'vue-router'
import {
  bulkImportPublishedVideos, type BulkResult, type PublishedVideoUpsert,
} from '../../api/publishedVideos'
import { bulkImportGenreStats, type GenreStatBulkResult, type GenreStatUpsert } from '../../api/genreStats'
import { getTopic } from '../../api/topics'
import { extractError } from '../../lib/format'
import type { ScriptListItem } from '../../types'

const props = defineProps<{
  open: boolean
  scripts: ScriptListItem[]
}>()

const emit = defineEmits<{
  (e: 'update:open', value: boolean): void
  (e: 'imported'): void
}>()

const router = useRouter()

const csvFile = ref<File | null>(null)
const csvFileName = ref<string>('')
const csvDragOver = ref(false)
const csvDefaultPlatform = ref<string>('抖音')
const csvScriptId = ref<number | null>(null)
const csvScriptDefaults = ref<{
  topicId: number | null
  projectName: string | null
  durationSeconds: number | null
  hookTemplate: string | null
} | null>(null)
const csvImporting = ref(false)
const csvResult = ref<BulkResult | null>(null)
const csvError = ref<string | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)

type ImportMode = 'video' | 'aggregate'
const csvMode = ref<ImportMode>('video')
const csvPeriodStart = ref<string>('')
const csvPeriodEnd = ref<string>('')
const csvAggregateResult = ref<GenreStatBulkResult | null>(null)
const pendingAggregateRows = ref<GenreStatUpsert[] | null>(null)
const pendingAggregateWarnings = ref<string[]>([])

watch(
  () => props.open,
  (open) => {
    if (!open) return
    csvFile.value = null
    csvFileName.value = ''
    csvScriptId.value = null
    csvScriptDefaults.value = null
    csvResult.value = null
    csvError.value = null
    csvMode.value = 'video'
    csvPeriodStart.value = ''
    csvPeriodEnd.value = ''
    csvAggregateResult.value = null
    pendingAggregateRows.value = null
    pendingAggregateWarnings.value = []
  },
)

async function onCsvScriptPick(idRaw: string | number | null) {
  const id = idRaw === '' || idRaw == null ? null : Number(idRaw)
  csvScriptId.value = id
  if (id == null) {
    csvScriptDefaults.value = null
    return
  }
  const s = props.scripts.find((x) => x.id === id)
  if (!s) {
    csvScriptDefaults.value = null
    return
  }
  let hookTemplate: string | null = null
  if (s.topicId != null) {
    try {
      const t = await getTopic(s.topicId)
      if (t.hookType) hookTemplate = t.hookType
    } catch { /* 静默 */ }
  }
  csvScriptDefaults.value = {
    topicId: s.topicId,
    projectName: s.projectName,
    durationSeconds: s.durationSeconds,
    hookTemplate,
  }
}

function scriptLabel(s: ScriptListItem): string {
  const name = s.projectName || `script #${s.id}`
  const dur = s.durationSeconds != null ? ` · ${s.durationSeconds}s` : ''
  const stage = s.lastRunStage ? ` · ${s.lastRunStage}` : ''
  return `#${s.id} · ${name}${dur}${stage}`
}

const CSV_HEADER_ALIASES: Record<string, keyof PublishedVideoUpsert> = {
  title: 'title', 标题: 'title', 视频名称: 'title', 作品名称: 'title', 作品标题: 'title',
  platform: 'platform', 平台: 'platform',
  publishedat: 'publishedAt', 'published_at': 'publishedAt', 发布时间: 'publishedAt',
  projectname: 'projectName', 'project_name': 'projectName', 项目名: 'projectName',
  platformvideoid: 'platformVideoId', 'platform_video_id': 'platformVideoId', vid: 'platformVideoId', 作品id: 'platformVideoId', 视频id: 'platformVideoId',
  scriptid: 'scriptId', 'script_id': 'scriptId',
  topicid: 'topicId', 'topic_id': 'topicId',
  durationseconds: 'durationSeconds', 'duration_seconds': 'durationSeconds', duration: 'durationSeconds', 时长: 'durationSeconds', '作品时长(秒)': 'durationSeconds', 视频时长: 'durationSeconds',
  views: 'views', 播放: 'views', 播放量: 'views', 播放数: 'views', 播放次数: 'views',
  likes: 'likes', 点赞: 'likes', 点赞数: 'likes', 点赞量: 'likes',
  comments: 'comments', 评论: 'comments', 评论数: 'comments', 评论量: 'comments',
  shares: 'shares', 转发: 'shares', 分享: 'shares', 分享数: 'shares', 分享量: 'shares',
  retentionpct: 'retentionPct', 'retention_pct': 'retentionPct', retention: 'retentionPct', 完播率: 'retentionPct',
  avgplayseconds: 'avgPlaySeconds', 'avg_play_seconds': 'avgPlaySeconds', 平均播放时长: 'avgPlaySeconds',
  drop2spct: 'drop2sPct', 'drop_2s_pct': 'drop2sPct', '2s跳出率': 'drop2sPct', '2秒跳出率': 'drop2sPct',
  play5spct: 'play5sPct', 'play_5s_pct': 'play5sPct', '5s完播率': 'play5sPct', '5秒完播率': 'play5sPct',
  avgplayratiopct: 'avgPlayRatioPct', 'avg_play_ratio_pct': 'avgPlayRatioPct', 平均播放占比: 'avgPlayRatioPct',
  favoriteratepct: 'favoriteRatePct', 'favorite_rate_pct': 'favoriteRatePct', 收藏率: 'favoriteRatePct',
  dislikeratepct: 'dislikeRatePct', 'dislike_rate_pct': 'dislikeRatePct', 不感兴趣率: 'dislikeRatePct',
  hookctr: 'hookCtr', 'hook_ctr': 'hookCtr', 钩子点击率: 'hookCtr',
  costyuan: 'costYuan', 'cost_yuan': 'costYuan', cost: 'costYuan', 成本: 'costYuan',
  hooktemplate: 'hookTemplate', 'hook_template': 'hookTemplate', 钩子模板: 'hookTemplate',
  notes: 'notes', 备注: 'notes',
}

function detectDelim(text: string): ',' | '\t' {
  const headerLine = text.split(/\r?\n/, 1)[0] || ''
  return headerLine.includes('\t') ? '\t' : ','
}

const AGGREGATE_REPORT_MARKERS = ['体裁', '垂类', '周期内投稿量', '条均点击率', '条均5s完播率',
  '条均2s跳出率', '条均播放时长', '播放量中位数', '条均点赞数', '条均评论量', '条均分享量']

function isAggregateReport(headerCells: string[]): boolean {
  const hits = headerCells.filter((h) => AGGREGATE_REPORT_MARKERS.includes(h)).length
  return hits >= 3
}

const AGG_HEADER_ALIASES: Record<string, keyof GenreStatUpsert> = {
  体裁: 'genre', genre: 'genre',
  垂类: 'vertical', vertical: 'vertical', '二级分类': 'vertical',
  周期内投稿量: 'submissionCount', 投稿量: 'submissionCount', submissioncount: 'submissionCount', 'submission_count': 'submissionCount',
  条均点击率: 'avgCtrPct', avgctrpct: 'avgCtrPct', 'avg_ctr_pct': 'avgCtrPct',
  '条均5s完播率': 'avgPlay5sPct', avgplay5spct: 'avgPlay5sPct', 'avg_play_5s_pct': 'avgPlay5sPct',
  '条均2s跳出率': 'avgDrop2sPct', avgdrop2spct: 'avgDrop2sPct', 'avg_drop_2s_pct': 'avgDrop2sPct',
  条均播放时长: 'avgPlaySeconds', avgplayseconds: 'avgPlaySeconds', 'avg_play_seconds': 'avgPlaySeconds',
  播放量中位数: 'medianViews', medianviews: 'medianViews', 'median_views': 'medianViews',
  条均点赞数: 'avgLikes', avglikes: 'avgLikes', 'avg_likes': 'avgLikes',
  条均评论量: 'avgComments', avgcomments: 'avgComments', 'avg_comments': 'avgComments',
  条均分享量: 'avgShares', avgshares: 'avgShares', 'avg_shares': 'avgShares',
}

const AGG_HEADER_IGNORED = new Set([
  '发布时间', 'publishedat', 'published_at',
  '项目名', '视频名称', '标题', 'title',
])

function parseAggregateCsv(text: string, delim: ',' | '\t', defaultPlatform: string): { rows: GenreStatUpsert[]; warnings: string[] } {
  const lines = text.split(/\r?\n/).map((l) => l.trim()).filter((l) => l.length > 0)
  if (lines.length < 2) return { rows: [], warnings: ['至少要表头 + 1 行数据'] }
  const headerCells = lines[0].split(delim).map((c) => c.trim())
  const headers = headerCells.map((h) => AGG_HEADER_ALIASES[h] || AGG_HEADER_ALIASES[h.toLowerCase()] || null)
  const warnings: string[] = []
  headerCells.forEach((h, i) => {
    if (headers[i] === null && !AGG_HEADER_IGNORED.has(h) && !AGG_HEADER_IGNORED.has(h.toLowerCase())) {
      warnings.push(`未识别的列名:${h}`)
    }
  })
  if (!headers.includes('genre' as any) || !headers.includes('vertical' as any)) {
    warnings.push('聚合表必须包含「体裁」+「垂类」列')
    return { rows: [], warnings }
  }
  const rows: GenreStatUpsert[] = []
  for (let li = 1; li < lines.length; li++) {
    const cells = lines[li].split(delim).map((c) => c.trim())
    const row: any = {
      platform: defaultPlatform, periodStart: '', periodEnd: '',
      genre: '', vertical: '',
    }
    headers.forEach((key, i) => {
      if (key == null) return
      const raw = cells[i]
      if (raw === undefined || raw === '') return
      const stripped = raw.replace(/[%,\s]/g, '').replace(/秒$/, '').replace(/s$/i, '')
      if (key === 'submissionCount' || key === 'medianViews') {
        const n = Number(stripped)
        if (!Number.isNaN(n)) row[key] = n
      } else if (key === 'avgCtrPct' || key === 'avgPlay5sPct' || key === 'avgDrop2sPct'
        || key === 'avgPlaySeconds' || key === 'avgLikes' || key === 'avgComments' || key === 'avgShares') {
        const n = Number(stripped)
        if (!Number.isNaN(n)) row[key] = n
      } else {
        row[key] = raw
      }
    })
    if (!row.genre || !row.vertical) continue
    rows.push(row as GenreStatUpsert)
  }
  return { rows, warnings }
}

function parseCsv(text: string, delim: ',' | '\t', defaultPlatform: string): { rows: PublishedVideoUpsert[]; warnings: string[] } {
  const lines = text.split(/\r?\n/).map((l) => l.trim()).filter((l) => l.length > 0)
  if (lines.length < 2) return { rows: [], warnings: ['至少要表头 + 1 行数据'] }
  const headerCells = lines[0].split(delim).map((c) => c.trim())
  if (isAggregateReport(headerCells)) {
    return { rows: [], warnings: ['这是聚合表(投稿作品.xlsx),请走「投稿作品聚合」模式'] }
  }
  const headers = headerCells.map((h) => CSV_HEADER_ALIASES[h.toLowerCase()] || null)
  const warnings: string[] = []
  headerCells.forEach((h, i) => {
    if (headers[i] === null) warnings.push(`未识别的列名:${h}`)
  })
  if ((!headers.includes('title' as any) && !headers.includes('projectName' as any)) || !headers.includes('publishedAt' as any)) {
    warnings.push('文件必须包含「发布时间」列 + 「项目名 / 视频名称 / 标题」中至少一列')
    return { rows: [], warnings }
  }
  const rows: PublishedVideoUpsert[] = []
  for (let li = 1; li < lines.length; li++) {
    const cells = lines[li].split(delim).map((c) => c.trim())
    const row: any = { platform: defaultPlatform, publishedAt: '', title: '' }
    headers.forEach((key, i) => {
      if (key == null) return
      const raw = cells[i]
      if (raw === undefined || raw === '') return
      const stripped = raw.replace(/[%,\s]/g, '').replace(/秒$/, '').replace(/s$/i, '')
      if (['views', 'likes', 'comments', 'shares', 'durationSeconds', 'scriptId', 'topicId'].includes(key)) {
        const n = Number(stripped)
        if (!Number.isNaN(n)) row[key] = n
      } else if (['retentionPct', 'hookCtr', 'costYuan', 'avgPlaySeconds', 'drop2sPct', 'play5sPct', 'avgPlayRatioPct', 'favoriteRatePct', 'dislikeRatePct'].includes(key)) {
        const n = Number(stripped)
        if (!Number.isNaN(n)) row[key] = n
      } else if (key === 'publishedAt') {
        const s = raw.replace(' ', 'T')
        row.publishedAt = s.length === 16 ? s + ':00' : s
      } else {
        row[key] = raw
      }
    })
    if ((!row.title || !row.title.trim()) && row.projectName) row.title = row.projectName
    rows.push(row as PublishedVideoUpsert)
  }
  return { rows, warnings }
}

async function readCsvFile(file: File): Promise<string> {
  const buffer = await file.arrayBuffer()
  const view = new Uint8Array(buffer)
  const start = view[0] === 0xEF && view[1] === 0xBB && view[2] === 0xBF ? 3 : 0
  return new TextDecoder('utf-8').decode(buffer.slice(start))
}

async function readXlsxFile(file: File): Promise<string> {
  const XLSX = await import('xlsx')
  const buffer = await file.arrayBuffer()
  const wb = XLSX.read(buffer, { type: 'array' })
  const firstSheetName = wb.SheetNames[0]
  if (!firstSheetName) throw new Error('Excel 文件里没有工作表')
  const sheet = wb.Sheets[firstSheetName]
  return XLSX.utils.sheet_to_csv(sheet, { FS: '\t' })
}

function onFilePicked(e: Event) {
  const input = e.target as HTMLInputElement
  const f = input.files?.[0]
  if (!f) return
  csvFile.value = f
  csvFileName.value = f.name
  csvResult.value = null
  csvError.value = null
}

function onFileDrop(e: DragEvent) {
  csvDragOver.value = false
  const f = e.dataTransfer?.files?.[0]
  if (!f) return
  csvFile.value = f
  csvFileName.value = f.name
  csvResult.value = null
  csvError.value = null
}

async function onCsvImport() {
  if (!csvFile.value) {
    csvError.value = '请先选择文件'
    return
  }
  csvError.value = null
  csvResult.value = null
  csvAggregateResult.value = null
  csvImporting.value = true
  try {
    const f = csvFile.value
    const ext = (f.name.toLowerCase().split('.').pop() || '').trim()
    let text: string
    let delim: ',' | '\t'
    if (ext === 'xlsx' || ext === 'xls') {
      text = await readXlsxFile(f)
      delim = '\t'
    } else if (ext === 'csv' || ext === 'txt' || ext === '') {
      text = await readCsvFile(f)
      delim = detectDelim(text)
    } else {
      throw new Error(`不支持的文件类型 .${ext},请选 .xlsx / .xls / .csv`)
    }

    const headerCells = (text.split(/\r?\n/, 1)[0] || '').split(delim).map((c) => c.trim())
    if (isAggregateReport(headerCells)) {
      const { rows, warnings } = parseAggregateCsv(text, delim, csvDefaultPlatform.value)
      if (rows.length === 0) {
        csvError.value = warnings.length > 0 ? warnings.join('\n') : '聚合表解析后没有可导入的行(检查体裁/垂类列是否为空)'
        return
      }
      csvMode.value = 'aggregate'
      pendingAggregateRows.value = rows
      pendingAggregateWarnings.value = warnings
      csvError.value = null
      return
    }

    csvMode.value = 'video'
    const { rows, warnings } = parseCsv(text, delim, csvDefaultPlatform.value)
    if (warnings.length > 0 && rows.length === 0) {
      csvError.value = warnings.join('\n')
      return
    }
    if (rows.length === 0) {
      csvError.value = '解析后没有可导入的行'
      return
    }
    if (csvScriptId.value != null) {
      const sid = csvScriptId.value
      const defs = csvScriptDefaults.value
      for (const row of rows) {
        if (row.scriptId == null) row.scriptId = sid
        if (defs) {
          if (row.topicId == null && defs.topicId != null) row.topicId = defs.topicId
          if ((!row.projectName || !row.projectName.trim()) && defs.projectName) {
            row.projectName = defs.projectName
            if (!row.title || !row.title.trim()) row.title = defs.projectName
          }
          if (row.durationSeconds == null && defs.durationSeconds != null) row.durationSeconds = defs.durationSeconds
          if ((!row.hookTemplate || !row.hookTemplate.trim()) && defs.hookTemplate) row.hookTemplate = defs.hookTemplate
        }
      }
    }
    const r = await bulkImportPublishedVideos(rows)
    csvResult.value = r
    if (warnings.length > 0) r.errors.unshift(...warnings.map((w) => '⚠️ ' + w))
    emit('imported')
  } catch (e: any) {
    csvError.value = extractError(e, '导入失败')
  } finally {
    csvImporting.value = false
  }
}

async function onAggregateSubmit() {
  if (!pendingAggregateRows.value || pendingAggregateRows.value.length === 0) {
    csvError.value = '没有解析到聚合数据,请先重新选文件'
    return
  }
  if (!csvPeriodStart.value || !csvPeriodEnd.value) {
    csvError.value = '请填写统计周期(开始 / 结束日期)'
    return
  }
  if (csvPeriodStart.value > csvPeriodEnd.value) {
    csvError.value = '开始日期不能晚于结束日期'
    return
  }
  csvError.value = null
  csvAggregateResult.value = null
  csvImporting.value = true
  try {
    const rows: GenreStatUpsert[] = pendingAggregateRows.value.map((r) => ({
      ...r,
      periodStart: csvPeriodStart.value,
      periodEnd: csvPeriodEnd.value,
      platform: r.platform || csvDefaultPlatform.value,
    }))
    const r = await bulkImportGenreStats(rows)
    csvAggregateResult.value = r
    if (pendingAggregateWarnings.value.length > 0) {
      r.errors.unshift(...pendingAggregateWarnings.value.map((w) => '⚠️ ' + w))
    }
  } catch (e: any) {
    csvError.value = extractError(e, '导入失败')
  } finally {
    csvImporting.value = false
  }
}

function close() {
  if (csvImporting.value) return
  emit('update:open', false)
}

function gotoGenreStats() {
  emit('update:open', false)
  router.push('/genre-stats')
}
</script>

<template>
  <div v-if="open"
       class="fixed inset-0 z-50 bg-black/60 flex items-center justify-center p-4"
       @click.self="close">
    <div class="card p-6 max-w-[640px] w-full max-h-[90vh] overflow-y-auto">
      <header class="flex items-center justify-between mb-4">
        <h2 class="text-lg font-semibold flex items-center gap-2">
          <Upload :size="18" class="text-accent" />
          <span v-if="csvMode === 'aggregate'">投稿作品聚合 · 体裁基准导入</span>
          <span v-else>导入 Excel / CSV 批量录入</span>
        </h2>
        <button class="text-text-muted hover:text-text-primary" :disabled="csvImporting" @click="close">
          <X :size="20" />
        </button>
      </header>

      <div v-if="csvMode === 'video'" class="text-xs text-text-muted mb-4 leading-relaxed">
        支持 <code class="font-mono">.xlsx / .xls / .csv</code> —— 创作者中心导出原生表头会自动识别。<br />
        必填列:<code class="font-mono">发布时间</code> + (<code class="font-mono">项目名</code> 或 <code class="font-mono">视频名称</code>)。<br />
        <span class="text-text-muted/70">检测到「投稿作品.xlsx」聚合表会自动切到体裁基准导入(KpiDrift 用)。</span>
      </div>

      <div v-else class="text-xs text-text-muted mb-4 leading-relaxed">
        已识别为「投稿作品.xlsx」按体裁/垂类汇总的聚合表 —— 解析到
        <span class="text-accent font-mono">{{ pendingAggregateRows?.length ?? 0 }}</span>
        行。
        <br />填写下方<span class="text-status-failed">统计周期</span>(导出文件标题里通常带这两个日期)后点导入,
        会落到体裁基准表,KpiDrift 用最新一份算"垂类基线"。
      </div>

      <label class="text-xs text-text-muted flex items-center gap-2 mb-3">
        默认平台<span class="text-text-muted/70">(文件里没有 platform / 平台 列时用)</span>
        <select v-model="csvDefaultPlatform" :disabled="csvImporting"
                class="bg-surface-tertiary border border-border-subtle rounded px-2 py-1 text-sm">
          <option value="抖音">抖音</option>
          <option value="B站">B站</option>
          <option value="小红书">小红书</option>
          <option value="视频号">视频号</option>
          <option value="快手">快手</option>
          <option value="西瓜">西瓜</option>
          <option value="知乎">知乎</option>
        </select>
      </label>

      <label v-if="csvMode === 'video'" class="flex flex-col gap-1.5 mb-3">
        <span class="text-xs text-text-muted">
          关联脚本 <span class="text-text-muted/70">(可选 · 选中后:① 给所有行的 topic / 项目名 / 时长 / 钩子模板兜底,行内 CSV 列优先;② 同脚本+平台+发布时间已存在则字段级合并,不再建新行)</span>
        </span>
        <select :value="csvScriptId ?? ''" :disabled="csvImporting"
                class="bg-surface-tertiary border border-border-subtle rounded-lg px-3 py-2 text-sm"
                @change="onCsvScriptPick(($event.target as HTMLSelectElement).value)">
          <option value="">— 不关联 —</option>
          <option v-for="s in scripts" :key="s.id" :value="s.id">
            {{ scriptLabel(s) }}
          </option>
        </select>
      </label>

      <div v-if="csvMode === 'aggregate'" class="grid grid-cols-2 gap-3 mb-3">
        <label class="flex flex-col gap-1.5">
          <span class="text-xs text-text-muted">统计周期 · 开始 <span class="text-status-failed">*</span></span>
          <input v-model="csvPeriodStart" type="date" :disabled="csvImporting"
                 class="bg-surface-tertiary border border-border-subtle rounded-lg px-3 py-2 text-sm font-mono" />
        </label>
        <label class="flex flex-col gap-1.5">
          <span class="text-xs text-text-muted">统计周期 · 结束 <span class="text-status-failed">*</span></span>
          <input v-model="csvPeriodEnd" type="date" :disabled="csvImporting"
                 class="bg-surface-tertiary border border-border-subtle rounded-lg px-3 py-2 text-sm font-mono" />
        </label>
      </div>

      <input ref="fileInput" type="file" accept=".xlsx,.xls,.csv,.txt"
             class="hidden" @change="onFilePicked" />

      <div
        :class="[
          'border-2 border-dashed rounded-lg px-6 py-10 text-center transition-colors cursor-pointer',
          csvDragOver ? 'border-accent bg-accent/5' : 'border-border-subtle hover:border-accent/40 hover:bg-surface-tertiary/40',
          csvImporting && 'opacity-50 pointer-events-none',
        ]"
        @click="fileInput?.click()"
        @dragover.prevent="csvDragOver = true"
        @dragleave.prevent="csvDragOver = false"
        @drop.prevent="onFileDrop"
      >
        <FileSpreadsheet :size="32" class="mx-auto text-text-muted mb-2" />
        <div v-if="!csvFile" class="text-sm text-text-secondary">
          点击选择文件,或拖拽到此处
        </div>
        <div v-else class="text-sm">
          <div class="text-text-primary font-mono">{{ csvFileName }}</div>
          <div class="text-xs text-text-muted mt-1">{{ (csvFile.size / 1024).toFixed(1) }} KB · 点此换文件</div>
        </div>
      </div>

      <div v-if="csvError" class="card p-3 mt-3 bg-status-failed/10 border-status-failed/30 text-xs text-status-failed whitespace-pre-wrap leading-relaxed">
        {{ csvError }}
      </div>

      <div v-if="csvResult" class="card p-3 mt-3 bg-status-done/10 border-status-done/30 text-xs">
        <div class="text-status-done">
          导入完成 — 新增 {{ csvResult.inserted }} 条 · 合并 {{ csvResult.updated }} 条<span v-if="csvResult.skipped > 0"> · 跳过 {{ csvResult.skipped }} 条</span>
        </div>
        <div v-if="csvResult.errors.length > 0" class="mt-2 text-status-failed">
          <div class="font-medium mb-1">{{ csvResult.errors.length }} 条警告 / 错误:</div>
          <ul class="list-disc pl-5 space-y-0.5">
            <li v-for="(err, i) in csvResult.errors" :key="i">{{ err }}</li>
          </ul>
        </div>
      </div>

      <div v-if="csvAggregateResult" class="card p-3 mt-3 bg-status-done/10 border-status-done/30 text-xs">
        <div class="text-status-done">
          体裁基准导入完成 — 新增 {{ csvAggregateResult.inserted }} 条 · 合并 {{ csvAggregateResult.updated }} 条<span v-if="csvAggregateResult.skipped > 0"> · 跳过 {{ csvAggregateResult.skipped }} 条</span>
        </div>
        <div class="text-text-muted mt-1">
          去 <a class="text-accent cursor-pointer" @click="gotoGenreStats">体裁基准</a> 看这一批快照
        </div>
        <div v-if="csvAggregateResult.errors.length > 0" class="mt-2 text-status-failed">
          <div class="font-medium mb-1">{{ csvAggregateResult.errors.length }} 条警告 / 错误:</div>
          <ul class="list-disc pl-5 space-y-0.5">
            <li v-for="(err, i) in csvAggregateResult.errors" :key="i">{{ err }}</li>
          </ul>
        </div>
      </div>

      <div class="flex items-center gap-2 mt-5">
        <button class="btn-ghost" :disabled="csvImporting" @click="close">关闭</button>
        <button v-if="csvMode === 'aggregate'"
                class="btn-primary ml-auto"
                :disabled="csvImporting || !pendingAggregateRows || !csvPeriodStart || !csvPeriodEnd"
                @click="onAggregateSubmit">
          <Loader2 v-if="csvImporting" :size="14" class="animate-spin" />
          {{ csvImporting ? '导入中…' : '导入体裁基准' }}
        </button>
        <button v-else class="btn-primary ml-auto" :disabled="csvImporting || !csvFile" @click="onCsvImport">
          <Loader2 v-if="csvImporting" :size="14" class="animate-spin" />
          {{ csvImporting ? '导入中…' : '导入' }}
        </button>
      </div>
    </div>
  </div>
</template>
