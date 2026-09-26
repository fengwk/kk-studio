import { describe, expect, it } from 'vitest'
import { aggregateBranchUsage } from '@/features/ai/runtime/thread-timeline/turn-usage'
import type {
  DialogueMessage,
  MetaDialogueMessage,
  TurnUsage,
} from '@/features/ai/runtime/thread-timeline-types'

function usageMessage(id: string, turnUsage: TurnUsage): MetaDialogueMessage {
  return {
    id,
    role: 'meta',
    kind: 'turn_usage',
    subjectEntryId: id,
    text: 'usage',
    turnUsage,
    createdAt: null,
    status: 'done',
  }
}

describe('aggregateBranchUsage', () => {
  it('sums every completed Turn usage field on the current branch', () => {
    const messages: DialogueMessage[] = [
      {
        id: 'user-1',
        role: 'user',
        subjectEntryId: 'user-1',
        text: 'hello',
        createdAt: null,
      },
      usageMessage('usage-1', {
        input: 10,
        output: 2,
        cacheRead: 3,
        cacheWrite: 4,
        reasoning: 5,
        providerTotal: 24,
        cost: 0.125,
      }),
      usageMessage('usage-2', {
        input: 20,
        output: 7,
        cacheRead: 11,
        cacheWrite: 13,
        reasoning: 17,
        providerTotal: 68,
        cost: 0.375,
      }),
    ]

    const aggregated = aggregateBranchUsage(messages)
    expect(aggregated).toMatchObject({
      input: 30,
      output: 9,
      cacheRead: 14,
      cacheWrite: 17,
      reasoning: 22,
      providerTotal: 92,
      decodeTokens: null,
      decodeDurationMillis: null,
      contextInputTokens: null,
    })
    // 费用按浮点原值累加，不做低精度 round
    expect(aggregated?.cost).toBeCloseTo(0.5, 10)
  })

  // 验证费用不再 toFixed(6) 截断：亚微级费用必须原样保留
  it('preserves sub-micro cost precision without truncation', () => {
    const aggregated = aggregateBranchUsage([
      usageMessage('usage-1', {
        input: 0,
        output: 0,
        cacheRead: 0,
        cacheWrite: 0,
        reasoning: 0,
        providerTotal: 0,
        cost: 0.0000004,
      }),
    ])
    expect(aggregated?.cost).toBe(0.0000004)
  })

  // 验证多回合聚合时，contextInputTokens 取最新调用，而测速有效样本分子与分母各自累加
  it('takes latest contextInputTokens and accumulates speed samples across closed turns', () => {
    const messages: DialogueMessage[] = [
      usageMessage('usage-1', {
        input: 100,
        output: 50,
        cacheRead: 20,
        cacheWrite: 10,
        reasoning: 0,
        providerTotal: 180,
        cost: 0.01,
        contextInputTokens: 130,
        decodeTokens: 50,
        decodeDurationMillis: 1000, // 50 tok/s
      }),
      usageMessage('usage-2', {
        input: 200,
        output: 100,
        cacheRead: 50,
        cacheWrite: 0,
        reasoning: 20,
        providerTotal: 370,
        cost: 0.02,
        contextInputTokens: 250, // latest
        decodeTokens: 120, // 100 + 20 reasoning
        decodeDurationMillis: 2000, // 60 tok/s
      }),
      // 第三个回合无测速样本（旧记录/未测），不参与测速分子分母累加
      usageMessage('usage-3', {
        input: 50,
        output: 10,
        cacheRead: 0,
        cacheWrite: 0,
        reasoning: 0,
        providerTotal: 60,
        cost: 0.005,
        contextInputTokens: 50, // latest
        decodeTokens: null,
        decodeDurationMillis: null,
      }),
    ]

    const aggregated = aggregateBranchUsage(messages)
    expect(aggregated).toMatchObject({
      input: 350,
      output: 160,
      cacheRead: 70,
      cacheWrite: 10,
      reasoning: 20,
      providerTotal: 610,
      decodeTokens: 170, // 50 + 120
      decodeDurationMillis: 3000, // 1000 + 2000
      contextInputTokens: 50, // 取最新 usage-3 的 contextInputTokens
    })
    // 0.01 + 0.02 + 0.005 的浮点原值不应被 round 篡改
    expect(aggregated?.cost).toBeCloseTo(0.035, 10)
  })

  it('returns null when the branch has no TURN_END usage summary', () => {
    expect(aggregateBranchUsage([
      {
        id: 'assistant-1',
        role: 'assistant',
        subjectEntryId: 'assistant-1',
        text: 'still running',
        createdAt: null,
      },
    ])).toBeNull()
  })
})
