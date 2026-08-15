import type { MetaDialogueMessage } from '@/features/ai/runtime/thread-timeline-types'
import {
  formatTurnUsageText,
  parseAssistantUsage,
} from '@/features/ai/runtime/thread-timeline/content-utils'

/**
 * 从持久 ASSISTANT Entry 的 assistantMetadata 投影用量。
 * 摘要文本按冻结契约：`↑input · ↓output · RcacheRead · WcacheWrite · $cost`，
 * 不附加 reasoning `T` 或 cache hit `CH`；缺失/为零的 cache 段省略。
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
    },
    createdAt,
    status: 'done',
  }
}
