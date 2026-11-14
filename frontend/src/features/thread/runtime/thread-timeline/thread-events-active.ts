import { translate } from '@/shared/i18n'
import type {
  RealtimeModelStream,
} from '@/features/thread/runtime/thread-realtime/model-stream'
import type {
  RealtimeToolStream,
} from '@/features/thread/runtime/thread-realtime/tool-stream'
import type {
  HarnessActiveAssistantOutputDTO,
  HarnessGenerationFailureDTO,
  ToolInvocationDTO,
} from '@/shared/api/generated/contracts'
import { formatBackendDateSeconds } from '@/shared/lib/date-format'
import { formatRawJsonText } from '@/features/thread/runtime/thread-timeline/payload-json'
import type {
  ThreadEventDetailRow,
  ThreadEventRecord,
  ThreadEventStatus,
} from '@/features/thread/runtime/thread-timeline/thread-events-types'
import { eventStatusText } from '@/features/thread/runtime/thread-timeline/thread-events-types'
import { formatDisplayJson, summarizeField, withTime } from '@/features/thread/runtime/thread-timeline/thread-events-json'

export function projectAttemptFailureRecord(
  failure: HarnessGenerationFailureDTO,
  turn: { turnStartEntryId: string | null; turnNumber: number },
): ThreadEventRecord {
  const details: ThreadEventDetailRow[] = [
    { label: translate('ai.runtime.event.detail.generationId'), value: failure.generationId },
    { label: translate('ai.runtime.event.detail.turnStartEntryId'), value: failure.turnStartEntryId },
    { label: translate('ai.runtime.event.detail.requestHeadEntryId'), value: failure.requestHeadEntryId },
    { label: translate('ai.runtime.event.detail.attempt'), value: String(failure.attempt) },
    { label: translate('ai.runtime.event.detail.sequence'), value: failure.sequence },
    { label: translate('ai.runtime.event.detail.errorCode'), value: failure.errorCode },
    { label: translate('ai.runtime.event.detail.errorMessage'), value: failure.errorMessage },
    { label: translate('ai.runtime.event.detail.failedAt'), value: formatBackendDateSeconds(failure.failedAt) },
    { label: translate('ai.runtime.event.detail.retryAt'), value: formatBackendDateSeconds(failure.retryAt) },
  ]
  if (failure.text) {
    details.push({
      label: translate('ai.runtime.event.detail.partialText'),
      value: failure.text,
    })
  }
  if (failure.thinking) {
    details.push({
      label: translate('ai.runtime.event.detail.partialThinking'),
      value: failure.thinking,
    })
  }
  return {
    id: `active:attempt-failure:${failure.generationId}:${failure.attempt}`,
    source: 'attempt-failure',
    entryId: null,
    turnStartEntryId: turn.turnStartEntryId,
    turnNumber: turn.turnNumber,
    kind: 'MODEL_ATTEMPT_FAILURE',
    status: 'failed',
    title: 'MODEL_ATTEMPT_FAILURE',
    summary: `${failure.errorCode} · ${summarizeField(failure.errorMessage, translate('ai.runtime.event.emptyText'))}`,
    createdAt: failure.failedAt,
    details,
    rawJson: formatDisplayJson(failure),
  }
}

export function projectActiveModelRecord(
  activeOutput: HarnessActiveAssistantOutputDTO,
  stream: RealtimeModelStream | null,
  turn: { turnStartEntryId: string | null; turnNumber: number },
): ThreadEventRecord {
  const status =
    stream?.status === 'error'
      ? 'failed'
      : mapActiveStatus(activeOutput.status)
  const chars = (stream?.text.length ?? 0) + (stream?.thinking.length ?? 0)
  const details: ThreadEventDetailRow[] = [
    { label: translate('ai.runtime.event.detail.status'), value: eventStatusText(status) },
    ...(activeOutput.status
      ? [{ label: translate('ai.runtime.event.detail.rawStatus'), value: activeOutput.status }]
      : []),
    { label: translate('ai.runtime.event.detail.generationId'), value: activeOutput.generationId },
    { label: translate('ai.runtime.event.detail.turnStartEntryId'), value: activeOutput.turnStartEntryId },
    { label: translate('ai.runtime.event.detail.requestHeadEntryId'), value: activeOutput.requestHeadEntryId },
    { label: translate('ai.runtime.event.detail.attempt'), value: String(activeOutput.attempt) },
    { label: translate('ai.runtime.event.detail.sequence'), value: activeOutput.sequence },
  ]
  if (activeOutput.error != null) {
    details.push(
      { label: translate('ai.runtime.event.detail.errorCode'), value: activeOutput.error.code },
      { label: translate('ai.runtime.event.detail.errorMessage'), value: activeOutput.error.message },
    )
  }
  if (activeOutput.resultEntryId != null) {
    details.push({
      label: translate('ai.runtime.event.detail.result'),
      value: activeOutput.resultEntryId,
    })
  }
  if (chars > 0) {
    details.push({ label: translate('ai.runtime.event.detail.chars'), value: String(chars) })
  }
  if (stream != null) {
    details.push({
      label: translate('ai.runtime.event.detail.realtime'),
      value: formatDisplayJson(stream),
    })
  }
  const rawPayload = stream != null
    ? { ...activeOutput, realtimeStream: stream }
    : activeOutput

  return {
    id: `active:model:${activeOutput.generationId}`,
    source: 'active-model',
    entryId: null,
    turnStartEntryId: turn.turnStartEntryId,
    turnNumber: turn.turnNumber,
    kind: 'ACTIVE_MODEL_INVOCATION',
    status,
    title: 'ACTIVE_MODEL_INVOCATION',
    summary: `${eventStatusText(status)} · ${translate('ai.runtime.event.detail.attempt')} ${activeOutput.attempt}`,
    createdAt: activeOutput.createdAt,
    details: withTime(details, activeOutput.createdAt),
    rawJson: formatDisplayJson(rawPayload),
  }
}

export function projectActiveToolRecord(
  invocation: ToolInvocationDTO,
  stream: RealtimeToolStream | null,
  turn: { turnStartEntryId: string | null; turnNumber: number },
): ThreadEventRecord {
  const status = activeToolStatus(invocation, stream)
  const details: ThreadEventDetailRow[] = [
    { label: translate('ai.runtime.event.detail.status'), value: eventStatusText(status) },
    ...(invocation.status
      ? [{ label: translate('ai.runtime.event.detail.rawStatus'), value: invocation.status }]
      : []),
    { label: translate('ai.runtime.event.detail.invocationId'), value: invocation.id },
    { label: translate('ai.runtime.event.detail.threadId'), value: invocation.threadId },
    { label: translate('ai.runtime.event.detail.turnStartEntryId'), value: invocation.turnStartEntryId },
    { label: translate('ai.runtime.event.detail.assistantEntryId'), value: invocation.assistantEntryId },
    { label: translate('ai.runtime.event.detail.toolName'), value: invocation.toolName || '' },
    { label: translate('ai.runtime.event.detail.toolCallId'), value: invocation.toolCallId || '' },
    { label: translate('ai.runtime.event.detail.rendererKey'), value: invocation.rendererKey || '' },
    { label: translate('ai.runtime.event.detail.callIndex'), value: String(invocation.callIndex) },
    { label: translate('ai.runtime.event.detail.attempt'), value: String(invocation.attempt) },
    {
      label: translate('ai.runtime.event.detail.environment'),
      value: invocation.environmentId ?? '',
    },
    { label: translate('ai.runtime.event.detail.arguments'), value: formatRawJsonText(invocation.argumentsJson) },
  ]
  if (invocation.approval != null) {
    details.push({
      label: translate('ai.runtime.event.detail.approval'),
      value: formatDisplayJson(invocation.approval),
    })
  }
  if (invocation.result != null) {
    details.push({
      label: translate('ai.runtime.event.detail.result'),
      value: formatDisplayJson(invocation.result),
    })
  }
  if (invocation.error != null) {
    details.push({
      label: translate('ai.runtime.event.detail.error'),
      value: formatDisplayJson(invocation.error),
    })
  }
  if (stream != null) {
    details.push({
      label: translate('ai.runtime.event.detail.realtime'),
      value: formatDisplayJson(stream),
    })
  }
  const rawPayload = stream != null
    ? { ...invocation, realtimeStream: stream }
    : invocation

  return {
    id: `active:tool:${invocation.id}`,
    source: 'active-tool',
    entryId: null,
    turnStartEntryId: turn.turnStartEntryId,
    turnNumber: turn.turnNumber,
    kind: 'ACTIVE_TOOL_INVOCATION',
    status,
    title: 'ACTIVE_TOOL_INVOCATION',
    summary: `${invocation.toolName || translate('ai.runtime.event.unknownTool')} · ${eventStatusText(status)}`,
    createdAt: invocation.createdAt,
    details: withTime(details, invocation.createdAt),
    rawJson: formatDisplayJson(rawPayload),
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
    case 'waiting_retry':
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
    case 'blocked':
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
  if (invocation.error != null) {
    return 'failed'
  }
  if (invocation.result != null) {
    return 'completed'
  }
  return mapActiveStatus(invocation.status)
}
