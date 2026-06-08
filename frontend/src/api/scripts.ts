import { http } from './client'
import type {
  ScriptDetailResponse, ScriptListItem, SpringPage, FactCheckIssue, StoryboardShot, ImageAsset, VoiceAsset, VideoAsset,
  CoverAsset, BrandIdentity,
} from '../types'

// 列表分页:?topicId 过滤;返回元素 enrich 了 projectName + 最近一条 run 的 stage/status/at
export async function listScripts(params: {
  topicId?: number
  page?: number
  size?: number
} = {}): Promise<SpringPage<ScriptListItem>> {
  const { data } = await http.get<SpringPage<ScriptListItem>>('/scripts', { params })
  return data
}

export async function getScript(scriptId: number): Promise<ScriptDetailResponse> {
  const { data } = await http.get<ScriptDetailResponse>(`/scripts/${scriptId}`)
  return data
}

// 删除脚本(连带 cascade 清理 sections / shots / images / voice / video / cover)。
// published_video / pipeline_run 是软引用,保留作历史。
export async function deleteScript(scriptId: number): Promise<void> {
  await http.delete(`/scripts/${scriptId}`)
}

// 编辑某段脚本(配合事实核查的 fix 闭环)。后端会同步重建 script.full_text。
export async function updateScriptSection(
  scriptId: number,
  sectionId: number,
  body: { textContent: string; title: string | null },
) {
  const { data } = await http.put(`/scripts/${scriptId}/sections/${sectionId}`, body)
  return data
}

// 重新生成脚本(version+1):带一段可选的「锚点」自由指令塞进 user prompt,后端反查 topicId 重跑。
// 立即返回 runId,前端轮询 /api/runs/{runId},DONE 后用 run.scriptId 跳新版。
export async function regenerateScriptAsync(
  scriptId: number,
  anchor: string | null,
): Promise<{ runId: number }> {
  const { data } = await http.post<{ runId: number }>(
    `/scripts/${scriptId}/regenerate-async`,
    { anchor: anchor && anchor.trim() ? anchor.trim() : null },
  )
  return data
}

// 对齐画面:用户改完段落文案后刷一遍 section / shot 的时间戳让画面切换跟朗读节奏对齐.
// 后端同步返回(纯算,毫秒级);有 voice+SRT 走真实对齐,否则按 4.5 字/秒估算.
export interface AlignmentResult {
  mode: 'srt' | 'estimate'
  totalSeconds: number
  sectionsUpdated: number
  shotsUpdated: number
  shotsSkipped: number
  note: string
}

export async function alignScriptTiming(scriptId: number): Promise<AlignmentResult> {
  const { data } = await http.post<AlignmentResult>(`/scripts/${scriptId}/align-timing`)
  return data
}

// FactCheck
export async function listIssues(scriptId: number): Promise<FactCheckIssue[]> {
  const { data } = await http.get<FactCheckIssue[]>(`/scripts/${scriptId}/issues`)
  return data
}

export async function runFactCheck(scriptId: number): Promise<FactCheckIssue[]> {
  // factcheck = 草稿 + N 个 verify(每个 grok 20-100s)。实测 6 issue 跑过 257s。
  // 但 timeout 不能给得太离谱(grok 偶尔单条 100s 就够爆的),给 360s + 前端在超时后轮询 listIssues 兜住
  const { data } = await http.post<FactCheckIssue[]>(`/scripts/${scriptId}/factcheck`, null, {
    timeout: 360_000,
  })
  return data
}

// 异步路径:立即返回 runId,前端轮询 GET /api/runs/{runId} 看进度。N×variable 延迟首选。
export async function runFactCheckAsync(scriptId: number): Promise<{ runId: number }> {
  const { data } = await http.post<{ runId: number }>(`/scripts/${scriptId}/factcheck-async`)
  return data
}

export interface ApplyFixResult {
  applied: boolean         // true=真改了 / false=LLM 判定无需改(仍然 resolved=true)
  action: string           // "replace" | "no_change"
  rationale: string
  before: string
  after: string
  sectionId: number
  sectionCode: string
}

// 一键修复:后端再调一次便宜 LLM 把 suggestion 抽换成精确替换串,改 section 文本+重建 fullText+标 resolved.
export async function applyIssueFix(issueId: number): Promise<ApplyFixResult> {
  const { data } = await http.post<ApplyFixResult>(`/factcheck-issues/${issueId}/apply`, null, {
    timeout: 60_000,  // DeepSeek-V3.2 通常 2-5s,留 60s 兜路由抖动
  })
  return data
}

// 忽略:不改文,只 resolved=true.
export async function dismissIssue(issueId: number) {
  const { data } = await http.post(`/factcheck-issues/${issueId}/dismiss`)
  return data
}

// Storyboard
export async function listShots(scriptId: number): Promise<StoryboardShot[]> {
  const { data } = await http.get<StoryboardShot[]>(`/scripts/${scriptId}/shots`)
  return data
}

export async function generateStoryboard(
  scriptId: number,
  force = false,
): Promise<StoryboardShot[]> {
  // storyboard 走 claude-opus 切 8 镜,实测 30-50s
  const { data } = await http.post<StoryboardShot[]>(
    `/scripts/${scriptId}/storyboard/generate`,
    null,
    { params: { force }, timeout: 180_000 },
  )
  return data
}

// 异步路径:立即返回 runId,前端轮询 GET /api/runs/{runId},DONE 后再拉 listShots
export async function generateStoryboardAsync(
  scriptId: number,
  force = false,
): Promise<{ runId: number }> {
  const { data } = await http.post<{ runId: number }>(
    `/scripts/${scriptId}/storyboard/generate-async`,
    null,
    { params: { force } },
  )
  return data
}

// Images
export async function listImages(scriptId: number): Promise<ImageAsset[]> {
  const { data } = await http.get<ImageAsset[]>(`/scripts/${scriptId}/images`)
  return data
}

export async function generateImagesAsync(
  scriptId: number,
  limit?: number,
  force = false,
): Promise<{ runId: number }> {
  const { data } = await http.post<{ runId: number }>(
    `/scripts/${scriptId}/images/generate-async`,
    null,
    { params: { limit, force } },
  )
  return data
}

export async function auditImagesAsync(scriptId: number): Promise<{ runId: number }> {
  const { data } = await http.post<{ runId: number }>(
    `/scripts/${scriptId}/images/audit-async`,
  )
  return data
}

// 单镜重生:删该 shot 已有 asset,重跑一次模型。立即返回 runId,前端轮询。
export async function regenerateImageForShotAsync(shotId: number): Promise<{ runId: number }> {
  const { data } = await http.post<{ runId: number }>(
    `/shots/${shotId}/images/regen-async`,
  )
  return data
}

// 手动改 prompt（自动脱敏失败后的人工兜底）。null/undefined = 不动该字段；空字符串 = 清空。
export async function updateShotPrompt(
  shotId: number,
  body: { promptZh?: string | null; promptEn?: string | null; negativePrompt?: string | null },
): Promise<StoryboardShot> {
  const { data } = await http.put<StoryboardShot>(`/shots/${shotId}/prompt`, body)
  return data
}

// 单图重审:对指定 asset 重新跑一次审图(不会触发 auto-regen)。立即返回 runId,前端轮询。
export async function auditImageAsync(assetId: number): Promise<{ runId: number }> {
  const { data } = await http.post<{ runId: number }>(
    `/images/${assetId}/audit-async`,
  )
  return data
}

// 把这张 asset 选作本镜 final(同 shot 下其他 asset 自动 unset)。
// 用户在分镜工作台点击图片卡片直接触发,无需走"重生"。
export async function selectImageAsFinal(assetId: number): Promise<{ assetId: number; shotId: number; isFinal: boolean }> {
  const { data } = await http.post<{ assetId: number; shotId: number; isFinal: boolean }>(
    `/images/${assetId}/select-final`,
  )
  return data
}

// Voice
export async function listVoices(scriptId: number): Promise<VoiceAsset[]> {
  const { data } = await http.get<VoiceAsset[]>(`/scripts/${scriptId}/voice`)
  return data
}

export interface GenerateVoiceParams {
  voiceModel: string
  voiceLabel?: string
  speed?: number
  pitch?: number
  subtitleStyle?: string  // standard | highlight
  markFinal?: boolean
}

export async function generateVoiceAsync(
  scriptId: number,
  params: GenerateVoiceParams,
): Promise<{ runId: number }> {
  const { data } = await http.post<{ runId: number }>(
    `/scripts/${scriptId}/voice/generate-async`,
    params,
  )
  return data
}

// Video
export async function listVideos(scriptId: number): Promise<VideoAsset[]> {
  const { data } = await http.get<VideoAsset[]>(`/scripts/${scriptId}/video`)
  return data
}

export interface RenderVideoParams {
  voiceAssetId?: number | null  // null/undefined = 后端自动选 final voice / 最新 voice
  format?: string               // 9:16 / 16:9
  width?: number
  height?: number
  markFinal?: boolean
}

export async function renderVideoAsync(
  scriptId: number,
  params: RenderVideoParams = {},
): Promise<{ runId: number }> {
  const { data } = await http.post<{ runId: number }>(
    `/scripts/${scriptId}/video/render-async`,
    params,
  )
  return data
}

// Cover
export interface GenerateCoverParams {
  templateId?: string
  titleText?: string
  heroImageUrl?: string | null   // /api/files/voice/... 或远程 OSS URL,后端反推到磁盘或下载到 work-dir
}

export async function generateCoversAsync(
  scriptId: number,
  params: GenerateCoverParams,
): Promise<{ runId: number }> {
  const { data } = await http.post<{ runId: number }>(
    `/scripts/${scriptId}/covers/generate-async`,
    params,
  )
  return data
}

export async function listCovers(scriptId: number): Promise<CoverAsset[]> {
  const { data } = await http.get<CoverAsset[]>(`/scripts/${scriptId}/covers`)
  return data
}

// 表单默认值:title=topic.title
export async function getCoverDefaults(scriptId: number): Promise<{ title: string }> {
  const { data } = await http.get<{ title: string }>(
    `/scripts/${scriptId}/covers/defaults`,
  )
  return data
}

// 标记某 ratio 行为最终封面(同 script 同 ratio 互斥)
export async function finalizeCover(coverId: number): Promise<CoverAsset> {
  const { data } = await http.post<CoverAsset>(`/covers/${coverId}/finalize`)
  return data
}

// Brand Identity (单行表)
export async function getBrandIdentity(): Promise<BrandIdentity> {
  const { data } = await http.get<BrandIdentity>(`/brand-identity`)
  return data
}

export async function saveBrandIdentity(brand: Partial<BrandIdentity>): Promise<BrandIdentity> {
  const { data } = await http.put<BrandIdentity>(`/brand-identity`, brand)
  return data
}
