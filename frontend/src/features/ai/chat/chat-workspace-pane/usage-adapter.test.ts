import { describe, expect, it } from 'vitest'
import type { ModelUsageSummaryDTO } from '@/shared/api/contracts'
import { toThreadUsageSummary } from '@/features/ai/chat/chat-workspace-pane/usage-adapter'

describe('toThreadUsageSummary', () => {
  it('keeps only the portable footer usage contract', () => {
    const backendUsage: ModelUsageSummaryDTO = {
      scopeType: 'thread',
      scopeId: 'thread-1',
      recordCount: 3,
      inputTokens: '120',
      outputTokens: 45,
      cacheReadTokens: 20,
      cacheWriteTokens: '6',
      cacheWriteLongTokens: 2,
      reasoningTokens: 12,
      providerTotalTokens: 199,
      cacheEligibleRecordCount: 4,
      cacheHitRecordCount: 1,
      cacheHitRatio: '0.25',
      tokenReadRatio: '0.5',
      unamortizedCacheWriteTokens: 2,
      costs: [
        {
          currency: 'USD',
          input: '0.1',
          output: '0.2',
          cacheRead: '0.01',
          cacheWrite: '0.02',
          cacheWriteLong: '0.03',
          reasoning: '0.04',
          total: '0.4',
        },
      ],
    }

    expect(toThreadUsageSummary(backendUsage)).toEqual({
      inputTokens: '120',
      outputTokens: 45,
      cacheReadTokens: 20,
      cacheWriteTokens: '6',
      cacheWriteLongTokens: 2,
      cacheEligibleRecordCount: 4,
      cacheHitRecordCount: 1,
      cacheHitRatio: '0.25',
      costs: [{ total: '0.4' }],
    })
  })
})
