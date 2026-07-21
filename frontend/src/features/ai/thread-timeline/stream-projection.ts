import type { ThreadEventDTO } from '@/shared/api/contracts'
import { getRecordList, getString } from '@/features/ai/thread-event-payload'
import { ThinkTagTextFilter } from '@/features/ai/thread-event-think-filter'
import type { DialogueMessage, TextDialogueMessage, ToolDialogueMessage } from '@/features/ai/thread-event-types'
import { contentText, toArtifactAttachment } from '@/features/ai/thread-timeline/content-utils'

export interface StreamingAssistant {
  event: ThreadEventDTO
  key: string
  message?: TextDialogueMessage
  textFilter: ThinkTagTextFilter
  pendingThinking: string
}

export function newStreamingAssistant(event: ThreadEventDTO, key: string): StreamingAssistant {
  return {
    event,
    key,
    textFilter: new ThinkTagTextFilter(),
    pendingThinking: '',
  }
}

export function assistantKey(event: ThreadEventDTO): string {
  return event.subjectEntryId ? `subject:${event.subjectEntryId}` : `event:${event.eventId}`
}

export function appendStreamingAssistantText(
  state: StreamingAssistant,
  messages: DialogueMessage[],
  delta: string,
) {
  const visible = state.textFilter.append(delta)
  if (!visible) {
    return
  }
  const message = ensureStreamingAssistantMessage(state, messages)
  message.text += visible
}

export function appendStreamingAssistantThinking(
  state: StreamingAssistant,
  messages: DialogueMessage[],
  delta: string,
) {
  if (!delta) {
    return
  }
  const message = ensureStreamingAssistantMessage(state, messages)
  message.thinking = (message.thinking ?? '') + delta
}

export function completeStreamingAssistant(
  state: StreamingAssistant | undefined,
  messages: DialogueMessage[],
) {
  if (!state) {
    return
  }
  const tail = state.textFilter.finish()
  if (tail || state.pendingThinking) {
    const message = ensureStreamingAssistantMessage(state, messages)
    if (tail) {
      message.text += tail
    }
  }
  if (state.message) {
    state.message.status = 'done'
  }
}

/** 自动重试会重新发起同一 response debt，不把中断的临时流片段留在可读 transcript。 */
export function discardStreamingAssistant(
  state: StreamingAssistant | undefined,
  messages: DialogueMessage[],
) {
  if (!state?.message) {
    return
  }
  const index = messages.indexOf(state.message)
  if (index >= 0) {
    messages.splice(index, 1)
  }
}

/**
 * 将尚未完成 Entries 刷新的失败事件暂时投影为独立错误消息。
 *
 * <p>后端使用 planned assistant entry id 作为 durable assistant_error Entry id；Entry 到达后由
 * buildThreadTimeline 的 materialized-entry 去重替换此临时消息。错误绝不能追加到同次 partial
 * assistant 文本，否则下一次成功响应会继承 error/red 状态。
 */
export function projectStreamingAssistantFailure(
  messages: DialogueMessage[],
  event: ThreadEventDTO,
  failureMessage: string,
) {
  const id = event.subjectEntryId || `assistant-error:${event.eventId}`
  if (messages.some((message) => message.id === id)) {
    return
  }
  messages.push({
    id,
    role: 'assistant',
    subjectEntryId: event.subjectEntryId,
    text: failureMessage || '助手请求失败',
    createdAt: event.createTime,
    status: 'error',
  })
}

export function ensureStreamingAssistantMessage(
  state: StreamingAssistant,
  messages: DialogueMessage[],
): TextDialogueMessage {
  if (state.message) {
    return state.message
  }
  const message: TextDialogueMessage = {
    id: state.event.eventId,
    role: 'assistant',
    subjectEntryId: state.event.subjectEntryId,
    text: '',
    thinking: state.pendingThinking || undefined,
    createdAt: state.event.createTime,
    status: 'streaming',
  }
  state.pendingThinking = ''
  state.message = message
  messages.push(message)
  return message
}

export function toolCallKey(toolCallId: string): string {
  return toolCallId ? `call:${toolCallId}` : ''
}

export function toolEventKey(event: ThreadEventDTO, payload: Record<string, unknown>): string {
  const invocationId = getString(payload.invocationId)
  if (invocationId) {
    return `invocation:${invocationId}`
  }
  const toolCallId = getString(payload.toolCallId)
  if (toolCallId) {
    return toolCallKey(toolCallId)
  }
  if (event.subjectEntryId) {
    return `subject:${event.subjectEntryId}:tool`
  }
  return ''
}

export function toolCallMaterialKey(payload: Record<string, unknown>, event: ThreadEventDTO): string {
  const toolCallId = getString(payload.toolCallId)
  if (toolCallId) {
    return `call:${toolCallId}`
  }
  return event.subjectEntryId ? `subject:${event.subjectEntryId}` : ''
}

export function prepareStreamingTool(
  activeTools: Map<string, ToolDialogueMessage>,
  messages: DialogueMessage[],
  event: ThreadEventDTO,
  payload: Record<string, unknown>,
) {
  const toolCallId = getString(payload.toolCallId)
  const key = toolEventKey(event, payload)
  if (!toolCallId || !key || activeTools.has(key)) {
    return
  }
  const tool: ToolDialogueMessage = {
    id: event.eventId,
    role: 'tool',
    subjectEntryId: event.subjectEntryId,
    toolCallId,
    toolName: getString(payload.toolName) || 'Tool',
    arguments: getString(payload.arguments) || getString(payload.argumentsJson),
    text: '',
    attachments: [],
    createdAt: event.createTime,
    status: 'streaming',
  }
  activeTools.set(key, tool)
  messages.push(tool)
}

export function appendStreamingToolResults(
  activeTools: Map<string, ToolDialogueMessage>,
  event: ThreadEventDTO,
  payload: Record<string, unknown>,
) {
  const invocationKey = toolEventKey(event, payload)
  for (const result of getRecordList(payload.partialResults)) {
    const key = invocationKey || toolCallKey(getString(result.toolCallId))
    const tool = activeTools.get(key)
    if (!tool) {
      continue
    }
    const contents = getRecordList(result.contents)
    const text = contents.map(contentText).filter(Boolean).join('\n')
    tool.text = tool.text && text ? `${tool.text}\n${text}` : tool.text || text
    tool.attachments.push(...contents.flatMap(toArtifactAttachment))
    if (result.error === true) {
      tool.errorMessage = '工具执行失败。'
    }
  }
}
