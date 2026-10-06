import type { MetaDialogueMessage, TurnUsage } from '@/features/ai/runtime/thread-timeline-types'
import { formatTurnUsageText } from '@/features/ai/runtime/thread-timeline/content-utils'

/**
 * 生成一次已关闭回合的 turn footer meta 消息。
 *
 * usage 为 null（该回合没有可用 usage 事实）时依然产出一条 footer：文本为空，
 * 但带上真正关闭它的 TURN_END `endEntryId`，由 MetaMessageBlock 渲染结束信息与分支入口。
 * 字段名与数值都保持精确：cost 是读取投影，函数本身不做任何定价。
 */
export function createTurnUsageMetaMessage(
  entryId: string,
  usage: TurnUsage | null,
  createdAt: MetaDialogueMessage['createdAt'],
  endEntryId: string | null = null,
): MetaDialogueMessage {
  return {
    id: `meta-usage-entry-${entryId}`,
    role: 'meta',
    kind: 'turn_usage',
    subjectEntryId: entryId,
    text: usage == null ? '' : formatTurnUsageText(usage),
    ...(usage == null ? {} : { turnUsage: usage }),
    endEntryId,
    ...(usage == null
      ? {}
      : {
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
        }),
    createdAt,
    status: 'done',
  }
}
