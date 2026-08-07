import type {
  HarnessSessionEntryDTO,
  HarnessThreadCommandDTO,
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
import { projectDurableEntry } from '@/features/ai/runtime/thread-timeline/entry-projection'
import { contentText } from '@/features/ai/runtime/thread-timeline/content-utils'

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
): ThreadTimeline {
  const messages: DialogueMessage[] = []
  const queuedMessages: QueuedThreadMessage[] = []
  const durableToolArguments = new Map<string, string[]>()
  let hasPendingInputs = false

  for (const entry of entries) {
    projectDurableEntry(entry, messages, durableToolArguments)
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
      commandId: command.commandId,
      role: queuedMessage.role,
      text: queuedMessage.text,
      sequence: command.sequence,
    })
    hasPendingInputs = true
  }

  projectInvocationOverlays(messages, toolInvocations, toolStreams)

  if (modelStream != null && (modelStream.text || modelStream.thinking)) {
    messages.push({
      id: `realtime:model:${modelStream.invocationId}:${modelStream.attempt}`,
      role: 'assistant',
      subjectEntryId: null,
      text: modelStream.text,
      thinking: modelStream.thinking || undefined,
      createdAt: modelStream.createdAt,
      // 终态持久投影（done/error）绝不会以 streaming 形式渲染。
      status: modelStream.status,
    })
  }

  return {
    messages,
    queuedMessages,
    hasPendingInputs,
  }
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
