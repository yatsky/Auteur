import { http } from './client'

// chat 走 fetch + ReadableStream(SSE),axios 不支持流式响应
export interface AgentSession {
  id: number
  title: string | null
  model: string | null
  systemPromptVersion: string | null
  createdAt: string
  updatedAt: string
}

/**
 * 工具消息状态:
 *   - CANCELLED  会话取消时给孤立 tool_call 补的 placeholder(不是用户拒绝,是整轮被中断)
 */
export type ToolStatus = 'OK' | 'ERROR' | 'REJECTED' | 'CANCELLED'

export interface AgentMessage {
  id: number
  sessionId: number
  seq: number
  role: 'user' | 'assistant' | 'tool' | 'system'
  content: string | null
  toolCallsJson: string | null
  toolCallId: string | null
  toolName: string | null
  toolArgsJson: string | null
  toolStatus: ToolStatus | null
  inputTokens: number | null
  outputTokens: number | null
  durationMs: number | null
  createdAt: string
}

export async function listSessions(): Promise<AgentSession[]> {
  const { data } = await http.get<AgentSession[]>('/agent/sessions')
  return data
}

export async function createSession(model?: string): Promise<AgentSession> {
  const { data } = await http.post<AgentSession>('/agent/sessions', model ? { model } : {})
  return data
}

export async function getSessionMessages(id: number): Promise<AgentMessage[]> {
  const { data } = await http.get<AgentMessage[]>(`/agent/sessions/${id}/messages`)
  return data
}

export async function deleteSession(id: number): Promise<void> {
  await http.delete(`/agent/sessions/${id}`)
}

export async function listTools(): Promise<{ tools: string[]; definitions: any[] }> {
  const { data } = await http.get<{ tools: string[]; definitions: any[] }>('/agent/tools')
  return data
}

/**
 * HITL:用户对一个待审批 tool_call 给出决定。
 */
export async function approveTool(
  sessionId: number,
  toolCallId: string,
  approved: boolean,
  reason?: string,
): Promise<{ ok: boolean; toolCallId: string; approved: boolean; note: string }> {
  const { data } = await http.post(`/agent/sessions/${sessionId}/approve`, {
    toolCallId,
    approved,
    reason: reason ?? null,
  })
  return data
}

/**
 * 显式取消正在跑的 turn。幂等:找不到活跃 turn 不报错。
 */
export async function cancelSession(sessionId: number): Promise<{ ok: boolean; sessionId: number; note: string }> {
  const { data } = await http.post(`/agent/sessions/${sessionId}/cancel`)
  return data
}

export type AgentEventType =
  | 'user_saved'
  | 'assistant_chunk'
  | 'assistant_done'
  | 'tool_call'
  | 'tool_approval_request'
  | 'tool_result'
  | 'done'
  | 'error'

export interface AgentEventPayload {
  type: AgentEventType
  data: any
}

/**
 * 前端不能用 EventSource(后端是 POST 触发),改用 fetch + ReadableStream 解析 text/event-stream。
 * 返回 abort 函数,用于中途取消。
 */
export function sendChatStream(
  sessionId: number,
  message: string,
  onEvent: (ev: AgentEventPayload) => void,
  onDone: () => void,
  onError: (err: Error) => void,
): () => void {
  const ctrl = new AbortController()

  fetch(`/api/agent/sessions/${sessionId}/chat`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
    body: JSON.stringify({ message }),
    signal: ctrl.signal,
  })
    .then(async (resp) => {
      if (!resp.ok || !resp.body) {
        const text = await resp.text().catch(() => '')
        throw new Error(`SSE 请求失败 ${resp.status}: ${text}`)
      }
      const reader = resp.body.getReader()
      const decoder = new TextDecoder('utf-8')
      let buffer = ''
      while (true) {
        const { value, done } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })
        let idx
        while ((idx = buffer.indexOf('\n\n')) >= 0) {
          const block = buffer.slice(0, idx)
          buffer = buffer.slice(idx + 2)
          const ev = parseSseBlock(block)
          if (ev) onEvent(ev)
        }
      }
      onDone()
    })
    .catch((e) => {
      if ((e as any)?.name === 'AbortError') return
      onError(e instanceof Error ? e : new Error(String(e)))
    })

  return () => ctrl.abort()
}

function parseSseBlock(block: string): AgentEventPayload | null {
  let event = 'message'
  const dataLines: string[] = []
  for (const line of block.split('\n')) {
    if (line.startsWith('event:')) event = line.slice(6).trim()
    else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim())
  }
  if (dataLines.length === 0) return null
  const dataStr = dataLines.join('\n')
  try {
    return { type: event as AgentEventType, data: JSON.parse(dataStr) }
  } catch {
    return { type: event as AgentEventType, data: dataStr }
  }
}
