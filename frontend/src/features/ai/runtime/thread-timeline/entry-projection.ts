import type { HarnessSessionEntryDTO } from '@/shared/api/contracts/ai-runtime'
import { asRecord, getRecordList, getString, parsePayload } from '@/features/ai/runtime/payload-json'
import type {
  DialogueMessage,
  MetaDialogueMessage,
  ToolDialogueMessage,
} from '@/features/ai/runtime/thread-timeline-types'
import {
  contentText,
  toResourceAttachment,
} from '@/features/ai/runtime/thread-timeline/content-utils'
import { createModelAttemptFailureMessage } from '@/features/ai/runtime/thread-timeline/model-attempt-failure'
import {
  projectEmptyMessageEntry,
  projectInvalidSettings,
  projectRootSettings,
  projectSettingsChanges,
  parseSettingsSnapshot,
  projectUnknownEntry,
  projectUnsupportedMessageEntry,
} from '@/features/ai/runtime/thread-timeline/entry-event-projection'
import type { SettingsSnapshot } from '@/features/ai/runtime/thread-timeline/entry-event-projection'
import { projectTurnUsageFromAssistantMetadata } from '@/features/ai/runtime/thread-timeline/meta-projection'
import { translate } from '@/shared/i18n'

/**
 * 逐 turn 的投影上下文。Turn usage 不紧跟 Assistant：summary 先挂起，在相应
 * TURN_END 之后才投影为 TurnSummary；新 turn 开始时丢弃未关闭 turn 的残留。
 */
export interface EntryProjectionContext {
  pendingTurnSummary: MetaDialogueMessage | null
  lastSettings: SettingsSnapshot | null
}

export function projectDurableEntry(
  entry: HarnessSessionEntryDTO,
  messages: DialogueMessage[],
  durableToolArguments: Map<string, string[]>,
  context: EntryProjectionContext,
) {
  const payload = parsePayload(entry.payloadJson)
  const entryType = entry.entryType
  if (entryType === 'ROOT') {
    const settings = parseSettingsSnapshot(payload.settings)
    context.lastSettings = settings
    messages.push(settings ? projectRootSettings(entry, settings) : projectInvalidSettings(entry))
    return
  }
  if (entryType === 'TURN_START' || entryType === 'COMPACTION' || entryType === 'TURN_END') {
    // 控制边界：不显示成 unknown entry，也不进入对话时间线。
    if (entryType === 'TURN_END') {
      // TurnSummary：usage 挂起至相应 TURN_END 之后投影（绝不紧跟 Assistant）。
      const summary = context.pendingTurnSummary
      context.pendingTurnSummary = null
      if (summary != null) {
        messages.push(summary)
      }
    } else {
      // 新 turn 开始：丢弃上一 turn 未关闭的残留 usage。
      context.pendingTurnSummary = null
      if (entryType === 'TURN_START' && getString(payload.reason) !== 'COMPACTION') {
        const settings = parseSettingsSnapshot(payload.settings)
        if (settings === null) {
          messages.push(projectInvalidSettings(entry))
        } else if (context.lastSettings !== null) {
          messages.push(...projectSettingsChanges(entry, context.lastSettings, settings))
        }
        context.lastSettings = settings
      }
    }
    return
  }
  if (entryType === 'MODEL_ATTEMPT_FAILURE') {
    const failure = parseModelAttemptFailure(entry, payload)
    messages.push(failure ?? projectUnknownEntry(entry))
    return
  }
  if (entryType === 'ASSISTANT_ERROR') {
    const error = asRecord(payload.error)
    const attempt = parseAttemptSnapshot(payload.attempt)
    if (attempt != null) {
      messages.push(
        createModelAttemptFailureMessage({
          id: entry.entryId,
          subjectEntryId: entry.entryId,
          createdAt: entry.createTime,
          attempt: attempt.attempt,
          sequence: attempt.sequence,
          text: attempt.text,
          thinking: attempt.thinking,
          errorCode: getString(error.code),
          errorMessage: getString(error.message) || translate('ai.runtime.message.assistantFailed'),
          failedAt: entry.createTime,
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
      createdAt: entry.createTime,
      status: 'error',
    })
    return
  }
  if (entryType === 'ASSISTANT_ABORTED') {
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
        createdAt: entry.createTime,
        status: 'done',
        aborted: true,
      })
    }
    return
  }
  if (entryType !== 'MESSAGE' && entryType !== 'CUSTOM_MESSAGE') {
    messages.push(projectUnknownEntry(entry))
    return
  }

  const message = asRecord(payload.message)
  const role = getString(message.role)
  const contents = getRecordList(message.contents)
  if (entryType === 'CUSTOM_MESSAGE' || role === 'USER') {
    const text = contents.map(contentText).filter(Boolean).join('\n')
    const attachments = contents.flatMap(toResourceAttachment)
    if (text || attachments.length > 0) {
      messages.push({
        id: entry.entryId,
        role: 'user',
        subjectEntryId: entry.entryId,
        text,
        attachments: attachments.length > 0 ? attachments : undefined,
        createdAt: entry.createTime,
        status: 'done',
      })
    } else {
      messages.push(projectEmptyMessageEntry(entry, role || 'USER'))
    }
    return
  }
  if (role === 'ASSISTANT') {
    const toolCalls = contents.filter((candidate) => getString(candidate.type) === 'tool_call')
    for (const content of toolCalls) {
      const key = toolCallKey(getString(content.toolCallId))
      if (!key) {
        continue
      }
      const argumentsQueue = durableToolArguments.get(key) ?? []
      argumentsQueue.push(getString(content.argumentsJson))
      durableToolArguments.set(key, argumentsQueue)
    }
    const text = contents.filter((content) => getString(content.type) === 'text').map(contentText).join('')
    const thinking = contents.filter((content) => getString(content.type) === 'thinking').map(contentText).join('')
    if (text || thinking) {
      messages.push({
        id: entry.entryId,
        role: 'assistant',
        subjectEntryId: entry.entryId,
        text,
        thinking: thinking || undefined,
        createdAt: entry.createTime,
        status: 'done',
      })
    }
    toolCalls.forEach((content, index) => {
      messages.push(projectToolCall(entry, content, index))
    })
    if (!text && !thinking && toolCalls.length === 0) {
      messages.push(projectEmptyMessageEntry(entry, role))
    }
    const metadata = asRecord(payload.assistantMetadata)
    // Turn usage 不在此处投影：挂起到当前 turn 的 TURN_END 之后再输出。
    context.pendingTurnSummary = projectTurnUsageFromAssistantMetadata(
      entry.entryId,
      metadata,
      entry.createTime,
    )
    return
  }
  if (role === 'TOOL') {
    const toolResults = contents.filter((candidate) => getString(candidate.type) === 'tool_result')
    toolResults.forEach((content, index) => {
      const key = toolCallKey(getString(content.toolCallId))
      const argumentsQueue = durableToolArguments.get(key) ?? []
      const argumentsJson = argumentsQueue.shift() ?? ''
      if (argumentsQueue.length === 0) {
        durableToolArguments.delete(key)
      }
      messages.push(projectToolResult(entry, content, argumentsJson, index))
    })
    if (toolResults.length === 0) {
      messages.push(projectEmptyMessageEntry(entry, role))
    }
    return
  }
  messages.push(projectUnsupportedMessageEntry(entry, role))
}

function parseModelAttemptFailure(
  entry: HarnessSessionEntryDTO,
  payload: Record<string, unknown>,
) {
  const attempt = parseAttemptSnapshot(payload.attempt)
  const error = asRecord(payload.error)
  const retryAt = timestampValue(payload.retryAt)
  if (attempt == null || retryAt == null) {
    return null
  }
  return createModelAttemptFailureMessage({
    id: entry.entryId,
    subjectEntryId: entry.entryId,
    createdAt: entry.createTime,
    attempt: attempt.attempt,
    sequence: attempt.sequence,
    text: attempt.text,
    thinking: attempt.thinking,
    errorCode: getString(error.code),
    errorMessage: getString(error.message) || translate('ai.runtime.message.assistantFailed'),
    failedAt: entry.createTime,
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

function timestampValue(value: unknown): string | number | readonly number[] | null {
  if (typeof value === 'string' || (typeof value === 'number' && Number.isFinite(value))) {
    return value
  }
  return Array.isArray(value)
    && value.every((part) => typeof part === 'number' && Number.isFinite(part))
    ? value
    : null
}

function projectToolCall(
  entry: HarnessSessionEntryDTO,
  content: Record<string, unknown>,
  callIndex: number,
): ToolDialogueMessage {
  return {
    id: `${entry.entryId}:tool-call:${toolCallIdentity(content, callIndex)}`,
    role: 'tool',
    phase: 'call',
    subjectEntryId: entry.entryId,
    toolCallId: getString(content.toolCallId),
    toolName: getString(content.toolName),
    rendererKey: getString(content.rendererKey),
    arguments: getString(content.argumentsJson),
    text: '',
    attachments: [],
    createdAt: entry.createTime,
    status: 'done',
  }
}

function projectToolResult(
  entry: HarnessSessionEntryDTO,
  content: Record<string, unknown>,
  argumentsJson: string,
  callIndex: number,
): ToolDialogueMessage {
  const contents = getRecordList(content.contents)
  const error = content.error === true
  return {
    id: `${entry.entryId}:tool-result:${toolCallIdentity(content, callIndex)}`,
    role: 'tool',
    phase: 'result',
    subjectEntryId: entry.entryId,
    toolCallId: getString(content.toolCallId),
    toolName: getString(content.toolName),
    rendererKey: getString(content.rendererKey),
    arguments: argumentsJson,
    text: contents.map(contentText).filter(Boolean).join('\n'),
    attachments: contents.flatMap(toResourceAttachment),
    errorMessage: error ? translate('ai.runtime.entry.toolFailed') : undefined,
    createdAt: entry.createTime,
    status: error ? 'error' : 'done',
  }
}

function toolCallIdentity(content: Record<string, unknown>, callIndex: number): string {
  return `${getString(content.toolCallId) || 'unknown'}:${callIndex}`
}

function toolCallKey(toolCallId: string): string {
  return toolCallId ? `call:${toolCallId}` : ''
}
