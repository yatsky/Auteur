import { http } from './client'

/**
 * UI 可编辑的运行时配置(LLM 中转/对象存储/语音/BGM 等密钥)。
 * RuntimeConfig 优先读 DB,空时回落 application-local.yml。
 */

export interface AppConfigItem {
  configKey: string
  description: string | null
  secret: boolean
  category: string
  sortOrder: number
  /** DB 里是否有值。true=用户已在 UI 填过(或 autoImport 自动迁移过)*/
  hasDbValue: boolean
  /** 显示值。secret=true 时已被后端 mask 成 abcd****wxyz 形式 */
  displayValue: string
  updatedAt: string | null
}

export async function listAppConfig(): Promise<AppConfigItem[]> {
  const { data } = await http.get<AppConfigItem[]>('/config')
  return data
}

/**
 * 批量保存。kv 是 { key: value } 对;value="" 表示清空走 yml 兜底。
 * secret 字段值若是 mask 占位(abcd****wxyz),前端不应传过来 — 只把"用户改过的"键值传 PUT。
 */
export async function updateAppConfig(kv: Record<string, string>): Promise<{ updated: number; keys: string[] }> {
  const { data } = await http.put<{ updated: number; keys: string[] }>('/config', kv)
  return data
}
