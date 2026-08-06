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
 * Thread transcript projection:
 * durable path Entries are the sole transcript authority;
 * only QUEUED USER_MESSAGE / CUSTOM_MESSAGE commands render as decoration overlays;
 * the active ModelInvocation checkpoint/stream and ToolInvocation partials render as transient
 * overlays until their durable Entries arrive.
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
      // Terminal durable projections (done/error) are never rendered as streaming.
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
 * Projects ToolInvocation state onto the durable tool-call messages: active status/approval and
 * the transient TOOL_PARTIAL overlay until the durable tool result Entry arrives.
 */
function projectInvocationOverlays(
  messages: DialogueMessage[],
  toolInvocations: ToolInvocationDTO[],
  toolStreams: ReadonlyMap<string, RealtimeToolStream> | null,
): void {
  if (toolInvocations.length === 0) {
    return
  }
  // Durable identity key (assistantEntryId, ordinal): an invocation belongs to exactly one
  // assistant Entry at one call position. A reused toolCallId on a different assistant Entry
  // must never receive this invocation's overlay, so there is NO first-candidate fallback.
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
      // A terminal ToolResult with error=true (projected via the overlay) must never render
      // as done; errorJson is the durable error projection.
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

/** Parses the canonical ToolApproval JSON into a display-safe projection. */
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
    // The durable codec persists the domain enum ALLOWED/DENIED (the input DTO used ALLOW/DENY).
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
