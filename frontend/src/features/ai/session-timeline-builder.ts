import type { HarnessSessionEntryDTO, RunEventDTO } from '@/shared/api/contracts'
import { asRecord, getRecordList, getString, parsePayload } from '@/features/ai/session-event-payload'
import { ThinkTagTextFilter } from '@/features/ai/session-event-think-filter'
import type {
  DialogueMessage,
  RuntimeContext,
  SessionTimeline,
  TextDialogueMessage,
  ToolAttachment,
  ToolAttachmentType,
  ToolDialogueMessage,
} from '@/features/ai/session-event-types'

interface StreamingAssistant {
  event: RunEventDTO
  message?: TextDialogueMessage
  textFilter: ThinkTagTextFilter
  pendingThinking: string
}

export function buildSessionTimeline(entries: HarnessSessionEntryDTO[], runEvents: RunEventDTO[]): SessionTimeline {
  const messages: DialogueMessage[] = []
  const runtimeContext: RuntimeContext = {}
  const activeTools = new Map<string, ToolDialogueMessage>()
  const activeAssistants = new Map<string, StreamingAssistant>()
  // How many assistant completion cycles already have a durable Entry for each run. Only that many
  // completed overlays are skipped; unmaterialized completions stay visible as done bubbles.
  const remainingMaterializedAssistants = countDurableAssistantsByRun(entries)
  const suppressedRuns = new Set<string>()

  for (const entry of entries) {
    projectDurableEntry(entry, messages, runtimeContext)
  }

  for (const event of runEvents) {
    const payload = parsePayload(event.payloadJson)

    switch (event.type) {
      case 'assistant_started': {
        if ((remainingMaterializedAssistants.get(event.runId) ?? 0) > 0) {
          suppressedRuns.add(event.runId)
          activeAssistants.delete(event.runId)
          break
        }
        suppressedRuns.delete(event.runId)
        activeAssistants.set(event.runId, newStreamingAssistant(event))
        break
      }
      case 'assistant_delta_batch': {
        if (suppressedRuns.has(event.runId)) {
          break
        }
        const state = activeAssistants.get(event.runId) ?? newStreamingAssistant(event)
        for (const delta of getRecordList(payload.deltas)) {
          const kind = getString(delta.kind)
          if (kind === 'text') {
            appendStreamingAssistantText(state, messages, getString(delta.text))
          } else if (kind === 'thinking') {
            appendStreamingAssistantThinking(state, getString(delta.text))
          }
        }
        activeAssistants.set(event.runId, state)
        break
      }
      case 'assistant_completed': {
        if ((remainingMaterializedAssistants.get(event.runId) ?? 0) > 0) {
          remainingMaterializedAssistants.set(
            event.runId,
            (remainingMaterializedAssistants.get(event.runId) ?? 1) - 1,
          )
          suppressedRuns.delete(event.runId)
          activeAssistants.delete(event.runId)
          break
        }
        // Entry refetch has not landed yet: keep the streamed assistant and mark it done.
        completeStreamingAssistant(activeAssistants.get(event.runId), messages, event, '')
        activeAssistants.delete(event.runId)
        suppressedRuns.delete(event.runId)
        break
      }
      case 'assistant_failed':
        if (suppressedRuns.has(event.runId) && (remainingMaterializedAssistants.get(event.runId) ?? 0) > 0) {
          // A failed attempt that already has a durable assistant should not re-project.
          remainingMaterializedAssistants.set(
            event.runId,
            (remainingMaterializedAssistants.get(event.runId) ?? 1) - 1,
          )
          suppressedRuns.delete(event.runId)
          activeAssistants.delete(event.runId)
          break
        }
        completeStreamingAssistant(activeAssistants.get(event.runId), messages, event, getString(payload.message))
        activeAssistants.delete(event.runId)
        suppressedRuns.delete(event.runId)
        break
      case 'tool_prepared':
        prepareStreamingTool(activeTools, messages, event, payload)
        break
      case 'tool_started': {
        const tool = activeTools.get(getString(payload.toolCallId))
        if (tool) {
          tool.status = 'streaming'
        }
        break
      }
      case 'tool_delta_batch':
        appendStreamingToolResults(activeTools, payload)
        break
      case 'tool_completed': {
        const tool = activeTools.get(getString(payload.toolCallId))
        if (tool) {
          tool.status = payload.error === true ? 'error' : 'done'
        }
        break
      }
    }
  }

  return { messages, runtimeContext }
}

function countDurableAssistantsByRun(entries: HarnessSessionEntryDTO[]): Map<string, number> {
  const counts = new Map<string, number>()
  for (const entry of entries) {
    if (entry.entryType !== 'message' || !entry.runId) {
      continue
    }
    const payload = parsePayload(entry.payloadJson)
    const message = asRecord(payload.message)
    if (getString(message.role) !== 'ASSISTANT') {
      continue
    }
    counts.set(entry.runId, (counts.get(entry.runId) ?? 0) + 1)
  }
  return counts
}

function projectDurableEntry(entry: HarnessSessionEntryDTO, messages: DialogueMessage[], runtimeContext: RuntimeContext) {
  const payload = parsePayload(entry.payloadJson)
  if (entry.entryType === 'agent_snapshot') {
    const snapshot = asRecord(payload.snapshot)
    runtimeContext.model = getString(snapshot.modelId) || runtimeContext.model
    runtimeContext.variant = getString(snapshot.variant) || runtimeContext.variant
    return
  }
  if (entry.entryType === 'compaction') {
    const summary = getString(payload.summary)
    if (summary) {
      messages.push({
        id: entry.sessionEntryId,
        role: 'system',
        runId: entry.runId,
        text: summary,
        createdAt: entry.createTime,
        status: 'done',
      })
    }
    return
  }
  if (entry.entryType !== 'message') {
    return
  }

  const message = asRecord(payload.message)
  const role = getString(message.role)
  const contents = getRecordList(message.contents)
  if (role === 'USER' || role === 'SYSTEM') {
    const text = contents.map(contentText).filter(Boolean).join('\n')
    if (text) {
      messages.push({
        id: entry.sessionEntryId,
        role: role === 'USER' ? 'user' : 'system',
        runId: entry.runId,
        text,
        createdAt: entry.createTime,
        status: 'done',
      })
    }
    return
  }
  if (role === 'ASSISTANT') {
    const text = contents.filter((content) => getString(content.type) === 'text').map(contentText).join('')
    const thinking = contents.filter((content) => getString(content.type) === 'thinking').map(contentText).join('')
    if (text || thinking) {
      messages.push({
        id: entry.sessionEntryId,
        role: 'assistant',
        runId: entry.runId,
        text,
        thinking: thinking || undefined,
        createdAt: entry.createTime,
        status: 'done',
      })
    }
    return
  }
  if (role === 'TOOL') {
    for (const content of contents.filter((candidate) => getString(candidate.type) === 'tool_result')) {
      messages.push(projectToolResult(entry, content))
    }
  }
}

function projectToolResult(entry: HarnessSessionEntryDTO, content: Record<string, unknown>): ToolDialogueMessage {
  const contents = getRecordList(content.contents)
  const error = content.error === true
  return {
    id: `${entry.sessionEntryId}:${getString(content.toolCallId)}`,
    role: 'tool',
    runId: entry.runId,
    toolCallId: getString(content.toolCallId),
    toolName: getString(content.toolName),
    arguments: '',
    text: contents.map(contentText).filter(Boolean).join('\n'),
    attachments: contents.flatMap(toArtifactAttachment),
    errorMessage: error ? '工具执行失败。' : undefined,
    createdAt: entry.createTime,
    status: error ? 'error' : 'done',
  }
}

function newStreamingAssistant(event: RunEventDTO): StreamingAssistant {
  return {
    event,
    textFilter: new ThinkTagTextFilter(),
    pendingThinking: '',
  }
}

function appendStreamingAssistantText(state: StreamingAssistant, messages: DialogueMessage[], delta: string) {
  const visible = state.textFilter.append(delta)
  if (!visible) {
    return
  }
  const message = ensureStreamingAssistantMessage(state, messages)
  message.text += visible
}

function appendStreamingAssistantThinking(state: StreamingAssistant, delta: string) {
  if (!delta) {
    return
  }
  if (state.message) {
    state.message.thinking = (state.message.thinking ?? '') + delta
  } else {
    state.pendingThinking += delta
  }
}

function completeStreamingAssistant(
  state: StreamingAssistant | undefined,
  messages: DialogueMessage[],
  event: RunEventDTO,
  failureMessage: string,
) {
  if (!state) {
    if (failureMessage) {
      messages.push({
        id: event.eventId,
        role: 'assistant',
        runId: event.runId,
        text: failureMessage,
        createdAt: event.createTime,
        status: 'error',
      })
    }
    return
  }
  const tail = state.textFilter.finish()
  if (tail) {
    const message = ensureStreamingAssistantMessage(state, messages)
    message.text += tail
  }
  if (state.message) {
    state.message.status = failureMessage ? 'error' : 'done'
    if (failureMessage) {
      state.message.text = state.message.text ? `${state.message.text}\n${failureMessage}` : failureMessage
    }
  }
}

function ensureStreamingAssistantMessage(state: StreamingAssistant, messages: DialogueMessage[]): TextDialogueMessage {
  if (state.message) {
    return state.message
  }
  const message: TextDialogueMessage = {
    id: state.event.eventId,
    role: 'assistant',
    runId: state.event.runId,
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

function prepareStreamingTool(
  activeTools: Map<string, ToolDialogueMessage>,
  messages: DialogueMessage[],
  event: RunEventDTO,
  payload: Record<string, unknown>,
) {
  const toolCallId = getString(payload.toolCallId)
  if (!toolCallId || activeTools.has(toolCallId)) {
    return
  }
  const tool: ToolDialogueMessage = {
    id: event.eventId,
    role: 'tool',
    runId: event.runId,
    toolCallId,
    toolName: getString(payload.toolName) || 'Tool',
    arguments: getString(payload.arguments),
    text: '',
    attachments: [],
    createdAt: event.createTime,
    status: 'streaming',
  }
  activeTools.set(toolCallId, tool)
  messages.push(tool)
}

function appendStreamingToolResults(activeTools: Map<string, ToolDialogueMessage>, payload: Record<string, unknown>) {
  for (const result of getRecordList(payload.partialResults)) {
    const tool = activeTools.get(getString(result.toolCallId))
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

function contentText(content: Record<string, unknown>): string {
  const type = getString(content.type)
  if (type === 'text' || type === 'thinking') {
    return getString(content.text)
  }
  if (type === 'json') {
    return getString(content.json)
  }
  return ''
}

function toArtifactAttachment(content: Record<string, unknown>): ToolAttachment[] {
  if (getString(content.type) !== 'artifact') {
    return []
  }
  const mediaType = getString(content.mediaType)
  const type = artifactType(mediaType)
  const artifactId = getString(content.artifactId)
  if (!artifactId) {
    return []
  }
  return [{ type, name: artifactId, mime: mediaType, data: `/api/artifacts/${encodeURIComponent(artifactId)}` }]
}

function artifactType(mediaType: string): ToolAttachmentType {
  if (mediaType.startsWith('image/')) {
    return 'image'
  }
  if (mediaType.startsWith('audio/')) {
    return 'audio'
  }
  if (mediaType.startsWith('video/')) {
    return 'video'
  }
  return 'file'
}
