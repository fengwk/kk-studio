import type { HarnessSessionEntryDTO } from '@/shared/api/contracts'
import { asRecord, getRecordList, getString, parsePayload } from '@/features/ai/payload-json'
import type { DialogueMessage, ToolDialogueMessage } from '@/features/ai/thread-timeline-types'
import { contentText, toArtifactAttachment } from '@/features/ai/thread-timeline/content-utils'
import { projectTurnUsageFromAssistantMetadata } from '@/features/ai/thread-timeline/meta-projection'

export function projectDurableEntry(
  entry: HarnessSessionEntryDTO,
  messages: DialogueMessage[],
  durableToolArguments: Map<string, string[]>,
) {
  const payload = parsePayload(entry.payloadJson)
  const entryType = entry.entryType
  if (entryType === 'ASSISTANT_ERROR') {
    const error = asRecord(payload.error)
    messages.push({
      id: entry.entryId,
      role: 'assistant',
      subjectEntryId: entry.entryId,
      text: getString(error.message) || '助手请求失败',
      createdAt: entry.createTime,
      status: 'error',
    })
    return
  }
  if (entryType !== 'MESSAGE' && entryType !== 'CUSTOM_MESSAGE') {
    return
  }

  const message = asRecord(payload.message)
  const role = getString(message.role)
  const contents = getRecordList(message.contents)
  if (role === 'USER' || role === 'SYSTEM') {
    const text = contents.map(contentText).filter(Boolean).join('\n')
    if (text) {
      messages.push({
        id: entry.entryId,
        role: role === 'USER' ? 'user' : 'system',
        subjectEntryId: entry.entryId,
        text,
        createdAt: entry.createTime,
        status: 'done',
      })
    }
    return
  }
  if (role === 'ASSISTANT') {
    for (const content of contents.filter((candidate) => getString(candidate.type) === 'tool_call')) {
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
    const metadata = asRecord(payload.assistantMetadata)
    const turnUsage = projectTurnUsageFromAssistantMetadata(
      entry.entryId,
      metadata,
      entry.createTime,
    )
    if (turnUsage) {
      messages.push(turnUsage)
    }
    return
  }
  if (role === 'TOOL') {
    for (const content of contents.filter((candidate) => getString(candidate.type) === 'tool_result')) {
      const key = toolCallKey(getString(content.toolCallId))
      const argumentsQueue = durableToolArguments.get(key) ?? []
      const argumentsJson = argumentsQueue.shift() ?? ''
      if (argumentsQueue.length === 0) {
        durableToolArguments.delete(key)
      }
      messages.push(projectToolResult(entry, content, argumentsJson))
    }
  }
}

function projectToolResult(
  entry: HarnessSessionEntryDTO,
  content: Record<string, unknown>,
  argumentsJson: string,
): ToolDialogueMessage {
  const contents = getRecordList(content.contents)
  const error = content.error === true
  return {
    id: `${entry.entryId}:${getString(content.toolCallId)}`,
    role: 'tool',
    subjectEntryId: entry.entryId,
    toolCallId: getString(content.toolCallId),
    toolName: getString(content.toolName),
    arguments: argumentsJson,
    text: contents.map(contentText).filter(Boolean).join('\n'),
    attachments: contents.flatMap(toArtifactAttachment),
    errorMessage: error ? '工具执行失败。' : undefined,
    createdAt: entry.createTime,
    status: error ? 'error' : 'done',
  }
}

function toolCallKey(toolCallId: string): string {
  return toolCallId ? `call:${toolCallId}` : ''
}
