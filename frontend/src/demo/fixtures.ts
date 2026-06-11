/**
 * Demo fixtures 总表
 *
 * 这个文件是 demo mode 的"剧本"。每个 entry 是 "<METHOD> <URL 模板>" → 数据。
 * URL 模板把数字 id 写成 :id,如 GET /topics/:id。
 *
 * Entry 类型可以是:
 *   - 直接的对象/数组(纯静态)
 *   - 函数:(config) => data(根据请求参数返回不同数据,如 listTopics(status=DRAFT) vs status=PUBLISHED)
 *
 * 真实 fixture 数据来自 backend/scripts/export-demo-fixtures.sh 导出的 JSON,
 * 那个脚本从你跑过一次的真实流水线 dump 数据 + 拷资源到 public/demo-assets/。
 *
 * ⚠️ 当前是占位空数据,等用户跑完流水线 + 导出脚本后自动填充。
 */

import type { InternalAxiosRequestConfig } from 'axios'

// 等用户跑完流水线后,这些 import 会指向真实数据
// import topicsFixture from './fixtures/topics.json'
// import scriptFixture from './fixtures/script-1.json'
// ... 暂时全用空占位

export type FixtureFn = (config: InternalAxiosRequestConfig) => unknown
export type FixtureEntry = unknown | FixtureFn

/**
 * 主表:把 method + URL 模板映射到响应数据
 *
 * 这里只列演示路径会用到的核心 endpoints。其余未列的会被 interceptor 默认返回 null,
 * 不会让 demo 站炸,只是 console 里会有一行 warn。
 */
export const fixturesTable: Record<string, FixtureEntry> = {
  // ─── Preset ───────────────────────────────────────────
  'GET /presets': [],
  'GET /presets/:id': null,
  'GET /presets/by-name/freeform': null,

  // ─── Topic ────────────────────────────────────────────
  'GET /topics': { content: [], totalElements: 0, totalPages: 0, number: 0, size: 30 },
  'GET /topics/:id': null,
  'GET /topics/:id/lineage': [],
  'POST /topics/brainstorm': [],
  'POST /topics/:id/scripts/generate': null,
  'POST /topics/:id/scripts/generate-async': () => ({ runId: 1001 }),
  'PATCH /topics/:id': (cfg) => cfg.data, // 假装保存成功,原样返回
  'DELETE /topics/:id': null,

  // ─── Script ───────────────────────────────────────────
  'GET /scripts/:id': null,
  'GET /scripts/:id/storyboard': [],
  'POST /scripts/:id/storyboard/generate': () => ({ runId: 1002 }),
  'GET /scripts/:id/images': [],
  'POST /scripts/:id/images/generate': () => ({ runId: 1003 }),
  'GET /scripts/:id/voice': null,
  'POST /scripts/:id/voice/generate': () => ({ runId: 1004 }),
  'GET /scripts/:id/videos': [],
  'GET /scripts/:id/factcheck': [],
  'POST /scripts/:id/factcheck-async': () => ({ runId: 1005 }),
  'GET /scripts/:id/covers': [],
  'POST /scripts/:id/covers': () => ({ runId: 1006 }),

  // ─── Pipeline run ─────────────────────────────────────
  // GET /runs/:id 走 interceptor 里的特殊 getRunStatus(),不在这里

  // ─── BGM ──────────────────────────────────────────────
  'GET /bgm/scripts/:id/recommend': [],
  'GET /bgm/scripts/:id/tracks': [],
  'GET /bgm/scripts/:id/choice': null,
  'POST /bgm/scripts/:id/select': null,

  // ─── Agent ────────────────────────────────────────────
  'GET /agent/tools': [],
  'GET /agent/sessions': [],
  'POST /agent/sessions': () => ({ id: 1, title: 'Demo 对话', createdAt: new Date(0).toISOString() }),
  'GET /agent/sessions/:id/messages': [],

  // ─── Insights ─────────────────────────────────────────
  'GET /insights/top-bottom': { top: [], bottom: [] },
  'GET /insights/dimension-weights': [],
  'GET /insights/weekly-review': null,
  'GET /analytics/daily-trend': [],
  'GET /analytics/compare': { rows: [] },
  'GET /reviews/weekly': [],

  // ─── 其他 ─────────────────────────────────────────────
  'GET /config': {},
  'GET /brand-identity': null,
  'GET /published-videos': { content: [], totalElements: 0 },
  'GET /genre-stats': [],
  'GET /series': [],
}

// ─── 长任务模拟 ─────────────────────────────────────────
// 长任务流水线(分镜/生图/配音/视频)前端会轮询 GET /runs/{runId} 看进度。
// 这里给每个 runId 一个内部状态机:第一次问 = RUNNING,过 N 秒后再问 = DONE。

interface RunProgress {
  startedAt: number
  durationMs: number
  scriptId?: number
}

const runs: Record<number, RunProgress> = {}

export function getRunStatus(runId: number): {
  id: number
  status: string
  progress: number
  scriptId?: number
} {
  // 不存在 → 自动开一个跑 5 秒的(覆盖前端调用 generate-async 后立即问的场景)
  if (!runs[runId]) {
    runs[runId] = { startedAt: Date.now(), durationMs: 5000, scriptId: 1 }
  }
  const r = runs[runId]
  const elapsed = Date.now() - r.startedAt
  if (elapsed >= r.durationMs) {
    return { id: runId, status: 'DONE', progress: 100, scriptId: r.scriptId }
  }
  return {
    id: runId,
    status: 'RUNNING',
    progress: Math.floor((elapsed / r.durationMs) * 100),
    scriptId: r.scriptId,
  }
}

/** 重置所有 run 状态(给 DemoBanner 的 "重新演示" 按钮用) */
export function resetRuns(): void {
  for (const k of Object.keys(runs)) delete runs[Number(k)]
}
