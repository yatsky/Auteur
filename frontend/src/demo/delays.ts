/**
 * Demo 模式下伪造的 loading 时长(毫秒)
 *
 * 设计思路:
 *   - 太快 → 没"真在跑"的感觉,demo 看着像静态切图
 *   - 太慢 → 用户失去耐心
 *   - 实际真跑大概慢 100x 以上,我们模拟"魔法版速度"——
 *     LLM 调用假装 1-3 秒,流水线长任务假装 3-8 秒
 *
 * 单位:毫秒
 */

export interface DelayProfile {
  /** 普通 GET(列表/详情/查状态)—— 几乎不延时 */
  fast: [number, number]
  /** LLM 同步调用(选题脑暴/生成脚本)—— 假装 LLM 在思考 */
  llm: [number, number]
  /** 长任务触发后立即返回(分镜/生图/配音/视频)—— 短延时,后续靠 runId 轮询模拟"渐进式完成" */
  trigger: [number, number]
  /** 长任务"完成"前的等待(轮询 runs/{id} 假装从 RUNNING → DONE) */
  longRun: [number, number]
}

export const DELAYS: DelayProfile = {
  fast: [50, 150],
  llm: [1200, 2400],
  trigger: [400, 800],
  longRun: [3000, 6000],
}

/** 在 [min, max] 之间随机一个时长,模拟真实抖动 */
export function jitter(range: [number, number]): number {
  const [min, max] = range
  return min + Math.random() * (max - min)
}

/** await 一段 jittered delay */
export function delay(range: [number, number]): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, jitter(range)))
}
