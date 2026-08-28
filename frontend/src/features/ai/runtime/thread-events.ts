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
import {
  contentText,
  formatTurnUsageText,
  parseAssistantUsage,
} from '@/features/ai/runtime/thread-timeline/content-utils'
import type {
  DialogueTimestamp,
  TurnUsage,
} from '@/features/ai/runtime/thread-timeline-types'
import { translate } from '@/shared/i18n'

/**
 * Event 投影的独立模型：不依赖 DialogueMessage 与 transcript 渲染。
 *
 * 输入持久 Entries 与活跃 overlay（modelInvocation / toolInvocations /
 * modelAttemptFailures / realtime streams），输出有序的 ThreadEventRecord 列表。
 * durable Entry 全类型都会保留且**恰好一条记录**（含控制边界 TURN_START /
 * TURN_END / COMPACTION）；活跃 model/tool invocation 是单条 synthetic 记录
 * （Provider delta token 绝不逐条成行），并锚定在所属 durable Entry 之后。
 */
export type ThreadEventSource = 'entry' | 'active-model' | 'active-tool' | 'attempt-failure'

export type ThreadEventKind =
  | 'ROOT'
  | 'TURN_START'
  | 'USER_MESSAGE'
  | 'ASSISTANT_MESSAGE'
  | 'TOOL_CALL'
  | 'TOOL_RESULT'
  | 'MODEL_ATTEMPT_FAILURE'
  | 'ASSISTANT_ERROR'
  | 'ASSISTANT_ABORTED'
  | 'CUSTOM'
  | 'CUSTOM_MESSAGE'
  | 'COMPACTION'
  | 'TURN_END'
  | 'ACTIVE_MODEL_INVOCATION'
  | 'ACTIVE_TOOL_INVOCATION'

export type ThreadEventStatus = 'pending' | 'running' | 'completed' | 'failed' | 'stopped'

export interface ThreadEventDetailRow {
  label: string
  value: string
}

export interface ThreadEventRecord {
  id: string
  source: ThreadEventSource
  /** durable Entry id；synthetic 活跃记录为 null（不冒充持久事实）。 */
  entryId: string | null
  /** 所属 Turn 的 TURN_START entryId；TURN_START 之前的记录为 null。 */
  turnStartEntryId: string | null
  /** 1-based Turn 序号；TURN_START 之前的记录为 0。 */
  turnNumber: number
  kind: ThreadEventKind
  status: ThreadEventStatus
  /** 调试标签：durable Entry 使用真实 entryType 枚举；synthetic 使用内部 kind 名。 */
  title: string
  /** 单行摘要：durable Entry 使用压缩后的 payload JSON。 */
  summary: string
  createdAt: DialogueTimestamp
  /** 结构化详情行（detail widget 渲染为 label/value）。 */
  details: ThreadEventDetailRow[]
  /** durable Entry 的原始 payload JSON；synthetic 记录为 null（只供详情展示）。 */
  rawJson: string | null
}

export interface ThreadEventTimelineInput {
  entries: HarnessSessionEntryDTO[]
  modelInvocation: ModelInvocationDTO | null
  toolInvocations: ToolInvocationDTO[]
  modelAttemptFailures?: readonly ModelAttemptFailureDTO[]
  modelStream?: RealtimeModelStream | null
  toolStreams?: ReadonlyMap<string, RealtimeToolStream> | null
}

const STATUS_TEXT_KEY: Record<ThreadEventStatus, string> = {
  pending: 'ai.runtime.event.status.PENDING',
  running: 'ai.runtime.event.status.RUNNING',
  completed: 'ai.runtime.event.status.COMPLETED',
  failed: 'ai.runtime.event.status.FAILED',
  stopped: 'ai.runtime.event.status.STOPPED',
}

/** 五态状态本地化。 */
export function eventStatusText(status: ThreadEventStatus): string {
  return translate(STATUS_TEXT_KEY[status])
}

function kindTitle(kind: ThreadEventKind): string {
  return kind
}

function asBranchDebugRecord(
  record: ThreadEventRecord,
  entry: HarnessSessionEntryDTO,
): ThreadEventRecord {
  return {
    ...record,
    title: entry.entryType || record.kind,
    summary: compactJson(entry.payloadJson || '{}'),
    rawJson: prettyJson(entry.payloadJson || '{}'),
  }
}

function compactJson(raw: string): string {
  try {
    return JSON.stringify(JSON.parse(raw))
  } catch {
    return raw.replace(/\s+/g, ' ').trim() || '{}'
  }
}

function prettyJson(raw: string): string {
  try {
    return JSON.stringify(JSON.parse(raw), null, 2)
  } catch {
    return raw
  }
}

/**
 * 构建 Event 时间线：每个 durable Entry 恰好一条记录；synthetic 活跃 overlay
 * 锚定在所属 durable Entry 之后（找不到锚点追加末尾）。沿 entries 线性路径
 * 跟踪当前 TURN_START entryId 与 turnNumber，并把 Turn usage 挂起至该 Turn 的
 * TURN_END（读取该 Turn Assistant metadata 的完整 usage/cost）。
 *
 * snapshot 在 ModelTerminalPending / ToolTerminalPending 窗口内会同时携带 durable
 * Entry 与尚未物化的活跃 overlay；按「Turn 内」的稳定 identity 去重，durable Entry
 * 是权威记录——旧 Turn 的 durable Entry 绝不能抑制新 Turn 相同
 * (attempt, sequence) / toolCallId 的活跃 overlay。
 */
export function buildThreadEventTimeline(
  input: ThreadEventTimelineInput,
): ThreadEventRecord[] {
  const {
    entries,
    modelInvocation,
    toolInvocations,
    modelAttemptFailures = [],
    modelStream = null,
    toolStreams = null,
  } = input
  const records: ThreadEventRecord[] = []
  // 活跃 overlay 锚定在其所属 durable Entry 之后；找不到锚点时追加到末尾。
  const anchored = new Map<number, ThreadEventRecord[]>()
  const unanchored: ThreadEventRecord[] = []
  const anchor = (entryId: string | null, record: ThreadEventRecord) => {
    const index = entryId == null ? -1 : entries.findIndex((entry) => entry.entryId === entryId)
    if (index < 0) {
      unanchored.push(record)
      return
    }
    const group = anchored.get(index) ?? []
    group.push(record)
    anchored.set(index, group)
  }
  const turnStartByEntryId = new Map<string, string | null>()
  const turnNumberByTurnStart = new Map<string, number>()
  const durableFailureIdentities = new Set<string>()
  const durableToolResultIdentities = new Set<string>()
  let currentTurnStartEntryId: string | null = null
  let currentTurnNumber = 0
  let pendingUsage: TurnUsage | null = null
  for (const entry of entries) {
    if (entry.entryType === 'TURN_START') {
      currentTurnStartEntryId = entry.entryId
      currentTurnNumber += 1
      turnNumberByTurnStart.set(currentTurnStartEntryId, currentTurnNumber)
      // 新 turn 开始：丢弃上一 turn 未关闭的残留 usage。
      pendingUsage = null
    }
    turnStartByEntryId.set(entry.entryId, currentTurnStartEntryId)
    const payload = parsePayload(entry.payloadJson)
    records.push(
      asBranchDebugRecord(
        projectEntryRecord(entry, payload, {
          turnStartEntryId: currentTurnStartEntryId,
          turnNumber: currentTurnNumber,
          pendingUsage,
        }),
        entry,
      ),
    )
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
      // Turn usage 挂起：该 Turn 最后一个 ASSISTANT 的 metadata 在 TURN_END 发射。
      if (entry.entryType === 'MESSAGE' && getString(message.role) === 'ASSISTANT') {
        const parsed = parseAssistantUsage(asRecord(payload.assistantMetadata))
        if (parsed != null) {
          pendingUsage = parsed
        }
      }
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
    if (entry.entryType === 'TURN_END') {
      // usage 已发射进 TURN_END 记录；防止后续重复 TURN_END 复用。
      pendingUsage = null
    }
  }

  const turnOf = (entryId: string | null): { turnStartEntryId: string | null; turnNumber: number } => {
    const turnStartEntryId = entryId == null ? null : (turnStartByEntryId.get(entryId) ?? null)
    return {
      turnStartEntryId,
      turnNumber: turnStartEntryId == null ? 0 : (turnNumberByTurnStart.get(turnStartEntryId) ?? 0),
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
    anchor(failure.turnStartEntryId, projectAttemptFailureRecord(failure, turnOf(failure.turnStartEntryId)))
  }
  // 活跃 model invocation synthetic；resultEntryId 已对应现存 Entry 时消失。
  const modelMaterialized =
    modelInvocation != null
    && modelInvocation.resultEntryId != null
    && entries.some((entry) => entry.entryId === modelInvocation.resultEntryId)
  if (modelInvocation != null && !modelMaterialized) {
    anchor(
      modelInvocation.turnStartEntryId,
      projectActiveModelRecord(modelInvocation, modelStream, turnOf(modelInvocation.turnStartEntryId)),
    )
  }
  for (const invocation of toolInvocations) {
    // 运行中的 invocation 在 durable assistant tool_call 物化后仍必须展示：只有
    // 同一 Turn 的 durable tool_result Entry 已物化时才跳过 terminal-pending
    // overlay；跨 Turn 复用相同 toolCallId 不误抑制。ToolResult Entry 写入与
    // invocation 删除原子提交，因此只需依据 durable Entry 与 invocation 存在性收敛。
    const materialized = durableToolResultIdentities.has(
      `${turnStartByEntryId.get(invocation.assistantEntryId) ?? ''}:${invocation.toolCallId}`,
    )
    if (materialized) {
      continue
    }
    anchor(
      invocation.assistantEntryId,
      projectActiveToolRecord(invocation, toolStreams?.get(invocation.id) ?? null, turnOf(invocation.assistantEntryId)),
    )
  }

  const result: ThreadEventRecord[] = []
  for (let index = 0; index < records.length; index += 1) {
    result.push(records[index]!)
    const group = anchored.get(index)
    if (group) {
      result.push(...group)
    }
  }
  result.push(...unanchored)
  return result
}

interface EntryTurnContext {
  turnStartEntryId: string | null
  turnNumber: number
  pendingUsage: TurnUsage | null
}

/** durable 记录的公共字段（kind/status/title/summary/details 由各分支补全）。 */
type RecordBase = Pick<ThreadEventRecord, 'id' | 'source' | 'entryId' | 'turnStartEntryId' | 'turnNumber' | 'createdAt' | 'rawJson'> & {
  details: ThreadEventDetailRow[]
}

function withTime(details: ThreadEventDetailRow[], createdAt: DialogueTimestamp): ThreadEventDetailRow[] {
  return [
    ...details,
    { label: translate('ai.runtime.event.detail.createTime'), value: String(createdAt) },
  ]
}

function projectEntryRecord(
  entry: HarnessSessionEntryDTO,
  payload: Record<string, unknown>,
  turn: EntryTurnContext,
): ThreadEventRecord {
  const baseDetails: ThreadEventDetailRow[] = [
    { label: translate('ai.runtime.event.detail.entryId'), value: entry.entryId },
    {
      label: translate('ai.runtime.event.detail.entryType'),
      value: entry.entryType || translate('ai.runtime.entry.unknownType'),
    },
    ...(turn.turnNumber > 0
      ? [{ label: translate('ai.runtime.event.detail.turn'), value: String(turn.turnNumber) }]
      : []),
  ]
  const base: RecordBase = {
    id: `entry:${entry.entryId}`,
    source: 'entry',
    entryId: entry.entryId,
    turnStartEntryId: turn.turnStartEntryId,
    turnNumber: turn.turnNumber,
    createdAt: entry.createTime,
    rawJson: entry.payloadJson || '{}',
    details: baseDetails,
  }

  switch (entry.entryType) {
    case 'ROOT':
      return {
        ...base,
        kind: 'ROOT',
        status: 'completed',
        title: kindTitle('ROOT'),
        summary: translate('ai.runtime.entry.rootText'),
        details: withTime(base.details, entry.createTime),
      }
    case 'TURN_START':
      return {
        ...base,
        kind: 'TURN_START',
        status: 'completed',
        title: kindTitle('TURN_START'),
        summary: summarizeField(getString(payload.reason), translate('ai.runtime.event.emptyText')),
        details: withTime(base.details, entry.createTime),
      }
    case 'COMPACTION':
      return {
        ...base,
        kind: 'COMPACTION',
        status: 'completed',
        title: kindTitle('COMPACTION'),
        summary: summarizeField(getString(payload.reason), translate('ai.runtime.event.emptyText')),
        details: withTime(base.details, entry.createTime),
      }
    case 'TURN_END': {
      const outcome = getString(payload.outcome)
      const usage = turn.pendingUsage
      const details = [...base.details]
      if (usage != null) {
        details.push(
          { label: translate('ai.runtime.event.detail.input'), value: String(usage.input) },
          { label: translate('ai.runtime.event.detail.output'), value: String(usage.output) },
          { label: translate('ai.runtime.event.detail.cacheRead'), value: String(usage.cacheRead) },
          { label: translate('ai.runtime.event.detail.cacheWrite'), value: String(usage.cacheWrite) },
          { label: translate('ai.runtime.event.detail.reasoning'), value: String(usage.reasoning) },
          {
            label: translate('ai.runtime.event.detail.providerTotal'),
            value: String(usage.providerTotal),
          },
          { label: translate('ai.runtime.event.detail.cost'), value: usage.cost.toFixed(3) },
        )
      }
      return {
        ...base,
        kind: 'TURN_END',
        status: 'completed',
        title: kindTitle('TURN_END'),
        summary: [
          outcome,
          ...(usage != null ? [formatTurnUsageText(usage)] : []),
        ].filter(Boolean).join(' · '),
        details: withTime([
          ...details,
          { label: translate('ai.runtime.event.detail.outcome'), value: outcome },
        ], entry.createTime),
      }
    }
    case 'MODEL_ATTEMPT_FAILURE': {
      const attempt = asRecord(payload.attempt)
      const error = asRecord(payload.error)
      // attempt.attempt 在 durable payload 中是数字（JSON number）。
      const attemptText = scalarText(attempt.attempt)
      const code = getString(error.code)
      return {
        ...base,
        kind: 'MODEL_ATTEMPT_FAILURE',
        status: 'failed',
        title: kindTitle('MODEL_ATTEMPT_FAILURE'),
        summary: [`attempt ${attemptText}`, code].filter(Boolean).join(' · '),
        details: withTime([
          ...base.details,
          { label: translate('ai.runtime.event.detail.attempt'), value: attemptText },
          { label: translate('ai.runtime.event.detail.sequence'), value: scalarText(attempt.sequence) },
          { label: translate('ai.runtime.event.detail.errorCode'), value: code },
          {
            label: translate('ai.runtime.event.detail.errorMessage'),
            value: getString(error.message),
          },
        ], entry.createTime),
      }
    }
    case 'ASSISTANT_ERROR': {
      const error = asRecord(payload.error)
      return {
        ...base,
        kind: 'ASSISTANT_ERROR',
        status: 'failed',
        title: kindTitle('ASSISTANT_ERROR'),
        summary: summarizeField(
          getString(error.message),
          translate('ai.runtime.entry.assistantRequestFailed'),
        ),
        details: withTime([
          ...base.details,
          {
            label: translate('ai.runtime.event.detail.errorMessage'),
            value: getString(error.message),
          },
        ], entry.createTime),
      }
    }
    case 'ASSISTANT_ABORTED':
      return {
        ...base,
        kind: 'ASSISTANT_ABORTED',
        status: 'stopped',
        title: kindTitle('ASSISTANT_ABORTED'),
        summary: translate('ai.runtime.event.abortedText'),
        details: withTime(base.details, entry.createTime),
      }
    case 'CUSTOM': {
      const customType = getString(payload.customType)
      const contributorId = getString(payload.contributorId)
      return {
        ...base,
        kind: 'CUSTOM',
        status: 'completed',
        title: kindTitle('CUSTOM'),
        summary: summarizeField(customType || contributorId, translate('ai.runtime.event.emptyText')),
        details: withTime(base.details, entry.createTime),
      }
    }
    case 'CUSTOM_MESSAGE':
      return projectMessageRecord(base, payload, 'CUSTOM_MESSAGE')
    case 'MESSAGE': {
      const message = asRecord(payload.message)
      const role = getString(message.role)
      if (role === 'USER') {
        return projectMessageRecord(base, payload, 'USER_MESSAGE')
      }
      if (role === 'ASSISTANT') {
        const contents = getRecordList(message.contents)
        return contents.some((content) => getString(content.type) === 'tool_call')
          ? projectToolCallRecord(base, payload)
          : projectMessageRecord(base, payload, 'ASSISTANT_MESSAGE')
      }
      if (role === 'TOOL') {
        return projectToolResultRecord(base, payload)
      }
      // SYSTEM/未知角色按 CUSTOM_MESSAGE。
      return projectMessageRecord(base, payload, 'CUSTOM_MESSAGE')
    }
    default:
      // 未知 Entry 类型：审计视图不丢信息，归入 CUSTOM 并以原类型标签展示。
      return {
        ...base,
        kind: 'CUSTOM',
        status: 'completed',
        title: translate('ai.runtime.event.unknownType', { type: entry.entryType }),
        summary: translate('ai.runtime.event.emptyText'),
        details: withTime(base.details, entry.createTime),
      }
  }
}

function projectMessageRecord(
  base: RecordBase,
  payload: Record<string, unknown>,
  kind: 'USER_MESSAGE' | 'ASSISTANT_MESSAGE' | 'CUSTOM_MESSAGE',
): ThreadEventRecord {
  const message = asRecord(payload.message)
  const role = getString(message.role) || translate('ai.runtime.entry.unknownRole')
  const text = messageContentsText(getRecordList(message.contents))
  return {
    ...base,
    kind,
    status: 'completed',
    title: kindTitle(kind),
    summary: summarizeField(text, role),
    details: withTime([
      ...base.details,
      { label: translate('ai.runtime.event.detail.role'), value: role },
    ], base.createdAt),
  }
}

function projectToolCallRecord(
  base: RecordBase,
  payload: Record<string, unknown>,
): ThreadEventRecord {
  const contents = getRecordList(asRecord(payload.message).contents)
  const toolCall = contents.find((content) => getString(content.type) === 'tool_call')
  const toolName = getString(toolCall?.toolName) || translate('ai.runtime.event.unknownTool')
  const text = messageContentsText(contents)
  return {
    ...base,
    kind: 'TOOL_CALL',
    status: 'completed',
    title: kindTitle('TOOL_CALL'),
    summary: summarizeField([text, toolName].filter(Boolean).join(' · '), toolName),
    details: withTime([
      ...base.details,
      { label: translate('ai.runtime.event.detail.toolName'), value: toolName },
      { label: translate('ai.runtime.event.detail.toolCallId'), value: getString(toolCall?.toolCallId) },
    ], base.createdAt),
  }
}

function projectToolResultRecord(
  base: RecordBase,
  payload: Record<string, unknown>,
): ThreadEventRecord {
  const contents = getRecordList(asRecord(payload.message).contents)
  const toolResult = contents.find((content) => getString(content.type) === 'tool_result')
  const text = toolResultText(contents)
  return {
    ...base,
    kind: 'TOOL_RESULT',
    status: 'completed',
    title: kindTitle('TOOL_RESULT'),
    summary: summarizeField(text, translate('ai.runtime.event.emptyText')),
    details: withTime([
      ...base.details,
      {
        label: translate('ai.runtime.event.detail.toolCallId'),
        value: getString(toolResult?.toolCallId),
      },
    ], base.createdAt),
  }
}

function projectAttemptFailureRecord(
  failure: ModelAttemptFailureDTO,
  turn: { turnStartEntryId: string | null; turnNumber: number },
): ThreadEventRecord {
  return {
    id: `active:attempt-failure:${failure.modelInvocationId}:${failure.attempt}`,
    source: 'attempt-failure',
    entryId: null,
    turnStartEntryId: turn.turnStartEntryId,
    turnNumber: turn.turnNumber,
    kind: 'MODEL_ATTEMPT_FAILURE',
    status: 'failed',
    title: kindTitle('MODEL_ATTEMPT_FAILURE'),
    summary: `${failure.errorCode} · ${summarizeField(failure.errorMessage, translate('ai.runtime.event.emptyText'))}`,
    createdAt: failure.failedAt,
    details: [
      { label: translate('ai.runtime.event.detail.attempt'), value: String(failure.attempt) },
      { label: translate('ai.runtime.event.detail.sequence'), value: failure.sequence },
      { label: translate('ai.runtime.event.detail.errorCode'), value: failure.errorCode },
      { label: translate('ai.runtime.event.detail.errorMessage'), value: failure.errorMessage },
      { label: translate('ai.runtime.event.detail.failedAt'), value: String(failure.failedAt) },
      { label: translate('ai.runtime.event.detail.retryAt'), value: String(failure.retryAt) },
    ],
    rawJson: null,
  }
}

function projectActiveModelRecord(
  invocation: ModelInvocationDTO,
  stream: RealtimeModelStream | null,
  turn: { turnStartEntryId: string | null; turnNumber: number },
): ThreadEventRecord {
  const status =
    stream?.status === 'error'
      ? 'failed'
      : mapActiveStatus(invocation.status)
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
    source: 'active-model',
    entryId: null,
    turnStartEntryId: turn.turnStartEntryId,
    turnNumber: turn.turnNumber,
    kind: 'ACTIVE_MODEL_INVOCATION',
    status,
    title: kindTitle('ACTIVE_MODEL_INVOCATION'),
    summary: `${eventStatusText(status)} · ${translate('ai.runtime.event.detail.attempt')} ${invocation.attempt}`,
    createdAt: invocation.createTime,
    details,
    rawJson: null,
  }
}

function projectActiveToolRecord(
  invocation: ToolInvocationDTO,
  stream: RealtimeToolStream | null,
  turn: { turnStartEntryId: string | null; turnNumber: number },
): ThreadEventRecord {
  const status = activeToolStatus(invocation, stream)
  return {
    id: `active:tool:${invocation.id}`,
    source: 'active-tool',
    entryId: null,
    turnStartEntryId: turn.turnStartEntryId,
    turnNumber: turn.turnNumber,
    kind: 'ACTIVE_TOOL_INVOCATION',
    status,
    title: kindTitle('ACTIVE_TOOL_INVOCATION'),
    summary: `${invocation.toolName || translate('ai.runtime.event.unknownTool')} · ${eventStatusText(status)}`,
    createdAt: invocation.createTime,
    details: [
      { label: translate('ai.runtime.event.detail.status'), value: eventStatusText(status) },
      { label: translate('ai.runtime.event.detail.toolName'), value: invocation.toolName || '' },
      { label: translate('ai.runtime.event.detail.toolCallId'), value: invocation.toolCallId || '' },
      { label: translate('ai.runtime.event.detail.attempt'), value: String(invocation.attempt) },
    ],
    rawJson: null,
  }
}

/**
 * 活跃状态映射到冻结五态：approval/派发中→pending，运行/流式→running，
 * 终态成功→completed，失败/错误→failed，取消/停止→stopped；未知原样按 pending。
 */
function mapActiveStatus(raw: string | undefined | null): ThreadEventStatus {
  switch ((raw ?? '').toLowerCase()) {
    case 'waiting_approval':
    case 'ready':
    case 'dispatching':
    case 'queued':
    case 'pending':
      return 'pending'
    case 'running':
    case 'streaming':
    case 'in_progress':
      return 'running'
    case 'succeeded':
    case 'done':
    case 'completed':
    case 'success':
      return 'completed'
    case 'failed':
    case 'error':
      return 'failed'
    case 'cancelled':
    case 'aborted':
    case 'stopped':
      return 'stopped'
    default:
      return 'pending'
  }
}

function activeToolStatus(
  invocation: ToolInvocationDTO,
  stream: RealtimeToolStream | null,
): ThreadEventStatus {
  if (stream?.error === true) {
    return 'failed'
  }
  if (invocation.errorJson != null) {
    return 'failed'
  }
  if (invocation.resultJson != null) {
    return 'completed'
  }
  return mapActiveStatus(invocation.status)
}

/** 截断为单行摘要（首行 + 140 字符上限）。 */
function summarizeField(value: string, fallback: string): string {
  const line = value.split('\n')[0]?.trim() ?? ''
  if (!line) {
    return fallback
  }
  return line.length > 140 ? `${line.slice(0, 140)}…` : line
}

function messageContentsText(contents: Array<Record<string, unknown>>): string {
  return contents.map(contentText).filter(Boolean).join(' ').trim()
}

/** tool_result content 的正文（text/json 内层 contents）。 */
function toolResultText(contents: Array<Record<string, unknown>>): string {
  for (const content of contents) {
    if (getString(content.type) === 'tool_result') {
      const text = messageContentsText(getRecordList(content.contents))
      if (text) {
        return text
      }
    }
  }
  return ''
}

/** 字符串或数字（JSON number）统一转文本。 */
function scalarText(value: unknown): string {
  return typeof value === 'number' ? String(value) : getString(value)
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
