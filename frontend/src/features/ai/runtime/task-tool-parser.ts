export interface TaskToolArguments {
  subagentType: string | null
  prompt: string | null
  threadId: string | null
  maxTurns: number | null
}

export interface TaskToolReceipt {
  status: 'accepted'
  threadId: string
}

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

/**
 * 解析 task 调用参数 JSON（后端 TaskTool.parseArguments 的只读投影）：
 * subagent_type / prompt 必填，thread_id / max_turns 可选。
 * 严格使用规范字段名，绝不识别 session_id 或 maxTurns 别名。
 * 非数字的 max_turns 直接丢弃为 null，绝不 Number() 强转。
 */
export function parseTaskArguments(json: string): TaskToolArguments {
  let value: unknown
  try {
    value = JSON.parse(json)
  } catch {
    return { subagentType: null, prompt: null, threadId: null, maxTurns: null }
  }
  if (!isRecord(value)) {
    return { subagentType: null, prompt: null, threadId: null, maxTurns: null }
  }
  return {
    subagentType: nonBlankString(value.subagent_type),
    prompt: nonBlankString(value.prompt),
    threadId: nonBlankString(value.thread_id),
    maxTurns: positiveInteger(value.max_turns),
  }
}

/**
 * 解析 task 的即时受理收据 JSON：
 * 后端 TaskTool 仅返回一次受理回执 {"thread_id":"uuid","status":"accepted"}。
 *
 * 仅当为合法 JSON 对象、status 严格为 'accepted' 且 thread_id 为规范非空 UUID 字符串时返回收据；
 * 缺少 thread_id、非规范 UUID、completed、未知 status、畸形 JSON、XML 等一律返回 null，
 * 由调用方完整降级到原始文本展示，绝不把受理收据混淆为终态，也不隐瞒畸形收据。
 */
export function parseTaskReceipt(text: string): TaskToolReceipt | null {
  if (!text || !text.trim()) {
    return null
  }
  let value: unknown
  try {
    value = JSON.parse(text)
  } catch {
    return null
  }
  if (!isRecord(value)) {
    return null
  }
  if (value.status !== 'accepted') {
    return null
  }
  if (typeof value.thread_id !== 'string') {
    return null
  }
  const threadId = value.thread_id.trim()
  if (!UUID_PATTERN.test(threadId)) {
    return null
  }
  return {
    status: 'accepted',
    threadId,
  }
}

function positiveInteger(value: unknown): number | null {
  return typeof value === 'number' && Number.isSafeInteger(value) && value > 0
    ? value
    : null
}

function nonBlankString(value: unknown): string | null {
  return typeof value === 'string' && value.trim().length > 0 ? value.trim() : null
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value != null && !Array.isArray(value)
}
