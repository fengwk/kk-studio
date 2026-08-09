import type {
  DialogueMessage,
  ToolDialogueMessage,
} from '@/features/ai/runtime/thread-timeline-types'

export type TaskRunState = 'queued' | 'running_model' | 'running_tool' | 'waiting_approval'

/** 子任务等待审批的工具调用（task.status 心跳中的 approvals 项）。 */
export interface TaskStatusApproval {
  invocationId: string
  toolName: string
  reason: string | null
}

/**
 * 规范化后的 task.status 心跳。原始 JSON 只是传输形态：字段顺序 / 空白变化
 * 不会产生语义差异，任何心跳都解析为同一份规范化状态。
 */
export interface TaskStatusState {
  threadId: string
  subagentType: string
  state: TaskRunState
  /** 非法（非数字）的深度直接丢弃为 null，绝不强转展示。 */
  depth: number | null
  /** 非法（非数字）的计数直接丢弃为 null，绝不强转展示。 */
  turns: number | null
  toolCalls: number | null
  lastActivity: string | null
  approvals: TaskStatusApproval[]
}

interface TaskStatusEnvelope {
  status: TaskStatusState
  descendants: TaskStatusState[]
}

const TASK_RUN_STATES: readonly string[] = [
  'queued',
  'running_model',
  'running_tool',
  'waiting_approval',
]

/**
 * 解析并规范化一条 task.status 心跳 JSON（完整快照，不是 delta）。
 *
 * - kind 必须精确等于 'task.status'，否则丢弃；
 * - threadId / subagentType / state 缺失或非法时丢弃整条状态；
 * - depth / turns / toolCalls 非法时丢弃字段本身（null），不做 Number() 强转；
 * - approvals 中的条目缺少合法 invocationId / toolName 时丢弃该条目；
 * - descendants 是祖先 task relay 的扁平活动子树快照，逐项应用相同校验。
 */
export function parseTaskStatus(json: string): TaskStatusState | null {
  return parseTaskStatusEnvelope(json)?.status ?? null
}

function parseTaskStatusEnvelope(json: string): TaskStatusEnvelope | null {
  let value: unknown
  try {
    value = JSON.parse(json)
  } catch {
    return null
  }
  if (!isRecord(value) || value.kind !== 'task.status') {
    return null
  }
  const status = parseStatus(value)
  if (status == null) {
    return null
  }
  const descendants = Array.isArray(value.descendants)
    ? value.descendants
        .map((candidate) => parseStatus(candidate))
        .filter((candidate): candidate is TaskStatusState => candidate != null)
    : []
  return { status, descendants }
}

function parseStatus(value: unknown): TaskStatusState | null {
  if (!isRecord(value)) {
    return null
  }
  const threadId = nonBlankString(value.threadId)
  const subagentType = nonBlankString(value.subagentType)
  const state = taskRunState(value.state)
  const depth = nonNegativeInteger(value.depth)
  if (threadId == null || subagentType == null || state == null) {
    return null
  }
  const turns = nonNegativeInteger(value.turns)
  const toolCalls = nonNegativeInteger(value.toolCalls)
  const lastActivity = nullableNonBlankString(value.lastActivity)
  const approvals: TaskStatusApproval[] = []
  if (Array.isArray(value.approvals)) {
    for (const raw of value.approvals) {
      const approval = parseApprovalItem(raw)
      if (approval != null) {
        approvals.push(approval)
      }
    }
  }
  return {
    threadId,
    subagentType,
    state,
    depth,
    turns,
    toolCalls,
    lastActivity,
    approvals,
  }
}

/**
 * task.status 的语义指纹。传输 JSON 的字段顺序、缩进与尾随空白不参与比较；
 * 非法可选字段按 {@link parseTaskStatus} 的丢弃规则统一规范化。
 */
export function taskStatusFingerprint(json: string): string | null {
  const envelope = parseTaskStatusEnvelope(json)
  if (envelope == null) {
    return null
  }
  const status = envelope.status
  return JSON.stringify({
    threadId: status.threadId,
    subagentType: status.subagentType,
    state: status.state,
    depth: status.depth,
    turns: status.turns,
    toolCalls: status.toolCalls,
    lastActivity: status.lastActivity,
    approvals: status.approvals,
    descendants: envelope.descendants,
  })
}

function parseApprovalItem(value: unknown): TaskStatusApproval | null {
  if (!isRecord(value)) {
    return null
  }
  const invocationId = nonBlankString(value.invocationId)
  const toolName = nonBlankString(value.toolName)
  if (invocationId == null || toolName == null) {
    // 缺少合法 invocationId / toolName 的审批条目无法回传决策，直接丢弃。
    return null
  }
  const reason = value.reason
  return {
    invocationId,
    toolName,
    reason: typeof reason === 'string' && reason.trim() ? reason : null,
  }
}

/**
 * 只读 call phase、rendererKey 为 'task' 且 partial 可解析为 task.status
 * 的消息才贡献活动子任务状态。
 */
export function taskStatusFromMessage(message: ToolDialogueMessage): TaskStatusState | null {
  return taskStatusEnvelopeFromMessage(message)?.status ?? null
}

function taskStatusEnvelopeFromMessage(message: ToolDialogueMessage): TaskStatusEnvelope | null {
  if (message.phase !== 'call' || message.rendererKey !== 'task') {
    return null
  }
  const partial = message.partial
  if (partial == null || !partial.trim()) {
    return null
  }
  return parseTaskStatusEnvelope(partial)
}

/**
 * 一次 reduce 把所有活动 task 消息及其 descendant relay 按
 * `parentTaskLevel + status.depth` 聚合。
 *
 * - 调用方不要为每个节点单独构建数组：直接消费返回的 level -> 状态列表映射；
 * - 同一子 Thread 的 heartbeat 用规范化状态替换旧帧，绝不重复堆积；
 * - 同一 task 在后续心跳中深度变化时，会从旧 level 移入新 level。
 */
export function createTaskLevelStateMap(
  items: readonly DialogueMessage[],
  parentTaskLevel = 0,
): Map<number, TaskStatusState[]> {
  const byThread = new Map<string, TaskStatusState>()
  const levels = new Map<number, TaskStatusState[]>()
  return items.reduce((state, item) => {
    if (item.role !== 'tool') {
      return state
    }
    const envelope = taskStatusEnvelopeFromMessage(item)
    if (envelope == null) {
      return state
    }
    for (const status of [envelope.status, ...envelope.descendants]) {
      const previous = byThread.get(status.threadId)
      if (previous != null) {
        // heartbeat 替换：先从旧 level 移除上一帧，再挂到新 level。
        const previousLevel = parentTaskLevel + (previous.depth ?? 0)
        const previousList = levels.get(previousLevel)
        const previousIndex = previousList?.indexOf(previous) ?? -1
        if (previousList != null && previousIndex >= 0) {
          previousList.splice(previousIndex, 1)
          if (previousList.length === 0) {
            levels.delete(previousLevel)
          }
        }
      }
      byThread.set(status.threadId, status)
      const level = parentTaskLevel + (status.depth ?? 0)
      const list = levels.get(level)
      if (list == null) {
        state.set(level, [status])
      } else {
        list.push(status)
      }
    }
    return state
  }, levels)
}

function taskRunState(value: unknown): TaskRunState | null {
  return typeof value === 'string' && TASK_RUN_STATES.includes(value)
    ? (value as TaskRunState)
    : null
}

function nonNegativeInteger(value: unknown): number | null {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
    ? value
    : null
}

function nonBlankString(value: unknown): string | null {
  return typeof value === 'string' && value.trim().length > 0 ? value.trim() : null
}

function nullableNonBlankString(value: unknown): string | null {
  if (typeof value !== 'string') {
    return null
  }
  const trimmed = value.trim()
  return trimmed.length > 0 ? trimmed : null
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value != null && !Array.isArray(value)
}
