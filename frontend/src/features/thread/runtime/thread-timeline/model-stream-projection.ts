import { translate } from '@/shared/i18n'
import type {
  RealtimeModelStream,
} from '@/features/thread/runtime/thread-realtime/model-stream'
import type {
  DialogueMessage,
  ToolDialogueMessage,
} from '@/features/thread/runtime/thread-timeline/thread-timeline-types'
import { createModelAttemptFailureMessage } from '@/features/thread/runtime/thread-timeline/model-attempt-failure'
import type {
  HarnessActiveAssistantOutputDTO,
  HarnessGenerationFailureDTO,
} from '@/shared/api/generated/contracts'

export function projectModelStream(
  messages: DialogueMessage[],
  stream: RealtimeModelStream,
  attemptAlreadyFailed: boolean,
  activeAssistantOutput: HarnessActiveAssistantOutputDTO | null,
): void {
  const id = `realtime:model:${stream.invocationId}:${stream.attempt}`
  if (stream.status === 'error') {
    const errorText =
      stream.errorText || translate('ai.runtime.entry.assistantRequestFailed')
    const cancelled =
      activeAssistantOutput?.generationId === stream.invocationId
      && activeAssistantOutput.status === 'CANCELLED'
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
      contents: [],
      createdAt: stream.createdAt,
      status: stream.status === 'streaming' ? 'streaming' : 'done',
    })
  }
}

export function isProjectableGenerationFailure(failure: HarnessGenerationFailureDTO): boolean {
  return Boolean(
    failure.generationId
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
