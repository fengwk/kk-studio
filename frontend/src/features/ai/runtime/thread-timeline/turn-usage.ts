import type {
  DialogueMessage,
  TurnUsage,
} from '@/features/ai/runtime/thread-timeline-types'

/**
 * 当前 root-to-head branch 的累计 usage。
 *
 * 只聚合 TURN_END 已经发射的 turn_usage；未关闭 Turn、compaction 与失败残留不会进入
 * 输入集合，因此这里不复制第二套 Entry 状态机。
 */
export function aggregateBranchUsage(
  messages: readonly DialogueMessage[],
): TurnUsage | null {
  const total: TurnUsage = {
    input: 0,
    output: 0,
    cacheRead: 0,
    cacheWrite: 0,
    reasoning: 0,
    providerTotal: 0,
    cost: 0,
  }
  let found = false
  for (const message of messages) {
    if (message.role !== 'meta' || message.kind !== 'turn_usage' || message.turnUsage == null) {
      continue
    }
    found = true
    total.input += message.turnUsage.input
    total.output += message.turnUsage.output
    total.cacheRead += message.turnUsage.cacheRead
    total.cacheWrite += message.turnUsage.cacheWrite
    total.reasoning += message.turnUsage.reasoning
    total.providerTotal += message.turnUsage.providerTotal
    total.cost += message.turnUsage.cost
  }
  return found ? total : null
}
