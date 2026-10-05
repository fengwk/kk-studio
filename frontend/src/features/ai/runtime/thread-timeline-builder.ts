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
import {
  sameToolCall,
  toolCallIdentity,
  type DialogueMessage,
  type QueuedThreadMessage,
  type ThreadTimeline,
  type ToolApprovalState,
  type ToolDialogueMessage,
} from '@/features/ai/runtime/thread-timeline-types'
import { parseToolErrorText } from '@/features/ai/runtime/thread-realtime-state'
import {
  projectDurableEntry,
  projectToolResultContent,
  type EntryProjectionContext,
} from '@/features/ai/runtime/thread-timeline/entry-projection'
import { contentText } from '@/features/ai/runtime/thread-timeline/content-utils'
import { createModelAttemptFailureMessage } from '@/features/ai/runtime/thread-timeline/model-attempt-failure'
import { translate } from '@/shared/i18n'

/** 仍在 mailbox 中排队的人类输入类型；NOTIFICATION / CUSTOM_MESSAGE 是系统事实与编排产物。 */
const HUMAN_INPUT_COMMAND_TYPES = new Set(['USER_MESSAGE', 'GOAL'])

/**
 * Thread transcript 投影：
 * 持久路径的 Entries 是 transcript 的唯一权威来源；
 * 仅 QUEUED USER_MESSAGE 命令会作为可编辑的装饰性 overlay 渲染；NOTIFICATION 等系统事实
 * 与 CUSTOM_MESSAGE 编排产物只出现在持久 transcript 中，不进入可编辑队列、草稿与上下键历史；
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
  const projectionContext: EntryProjectionContext = { pendingTurnSummary: null, lastSettings: null }
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
    // 可编辑队列与上下键历史只承载人类输入 USER_MESSAGE：NOTIFICATION（系统结果事实）、
    // CUSTOM_MESSAGE（编排产物）、GOAL（在 Goal 编辑区按专用语义编辑）与配置命令都不进入。
    if (command.type !== 'USER_MESSAGE') {
      if (HUMAN_INPUT_COMMAND_TYPES.has(command.type)) {
        hasPendingInputs = true
      }
      continue
    }
    const queuedMessage = extractQueuedMessage(command.payloadJson)
    if (!queuedMessage) {
      continue
    }
    queuedMessages.push({
      idempotencyKey: command.idempotencyKey,
      role: queuedMessage.role,
      text: queuedMessage.text,
      sequence: command.sequence,
    })
    hasPendingInputs = true
  }

  projectInvocationOverlays(messages, toolInvocations, toolStreams, modelInvocation?.threadId)

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
        requestHeadEntryId: failure.requestHeadEntryId,
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
  if (attemptAlreadyFailed || (!stream.text && !stream.thinking && stream.toolCalls.length === 0)) {
    return
  }
  if (stream.text || stream.thinking) {
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
  projectStreamingToolCalls(messages, stream)
}

function projectStreamingToolCalls(messages: DialogueMessage[], stream: RealtimeModelStream): void {
  if (stream.toolCalls.length === 0) {
    return
  }
  const existing = new Set(
    messages
      .filter((message): message is ToolDialogueMessage => message.role === 'tool')
      .map((message) => message.toolCallId)
      .filter(Boolean),
  )
  for (const draft of stream.toolCalls) {
    if (draft.id && existing.has(draft.id)) {
      continue
    }
    const toolName = draft.name.trim() || 'Tool'
    messages.push({
      id: `realtime:model:${stream.invocationId}:${stream.attempt}:tool:${draft.index}`,
      role: 'tool',
      phase: 'call',
      subjectEntryId: null,
      toolCallId: draft.id,
      toolName,
      rendererKey: draft.name.trim(),
      arguments: draft.argumentsJson,
      text: '',
      attachments: [],
      createdAt: stream.createdAt,
      status: stream.status === 'streaming' ? 'streaming' : 'done',
    })
  }
}

function isProjectableModelAttemptFailure(failure: ModelAttemptFailureDTO): boolean {
  return Boolean(
    failure.modelInvocationId
    && failure.turnStartEntryId
    && failure.requestHeadEntryId
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
  threadId?: string,
): void {
  if (toolInvocations.length === 0) {
    return
  }
  // 持久身份键（assistantEntryId, callIndex）：一次 invocation 只属于一个 assistant Entry 的
  // 一个调用位置。复用的 toolCallId 出现在不同的 assistant Entry 上时，绝不能接收该
  // invocation 的 overlay，因此不存在「第一个候选」回退。
  const byDurableIdentity = new Map<string, ToolInvocationDTO>()
  for (const invocation of toolInvocations) {
    const key = `${invocation.assistantEntryId}:${invocation.callIndex}`
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
    const terminalResult = terminalInvocationResult(invocation, message)
    const stream = toolStreams?.get(invocation.id) ?? null
    const overlay =
      stream != null && stream.attempt === invocation.attempt ? stream : null
    const approval = parseApproval(invocation.approvalJson)
    const failed =
      terminalResult != null
      && (
        terminalResult.status === 'error'
        || invocation.errorJson != null
        || isFailedInvocationStatus(invocation.status)
      )
    const failureText = invocation.errorJson != null
      ? parseToolErrorText(invocation.errorJson)
      : null
    const projected: ToolDialogueMessage = {
      ...message,
      callIdentity: toolCallIdentity(invocation.assistantEntryId, invocation.callIndex),
      // 终态以 invocation 自己的 resultJson/errorJson 为准。call.status=done 只表示
      // arguments 已完成，不能在 durable result 或 invocation 结果到达前当成成功。
      status: terminalResult == null ? 'streaming' : (failed ? 'error' : 'done'),
      invocationId: invocation.id,
      // environmentId 是环境路由身份，绝不能当作审批目标 Thread。
      threadId: threadId || undefined,
      // 终态结果已经可见时，旧 partial 不能再盖过正文、附件或错误。
      partial: terminalResult == null && overlay?.text ? overlay.text : undefined,
      partialAttachments:
        terminalResult == null && overlay?.attachments && overlay.attachments.length > 0
          ? overlay.attachments
          : undefined,
      partialErrorText: terminalResult == null ? overlay?.errorText : undefined,
      approval: approval?.required ? approval : undefined,
      errorMessage: failed ? failureText ?? undefined : message.errorMessage,
    }
    messages[index] = projected
    if (terminalResult != null && !hasDurableToolResult(messages, projected)) {
      messages.splice(index + 1, 0, {
        ...terminalResult,
        id: `transient:tool-result:${invocation.id}:${invocation.attempt}`,
        role: 'tool',
        phase: 'result',
        subjectEntryId: projected.subjectEntryId,
        callIdentity: projected.callIdentity,
        toolCallId: projected.toolCallId,
        toolName: projected.toolName,
        rendererKey: projected.rendererKey,
        createdAt: invocation.updateTime,
        invocationId: invocation.id,
        threadId: projected.threadId,
        status: failed ? 'error' : terminalResult.status,
        errorMessage: failed
          ? terminalResult.errorMessage ?? failureText ?? translate('ai.runtime.entry.toolFailed')
          : undefined,
      })
      index += 1
    }
  }
}

/**
 * invocation 自身的终态结果。resultJson 里 error=true 优先于 SUCCEEDED；
 * FAILED / UNKNOWN / CANCELLED 即使没有结果正文也是错误终态。
 * 没有 resultJson/errorJson 且状态仍活动时返回 null，调用保持 pending。
 */
function terminalInvocationResult(
  invocation: ToolInvocationDTO,
  call: ToolDialogueMessage,
): Pick<ToolDialogueMessage, 'arguments' | 'text' | 'attachments' | 'errorMessage' | 'status'> | null {
  if (invocation.resultJson != null) {
    return projectToolResultContent(parsePayload(invocation.resultJson), call.arguments)
  }
  if (invocation.errorJson != null || isFailedInvocationStatus(invocation.status)) {
    return {
      arguments: call.arguments,
      text: '',
      attachments: [],
      errorMessage: translate('ai.runtime.entry.toolFailed'),
      status: 'error',
    }
  }
  return null
}

function isFailedInvocationStatus(status: string): boolean {
  return status === 'FAILED' || status === 'UNKNOWN' || status === 'CANCELLED'
}

/** durable result 到达后是唯一展示源；transient overlay 不得再重复输出。 */
function hasDurableToolResult(
  messages: readonly DialogueMessage[],
  call: ToolDialogueMessage,
): boolean {
  return messages.some((candidate) =>
    candidate.role === 'tool'
    && candidate.phase === 'result'
    && !candidate.id.startsWith('transient:tool-result:')
    && sameToolCall(call, candidate),
  )
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
): { role: 'user'; text: string } | null {
  const payload = parsePayload(payloadJson)
  const message = asRecord(payload.message)
  const contents = getRecordList(message.contents)
  const text = contents.map(contentText).filter(Boolean).join('\n')
  if (!text) {
    return null
  }
  return { role: 'user', text }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value != null && !Array.isArray(value)
}
