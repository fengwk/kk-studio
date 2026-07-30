import { asRecord } from '@/features/ai/runtime/payload-json'
import type { MetaDialogueMessage } from '@/features/ai/runtime/thread-timeline-types'
import { formatCompactTokens, numberField } from '@/features/ai/runtime/thread-timeline/content-utils'

/** 从持久 ASSISTANT Entry 的 assistantMetadata 投影用量。 */
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
