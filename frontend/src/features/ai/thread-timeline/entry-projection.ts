import type { HarnessSessionEntryDTO } from '@/shared/api/contracts'
import { asRecord, getRecordList, getString, parsePayload } from '@/features/ai/thread-event-payload'
import type { DialogueMessage, ToolDialogueMessage } from '@/features/ai/thread-event-types'
import { contentText, toArtifactAttachment } from '@/features/ai/thread-timeline/content-utils'
import { projectTurnUsageFromAssistantMetadata } from '@/features/ai/thread-timeline/meta-projection'
import { toolCallKey } from '@/features/ai/thread-timeline/stream-projection'

export function projectDurableEntry(
  entry: HarnessSessionEntryDTO,
  messages: DialogueMessage[],
  durableToolArguments: Map<string, string[]>,
) {
  const payload = parsePayload(entry.payloadJson)
  if (entry.entryType === 'compaction') {
    const summary = getString(payload.summary)
    if (summary) {
      messages.push({
        id: entry.entryId,
        role: 'system',
        subjectEntryId: entry.entryId,
        text: summary,
        createdAt: entry.createTime,
        status: 'done',
      })
    }
    return
  }
  if (entry.entryType === 'agent_change') {
    const agentName = getString(payload.agentName) || getString(payload.agentDefinitionId) || 'agent'
    messages.push({
      id: entry.entryId,
      role: 'meta',
      kind: 'agent_change',
      subjectEntryId: entry.entryId,
      text: `Agent 已切换为 ${agentName}`,
      details: {
        agentDefinitionId: payload.agentDefinitionId,
        agentName,
      },
      createdAt: entry.createTime,
      status: 'done',
    })
    return
  }
  if (entry.entryType !== 'message' && entry.entryType !== 'custom_message') {
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
      // 持久元数据里的 usage/cost：刷新后也能显示本回合费用（不依赖 event 是否被 skip）
      const metadata = asRecord(payload.assistantMetadata)
      const turnUsage = projectTurnUsageFromAssistantMetadata(
        entry.entryId,
        metadata,
        entry.createTime,
      )
      if (turnUsage) {
        messages.push(turnUsage)
      }
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

export function findMaterializedAssistantEntryIds(entries: HarnessSessionEntryDTO[]): Set<string> {
  const ids = new Set<string>()
  for (const entry of entries) {
    if (entry.entryType !== 'message') {
      continue
    }
    const message = asRecord(parsePayload(entry.payloadJson).message)
    if (getString(message.role) === 'ASSISTANT') {
      ids.add(entry.entryId)
    }
  }
  return ids
}

export function findMaterializedToolKeys(entries: HarnessSessionEntryDTO[]): Set<string> {
  const keys = new Set<string>()
  for (const entry of entries) {
    if (entry.entryType !== 'message') {
      continue
    }
    const message = asRecord(parsePayload(entry.payloadJson).message)
    if (getString(message.role) !== 'TOOL') {
      continue
    }
    for (const content of getRecordList(message.contents)) {
      if (getString(content.type) !== 'tool_result') {
        continue
      }
      const toolCallId = getString(content.toolCallId)
      if (toolCallId) {
        keys.add(`call:${toolCallId}`)
      }
    }
  }
  return keys
}
