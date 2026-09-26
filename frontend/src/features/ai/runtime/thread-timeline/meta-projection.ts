import type { MetaDialogueMessage, TurnUsage } from '@/features/ai/runtime/thread-timeline-types'
import {
  formatTurnUsageText,
  parseAssistantUsage,
} from '@/features/ai/runtime/thread-timeline/content-utils'

/**
 * 根据已完成或聚合的 TurnUsage 生成标准 meta turn_usage 消息。
 */
export function createTurnUsageMetaMessage(
  entryId: string,
  usage: TurnUsage,
  createdAt: MetaDialogueMessage['createdAt'],
): MetaDialogueMessage {
  return {
    id: `meta-usage-entry-${entryId}`,
    role: 'meta',
    kind: 'turn_usage',
    subjectEntryId: entryId,
    text: formatTurnUsageText(usage),
    turnUsage: usage,
    details: {
      input: usage.input,
      output: usage.output,
      cacheRead: usage.cacheRead,
      cacheWrite: usage.cacheWrite,
      reasoning: usage.reasoning,
      providerTotal: usage.providerTotal,
      cost: usage.cost,
      decodeTokens: usage.decodeTokens ?? null,
      decodeDurationMillis: usage.decodeDurationMillis ?? null,
      contextInputTokens: usage.contextInputTokens ?? null,
    },
    createdAt,
    status: 'done',
  }
}

/**
 * 从持久 ASSISTANT Entry 的 assistantMetadata 投影用量。
 */
export function projectTurnUsageFromAssistantMetadata(
  entryId: string,
  metadata: Record<string, unknown>,
  createdAt: MetaDialogueMessage['createdAt'],
): MetaDialogueMessage | null {
  const usage = parseAssistantUsage(metadata)
  if (usage == null) {
    return null
  }
  return createTurnUsageMetaMessage(entryId, usage, createdAt)
}
