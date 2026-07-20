import type { ThreadEventDTO } from '@/shared/api/contracts'
import { asRecord, getString } from '@/features/ai/thread-event-payload'
import type { DialogueMessage, MetaDialogueMessage } from '@/features/ai/thread-event-types'
import { formatCompactTokens, numberField } from '@/features/ai/thread-timeline/content-utils'

/**
 * 将 meta 插到指定 subject（助手 Entry）及其后续 tool 块之后，避免刷新后全部堆在列表底部。
 */
export function insertAfterTurnTail(
  messages: DialogueMessage[],
  subjectEntryId: string | null | undefined,
  message: DialogueMessage,
) {
  if (messages.some((item) => item.id === message.id)) {
    return
  }
  if (!subjectEntryId) {
    messages.push(message)
    return
  }
  let anchor = -1
  for (let index = 0; index < messages.length; index += 1) {
    if (messages[index]?.id === subjectEntryId) {
      anchor = index
    }
  }
  if (anchor < 0) {
    messages.push(message)
    return
  }
  let insertAt = anchor
  for (let index = anchor + 1; index < messages.length; index += 1) {
    const current = messages[index]
    if (!current) {
      break
    }
    if (current.role === 'tool') {
      insertAt = index
      continue
    }
    break
  }
  messages.splice(insertAt + 1, 0, message)
}

/**
 * 从 assistant_completed 事件投影「本回合模型调用」用量。
 * 语义：单次 assistant_completed 的 usage/cost，不等于含后续 tool 的业务整轮。
 */
export function projectTurnUsageMeta(
  event: ThreadEventDTO,
  payload: Record<string, unknown>,
): MetaDialogueMessage | null {
  const usage = asRecord(payload.usage)
  const costNode = payload.cost
  const input = numberField(usage, 'inputTokens', 'input_tokens', 'promptTokens')
  const output = numberField(usage, 'outputTokens', 'output_tokens', 'completionTokens')
  const cacheRead = numberField(
    usage,
    'cacheReadTokens',
    'cache_read_tokens',
    'cacheRead',
    'cachedTokens',
  )
  const cacheWrite =
    numberField(usage, 'cacheWriteTokens', 'cache_write_tokens', 'cacheWrite')
    + numberField(usage, 'cacheWriteLongTokens', 'cache_write_long_tokens', 'cacheWriteLong')
  const reasoning = numberField(usage, 'reasoningTokens', 'reasoning_tokens')
  let cost = 0
  if (typeof costNode === 'number' && Number.isFinite(costNode)) {
    cost = costNode
  } else if (typeof costNode === 'string' && costNode.trim()) {
    const parsed = Number(costNode)
    if (Number.isFinite(parsed)) {
      cost = parsed
    }
  } else if (costNode && typeof costNode === 'object') {
    cost = numberField(asRecord(costNode), 'total', 'amount', 'usd')
  }
  if (input <= 0 && output <= 0 && cacheRead <= 0 && cacheWrite <= 0 && reasoning <= 0 && cost <= 0) {
    return null
  }
  const parts = [
    `↑${formatCompactTokens(input)}`,
    `↓${formatCompactTokens(output)}`,
    `R${formatCompactTokens(cacheRead)}`,
    `W${formatCompactTokens(cacheWrite)}`,
  ]
  if (reasoning > 0) {
    parts.push(`T${formatCompactTokens(reasoning)}`)
  }
  if (cacheRead > 0 || input > 0) {
    const denom = input + cacheRead
    const hit = denom > 0 ? (cacheRead / denom) * 100 : 0
    parts.push(`CH${hit.toFixed(1)}%`)
  }
  parts.push(`$${cost.toFixed(3)}`)
  // 与 Entry 投影共用稳定 id，避免 entry+event 双份
  const id = event.subjectEntryId
    ? `meta-usage-entry-${event.subjectEntryId}`
    : `meta-usage-${event.eventId}`
  return {
    id,
    role: 'meta',
    kind: 'turn_usage',
    subjectEntryId: event.subjectEntryId,
    text: parts.join(' · '),
    details: { input, output, cacheRead, cacheWrite, reasoning, cost },
    createdAt: event.createTime,
    status: 'done',
  }
}

/** 从持久 ASSISTANT Entry 的 assistantMetadata 投影用量（刷新后不依赖 event 是否被 skip） */
export function projectTurnUsageFromAssistantMetadata(
  entryId: string,
  metadata: Record<string, unknown>,
  createdAt: MetaDialogueMessage['createdAt'],
): MetaDialogueMessage | null {
  const usage = asRecord(metadata.usage)
  const costNode = metadata.cost
  const input = numberField(usage, 'inputTokens', 'input_tokens', 'promptTokens')
  const output = numberField(usage, 'outputTokens', 'output_tokens', 'completionTokens')
  const cacheRead = numberField(
    usage,
    'cacheReadTokens',
    'cache_read_tokens',
    'cacheRead',
    'cachedTokens',
  )
  const cacheWrite =
    numberField(usage, 'cacheWriteTokens', 'cache_write_tokens', 'cacheWrite')
    + numberField(usage, 'cacheWriteLongTokens', 'cache_write_long_tokens', 'cacheWriteLong')
  const reasoning = numberField(usage, 'reasoningTokens', 'reasoning_tokens')
  let cost = 0
  if (typeof costNode === 'number' && Number.isFinite(costNode)) {
    cost = costNode
  } else if (typeof costNode === 'string' && costNode.trim()) {
    const parsed = Number(costNode)
    if (Number.isFinite(parsed)) {
      cost = parsed
    }
  } else if (costNode && typeof costNode === 'object') {
    cost = numberField(asRecord(costNode), 'total', 'amount', 'usd')
  }
  if (input <= 0 && output <= 0 && cacheRead <= 0 && cacheWrite <= 0 && reasoning <= 0 && cost <= 0) {
    return null
  }
  const parts = [
    `↑${formatCompactTokens(input)}`,
    `↓${formatCompactTokens(output)}`,
    `R${formatCompactTokens(cacheRead)}`,
    `W${formatCompactTokens(cacheWrite)}`,
  ]
  if (reasoning > 0) {
    parts.push(`T${formatCompactTokens(reasoning)}`)
  }
  if (cacheRead > 0 || input > 0) {
    const denom = input + cacheRead
    const hit = denom > 0 ? (cacheRead / denom) * 100 : 0
    parts.push(`CH${hit.toFixed(1)}%`)
  }
  parts.push(`$${cost.toFixed(3)}`)
  return {
    id: `meta-usage-entry-${entryId}`,
    role: 'meta',
    kind: 'turn_usage',
    subjectEntryId: entryId,
    text: parts.join(' · '),
    details: { input, output, cacheRead, cacheWrite, reasoning, cost },
    createdAt,
    status: 'done',
  }
}

export function projectModelChangedMeta(
  event: ThreadEventDTO,
  payload: Record<string, unknown>,
): MetaDialogueMessage {
  const modelId = getString(payload.modelId) || 'model'
  const variant = getString(payload.variant)
  return {
    id: `meta-model-${event.eventId}`,
    role: 'meta',
    kind: 'model_change',
    subjectEntryId: event.subjectEntryId,
    text: variant ? `Model 已切换为 ${modelId} · ${variant}` : `Model 已切换为 ${modelId}`,
    details: { modelId, variant },
    createdAt: event.createTime,
    status: 'done',
  }
}

export function projectAgentChangedMeta(
  event: ThreadEventDTO,
  payload: Record<string, unknown>,
): MetaDialogueMessage {
  const agentName = getString(payload.agentName) || getString(payload.agentDefinitionId) || 'agent'
  return {
    id: `meta-agent-${event.eventId}`,
    role: 'meta',
    kind: 'agent_change',
    subjectEntryId: event.subjectEntryId,
    text: `Agent 已切换为 ${agentName}`,
    details: {
      agentDefinitionId: payload.agentDefinitionId,
      agentName,
    },
    createdAt: event.createTime,
    status: 'done',
  }
}
