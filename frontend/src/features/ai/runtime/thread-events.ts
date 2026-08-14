import type {
  HarnessSessionEntryDTO,
  ModelAttemptFailureDTO,
  ModelInvocationDTO,
  ToolInvocationDTO,
} from '@/shared/api/contracts/ai-runtime'
import { asRecord, getRecordList, getString, parsePayload } from '@/features/ai/runtime/payload-json'
import type {
  RealtimeModelStream,
  RealtimeToolStream,
} from '@/features/ai/runtime/thread-realtime-state'
import { contentText } from '@/features/ai/runtime/thread-timeline/content-utils'
import type { DialogueTimestamp } from '@/features/ai/runtime/thread-timeline-types'
import { translate } from '@/shared/i18n'

/**
 * Event 投影的独立模型：不依赖 DialogueMessage 与 transcript 渲染。
 *
 * 输入持久 Entries 与活跃 overlay（modelInvocation / toolInvocations /
 * modelAttemptFailures / realtime streams），输出有序的事件列表。durable Entry 全类型
 * 都会保留（含控制边界 TURN_START/TURN_END/COMPACTION 与未知类型）；活跃 model/tool
 * invocation 是单条事件（Provider delta token 绝不逐条成行），并锚定在所属 Entry 之后。
 */
export type ThreadEventSource = 'entry' | 'model' | 'tool' | 'attempt-failure'

export interface ThreadEventDetailRow {
  label: string
  value: string
}

export interface ThreadEventItem {
  id: string
  source: ThreadEventSource
  /** 类型标签（本地化）。 */
  title: string
  /** 单行摘要。 */
  text: string
  createTime: DialogueTimestamp
  /** 结构化详情行（detail widget 渲染为 label/value）。 */
  details: ThreadEventDetailRow[]
  /** durable Entry 的原始 payload JSON；overlay 事件为 null。 */
  payloadJson: string | null
}

const ENTRY_TYPE_TITLE_KEY: Record<string, string> = {
  ROOT: 'ai.runtime.event.entryType.ROOT',
  TURN_START: 'ai.runtime.event.entryType.TURN_START',
  MESSAGE: 'ai.runtime.event.entryType.MESSAGE',
  CUSTOM_MESSAGE: 'ai.runtime.event.entryType.CUSTOM_MESSAGE',
  MODEL_ATTEMPT_FAILURE: 'ai.runtime.event.entryType.MODEL_ATTEMPT_FAILURE',
  ASSISTANT_ERROR: 'ai.runtime.event.entryType.ASSISTANT_ERROR',
  ASSISTANT_ABORTED: 'ai.runtime.event.entryType.ASSISTANT_ABORTED',
  COMPACTION: 'ai.runtime.event.entryType.COMPACTION',
  TURN_END: 'ai.runtime.event.entryType.TURN_END',
  CUSTOM: 'ai.runtime.event.entryType.CUSTOM',
}

const STATUS_TEXT_KEY: Record<string, string> = {
  waiting_approval: 'ai.runtime.event.status.WAITING_APPROVAL',
  ready: 'ai.runtime.event.status.READY',
  dispatching: 'ai.runtime.event.status.DISPATCHING',
  running: 'ai.runtime.event.status.RUNNING',
  succeeded: 'ai.runtime.event.status.SUCCEEDED',
  failed: 'ai.runtime.event.status.FAILED',
  cancelled: 'ai.runtime.event.status.CANCELLED',
  unknown: 'ai.runtime.event.status.UNKNOWN',
  streaming: 'ai.runtime.event.status.STREAMING',
  done: 'ai.runtime.event.status.DONE',
  error: 'ai.runtime.event.status.ERROR',
}

/** 已知状态本地化；未知状态原样展示（事件视图是审计视图，不丢信息）。 */
export function eventStatusText(status: string | undefined): string {
  if (!status) {
    return ''
  }
  const key = STATUS_TEXT_KEY[status.toLowerCase()]
  return key ? translate(key) : status
}

export function buildThreadEvents(
  entries: HarnessSessionEntryDTO[],
  modelInvocation: ModelInvocationDTO | null,
  toolInvocations: ToolInvocationDTO[],
  modelAttemptFailures: readonly ModelAttemptFailureDTO[] = [],
  modelStream: RealtimeModelStream | null = null,
  toolStreams: ReadonlyMap<string, RealtimeToolStream> | null = null,
): ThreadEventItem[] {
  const items: ThreadEventItem[] = entries.map(projectEntryEvent)
  // 活跃 overlay 锚定在其所属 durable Entry 之后；找不到锚点时追加到末尾。
  const anchored = new Map<number, ThreadEventItem[]>()
  const unanchored: ThreadEventItem[] = []
  const anchor = (entryId: string | null, item: ThreadEventItem) => {
    const index = entryId == null ? -1 : entries.findIndex((entry) => entry.entryId === entryId)
    if (index < 0) {
      unanchored.push(item)
      return
    }
    const group = anchored.get(index) ?? []
    group.push(item)
    anchored.set(index, group)
  }
  // snapshot 在 ModelTerminalPending / ToolTerminalPending 窗口内会同时携带 durable
  // Entry 与尚未物化的活跃 overlay；按「Turn 内」的稳定 identity 去重，durable Entry
  // 是权威记录。身份必须带 Turn（沿 entries 线性路径跟踪当前 TURN_START entryId）：
  // 旧 Turn 的 durable Entry 绝不能抑制新 Turn 相同 (attempt, sequence) / toolCallId
  // 的活跃 overlay。
  const turnStartByEntryId = new Map<string, string | null>()
  const durableFailureIdentities = new Set<string>()
  const durableToolResultIdentities = new Set<string>()
  let currentTurnStartEntryId: string | null = null
  for (const entry of entries) {
    if (entry.entryType === 'TURN_START') {
      currentTurnStartEntryId = entry.entryId
    }
    turnStartByEntryId.set(entry.entryId, currentTurnStartEntryId)
    const payload = parsePayload(entry.payloadJson)
    if (entry.entryType === 'MODEL_ATTEMPT_FAILURE') {
      const attempt = asRecord(payload.attempt)
      const attemptNumber = scalarText(attempt.attempt)
      const sequence = scalarText(attempt.sequence)
      if (attemptNumber && sequence) {
        durableFailureIdentities.add(
          `${currentTurnStartEntryId ?? ''}:${attemptNumber}:${sequence}`,
        )
      }
      continue
    }
    if (entry.entryType === 'MESSAGE' || entry.entryType === 'CUSTOM_MESSAGE') {
      const message = asRecord(payload.message)
      for (const content of getRecordList(message.contents)) {
        // 只有物化的 durable tool_result 才让活跃 tool overlay 退休：durable
        // assistant tool_call 只表示调用已记录，运行中的 invocation 必须继续展示。
        if (getString(content.type) === 'tool_result') {
          const toolCallId = getString(content.toolCallId)
          if (toolCallId) {
            durableToolResultIdentities.add(`${currentTurnStartEntryId ?? ''}:${toolCallId}`)
          }
        }
      }
    }
  }
  const orderedFailures = [...modelAttemptFailures].sort(
    (left, right) => left.attempt - right.attempt || compareTimestamp(left.failedAt, right.failedAt),
  )
  const seenFailures = new Set<string>()
  for (const failure of orderedFailures) {
    // 同 identity（modelInvocationId:attempt）只保留一条活跃失败记录。
    const identity = `${failure.modelInvocationId}:${failure.attempt}`
    if (seenFailures.has(identity)) {
      continue
    }
    seenFailures.add(identity)
    // durable MODEL_ATTEMPT_FAILURE Entry 已在同一 Turn 物化该失败时，跳过活跃 overlay。
    if (
      durableFailureIdentities.has(
        `${failure.turnStartEntryId}:${failure.attempt}:${failure.sequence}`,
      )
    ) {
      continue
    }
    anchor(failure.turnStartEntryId, projectAttemptFailureEvent(failure))
  }
  if (modelInvocation != null) {
    anchor(
      modelInvocation.turnStartEntryId,
      projectModelInvocationEvent(modelInvocation, modelStream),
    )
  }
  for (const invocation of toolInvocations) {
    // 运行中的 invocation 在 durable assistant tool_call 物化后仍必须展示：只有
    // 同一 Turn 的 durable tool_result 已物化（或 DTO resultEntryId 已指向存在的
    // Entry）时才跳过 terminal-pending overlay；跨 Turn 复用相同 toolCallId 不误抑制。
    const materialized =
      durableToolResultIdentities.has(
        `${turnStartByEntryId.get(invocation.assistantEntryId) ?? ''}:${invocation.toolCallId}`,
      )
      || (invocation.resultEntryId != null
        && entries.some((entry) => entry.entryId === invocation.resultEntryId))
    if (materialized) {
      continue
    }
    anchor(
      invocation.assistantEntryId,
      projectToolInvocationEvent(invocation, toolStreams?.get(invocation.id) ?? null),
    )
  }
  const result: ThreadEventItem[] = []
  for (let index = 0; index < items.length; index += 1) {
    result.push(items[index]!)
    const group = anchored.get(index)
    if (group) {
      result.push(...group)
    }
  }
  result.push(...unanchored)
  return result
}

function projectEntryEvent(entry: HarnessSessionEntryDTO): ThreadEventItem {
  const payload = parsePayload(entry.payloadJson)
  const title = entryTypeTitle(entry.entryType)
  const text = entrySummary(entry.entryType, payload)
  return {
    id: `entry:${entry.entryId}`,
    source: 'entry',
    title,
    text,
    createTime: entry.createTime,
    details: [
      { label: translate('ai.runtime.event.detail.entryId'), value: entry.entryId },
      { label: translate('ai.runtime.event.detail.entryType'), value: entry.entryType || translate('ai.runtime.entry.unknownType') },
      { label: translate('ai.runtime.event.detail.createTime'), value: String(entry.createTime) },
    ],
    payloadJson: entry.payloadJson || '{}',
  }
}

function entryTypeTitle(entryType: string): string {
  const key = ENTRY_TYPE_TITLE_KEY[entryType]
  return key ? translate(key) : translate('ai.runtime.event.unknownType', { type: entryType })
}

/** 从 payload 派生单行摘要；text/error 截断为一行。 */
function entrySummary(entryType: string, payload: Record<string, unknown>): string {
  if (entryType === 'ROOT') {
    return translate('ai.runtime.entry.rootText')
  }
  if (entryType === 'TURN_START' || entryType === 'COMPACTION') {
    return summarizeField(getString(payload.reason), translate('ai.runtime.event.emptyText'))
  }
  if (entryType === 'TURN_END') {
    const outcome = getString(payload.outcome)
    const reason = getString(payload.reason)
    return [outcome, reason].filter(Boolean).join(' · ')
  }
  if (entryType === 'MESSAGE' || entryType === 'CUSTOM_MESSAGE') {
    const message = asRecord(payload.message)
    const role = getString(message.role) || translate('ai.runtime.entry.unknownRole')
    const contents = getRecordList(message.contents)
    const text = contents.map(contentText).filter(Boolean).join(' ').trim()
    return summarizeField(text, `${role}`)
  }
  if (entryType === 'MODEL_ATTEMPT_FAILURE') {
    const attempt = asRecord(payload.attempt)
    const error = asRecord(payload.error)
    // attempt.attempt 在 durable payload 中是数字（JSON number）。
    const attemptText = scalarText(attempt.attempt)
    const code = getString(error.code)
    return [`attempt ${attemptText}`, code].filter(Boolean).join(' · ')
  }
  if (entryType === 'ASSISTANT_ERROR') {
    const error = asRecord(payload.error)
    return summarizeField(
      getString(error.message),
      translate('ai.runtime.entry.assistantRequestFailed'),
    )
  }
  if (entryType === 'ASSISTANT_ABORTED') {
    return translate('ai.runtime.event.abortedText')
  }
  return translate('ai.runtime.event.emptyText')
}

/** 截断为单行摘要（首行 + 140 字符上限）。 */
function summarizeField(value: string, fallback: string): string {
  const line = value.split('\n')[0]?.trim() ?? ''
  if (!line) {
    return fallback
  }
  return line.length > 140 ? `${line.slice(0, 140)}…` : line
}

/** 字符串或数字（JSON number）统一转文本。 */
function scalarText(value: unknown): string {
  return typeof value === 'number' ? String(value) : getString(value)
}

function projectModelInvocationEvent(
  invocation: ModelInvocationDTO,
  stream: RealtimeModelStream | null,
): ThreadEventItem {
  const status = stream?.status === 'error' ? 'error' : invocation.status
  const chars = (stream?.text.length ?? 0) + (stream?.thinking.length ?? 0)
  const details: ThreadEventDetailRow[] = [
    { label: translate('ai.runtime.event.detail.status'), value: eventStatusText(status) },
    { label: translate('ai.runtime.event.detail.attempt'), value: String(invocation.attempt) },
  ]
  if (chars > 0) {
    details.push({ label: translate('ai.runtime.event.detail.chars'), value: String(chars) })
  }
  return {
    id: `active:model:${invocation.id}`,
    source: 'model',
    title: translate('ai.runtime.event.activeModel'),
    text: eventStatusText(status),
    createTime: invocation.createTime,
    details,
    payloadJson: null,
  }
}

function projectToolInvocationEvent(
  invocation: ToolInvocationDTO,
  stream: RealtimeToolStream | null,
): ThreadEventItem {
  const status =
    stream?.error === true ? 'error' : (invocation.status ?? 'UNKNOWN')
  return {
    id: `active:tool:${invocation.id}`,
    source: 'tool',
    title: translate('ai.runtime.event.activeTool'),
    text: `${invocation.toolName || translate('ai.runtime.event.unknownTool')} · ${eventStatusText(status)}`,
    createTime: invocation.createTime,
    details: [
      { label: translate('ai.runtime.event.detail.status'), value: eventStatusText(status) },
      { label: translate('ai.runtime.event.detail.toolName'), value: invocation.toolName || '' },
      { label: translate('ai.runtime.event.detail.toolCallId'), value: invocation.toolCallId || '' },
      { label: translate('ai.runtime.event.detail.attempt'), value: String(invocation.attempt) },
    ],
    payloadJson: null,
  }
}

function projectAttemptFailureEvent(failure: ModelAttemptFailureDTO): ThreadEventItem {
  const details: ThreadEventDetailRow[] = [
    { label: translate('ai.runtime.event.detail.attempt'), value: String(failure.attempt) },
    { label: translate('ai.runtime.event.detail.sequence'), value: failure.sequence },
    { label: translate('ai.runtime.event.detail.errorCode'), value: failure.errorCode },
    { label: translate('ai.runtime.event.detail.errorMessage'), value: failure.errorMessage },
    { label: translate('ai.runtime.event.detail.failedAt'), value: String(failure.failedAt) },
    { label: translate('ai.runtime.event.detail.retryAt'), value: String(failure.retryAt) },
  ]
  return {
    id: `active:attempt-failure:${failure.modelInvocationId}:${failure.attempt}`,
    source: 'attempt-failure',
    title: translate('ai.runtime.event.activeFailure'),
    text: `${failure.errorCode} · ${summarizeField(failure.errorMessage, translate('ai.runtime.event.emptyText'))}`,
    createTime: failure.failedAt,
    details,
    payloadJson: null,
  }
}

function compareTimestamp(left: DialogueTimestamp, right: DialogueTimestamp): number {
  return timestampValue(left) - timestampValue(right)
}

function timestampValue(value: DialogueTimestamp): number {
  if (typeof value === 'number') {
    return value
  }
  if (typeof value === 'string') {
    const parsed = Date.parse(value)
    return Number.isFinite(parsed) ? parsed : 0
  }
  if (Array.isArray(value)) {
    return value.length > 0 && typeof value[0] === 'number' ? value[0] : 0
  }
  return 0
}
