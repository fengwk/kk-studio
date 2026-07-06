import type { AgentRunDTO, AgentSessionEventDTO, BackendDateTime } from '@/shared/api/contracts'

export type DialogueRole = 'user' | 'assistant' | 'system' | 'tool'
export type DialogueStatus = 'streaming' | 'done' | 'error'
export type ToolAttachmentType = 'image' | 'audio' | 'video'

export interface ToolAttachment {
  type: ToolAttachmentType
  name: string
  mime: string
  data: string
}

interface BaseDialogueMessage {
  id: string
  role: DialogueRole
  runId: string | null
  createdAt: BackendDateTime
  status?: DialogueStatus
}

export interface TextDialogueMessage extends BaseDialogueMessage {
  role: 'user' | 'assistant' | 'system'
  text: string
  metadata?: Record<string, unknown>
}

export interface ToolDialogueMessage extends BaseDialogueMessage {
  role: 'tool'
  text: string
  toolCallId: string
  toolName: string
  arguments: string
  attachments: ToolAttachment[]
  errorMessage?: string
}

export type DialogueMessage = TextDialogueMessage | ToolDialogueMessage

export interface RuntimeContext {
  provider?: string
  model?: string
  variant?: string
  agentName?: string
}

export interface SessionTimeline {
  messages: DialogueMessage[]
  runtimeContext: RuntimeContext
}

export function buildSessionTimeline(events: AgentSessionEventDTO[]): SessionTimeline {
  const messages: DialogueMessage[] = []
  const runtimeContext: RuntimeContext = {}
  const activeTools = new Map<string, ToolProjectionState>()
  let activeAssistant: AssistantProjectionState | null = null

  for (const event of events) {
    const payload = parsePayload(event.payloadJson)

    if (event.eventType === 'user_message') {
      messages.push({
        id: event.eventId,
        role: 'user',
        runId: event.runId,
        text: getString(payload.content),
        createdAt: event.createTime,
        status: 'done',
      })
      continue
    }

    if (event.eventType === 'set_agent_info') {
      runtimeContext.agentName = getString(payload.agentName) || runtimeContext.agentName
      continue
    }

    if (event.eventType === 'set_model_info') {
      runtimeContext.provider = getString(payload.provider) || runtimeContext.provider
      runtimeContext.model = getString(payload.model) || runtimeContext.model
      runtimeContext.variant = getString(payload.variant) || runtimeContext.variant
      continue
    }

    if (event.eventType === 'assistant_start') {
      activeAssistant = beginAssistantAttempt(activeAssistant, messages, event)
      continue
    }

    if (event.eventType === 'assistant_delta') {
      activeAssistant = appendAssistantDelta(activeAssistant, messages, event, getString(payload.textDelta))
      continue
    }

    if (event.eventType === 'assistant_end') {
      activeAssistant = finalizeAssistant(activeAssistant, messages, event, 'done', asRecord(payload.metadata))
      continue
    }

    if (event.eventType === 'assistant_error') {
      activeAssistant = finalizeAssistant(
        activeAssistant,
        messages,
        event,
        'error',
        undefined,
        getString(payload.message) || 'Assistant failed',
      )
      continue
    }

    if (event.eventType === 'tool_start') {
      startToolProjection(activeTools, messages, event, payload)
      continue
    }

    if (event.eventType === 'tool_delta') {
      appendToolDelta(activeTools, event, payload)
      continue
    }

    if (event.eventType === 'tool_end') {
      finalizeTool(activeTools, messages, event, payload, 'done')
      continue
    }

    if (event.eventType === 'tool_error') {
      finalizeTool(activeTools, messages, event, payload, 'error')
    }
  }

  return { messages, runtimeContext }
}

export function hasActiveRun(runs: AgentRunDTO[]): boolean {
  return runs.some((run) => run.status === 'queued' || run.status === 'running')
}

interface AssistantProjectionState {
  eventId: string
  runId: string | null
  createTime: BackendDateTime
  message?: TextDialogueMessage
  textFilter: ThinkTagTextFilter
}

interface ToolProjectionState {
  toolCallId: string
  message: ToolDialogueMessage
  outputSlots: Map<number, ToolOutputSlot>
}

interface ToolOutputSlot {
  type?: 'text' | ToolAttachmentType
  text: string
  attachment?: ToolAttachment
}

function beginAssistantAttempt(
  activeAssistant: AssistantProjectionState | null,
  messages: DialogueMessage[],
  event: AgentSessionEventDTO,
): AssistantProjectionState {
  finalizeAssistant(activeAssistant, messages, event, 'done')
  return newAssistantProjectionState(event)
}

function appendAssistantDelta(
  activeAssistant: AssistantProjectionState | null,
  messages: DialogueMessage[],
  event: AgentSessionEventDTO,
  textDelta: string,
): AssistantProjectionState {
  const state = activeAssistant ?? newAssistantProjectionState(event)
  const visibleText = state.textFilter.append(textDelta)
  if (visibleText) {
    const message = ensureAssistantMessage(messages, state)
    message.text += visibleText
    message.status = 'streaming'
  }
  return state
}

function finalizeAssistant(
  activeAssistant: AssistantProjectionState | null,
  messages: DialogueMessage[],
  event: AgentSessionEventDTO,
  status: DialogueStatus,
  metadata?: Record<string, unknown>,
  closingMessage?: string,
): null {
  const state = activeAssistant ?? newAssistantProjectionState(event)
  const visibleTail = state.textFilter.finish()
  if (visibleTail) {
    const message = ensureAssistantMessage(messages, state)
    message.text += visibleTail
  }

  if (!state.message) {
    if (status === 'error') {
      const message = ensureAssistantMessage(messages, state)
      message.text = closingMessage || 'Assistant failed'
      message.status = 'error'
      message.metadata = metadata
    }
    return null
  }

  if (closingMessage) {
    state.message.text = state.message.text ? `${state.message.text}\n${closingMessage}` : closingMessage
  }
  state.message.status = status
  state.message.metadata = metadata
  return null
}

function newAssistantProjectionState(event: AgentSessionEventDTO): AssistantProjectionState {
  return {
    eventId: event.eventId,
    runId: event.runId,
    createTime: event.createTime,
    textFilter: new ThinkTagTextFilter(),
  }
}

function ensureAssistantMessage(messages: DialogueMessage[], state: AssistantProjectionState): TextDialogueMessage {
  if (state.message) {
    return state.message
  }
  const created: TextDialogueMessage = {
    id: state.eventId,
    role: 'assistant',
    runId: state.runId,
    text: '',
    createdAt: state.createTime,
    status: 'streaming',
  }
  state.message = created
  messages.push(created)
  return created
}

function startToolProjection(
  activeTools: Map<string, ToolProjectionState>,
  messages: DialogueMessage[],
  event: AgentSessionEventDTO,
  payload: Record<string, unknown>,
) {
  const toolCallId = getString(payload.toolCallId)
  const toolName = getString(payload.toolName)
  if (!toolCallId || !toolName) {
    return
  }

  const existing = activeTools.get(toolCallId)
  if (existing) {
    existing.message.status = 'done'
    activeTools.delete(toolCallId)
  }

  const state: ToolProjectionState = {
    toolCallId,
    message: {
      id: event.eventId,
      role: 'tool',
      runId: event.runId,
      createdAt: event.createTime,
      status: 'streaming',
      text: '',
      toolCallId,
      toolName,
      arguments: getString(payload.arguments),
      attachments: [],
    },
    outputSlots: new Map(),
  }

  messages.push(state.message)
  activeTools.set(toolCallId, state)
}

function appendToolDelta(
  activeTools: Map<string, ToolProjectionState>,
  _event: AgentSessionEventDTO,
  payload: Record<string, unknown>,
) {
  const toolCallId = getString(payload.toolCallId)
  if (!toolCallId) {
    return
  }
  const state = activeTools.get(toolCallId)
  if (!state) {
    return
  }

  for (const indexedDelta of getRecordList(payload.contentDeltas)) {
    const slotIndex = getInteger(indexedDelta.index)
    if (slotIndex === null) {
      continue
    }
    const contentDelta = asRecord(indexedDelta.contentDelta)
    const contentType = getToolContentType(contentDelta.type)
    if (!contentType) {
      continue
    }

    const slot = state.outputSlots.get(slotIndex) ?? { text: '' }
    slot.type = contentType
    if (contentType === 'text') {
      slot.text += getString(contentDelta.text)
      slot.attachment = undefined
    } else {
      slot.attachment = {
        type: contentType,
        name: getString(contentDelta.name),
        mime: getString(contentDelta.mime),
        data: getString(contentDelta.data),
      }
    }
    state.outputSlots.set(slotIndex, slot)
  }

  syncToolMessage(state)
}

function finalizeTool(
  activeTools: Map<string, ToolProjectionState>,
  messages: DialogueMessage[],
  event: AgentSessionEventDTO,
  payload: Record<string, unknown>,
  status: Extract<DialogueStatus, 'done' | 'error'>,
) {
  const toolCallId = getString(payload.toolCallId)
  if (!toolCallId) {
    return
  }
  const state = activeTools.get(toolCallId) ?? createSyntheticToolProjection(event, toolCallId)
  if (!activeTools.has(toolCallId)) {
    messages.push(state.message)
    activeTools.set(toolCallId, state)
  }

  if (status === 'error') {
    state.message.errorMessage = getString(payload.message) || 'Tool failed'
  }

  syncToolMessage(state)
  if (status === 'error' && !state.message.text.trim()) {
    state.message.text = state.message.errorMessage || 'Tool failed'
  }
  state.message.status = status
  activeTools.delete(toolCallId)
}

function createSyntheticToolProjection(
  event: AgentSessionEventDTO,
  toolCallId: string,
): ToolProjectionState {
  return {
    toolCallId,
    message: {
      id: event.eventId,
      role: 'tool',
      runId: event.runId,
      createdAt: event.createTime,
      status: 'streaming',
      text: '',
      toolCallId,
      toolName: 'Tool',
      arguments: '',
      attachments: [],
    },
    outputSlots: new Map(),
  }
}

function syncToolMessage(state: ToolProjectionState) {
  const orderedSlots = Array.from(state.outputSlots.entries())
    .sort(([left], [right]) => left - right)
    .map(([, slot]) => slot)

  const textBlocks: string[] = []
  const attachments: ToolAttachment[] = []
  for (const slot of orderedSlots) {
    if (slot.type === 'text') {
      if (slot.text) {
        textBlocks.push(slot.text)
      }
      continue
    }
    if (slot.attachment?.data) {
      attachments.push(slot.attachment)
    }
  }

  state.message.text = textBlocks.join('\n\n')
  state.message.attachments = attachments
}

function parsePayload(payloadJson: string | null): Record<string, unknown> {
  if (!payloadJson) {
    return {}
  }
  try {
    const parsed = JSON.parse(payloadJson)
    return asRecord(parsed)
  } catch {
    return {}
  }
}

function asRecord(value: unknown): Record<string, unknown> {
  return value && typeof value === 'object' && !Array.isArray(value) ? (value as Record<string, unknown>) : {}
}

function getRecordList(value: unknown): Record<string, unknown>[] {
  if (!Array.isArray(value)) {
    return []
  }
  return value.map(asRecord).filter((item) => Object.keys(item).length > 0)
}

function getString(value: unknown): string {
  return typeof value === 'string' ? value : ''
}

function getInteger(value: unknown): number | null {
  return typeof value === 'number' && Number.isInteger(value) ? value : null
}

function getToolContentType(value: unknown): 'text' | ToolAttachmentType | null {
  if (value === 'text' || value === 'image' || value === 'audio' || value === 'video') {
    return value
  }
  return null
}

class ThinkTagTextFilter {
  private static readonly OPEN_TAG = '<think>'
  private static readonly CLOSE_TAG = '</think>'

  private carry = ''
  private inThinking = false
  private suppressLeadingWhitespace = false

  append(chunk: string): string {
    if (!chunk) {
      return ''
    }
    this.carry += chunk
    return this.drain(false)
  }

  finish(): string {
    return this.drain(true)
  }

  private drain(flushAll: boolean): string {
    let text = ''
    while (this.carry.length > 0) {
      if (this.inThinking) {
        const closeIndex = this.carry.indexOf(ThinkTagTextFilter.CLOSE_TAG)
        if (closeIndex >= 0) {
          this.carry = this.carry.slice(closeIndex + ThinkTagTextFilter.CLOSE_TAG.length)
          this.inThinking = false
          this.suppressLeadingWhitespace = true
          continue
        }
        const keep = flushAll ? 0 : this.longestTagPrefixSuffix(ThinkTagTextFilter.CLOSE_TAG)
        const emitLength = this.carry.length - keep
        if (emitLength <= 0) {
          break
        }
        this.carry = this.carry.slice(emitLength)
        continue
      }

      const openIndex = this.carry.indexOf(ThinkTagTextFilter.OPEN_TAG)
      if (openIndex >= 0) {
        text += this.normalizeVisibleText(this.carry.slice(0, openIndex))
        this.carry = this.carry.slice(openIndex + ThinkTagTextFilter.OPEN_TAG.length)
        this.inThinking = true
        continue
      }

      const keep = flushAll ? 0 : this.longestTagPrefixSuffix(ThinkTagTextFilter.OPEN_TAG)
      const emitLength = this.carry.length - keep
      if (emitLength <= 0) {
        break
      }
      text += this.normalizeVisibleText(this.carry.slice(0, emitLength))
      this.carry = this.carry.slice(emitLength)
    }
    return text
  }

  private normalizeVisibleText(value: string): string {
    if (!value) {
      return ''
    }
    if (!this.suppressLeadingWhitespace) {
      return value
    }
    const normalized = value.replace(/^\s+/, '')
    if (!normalized) {
      return ''
    }
    this.suppressLeadingWhitespace = false
    return normalized
  }

  private longestTagPrefixSuffix(tag: string): number {
    const max = Math.min(this.carry.length, tag.length - 1)
    for (let length = max; length > 0; length -= 1) {
      if (this.carry.slice(-length) === tag.slice(0, length)) {
        return length
      }
    }
    return 0
  }
}
