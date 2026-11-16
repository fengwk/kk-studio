import {
  asRecord,
  getRecordList,
  integerValue,
} from '@/features/thread/runtime/thread-timeline/payload-json'
import { getString } from '@/shared/lib/value'
import type {
  DialogueContent,
  DialogueMessage,
  ToolDialogueMessage,
} from '@/features/thread/runtime/thread-timeline/thread-timeline-types'
import { toolCallIdentity as strictToolCallIdentity } from '@/features/thread/runtime/thread-timeline/thread-timeline-types'
import {
  contentText,
  toResourceAttachment,
} from '@/features/thread/runtime/thread-timeline/content-utils'
import {
  projectEmptyMessageEntry,
  projectUnsupportedMessageEntry,
} from '@/features/thread/runtime/thread-timeline/entry-event-projection'
import { createTurnUsageMetaMessage } from '@/features/thread/runtime/thread-timeline/meta-projection'
import {
  accumulateTurnUsage,
  parseAssistantUsage,
} from '@/features/thread/runtime/thread-timeline/turn-usage-format'
import {
  decodeDurableToolResultContent,
  projectToolResultContent,
} from '@/features/thread/runtime/thread-timeline/tool-result-projection'
import type { EntryProjectionContext } from '@/features/thread/runtime/thread-timeline/entry-projection-context'
import type { HarnessSessionEntryDTO } from '@/shared/api/generated/contracts'

/** durable tool_call 已记录的 arguments，按调用身份排队等待同名 tool_result 配对。 */
type DurableToolArguments = Map<string, string[]>

/** MESSAGE / CUSTOM_MESSAGE：按真实角色投影为用户、助手或工具消息。 */
export function projectMessageEntry(
  entry: HarnessSessionEntryDTO,
  payload: Record<string, unknown>,
  entryType: string,
  messages: DialogueMessage[],
  durableToolArguments: DurableToolArguments,
  context: EntryProjectionContext,
): void {
  const message = asRecord(payload.message)
  const role = getString(message.role)
  const contents = getRecordList(message.contents)
  if (entryType === 'CUSTOM_MESSAGE' || role === 'USER') {
    projectUserMessage(entry, contents, messages, role)
    return
  }
  if (role === 'ASSISTANT') {
    projectAssistantMessage(entry, payload, contents, messages, durableToolArguments, context)
    return
  }
  if (role === 'TOOL') {
    projectToolMessage(entry, payload, contents, messages, durableToolArguments, role)
    return
  }
  messages.push(projectUnsupportedMessageEntry(entry, role))
}

/** 用户（或编排产出的 CUSTOM_MESSAGE）正文与资源；无可展示内容时记为空消息。 */
function projectUserMessage(
  entry: HarnessSessionEntryDTO,
  contents: Array<Record<string, unknown>>,
  messages: DialogueMessage[],
  role: string,
): void {
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
      createdAt: entry.createdAt,
      status: 'done',
    })
    return
  }
  messages.push(projectEmptyMessageEntry(entry, role || 'USER'))
}

/**
 * 助手正文与思考、tool_call 行，并把参数排入 tool_result 的配对队列；
 * 最后把该次调用的 usage 挂起到回合 summary。
 */
function projectAssistantMessage(
  entry: HarnessSessionEntryDTO,
  payload: Record<string, unknown>,
  contents: Array<Record<string, unknown>>,
  messages: DialogueMessage[],
  durableToolArguments: DurableToolArguments,
  context: EntryProjectionContext,
): void {
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
      createdAt: entry.createdAt,
      status: 'done',
    })
  }
  toolCalls.forEach((content, index) => {
    messages.push(projectToolCall(entry, content, index))
  })
  if (!text && !thinking && toolCalls.length === 0) {
    messages.push(projectEmptyMessageEntry(entry, 'ASSISTANT'))
  }
  // 价格是读取投影（entry.usageCost），不属于历史 payload；usage 的 token 事实仍来自
  // assistantMetadata。保留原始形态，空对象和非法值不能先压成“无模型调用”。
  const usage = parseAssistantUsage(payload.assistantMetadata, entry.usageCost)
  if (usage != null) {
    context.pendingTurnUsage = accumulateTurnUsage(context.pendingTurnUsage ?? null, usage)
    context.pendingTurnSummary = createTurnUsageMetaMessage(
      entry.entryId,
      context.pendingTurnUsage,
      entry.createdAt,
    )
  }
}

/** 工具结果：按顺序消费配对队列里的 arguments；没有结果时记为空消息。 */
function projectToolMessage(
  entry: HarnessSessionEntryDTO,
  payload: Record<string, unknown>,
  contents: Array<Record<string, unknown>>,
  messages: DialogueMessage[],
  durableToolArguments: DurableToolArguments,
  role: string,
): void {
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
    createdAt: entry.createdAt,
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
  const canonicalContent = decodeDurableToolResultContent(content)
  const projected = projectToolResultContent(canonicalContent, argumentsJson)
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
    details: projected.details,
    errorMessage: projected.errorMessage,
    createdAt: entry.createdAt,
    status: projected.status,
    editReview: projected.editReview,
    // Invocation 行物化后会删除；终态身份只取 durable toolResultMetadata。
    invocationStatus: ['SUCCEEDED', 'FAILED', 'CANCELLED', 'UNKNOWN'].includes(getString(metadata.status))
      ? getString(metadata.status) : undefined,
    callIdentity: assistantEntryId && metadataCallIndex != null
      ? strictToolCallIdentity(assistantEntryId, metadataCallIndex)
      : undefined,
  }
}

function toolCallIdentity(content: Record<string, unknown>, callIndex: number): string {
  return `${getString(content.toolCallId) || 'unknown'}:${callIndex}`
}

function toolCallKey(toolCallId: string): string {
  return toolCallId ? `call:${toolCallId}` : ''
}

