import type { HarnessSessionEntryDTO, HarnessThreadInputDTO, ThreadEventDTO } from '@/shared/api/contracts'
import { normalizeThreadEvent } from '@/features/ai/harness-thread-event-stream'
import { asRecord, getRecordList, getString, parsePayload } from '@/features/ai/thread-event-payload'
import type {
  DialogueMessage,
  QueuedThreadMessage,
  TextDialogueMessage,
  ThreadTimeline,
  ToolDialogueMessage,
} from '@/features/ai/thread-event-types'
import {
  findMaterializedAssistantEntryIds,
  findMaterializedToolKeys,
  projectDurableEntry,
} from '@/features/ai/thread-timeline/entry-projection'
import {
  insertAfterTurnTail,
  projectAgentChangedMeta,
  projectModelChangedMeta,
  projectTurnUsageMeta,
} from '@/features/ai/thread-timeline/meta-projection'
import {
  appendStreamingAssistantText,
  appendStreamingAssistantThinking,
  appendStreamingToolResults,
  assistantKey,
  completeStreamingAssistant,
  discardStreamingAssistant,
  newStreamingAssistant,
  projectStreamingAssistantFailure,
  prepareStreamingTool,
  toolCallMaterialKey,
  toolEventKey,
  type StreamingAssistant,
} from '@/features/ai/thread-timeline/stream-projection'
import { contentText } from '@/features/ai/thread-timeline/content-utils'

interface AppliedUserProjection {
  input: HarnessThreadInputDTO
  message: TextDialogueMessage
}

/**
 * Thread transcript projection:
 * committed path Entries + applied-input journal overlays + live ThreadEvents.
 *
 * 实现已拆到 thread-timeline/*；本文件只做编排。
 */
export function buildThreadTimeline(
  entries: HarnessSessionEntryDTO[],
  inputs: HarnessThreadInputDTO[],
  threadEvents: ThreadEventDTO[],
): ThreadTimeline {
  const messages: DialogueMessage[] = []
  const queuedMessages: QueuedThreadMessage[] = []
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

  // 路径 Entry：parent-chain 顺序权威
  for (const entry of entries) {
    projectDurableEntry(entry, messages, durableToolArguments)
  }

  // Mailbox inputs：QUEUED 进装饰区；APPLIED 可在 Entry 滞后时桥接
  for (const input of inputs) {
    const inputType = input.inputType.toUpperCase()
    const inputStatus = input.status?.toUpperCase()
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

  for (const projection of appliedUsers) {
    if (projection.input.appliedEntryId && !appliedEntryByInputId.has(projection.input.inputId)) {
      messages.push(projection.message)
      emittedAppliedInputs.add(projection.input.inputId)
    }
  }

  // Events：journal 顺序
  for (const rawEvent of threadEvents) {
    const event = normalizeThreadEvent(rawEvent)
    if (!event) {
      continue
    }
    const subjectKey = event.subjectEntryId ?? ''
    const payload = parsePayload(event.payloadJson)

    // 物化助手后跳过流式，但 completed 仍抽本回合模型用量并插回回合尾
    if (subjectKey && materializedAssistantIds.has(subjectKey) && isAssistantEvent(event.eventType)) {
      if (event.eventType === 'assistant_completed') {
        const turnUsage = projectTurnUsageMeta(event, payload)
        if (turnUsage) {
          insertAfterTurnTail(messages, event.subjectEntryId, turnUsage)
        }
      }
      continue
    }

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
        completeStreamingAssistant(activeAssistants.get(assistantKey(event)), messages)
        activeAssistants.delete(assistantKey(event))
        const turnUsage = projectTurnUsageMeta(event, payload)
        if (turnUsage) {
          insertAfterTurnTail(messages, event.subjectEntryId, turnUsage)
        }
        break
      }
      case 'model_changed': {
        insertAfterTurnTail(messages, event.subjectEntryId, projectModelChangedMeta(event, payload))
        break
      }
      case 'agent_changed': {
        if (event.subjectEntryId && entryIds.has(event.subjectEntryId)) {
          break
        }
        insertAfterTurnTail(messages, event.subjectEntryId, projectAgentChangedMeta(event, payload))
        break
      }
      case 'assistant_failed':
        // 无论是否已安排自动重试，失败本身都必须可见。移除同一次 attempt 的部分流，
        // 再投影独立错误消息；绝不能把错误拼进 partial assistant，否则后续成功会被染红。
        discardStreamingAssistant(activeAssistants.get(assistantKey(event)), messages)
        activeAssistants.delete(assistantKey(event))
        projectStreamingAssistantFailure(messages, event, getString(payload.message))
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

  const hasLiveProjection =
    activeAssistants.size > 0
    || [...activeTools.values()].some((tool) => tool.status === 'streaming')

  return {
    messages,
    queuedMessages,
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

function isAssistantEvent(eventType: string): boolean {
  return (
    eventType === 'assistant_started'
    || eventType === 'assistant_delta_batch'
    || eventType === 'assistant_completed'
    || eventType === 'assistant_failed'
  )
}
