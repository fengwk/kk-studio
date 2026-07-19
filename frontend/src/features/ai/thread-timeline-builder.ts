import type { HarnessSessionEntryDTO, HarnessThreadInputDTO, ThreadEventDTO } from '@/shared/api/contracts'
import { normalizeThreadEvent } from '@/features/ai/harness-thread-event-stream'
import { asRecord, getRecordList, getString, parsePayload } from '@/features/ai/thread-event-payload'
import { ThinkTagTextFilter } from '@/features/ai/thread-event-think-filter'
import type {
  DialogueMessage,
  QueuedThreadMessage,
  RuntimeContext,
  TextDialogueMessage,
  ThreadTimeline,
  ToolAttachment,
  ToolAttachmentType,
  ToolDialogueMessage,
} from '@/features/ai/thread-event-types'

interface StreamingAssistant {
  event: ThreadEventDTO
  key: string
  message?: TextDialogueMessage
  textFilter: ThinkTagTextFilter
  pendingThinking: string
}

interface AppliedUserProjection {
  input: HarnessThreadInputDTO
  message: TextDialogueMessage
}

/**
 * Thread transcript projection:
 * committed path Entries + applied-input journal overlays + live ThreadEvents.
 * QUEUED USER/CUSTOM_MESSAGE inputs are projected separately for the decoration zone.
 * Correlate stream events by subjectEntryId; suppress once that assistant Entry materializes.
 */
export function buildThreadTimeline(
  entries: HarnessSessionEntryDTO[],
  inputs: HarnessThreadInputDTO[],
  threadEvents: ThreadEventDTO[],
): ThreadTimeline {
  const messages: DialogueMessage[] = []
  const queuedMessages: QueuedThreadMessage[] = []
  const runtimeContext: RuntimeContext = {}
  const activeTools = new Map<string, ToolDialogueMessage>()
  const activeAssistants = new Map<string, StreamingAssistant>()
  const durableToolArguments = new Map<string, string[]>()
  const materializedAssistantIds = findMaterializedAssistantEntryIds(entries)
  const materializedToolKeys = findMaterializedToolKeys(entries)
  const suppressedTools = new Set<string>()
  const entryIds = new Set(entries.map((entry) => entry.entryId))
  const appliedEntryByInputId = findAppliedEntryIdsFromEvents(threadEvents)
  const appliedUsers: AppliedUserProjection[] = []
  const appliedUserByInputId = new Map<string, AppliedUserProjection>()
  const emittedAppliedInputs = new Set<string>()
  let hasPendingInputs = false

  // The backend returns the root-to-head Entry path. Its parent-chain order is authoritative;
  // wall-clock createTime may regress and must never reorder durable dialogue.
  for (const entry of entries) {
    projectDurableEntry(entry, messages, runtimeContext, durableToolArguments)
  }

  // The backend returns mailbox inputs by sequence. QUEUED messages stay outside the transcript;
  // APPLIED messages may temporarily bridge a stale Entry query at their durable journal position.
  for (const input of inputs) {
    const inputType = input.inputType.toUpperCase()
    const inputStatus = input.status?.toUpperCase()
    // Missing status only occurs in stale browser cache fixtures; durable API responses are typed.
    const visible = inputStatus === 'QUEUED' || inputStatus === 'APPLIED' || !inputStatus
    if (!visible || (inputType !== 'USER_MESSAGE' && inputType !== 'CUSTOM_MESSAGE')) {
      continue
    }
    const appliedEntryId = input.appliedEntryId || appliedEntryByInputId.get(input.inputId) || null
    if (appliedEntryId && entryIds.has(appliedEntryId)) {
      continue
    }
    const queuedMessage = extractQueuedMessage(input.payloadJson, inputType)
    if (!queuedMessage) {
      continue
    }
    if (!appliedEntryId) {
      if (inputStatus === 'QUEUED' || !inputStatus) {
        queuedMessages.push({
          inputId: input.inputId,
          role: queuedMessage.role,
          text: queuedMessage.text,
          sequence: input.sequence,
        })
        hasPendingInputs = true
      }
      continue
    }
    const projection: AppliedUserProjection = {
      input,
      message: {
        id: `input:${input.inputId}`,
        role: queuedMessage.role,
        subjectEntryId: null,
        text: queuedMessage.text,
        createdAt: input.createTime,
        status: 'done',
      },
    }
    appliedUsers.push(projection)
    appliedUserByInputId.set(input.inputId, projection)
  }

  // An applied input can outlive the retained event window while the Entry query is stale.
  // In that case it still precedes all currently visible live journal projections.
  for (const projection of appliedUsers) {
    if (projection.input.appliedEntryId && !appliedEntryByInputId.has(projection.input.inputId)) {
      messages.push(projection.message)
      emittedAppliedInputs.add(projection.input.inputId)
    }
  }

  // Thread events arrive in backend journal order (event_id ASC). Preserve that order verbatim.
  for (const rawEvent of threadEvents) {
    const event = normalizeThreadEvent(rawEvent)
    if (!event) {
      continue
    }
    const subjectKey = event.subjectEntryId ?? ''
    if (subjectKey && materializedAssistantIds.has(subjectKey) && isAssistantEvent(event.eventType)) {
      continue
    }
    const payload = parsePayload(event.payloadJson)

    switch (event.eventType) {
      case 'input_applied': {
        const inputId = getString(payload.inputId)
        const projection = appliedUserByInputId.get(inputId)
        if (projection && !emittedAppliedInputs.has(inputId)) {
          messages.push(projection.message)
          emittedAppliedInputs.add(inputId)
        }
        break
      }
      case 'assistant_started': {
        const key = assistantKey(event)
        activeAssistants.set(key, newStreamingAssistant(event, key))
        break
      }
      case 'assistant_delta_batch': {
        const key = assistantKey(event)
        const state = activeAssistants.get(key) ?? newStreamingAssistant(event, key)
        for (const delta of getRecordList(payload.deltas)) {
          const kind = getString(delta.kind)
          if (kind === 'text') {
            appendStreamingAssistantText(state, messages, getString(delta.text))
          } else if (kind === 'thinking') {
            appendStreamingAssistantThinking(state, messages, getString(delta.text))
          }
        }
        activeAssistants.set(key, state)
        break
      }
      case 'assistant_completed': {
        completeStreamingAssistant(activeAssistants.get(assistantKey(event)), messages, event, '')
        activeAssistants.delete(assistantKey(event))
        break
      }
      case 'assistant_failed':
        completeStreamingAssistant(
          activeAssistants.get(assistantKey(event)),
          messages,
          event,
          getString(payload.message),
        )
        activeAssistants.delete(assistantKey(event))
        break
      case 'tool_prepared': {
        const key = toolEventKey(event, payload)
        if (key && materializedToolKeys.has(toolCallMaterialKey(payload, event))) {
          suppressedTools.add(key)
          break
        }
        prepareStreamingTool(activeTools, messages, event, payload)
        break
      }
      case 'tool_started': {
        const key = toolEventKey(event, payload)
        if (key && suppressedTools.has(key)) {
          break
        }
        const tool = key ? activeTools.get(key) : undefined
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
        if (key && suppressedTools.delete(key)) {
          break
        }
        const tool = key ? activeTools.get(key) : undefined
        if (tool) {
          tool.status = payload.error === true ? 'error' : 'done'
        }
        break
      }
    }
  }

  // Live projection only for open stream work: open assistant or streaming tool.
  const hasLiveProjection =
    activeAssistants.size > 0
    || [...activeTools.values()].some((tool) => tool.status === 'streaming')

  return {
    messages,
    queuedMessages,
    runtimeContext,
    hasPendingInputs,
    hasLiveProjection,
  }
}

function findAppliedEntryIdsFromEvents(threadEvents: ThreadEventDTO[]): Map<string, string> {
  const applied = new Map<string, string>()
  for (const rawEvent of threadEvents) {
    const event = normalizeThreadEvent(rawEvent)
    if (!event || event.eventType !== 'input_applied' || !event.subjectEntryId) {
      continue
    }
    const payload = parsePayload(event.payloadJson)
    const inputId = getString(payload.inputId)
    if (inputId) {
      applied.set(inputId, event.subjectEntryId)
    }
  }
  return applied
}

function extractQueuedMessage(
  payloadJson: string,
  inputType: string,
): { role: 'user' | 'system'; text: string } | null {
  const payload = parsePayload(payloadJson)
  const message = asRecord(payload.message)
  const contents = getRecordList(message.contents)
  const text = contents.map(contentText).filter(Boolean).join('\n')
  if (!text) {
    return null
  }
  const role = getString(message.role)
  if (inputType === 'CUSTOM_MESSAGE' && role === 'SYSTEM') {
    return { role: 'system', text }
  }
  return { role: 'user', text }
}

function findMaterializedAssistantEntryIds(entries: HarnessSessionEntryDTO[]): Set<string> {
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

function findMaterializedToolKeys(entries: HarnessSessionEntryDTO[]): Set<string> {
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

function isAssistantEvent(eventType: string): boolean {
  return (
    eventType === 'assistant_started'
    || eventType === 'assistant_delta_batch'
    || eventType === 'assistant_completed'
    || eventType === 'assistant_failed'
  )
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
    runtimeContext.agentDefinitionId = getString(payload.agentDefinitionId) || runtimeContext.agentDefinitionId
    runtimeContext.model = getString(snapshot.modelId) || runtimeContext.model
    runtimeContext.variant = getString(snapshot.variant) || runtimeContext.variant
    return
  }
  if (entry.entryType === 'model_change') {
    runtimeContext.model = getString(payload.modelId) || runtimeContext.model
    runtimeContext.variant = getString(payload.variant) || runtimeContext.variant
    return
  }
  if (entry.entryType === 'yolo_change') {
    runtimeContext.yoloEnabled = payload.yoloEnabled === true
    return
  }
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

function newStreamingAssistant(event: ThreadEventDTO, key: string): StreamingAssistant {
  return {
    event,
    key,
    textFilter: new ThinkTagTextFilter(),
    pendingThinking: '',
  }
}

function assistantKey(event: ThreadEventDTO): string {
  return event.subjectEntryId ? `subject:${event.subjectEntryId}` : `event:${event.eventId}`
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
  const message = ensureStreamingAssistantMessage(state, messages)
  message.thinking = (message.thinking ?? '') + delta
}

function completeStreamingAssistant(
  state: StreamingAssistant | undefined,
  messages: DialogueMessage[],
  event: ThreadEventDTO,
  failureMessage: string,
) {
  if (!state) {
    if (failureMessage) {
      messages.push({
        id: event.eventId,
        role: 'assistant',
        subjectEntryId: event.subjectEntryId,
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

function prepareStreamingTool(
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

function appendStreamingToolResults(
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

function toolEventKey(event: ThreadEventDTO, payload: Record<string, unknown>): string {
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

function toolCallMaterialKey(payload: Record<string, unknown>, event: ThreadEventDTO): string {
  const toolCallId = getString(payload.toolCallId)
  if (toolCallId) {
    return `call:${toolCallId}`
  }
  return event.subjectEntryId ? `subject:${event.subjectEntryId}` : ''
}

function toolCallKey(toolCallId: string): string {
  return toolCallId ? `call:${toolCallId}` : ''
}

function contentText(content: Record<string, unknown>): string {
  const type = getString(content.type)
  if (type === 'text' || type === 'thinking') {
    return getString(content.text)
  }
  if (type === 'json') {
    return stringifyJsonContent(content.json)
  }
  return ''
}

/** Canonical Tool delta: stringify object/array json values instead of dropping them. */
function stringifyJsonContent(value: unknown): string {
  if (typeof value === 'string') {
    return value
  }
  if (value == null) {
    return ''
  }
  if (typeof value === 'object') {
    try {
      return JSON.stringify(value)
    } catch {
      return ''
    }
  }
  return String(value)
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
