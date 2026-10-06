import { describe, expect, it } from 'vitest'
import type { HarnessSessionEntryDTO } from '@/shared/api/contracts/ai-runtime'
import { aggregateEntryUsage } from '@/features/ai/runtime/thread-timeline/turn-usage'

/**
 * aggregateEntryUsage 只从全部 Entry 事实（ASSISTANT assistantMetadata + usageCost 读取投影）派生，
 * 不依赖对话消息、卡片 visible、TURN_END 投影或任何 flag；compaction 等隐藏内容里真实发生的
 * token/费用同样进入累计。费用按精确十进制求和，缺失/跨币种不伪造完整总额。
 */
function assistantEntry(
  entryId: string,
  usage: Record<string, number>,
  usageCost?: { currency: string; amount: string } | null,
  entryType: HarnessSessionEntryDTO['entryType'] = 'MESSAGE',
): HarnessSessionEntryDTO {
  return {
    entryId,
    sessionId: 's1',
    parentEntryId: null,
    entryType,
    payloadJson: JSON.stringify({
      message: { role: 'ASSISTANT', contents: [{ type: 'text', text: 'ok' }] },
      ...(Object.keys(usage).length > 0 ? { assistantMetadata: { usage } } : {}),
    }),
    createTime: '2026-07-28T10:00:00Z',
    usageCost: usageCost ?? null,
  }
}

function otherEntry(entryId: string, entryType: HarnessSessionEntryDTO['entryType']): HarnessSessionEntryDTO {
  return {
    entryId,
    sessionId: 's1',
    parentEntryId: null,
    entryType,
    payloadJson: JSON.stringify({ reason: 'USER_MESSAGE' }),
    createTime: '2026-07-28T09:59:00Z',
  }
}

/** COMPACTION 结果 Entry：canonical shape 的 summaryText + 可空 assistantMetadata。 */
function compactionEntry(
  entryId: string,
  usage: Record<string, number> | null,
  usageCost?: { currency: string; amount: string } | null,
): HarnessSessionEntryDTO {
  return {
    entryId,
    sessionId: 's1',
    parentEntryId: null,
    entryType: 'COMPACTION',
    payloadJson: JSON.stringify({
      summaryText: 'summarized',
      assistantMetadata: usage == null ? null : { stopReason: 'COMPLETE', usage },
    }),
    createTime: '2026-07-28T10:00:00Z',
    usageCost: usageCost ?? null,
  }
}

describe('aggregateEntryUsage', () => {
  it('returns null when no Entry carries usage facts', () => {
    expect(aggregateEntryUsage([])).toBeNull()
    expect(aggregateEntryUsage([
      otherEntry('turn-1', 'TURN_START'),
      assistantEntry('assistant-1', {}),
    ])).toBeNull()
  })

  it('sums every model call across the whole branch', () => {
    const aggregated = aggregateEntryUsage([
      otherEntry('turn-1', 'TURN_START'),
      assistantEntry('assistant-1', {
        inputTokens: 10, outputTokens: 2, cacheReadTokens: 3, cacheWriteTokens: 4,
        reasoningTokens: 5, providerTotalTokens: 24,
      }, { currency: 'USD', amount: '0.125000000000' }),
      otherEntry('end-1', 'TURN_END'),
      otherEntry('turn-2', 'TURN_START'),
      assistantEntry('assistant-2', {
        inputTokens: 20, outputTokens: 7, cacheReadTokens: 11, cacheWriteTokens: 13,
        reasoningTokens: 17, providerTotalTokens: 68,
      }, { currency: 'USD', amount: '0.375000000000' }),
    ])

    expect(aggregated).toMatchObject({
      input: 30,
      output: 9,
      cacheRead: 14,
      cacheWrite: 17,
      reasoning: 22,
      providerTotal: 92,
      decodeTokens: null,
      decodeDurationMillis: null,
    })
    // 精确十进制求和：0.125 + 0.375 必须正好等于 0.5，无浮点残差
    expect(aggregated?.cost).toEqual({ currency: 'USD', amount: '0.5' })
  })

  // compaction 回合的真实 usage 由结果 Entry 的 canonical assistantMetadata 承载：同样不能因隐藏内容漏计
  it('includes model usage recorded on a compaction result entry', () => {
    const aggregated = aggregateEntryUsage([
      otherEntry('compact-turn', 'TURN_START'),
      compactionEntry(
        'compaction-1',
        { inputTokens: 1000, outputTokens: 100, providerTotalTokens: 1100 },
        { currency: 'USD', amount: '1.5' },
      ),
      otherEntry('compact-end', 'TURN_END'),
      assistantEntry('assistant-real', {
        inputTokens: 10, outputTokens: 1, providerTotalTokens: 11,
      }, { currency: 'USD', amount: '0.25' }),
    ])
    expect(aggregated).toMatchObject({ input: 1010, output: 101, providerTotal: 1111 })
    expect(aggregated?.cost).toEqual({ currency: 'USD', amount: '1.75' })
  })

  // 纯摘要（无 metadata）没有真实模型用量：既不计价也不伪造 0
  it('ignores a compaction result without canonical metadata', () => {
    expect(aggregateEntryUsage([
      otherEntry('compact-turn', 'TURN_START'),
      compactionEntry('compaction-1', null),
      otherEntry('compact-end', 'TURN_END'),
    ])).toBeNull()
  })

  // 亚微级费用必须精确保留：先 sum 后 round，绝不在中间截断
  it('preserves sub-micro cost precision before formatting', () => {
    const aggregated = aggregateEntryUsage([
      assistantEntry('a', { inputTokens: 1, providerTotalTokens: 1 }, { currency: 'USD', amount: '0.0000004' }),
      assistantEntry('b', { inputTokens: 1, providerTotalTokens: 1 }, { currency: 'USD', amount: '0.0000005' }),
    ])
    expect(aggregated?.cost).toEqual({ currency: 'USD', amount: '0.0000009' })
  })

  // 缺失定价或跨币种：不给误导性的完整总额，也不伪造成 $0
  it('drops the total when pricing is missing or mixed', () => {
    const usd = assistantEntry('a', { inputTokens: 1 }, { currency: 'USD', amount: '0.1' })
    const unpriced = assistantEntry('b', { inputTokens: 1 })
    const eur = assistantEntry('c', { inputTokens: 1 }, { currency: 'EUR', amount: '0.1' })

    expect(aggregateEntryUsage([usd, unpriced])?.cost).toBeNull()
    expect(aggregateEntryUsage([usd, eur])?.cost).toBeNull()
    // 全部无定价时整体 cost 仍为 null（不是 0）
    expect(aggregateEntryUsage([unpriced])?.cost).toBeNull()
  })

  // 测速样本按 token/duration 分子分母加权累加，无样本项不参与；上下文取最新调用
  it('weights speed samples and takes the latest context estimate', () => {
    const withSpeed = (entryId: string, input: number, tokens: number, millis: number): HarnessSessionEntryDTO => ({
      ...assistantEntry(entryId, { inputTokens: input, outputTokens: tokens, reasoningTokens: 0, providerTotalTokens: input + tokens }),
      payloadJson: JSON.stringify({
        message: { role: 'ASSISTANT', contents: [{ type: 'text', text: 'ok' }] },
        assistantMetadata: { usage: { inputTokens: input, outputTokens: tokens }, decodeDurationMillis: millis },
      }),
    })

    const aggregated = aggregateEntryUsage([
      withSpeed('a', 100, 50, 1000), // 50 tok/s
      withSpeed('b', 200, 120, 2000), // 60 tok/s
      assistantEntry('c', { inputTokens: 50, outputTokens: 10 }), // 无测速样本
    ])
    // 加权总速率 = (50 + 120) / (1000 + 2000) * 1000 = 56.67 -> 57
    expect(aggregated?.decodeTokens).toBe(170)
    expect(aggregated?.decodeDurationMillis).toBe(3000)
    expect(aggregated?.contextInputTokens).toBe(50)
  })

  it('ignores non-assistant and non-message entries', () => {
    expect(aggregateEntryUsage([
      otherEntry('root', 'ROOT'),
      otherEntry('turn-1', 'TURN_START'),
      otherEntry('end-1', 'TURN_END'),
      otherEntry('compaction-1', 'COMPACTION'),
    ])).toBeNull()
  })
})
