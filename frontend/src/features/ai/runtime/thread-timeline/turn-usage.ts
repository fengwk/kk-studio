import { isValidDecodeSample } from '@/features/ai/runtime/thread-timeline/content-utils'
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
    decodeTokens: null,
    decodeDurationMillis: null,
    contextInputTokens: null,
  }
  let found = false
  let speedTokens = 0
  let speedDuration = 0
  let hasSpeedSample = false

  for (const message of messages) {
    if (message.role !== 'meta' || message.kind !== 'turn_usage' || message.turnUsage == null) {
      continue
    }
    found = true
    const usage = message.turnUsage
    total.input += usage.input
    total.output += usage.output
    total.cacheRead += usage.cacheRead
    total.cacheWrite += usage.cacheWrite
    total.reasoning += usage.reasoning
    total.providerTotal += usage.providerTotal
    total.cost += usage.cost

    // 最新一次成功模型调用的已知上下文输入估计取 latest，非 sum
    if (usage.contextInputTokens != null) {
      total.contextInputTokens = usage.contextInputTokens
    }

    // 测速有效样本：仅有效样本累加分子分母
    if (isValidDecodeSample(usage)) {
      speedTokens += usage.decodeTokens
      speedDuration += usage.decodeDurationMillis
      hasSpeedSample = true
    }
  }

  if (!found) {
    return null
  }

  if (hasSpeedSample) {
    total.decodeTokens = speedTokens
    total.decodeDurationMillis = speedDuration
  }

  return total
}
