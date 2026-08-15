import type {
  HarnessSessionEntryDTO,
  HarnessThreadCommandDTO,
  ModelAttemptFailureDTO,
  ModelInvocationDTO,
  ToolInvocationDTO,
} from '@/shared/api/contracts/ai-runtime'
import { asRecord, getRecordList, getString, parsePayload } from '@/features/ai/runtime/payload-json'
import type {
  RealtimeModelStream,
  RealtimeToolStream,
} from '@/features/ai/runtime/thread-realtime-state'
import type {
  DialogueMessage,
  QueuedThreadMessage,
  ThreadTimeline,
  ToolApprovalState,
  ToolDialogueMessage,
} from '@/features/ai/runtime/thread-timeline-types'
import {
  projectDurableEntry,
  type EntryProjectionContext,
} from '@/features/ai/runtime/thread-timeline/entry-projection'
import { contentText } from '@/features/ai/runtime/thread-timeline/content-utils'
import { createModelAttemptFailureMessage } from '@/features/ai/runtime/thread-timeline/model-attempt-failure'
import { translate } from '@/shared/i18n'

/**
 * Thread transcript 投影：
 * 持久路径的 Entries 是 transcript 的唯一权威来源；
 * 仅 QUEUED USER_MESSAGE / CUSTOM_MESSAGE 命令会作为装饰性 overlay 渲染；
 * 活动 ModelInvocation 的 checkpoint/stream 以及 ToolInvocation 的 partial，作为
 * 瞬态 overlay 渲染，直到对应的持久 Entry 到达为止。
 */
export function buildThreadTimeline(
  entries: HarnessSessionEntryDTO[],
  queuedCommands: HarnessThreadCommandDTO[],
  toolInvocations: ToolInvocationDTO[],
  modelStream: RealtimeModelStream | null = null,
  toolStreams: ReadonlyMap<string, RealtimeToolStream> | null = null,
  modelAttemptFailures: readonly ModelAttemptFailureDTO[] = [],
  modelInvocation: ModelInvocationDTO | null = null,
): ThreadTimeline {
  const messages: DialogueMessage[] = []
  const queuedMessages: QueuedThreadMessage[] = []
  const durableToolArguments = new Map<string, string[]>()
  // Turn usage 挂起上下文：summary 在相应 TURN_END 之后投影。
  const projectionContext: EntryProjectionContext = { pendingTurnSummary: null }
  let hasPendingInputs = false
  let inCompactionTurn = false
  let latestTurnIsCompaction = false

  for (const entry of entries) {
    if (entry.entryType === 'TURN_START') {
      const payload = asRecord(parsePayload(entry.payloadJson))
      latestTurnIsCompaction = getString(payload.reason) === 'COMPACTION'
      inCompactionTurn = latestTurnIsCompaction
      projectDurableEntry(entry, messages, durableToolArguments, projectionContext)
      continue
    }
    if (inCompactionTurn) {
      if (entry.entryType === 'TURN_END') {
        inCompactionTurn = false
      }
      continue
    }
    projectDurableEntry(entry, messages, durableToolArguments, projectionContext)
  }

  for (const command of queuedCommands) {
    if (command.state !== 'QUEUED') {
      continue
    }
    const queuedMessage = extractQueuedMessage(command.payloadJson, command.type)
    if (!queuedMessage) {
      continue
    }
    queuedMessages.push({
      clientCommandId: command.clientCommandId,
      role: queuedMessage.role,
      text: queuedMessage.text,
      sequence: command.sequence,
    })
    hasPendingInputs = true
  }

  projectInvocationOverlays(messages, toolInvocations, toolStreams)

  if (!latestTurnIsCompaction) {
    projectModelAttemptFailures(messages, modelAttemptFailures, modelInvocation)
  }

  const modelStreamAttemptFailed =
    modelStream != null
    && modelAttemptFailures.some(
      (failure) =>
        isProjectableModelAttemptFailure(failure)
        && failure.modelInvocationId === modelStream.invocationId
        && failure.attempt === modelStream.attempt,
    )
  if (!latestTurnIsCompaction && modelStream != null) {
    projectModelStream(
      messages,
      modelStream,
      modelStreamAttemptFailed,
      modelInvocation,
    )
  }

  return {
    messages,
    queuedMessages,
    hasPendingInputs,
  }
}

function projectModelAttemptFailures(
  messages: DialogueMessage[],
  failures: readonly ModelAttemptFailureDTO[],
  modelInvocation: ModelInvocationDTO | null,
): void {
  const identities = new Set<string>()
  const orderedFailures = [...failures].sort((left, right) => left.attempt - right.attempt)
  for (const failure of orderedFailures) {
    if (!isProjectableModelAttemptFailure(failure)) {
      continue
    }
    const identity = `${failure.modelInvocationId}:${failure.attempt}`
    if (identities.has(identity)) {
      continue
    }
    identities.add(identity)
    const liveRetry = isLiveRetryPending(failure, modelInvocation)
    messages.push(
      createModelAttemptFailureMessage({
        id: `snapshot:model-attempt-failure:${identity}`,
        subjectEntryId: null,
        createdAt: failure.failedAt,
        attempt: failure.attempt,
        sequence: failure.sequence,
        text: failure.text,
        thinking: failure.thinking,
        errorCode: failure.errorCode,
        errorMessage: failure.errorMessage,
        failedAt: failure.failedAt,
        retryAt: failure.retryAt,
        nextAttempt: failure.attempt + 1,
        modelInvocationId: liveRetry ? failure.modelInvocationId : undefined,
        turnStartEntryId: failure.turnStartEntryId,
        basisHeadEntryId: failure.basisHeadEntryId,
      }),
    )
  }
}

function isLiveRetryPending(
  failure: ModelAttemptFailureDTO,
  invocation: ModelInvocationDTO | null,
): boolean {
  return invocation != null
    && invocation.id === failure.modelInvocationId
    && invocation.attempt === failure.attempt
    && (invocation.status === 'READY' || invocation.status === 'DISPATCHING')
}

function projectModelStream(
  messages: DialogueMessage[],
  stream: RealtimeModelStream,
  attemptAlreadyFailed: boolean,
  invocation: ModelInvocationDTO | null,
): void {
  const id = `realtime:model:${stream.invocationId}:${stream.attempt}`
  if (stream.status === 'error') {
    const errorText =
      stream.errorText || translate('ai.runtime.entry.assistantRequestFailed')
    const cancelled =
      invocation?.id === stream.invocationId && invocation.status === 'CANCELLED'
    if (cancelled && (stream.text || stream.thinking)) {
      messages.push({
        id,
        role: 'assistant',
        subjectEntryId: null,
        text: stream.text,
        thinking: stream.thinking || undefined,
        createdAt: stream.createdAt,
        status: 'done',
        aborted: true,
      })
      return
    }
    if (cancelled || attemptAlreadyFailed || stream.attempt <= 0) {
      messages.push({
        id,
        role: 'assistant',
        subjectEntryId: null,
        text: errorText,
        createdAt: stream.createdAt,
        status: 'error',
      })
      return
    }
    messages.push(
      createModelAttemptFailureMessage({
        id,
        subjectEntryId: null,
        createdAt: stream.createdAt,
        attempt: stream.attempt,
        sequence: String(stream.sequence),
        text: stream.text,
        thinking: stream.thinking,
        errorCode: stream.errorCode ?? '',
        errorMessage: errorText,
        failedAt: stream.createdAt,
        retryAt: null,
        nextAttempt: null,
      }),
    )
    return
  }
  if (attemptAlreadyFailed || (!stream.text && !stream.thinking)) {
    return
  }
  messages.push({
    id,
    role: 'assistant',
    subjectEntryId: null,
    text: stream.text,
    thinking: stream.thinking || undefined,
    createdAt: stream.createdAt,
    // 持久终止态 success 投影绝不会以 streaming 形式渲染。
    status: stream.status,
  })
}

function isProjectableModelAttemptFailure(failure: ModelAttemptFailureDTO): boolean {
  return Boolean(
    failure.modelInvocationId
    && failure.turnStartEntryId
    && failure.basisHeadEntryId
    && Number.isSafeInteger(failure.attempt)
    && failure.attempt > 0
    && isCanonicalNonNegativeDecimal(failure.sequence)
    && typeof failure.text === 'string'
    && typeof failure.thinking === 'string'
    && typeof failure.errorCode === 'string'
    && typeof failure.errorMessage === 'string',
  )
}

function isCanonicalNonNegativeDecimal(value: unknown): value is string {
  return typeof value === 'string' && /^(?:0|[1-9]\d*)$/.test(value)
}

/**
 * 将 ToolInvocation 状态投影到持久的 tool-call 消息上：活动状态/审批，以及
 * 在持久的 tool result Entry 到达之前的瞬态 TOOL_PARTIAL overlay。
 */
function projectInvocationOverlays(
  messages: DialogueMessage[],
  toolInvocations: ToolInvocationDTO[],
  toolStreams: ReadonlyMap<string, RealtimeToolStream> | null,
): void {
  if (toolInvocations.length === 0) {
    return
  }
  // 持久身份键（assistantEntryId, ordinal）：一次 invocation 只属于一个 assistant Entry 的
  // 一个调用位置。复用的 toolCallId 出现在不同的 assistant Entry 上时，绝不能接收该
  // invocation 的 overlay，因此不存在「第一个候选」回退。
  const byDurableIdentity = new Map<string, ToolInvocationDTO>()
  for (const invocation of toolInvocations) {
    const key = `${invocation.assistantEntryId}:${invocation.ordinal}`
    if (!byDurableIdentity.has(key)) {
      byDurableIdentity.set(key, invocation)
    }
  }
  for (let index = 0; index < messages.length; index += 1) {
    const message = messages[index]
    if (message == null || message.role !== 'tool' || message.phase !== 'call') {
      continue
    }
    const invocation = byDurableIdentity.get(
      `${message.subjectEntryId ?? ''}:${toolCallOrdinal(message.id)}`,
    )
    if (
      invocation == null
      || message.subjectEntryId !== invocation.assistantEntryId
      || invocation.toolCallId !== message.toolCallId
      || invocation.rendererKey !== message.rendererKey
    ) {
      continue
    }
    const terminal = invocation.resultJson != null || invocation.errorJson != null
    const stream = toolStreams?.get(invocation.id) ?? null
    const overlay =
      stream != null && stream.attempt === invocation.attempt ? stream : null
    const approval = parseApproval(invocation.approvalJson)
    const projected: ToolDialogueMessage = {
      ...message,
      // error=true 的终态 ToolResult（经由 overlay 投影）绝不能渲染为 done；
      // errorJson 才是持久的错误投影。
      status: terminal
        ? (invocation.errorJson != null || overlay?.error === true ? 'error' : 'done')
        : 'streaming',
      invocationId: invocation.id,
      partial: overlay && overlay.text ? overlay.text : undefined,
      partialAttachments:
        overlay && overlay.attachments && overlay.attachments.length > 0
          ? overlay.attachments
          : undefined,
      partialErrorText: overlay?.errorText,
      approval: approval?.required ? approval : undefined,
      errorMessage: invocation.errorJson != null ? undefined : message.errorMessage,
    }
    messages[index] = projected
  }
}

/** 将规范的 ToolApproval JSON 解析为可安全展示的投影。 */
export function parseApproval(approvalJson: string | null): ToolApprovalState | null {
  if (approvalJson == null) {
    return null
  }
  let value: unknown
  try {
    value = JSON.parse(approvalJson)
  } catch {
    return null
  }
  if (!isRecord(value)) {
    return null
  }
  const required = value.required === true
  const decision = value.decision
  return {
    required,
    // 持久 codec 保存的是领域枚举 ALLOWED/DENIED（输入 DTO 使用的是 ALLOW/DENY）。
    decision: decision === 'ALLOWED' || decision === 'DENIED' ? decision : null,
    decisionId: typeof value.decisionId === 'string' ? value.decisionId : null,
    reason: typeof value.reason === 'string' && value.reason.trim() ? value.reason : null,
  }
}

function toolCallOrdinal(messageId: string): number {
  const last = messageId.split(':').pop() ?? ''
  const parsed = Number(last)
  return Number.isSafeInteger(parsed) ? parsed : -1
}

function extractQueuedMessage(
  payloadJson: string,
  commandType: string,
): { role: 'user' | 'system'; text: string } | null {
  const payload = parsePayload(payloadJson)
  const message = asRecord(payload.message)
  const contents = getRecordList(message.contents)
  const text = contents.map(contentText).filter(Boolean).join('\n')
  if (!text) {
    return null
  }
  const role = getString(message.role)
  if (commandType === 'CUSTOM_MESSAGE' && role === 'SYSTEM') {
    return { role: 'system', text }
  }
  if (commandType === 'CUSTOM_MESSAGE' && role === 'USER') {
    return { role: 'user', text }
  }
  return { role: 'user', text }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value != null && !Array.isArray(value)
}
