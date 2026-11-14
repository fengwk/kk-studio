import { asRecord, getRecordList } from '@/features/thread/runtime/payload-json'
import { getString } from '@/shared/lib/value'
import { parseCodecInstantMillis } from '@/shared/lib/date-format'
import type {
  DialogueMessage,
  MetaDialogueMessage,
} from '@/features/thread/runtime/thread-timeline-types'
import { contentText } from '@/features/thread/runtime/thread-timeline/content-utils'
import { createModelAttemptFailureMessage } from '@/features/thread/runtime/thread-timeline/model-attempt-failure'
import {
  projectForkEntry,
  projectInvalidSettings,
  projectRootSettings,
  projectSettingsChanges,
  projectModelFallback,
  parseSettingsSnapshot,
  projectNotificationEntry,
  projectUnknownEntry,
} from '@/features/thread/runtime/thread-timeline/entry-event-projection'
import { projectMessageEntry } from '@/features/thread/runtime/thread-timeline/entry-message-projection'
import type { EntryProjectionContext } from '@/features/thread/runtime/thread-timeline/entry-projection-context'
import { translate } from '@/shared/i18n'
import type { HarnessSessionEntryDTO } from '@/shared/api/generated/contracts'

export type { EntryProjectionContext } from '@/features/thread/runtime/thread-timeline/entry-projection-context'

/** durable tool_call 已记录的 arguments，按调用身份排队等待同名 tool_result 配对。 */
type DurableToolArguments = Map<string, string[]>

/**
 * 一条 durable Entry 投影到对话消息：
 * 控制边界（TURN_START / COMPACTION / TURN_END）更新 turn 上下文但不产生对话行，
 * 其余 Entry 类型各自投影。
 */
export function projectDurableEntry(
  entry: HarnessSessionEntryDTO,
  messages: DialogueMessage[],
  durableToolArguments: DurableToolArguments,
  context: EntryProjectionContext,
): void {
  const payload = asRecord(entry.payload)
  const entryType = entry.entryType
  if (entryType === 'ROOT') {
    projectRootEntry(entry, payload, messages, context)
    return
  }
  if (entryType === 'SETTINGS') {
    projectSettingsEntry(entry, payload, messages, context)
    return
  }
  if (entryType === 'TURN_START' || entryType === 'COMPACTION' || entryType === 'TURN_END') {
    projectTurnBoundary(entry, payload, messages, context)
    return
  }
  if (entryType === 'MODEL_ATTEMPT_FAILURE') {
    const failure = parseModelAttemptFailure(entry, payload)
    messages.push(failure ?? projectUnknownEntry(entry))
    return
  }
  if (entryType === 'ASSISTANT_ERROR') {
    projectAssistantError(entry, payload, messages)
    return
  }
  if (entryType === 'ASSISTANT_ABORTED') {
    projectAssistantAborted(entry, payload, messages)
    return
  }
  if (entryType === 'NOTIFICATION') {
    messages.push(projectNotificationEntry(entry, payload))
    return
  }
  if (entryType === 'FORK') {
    messages.push(projectForkEntry(entry, payload))
    return
  }
  if (entryType !== 'MESSAGE' && entryType !== 'CUSTOM_MESSAGE') {
    messages.push(projectUnknownEntry(entry))
    return
  }
  projectMessageEntry(entry, payload, entryType, messages, durableToolArguments, context)
}

/** ROOT：建立首份 settings 基准。 */
function projectRootEntry(
  entry: HarnessSessionEntryDTO,
  payload: Record<string, unknown>,
  messages: DialogueMessage[],
  context: EntryProjectionContext,
): void {
  const settings = parseSettingsSnapshot(payload.settings)
  context.lastSettings = settings
  messages.push(settings ? projectRootSettings(entry, settings) : projectInvalidSettings(entry))
}

/** SETTINGS：安全点快照更新差值基准，不改变回合或用量上下文。 */
function projectSettingsEntry(
  entry: HarnessSessionEntryDTO,
  payload: Record<string, unknown>,
  messages: DialogueMessage[],
  context: EntryProjectionContext,
): void {
  const settings = parseSettingsSnapshot(payload.settings)
  if (settings === null) {
    context.lastSettings = null
    messages.push(projectInvalidSettings(entry))
    return
  }
  if (context.lastSettings !== null) {
    messages.push(...projectSettingsChanges(entry, context.lastSettings, settings))
  }
  context.lastSettings = settings
}

/**
 * 控制边界：不显示成 unknown entry，也不进入对话时间线。
 *
 * TURN_END 消费挂起的 TurnSummary；TURN_START / COMPACTION 丢弃上一 turn 未关闭的
 * 残留 usage，非压缩的 TURN_START 还会投影设置差值与模型回退。
 */
function projectTurnBoundary(
  entry: HarnessSessionEntryDTO,
  payload: Record<string, unknown>,
  messages: DialogueMessage[],
  context: EntryProjectionContext,
): void {
  if (entry.entryType === 'TURN_END') {
    // TurnSummary：usage 挂起至相应 TURN_END 之后投影（绝不紧跟 Assistant）。
    const summary = context.pendingTurnSummary
    context.pendingTurnSummary = null
    context.pendingTurnUsage = null
    // 无论该回合是否有 usage，TURN_END 都必须产出一条携带真实 TURN_END Entry id 的
    // 结束 meta：回合 footer 的“从此处分支”只以这个 id 分叉（未消费 usage 的回合、
    // 以及作为 head 的最新回合同样可用）。
    messages.push(turnEndMeta(summary, entry))
    return
  }
  // 新 turn 开始：丢弃上一 turn 未关闭的残留 usage。
  context.pendingTurnSummary = null
  context.pendingTurnUsage = null
  if (entry.entryType === 'TURN_START' && getString(payload.reason) !== 'COMPACTION') {
    projectTurnStartSettings(entry, payload, messages, context)
  }
}

/** 非压缩 TURN_START 的设置差值与模型回退，并推进 settings 基准。 */
function projectTurnStartSettings(
  entry: HarnessSessionEntryDTO,
  payload: Record<string, unknown>,
  messages: DialogueMessage[],
  context: EntryProjectionContext,
): void {
  const settings = parseSettingsSnapshot(payload.settings)
  if (settings === null) {
    messages.push(projectInvalidSettings(entry))
  } else if (context.lastSettings !== null) {
    messages.push(...projectSettingsChanges(entry, context.lastSettings, settings))
  }
  if (settings !== null) {
    const fallback = projectModelFallback(entry, settings, payload.executedSelection)
    if (fallback !== null) {
      messages.push(fallback)
    }
  }
  context.lastSettings = settings
}

/** 回合页脚只展示用量；缺失时以 '-' 占位，分支始终绑定真实 TURN_END Entry id。 */
type TurnEndMeta = MetaDialogueMessage & { endEntryId: string }

function turnEndMeta(
  summary: MetaDialogueMessage | null,
  entry: HarnessSessionEntryDTO,
): TurnEndMeta {
  if (summary != null) {
    return { ...summary, endEntryId: entry.entryId }
  }
  return {
    id: `meta-turn-end-${entry.entryId}`,
    role: 'meta',
    kind: 'turn_usage',
    subjectEntryId: entry.entryId,
    text: '-',
    createdAt: entry.createdAt,
    status: 'done',
    endEntryId: entry.entryId,
  }
}

function parseModelAttemptFailure(
  entry: HarnessSessionEntryDTO,
  payload: Record<string, unknown>,
) {
  const attempt = parseAttemptSnapshot(payload.attempt)
  const error = asRecord(payload.error)
  // payload 是历史 codec JSON：节点内时间只接受 Java Instant.toString() 的毫秒精度文本。
  const retryAt = parseCodecInstantMillis(payload.retryAt)
  if (attempt == null || retryAt == null) {
    return null
  }
  return createModelAttemptFailureMessage({
    id: entry.entryId,
    subjectEntryId: entry.entryId,
    createdAt: entry.createdAt,
    attempt: attempt.attempt,
    sequence: attempt.sequence,
    text: attempt.text,
    thinking: attempt.thinking,
    errorCode: getString(error.code),
    errorMessage: getString(error.message) || translate('ai.runtime.message.assistantFailed'),
    failedAt: entry.createdAt,
    retryAt,
    nextAttempt: attempt.attempt + 1,
  })
}

function parseAttemptSnapshot(value: unknown): {
  attempt: number
  sequence: string
  text: string
  thinking: string
} | null {
  const snapshot = asRecord(value)
  const attempt = integerValue(snapshot.attempt)
  const sequence = integerValue(snapshot.sequence)
  const text = snapshot.text
  const thinking = snapshot.thinking
  if (
    attempt == null
    || attempt <= 0
    || sequence == null
    || sequence < 0
    || typeof text !== 'string'
    || typeof thinking !== 'string'
  ) {
    return null
  }
  return { attempt, sequence: String(sequence), text, thinking }
}

function integerValue(value: unknown): number | null {
  return typeof value === 'number' && Number.isSafeInteger(value) ? value : null
}

/** ASSISTANT_ERROR：有 attempt 快照时投影为可重试失败，否则是一条错误消息。 */
function projectAssistantError(
  entry: HarnessSessionEntryDTO,
  payload: Record<string, unknown>,
  messages: DialogueMessage[],
): void {
  const error = asRecord(payload.error)
  const attempt = parseAttemptSnapshot(payload.attempt)
  if (attempt != null) {
    messages.push(
      createModelAttemptFailureMessage({
        id: entry.entryId,
        subjectEntryId: entry.entryId,
        createdAt: entry.createdAt,
        attempt: attempt.attempt,
        sequence: attempt.sequence,
        text: attempt.text,
        thinking: attempt.thinking,
        errorCode: getString(error.code),
        errorMessage: getString(error.message) || translate('ai.runtime.message.assistantFailed'),
        failedAt: entry.createdAt,
        retryAt: null,
        nextAttempt: null,
      }),
    )
    return
  }
  messages.push({
    id: entry.entryId,
    role: 'assistant',
    subjectEntryId: entry.entryId,
    text: getString(error.message) || translate('ai.runtime.entry.assistantRequestFailed'),
    createdAt: entry.createdAt,
    status: 'error',
  })
}

/** ASSISTANT_ABORTED：只保留已产出的正文与思考，全空时不占位。 */
function projectAssistantAborted(
  entry: HarnessSessionEntryDTO,
  payload: Record<string, unknown>,
  messages: DialogueMessage[],
): void {
  const message = asRecord(payload.message)
  const contents = getRecordList(message.contents)
  const text = contents.filter((content) => getString(content.type) === 'text').map(contentText).join('')
  const thinking = contents
    .filter((content) => getString(content.type) === 'thinking')
    .map(contentText)
    .join('')
  if (text || thinking) {
    messages.push({
      id: entry.entryId,
      role: 'assistant',
      subjectEntryId: entry.entryId,
      text,
      thinking: thinking || undefined,
      createdAt: entry.createdAt,
      status: 'done',
      aborted: true,
    })
  }
}
