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
  const durableToolArguments = new Map<string, string[]>()
  // Durable Assistant Entries cover the Run Event prefix through the matching completed cycle.
  // Events after that cutoff remain the live overlay, including a completion whose Entry refetch
  // has not landed yet.
  const materializedCutoffs = findMaterializedAssistantCutoffs(entries, runEvents)
  const materializedToolCycles = findMaterializedToolCycles(entries, runEvents)
  const suppressedTools = new Set<string>()

  for (const entry of entries) {
    projectDurableEntry(entry, messages, runtimeContext, durableToolArguments)
  }

  for (const event of runEvents) {
    if (event.sequence <= (materializedCutoffs.get(event.runId) ?? 0)) {
      continue
    }
    const payload = parsePayload(event.payloadJson)

    switch (event.type) {
      case 'assistant_started':
        activeAssistants.set(event.runId, newStreamingAssistant(event))
        break
      case 'assistant_delta_batch': {
        const state = activeAssistants.get(event.runId) ?? newStreamingAssistant(event)
        for (const delta of getRecordList(payload.deltas)) {
          const kind = getString(delta.kind)
          if (kind === 'text') {
            appendStreamingAssistantText(state, messages, getString(delta.text))
          } else if (kind === 'thinking') {
            appendStreamingAssistantThinking(state, messages, getString(delta.text))
          }
        }
        activeAssistants.set(event.runId, state)
        break
      }
      case 'assistant_completed': {
        // Entry refetch has not landed yet: keep the streamed assistant and mark it done.
        completeStreamingAssistant(activeAssistants.get(event.runId), messages, event, '')
        activeAssistants.delete(event.runId)
        break
      }
      case 'assistant_failed':
        completeStreamingAssistant(activeAssistants.get(event.runId), messages, event, getString(payload.message))
        activeAssistants.delete(event.runId)
        break
      case 'tool_prepared': {
        const key = toolEventKey(event, payload)
        if (materializedToolCycles.has(key)) {
          suppressedTools.add(key)
          break
        }
        prepareStreamingTool(activeTools, messages, event, payload)
        break
      }
      case 'tool_started': {
        const key = toolEventKey(event, payload)
        if (suppressedTools.has(key)) {
          break
        }
        const tool = activeTools.get(key)
        if (tool) {
          tool.status = 'streaming'
        }
        break
      }
      case 'tool_delta_batch': {
        const key = toolEventKey(event, payload)
        if (key && suppressedTools.has(key)) {
          break
        }
        appendStreamingToolResults(activeTools, event, payload)
        break
      }
      case 'tool_completed': {
        const key = toolEventKey(event, payload)
        if (suppressedTools.delete(key)) {
          break
        }
        const tool = activeTools.get(key)
        if (tool) {
          tool.status = payload.error === true ? 'error' : 'done'
        }
        break
      }
    }
  }

  return { messages, runtimeContext }
}

function findMaterializedAssistantCutoffs(
  entries: HarnessSessionEntryDTO[],
  runEvents: RunEventDTO[],
): Map<string, number> {
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
  const cutoffs = new Map<string, number>()
  const maximumSequences = new Map<string, number>()
  for (const event of runEvents) {
    maximumSequences.set(event.runId, Math.max(maximumSequences.get(event.runId) ?? 0, event.sequence))
    const remaining = counts.get(event.runId) ?? 0
    if (event.type !== 'assistant_completed' || remaining <= 0) {
      continue
    }
    cutoffs.set(event.runId, event.sequence)
    counts.set(event.runId, remaining - 1)
  }
  // Parallel initial queries can observe the Entry commit before their Run Event snapshot. In that
  // case the durable baseline is authoritative, so suppress the stale event prefix already loaded.
  for (const [runId, remaining] of counts) {
    if (remaining > 0) {
      cutoffs.set(runId, maximumSequences.get(runId) ?? 0)
    }
  }
  return cutoffs
}

function findMaterializedToolCycles(entries: HarnessSessionEntryDTO[], runEvents: RunEventDTO[]): Set<string> {
  const counts = new Map<string, number>()
  for (const entry of entries) {
    if (entry.entryType !== 'message' || !entry.runId) {
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
      const key = toolCallKey(entry.runId, getString(content.toolCallId))
      if (key) {
        counts.set(key, (counts.get(key) ?? 0) + 1)
      }
    }
  }
  const materialized = new Set<string>()
  for (const event of runEvents) {
    if (event.type !== 'tool_prepared') {
      continue
    }
    const payload = parsePayload(event.payloadJson)
    const callKey = toolCallKey(event.runId, getString(payload.toolCallId))
    const remaining = counts.get(callKey) ?? 0
    if (!callKey || remaining <= 0) {
      continue
    }
    const cycleKey = toolEventKey(event, payload)
    if (cycleKey) {
      materialized.add(cycleKey)
      counts.set(callKey, remaining - 1)
    }
  }
  return materialized
}

function projectDurableEntry(
  entry: HarnessSessionEntryDTO,
  messages: DialogueMessage[],
  runtimeContext: RuntimeContext,
  durableToolArguments: Map<string, string[]>,
) {
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
    for (const content of contents.filter((candidate) => getString(candidate.type) === 'tool_call')) {
      const key = toolCallKey(entry.runId ?? '', getString(content.toolCallId))
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
      const key = toolCallKey(entry.runId ?? '', getString(content.toolCallId))
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
    id: `${entry.sessionEntryId}:${getString(content.toolCallId)}`,
    role: 'tool',
    runId: entry.runId,
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

function appendStreamingAssistantThinking(
  state: StreamingAssistant,
  messages: DialogueMessage[],
  delta: string,
) {
  if (!delta) {
    return
  }
  // Thinking-only streams must materialize an assistant row immediately (pi shows thinking blocks live).
  const message = ensureStreamingAssistantMessage(state, messages)
  message.thinking = (message.thinking ?? '') + delta
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
  if (tail || state.pendingThinking || failureMessage) {
    const message = ensureStreamingAssistantMessage(state, messages)
    if (tail) {
      message.text += tail
    }
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
  const key = toolEventKey(event, payload)
  if (!toolCallId || !key || activeTools.has(key)) {
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
  activeTools.set(key, tool)
  messages.push(tool)
}

function appendStreamingToolResults(
  activeTools: Map<string, ToolDialogueMessage>,
  event: RunEventDTO,
  payload: Record<string, unknown>,
) {
  const invocationKey = toolEventKey(event, payload)
  for (const result of getRecordList(payload.partialResults)) {
    const key = invocationKey || toolCallKey(event.runId, getString(result.toolCallId))
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

function toolEventKey(event: RunEventDTO, payload: Record<string, unknown>): string {
  const invocationId = getString(payload.invocationId)
  return invocationId
    ? `${event.runId}:invocation:${invocationId}`
    : toolCallKey(event.runId, getString(payload.toolCallId))
}

function toolCallKey(runId: string, toolCallId: string): string {
  return toolCallId ? `${runId}:call:${toolCallId}` : ''
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
