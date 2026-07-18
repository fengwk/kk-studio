import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ThreadStatusFooter } from '@/features/ai/thread-panel/ThreadStatusFooter'

describe('ThreadStatusFooter', () => {
  it('formats tokens, YOLO and cache hit metrics', () => {
    render(
      <ThreadStatusFooter
        agentName="assistant"
        providerName="minimax"
        modelName="MiniMax"
        variantName="default"
        yoloEnabled
        contextWindow={100000}
        usage={{
          scopeType: 'thread',
          scopeId: '1',
          recordCount: 2,
          inputTokens: 1500,
          outputTokens: 2500,
          cacheReadTokens: 1000,
          cacheWriteTokens: 200,
          cacheWriteLongTokens: 50,
          reasoningTokens: 0,
          providerTotalTokens: 5000,
          cacheEligibleRecordCount: 2,
          cacheHitRecordCount: 1,
          cacheHitRatio: '0.5',
          tokenReadRatio: '0.2',
          unamortizedCacheWriteTokens: 0,
          costs: [{ currency: 'USD', input: '0.1', output: '0.2', cacheRead: '0', cacheWrite: '0', cacheWriteLong: '0', reasoning: '0', total: '0.3' }],
        }}
      />,
    )
    const line = screen.getByLabelText('会话状态').textContent ?? ''
    expect(line).toContain('agent:assistant')
    expect(line).toContain('YOLO')
    expect(line).toContain('CH50.0%')
    expect(line).toContain('$0.300')
  })

  it('falls back when usage is missing and labels are empty', () => {
    render(<ThreadStatusFooter agentName="-" providerName={null as never} modelName="undefined" />)
    const line = screen.getByLabelText('会话状态').textContent ?? ''
    expect(line).toContain('agent:agent')
    expect(line).toContain('unknown-model')
    expect(line).not.toContain('YOLO')
  })

  it('uses cache token ratio and large token formatting branches', () => {
    render(
      <ThreadStatusFooter
        usage={{
          scopeType: 'thread',
          scopeId: '1',
          recordCount: 1,
          inputTokens: 12_000,
          outputTokens: 2_000_000,
          cacheReadTokens: 3000,
          cacheWriteTokens: 0,
          cacheWriteLongTokens: 0,
          reasoningTokens: 0,
          providerTotalTokens: 0,
          cacheEligibleRecordCount: 0,
          cacheHitRecordCount: 0,
          cacheHitRatio: 0,
          tokenReadRatio: 0,
          unamortizedCacheWriteTokens: 0,
          costs: [],
        }}
      />,
    )
    const line = screen.getByLabelText('会话状态').textContent ?? ''
    expect(line).toMatch(/CH\d+\.\d+%/)
    expect(line).toMatch(/2\.0M|2M/)
  })
})
