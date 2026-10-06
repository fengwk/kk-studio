import type { HarnessSessionEntryDTO } from '@/shared/api/contracts/ai-runtime'
import { asRecord, getString, parsePayload } from '@/features/ai/runtime/payload-json'
import {
  mergeTurnUsage,
  parseAssistantUsage,
} from '@/features/ai/runtime/thread-timeline/content-utils'
import type { TurnUsage } from '@/features/ai/runtime/thread-timeline-types'

/**
 * 当前 branch（root-to-head 全 Entry）的累计 usage，纯函数、只看 Entry 事实。
 *
 * 直接遍历**全部** Entry 的 ASSISTANT `assistantMetadata` 与读取投影 `usageCost`，
 * 不依赖任何对话消息、卡片 visible、TURN_END 是否已投影，也不加入虚拟消息或 flags：
 * 因此 compaction 回合等被隐藏内容里真实发生的 token/费用同样进入累计，不会从事实漏计。
 * 未物化（无 metadata）的调用不会有事实，自然不会重复计算。
 */
export function aggregateEntryUsage(
  entries: readonly HarnessSessionEntryDTO[],
): TurnUsage | null {
  let total: TurnUsage | null = null
  for (const entry of entries) {
    if (entry.entryType !== 'MESSAGE' && entry.entryType !== 'CUSTOM_MESSAGE') {
      continue
    }
    const payload = parsePayload(entry.payloadJson)
    const message = asRecord(payload.message)
    if (getString(message.role) !== 'ASSISTANT') {
      continue
    }
    const usage = parseAssistantUsage(asRecord(payload.assistantMetadata), entry.usageCost ?? null)
    if (usage == null) {
      continue
    }
    total = total == null ? usage : mergeTurnUsage(total, usage)
  }
  return total
}
