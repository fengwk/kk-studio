import type { HarnessSessionEntryDTO } from '@/shared/api/contracts/ai-runtime'
import { asRecord, getRecordList, getString, parsePayload } from '@/features/ai/runtime/payload-json'
import type {
  DialogueContent,
  DialogueMessage,
  MetaDialogueMessage,
  ToolDialogueMessage,
  TurnUsage,
} from '@/features/ai/runtime/thread-timeline-types'
import { toolCallIdentity as strictToolCallIdentity } from '@/features/ai/runtime/thread-timeline-types'
import {
  contentText,
  toResourceAttachment,
  toToolContents,
} from '@/features/ai/runtime/thread-timeline/content-utils'
import { createModelAttemptFailureMessage } from '@/features/ai/runtime/thread-timeline/model-attempt-failure'
import {
  projectEmptyMessageEntry,
  projectInvalidSettings,
  projectRootSettings,
  projectSettingsChanges,
  parseSettingsSnapshot,
  projectNotificationEntry,
  projectUnknownEntry,
  projectUnsupportedMessageEntry,
} from '@/features/ai/runtime/thread-timeline/entry-event-projection'
import type { SettingsSnapshot } from '@/features/ai/runtime/thread-timeline/entry-event-projection'
import {
  createTurnUsageMetaMessage,
} from '@/features/ai/runtime/thread-timeline/meta-projection'
import {
  mergeTurnUsage,
  parseAssistantUsage,
} from '@/features/ai/runtime/thread-timeline/content-utils'
import { translate } from '@/shared/i18n'

/**
 * 逐 turn 的投影上下文。Turn usage 不紧跟 Assistant：summary 先挂起，在相应
 * TURN_END 之后才投影为 TurnSummary；新 turn 开始时丢弃未关闭 turn 的残留。
 */
export interface EntryProjectionContext {
  pendingTurnSummary: MetaDialogueMessage | null
  lastSettings: SettingsSnapshot | null
  pendingTurnUsage?: TurnUsage | null
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
      context.pendingTurnUsage = null
      // 无论该回合是否有 usage，TURN_END 都必须产出一条携带真实 TURN_END Entry id 的
      // 结束 meta：回合 footer 的“从此处分支”只以这个 id 分叉（未消费 usage 的回合、
      // 以及作为 head 的最新回合同样可用）。
      messages.push(turnEndMeta(summary, entry, getString(payload.outcome)))
    } else {
      // 新 turn 开始：丢弃上一 turn 未关闭的残留 usage。
      context.pendingTurnSummary = null
      context.pendingTurnUsage = null
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
  if (entryType === 'NOTIFICATION') {
    messages.push(projectNotificationEntry(entry, payload))
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
    const displayContents = contents.flatMap<DialogueContent>((content) => {
      const value = contentText(content)
      if (value) {
        return [{ type: 'text', text: value }]
      }
      return toResourceAttachment(content).map((attachment) => ({
        type: 'resource',
        attachment,
      }))
    })
    if (displayContents.length > 0) {
      messages.push({
        id: entry.entryId,
        role: 'user',
        subjectEntryId: entry.entryId,
        text,
        contents: displayContents,
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
    // 价格是读取投影（entry.usageCost），不属于历史 payload；usage 的 token 事实仍来自
    // assistantMetadata。两参 API 由 usage-owner 提供。
    const usage = parseAssistantUsage(metadata, entry.usageCost)
    if (usage != null) {
      context.pendingTurnUsage =
        context.pendingTurnUsage == null ? usage : mergeTurnUsage(context.pendingTurnUsage, usage)
      context.pendingTurnSummary = createTurnUsageMetaMessage(
        entry.entryId,
        context.pendingTurnUsage,
        entry.createTime,
      )
    }
    return
  }
  if (role === 'TOOL') {
    const toolResults = contents.filter((candidate) => getString(candidate.type) === 'tool_result')
    const metadata = asRecord(payload.toolResultMetadata)
    toolResults.forEach((content, index) => {
      const key = toolCallKey(getString(content.toolCallId))
      const argumentsQueue = durableToolArguments.get(key) ?? []
      const argumentsJson = argumentsQueue.shift() ?? ''
      if (argumentsQueue.length === 0) {
        durableToolArguments.delete(key)
      }
      messages.push(projectToolResult(entry, content, argumentsJson, index, metadata))
    })
    if (toolResults.length === 0) {
      messages.push(projectEmptyMessageEntry(entry, role))
    }
    return
  }
  messages.push(projectUnsupportedMessageEntry(entry, role))
}

/**
 * 回合结束 meta 一定携带真实 TURN_END Entry id。
 *
 * `endEntryId` 字段由 usage-owner 在 `MetaDialogueMessage` 上新增（本切片不修改该共享
 * 类型文件，父合并时可直接删除此交叉类型）；meta-projection 的“可空 usage + endEntryId”
 * 工厂落地后，这里改用其工厂，字段语义不变。
 */
type TurnEndMeta = MetaDialogueMessage & { endEntryId: string }

function turnEndMeta(
  summary: MetaDialogueMessage | null,
  entry: HarnessSessionEntryDTO,
  outcome: string,
): TurnEndMeta {
  if (summary != null) {
    return { ...summary, endEntryId: entry.entryId }
  }
  return {
    id: `meta-turn-end-${entry.entryId}`,
    role: 'meta',
    kind: 'turn_usage',
    subjectEntryId: entry.entryId,
    text: outcome,
    createdAt: entry.createTime,
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
    contents: [],
    createdAt: entry.createTime,
    status: 'done',
    callIdentity: strictToolCallIdentity(entry.entryId, callIndex),
  }
}

function projectToolResult(
  entry: HarnessSessionEntryDTO,
  content: Record<string, unknown>,
  argumentsJson: string,
  callIndex: number,
  metadata: Record<string, unknown>,
): ToolDialogueMessage {
  const projected = projectToolResultContent(content, argumentsJson)
  const assistantEntryId = getString(metadata.assistantEntryId)
  const metadataCallIndex = integerValue(metadata.callIndex)
  return {
    id: `${entry.entryId}:tool-result:${toolCallIdentity(content, callIndex)}`,
    role: 'tool',
    phase: 'result',
    subjectEntryId: entry.entryId,
    toolCallId: getString(content.toolCallId),
    toolName: getString(content.toolName),
    rendererKey: getString(content.rendererKey),
    arguments: projected.arguments,
    contents: projected.contents,
    errorMessage: projected.errorMessage,
    createdAt: entry.createTime,
    status: projected.status,
    callIdentity: assistantEntryId && metadataCallIndex != null
      ? strictToolCallIdentity(assistantEntryId, metadataCallIndex)
      : undefined,
  }
}

/**
 * 从规范 ToolResult JSON 解析有序展示内容和错误。
 * durable Entry 与尚未物化的 invocation resultJson 共用这一事实，避免两套结果格式。
 */
export function projectToolResultContent(
  content: Record<string, unknown>,
  argumentsJson: string,
): Pick<ToolDialogueMessage, 'arguments' | 'contents' | 'errorMessage' | 'status'> {
  const error = content.error === true
  return {
    arguments: argumentsJson,
    contents: toToolContents(getRecordList(content.contents)),
    errorMessage: error ? translate('ai.runtime.entry.toolFailed') : undefined,
    status: error ? 'error' : 'done',
  }
}

function toolCallIdentity(content: Record<string, unknown>, callIndex: number): string {
  return `${getString(content.toolCallId) || 'unknown'}:${callIndex}`
}

function toolCallKey(toolCallId: string): string {
  return toolCallId ? `call:${toolCallId}` : ''
}
