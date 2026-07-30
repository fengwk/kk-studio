import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ThreadStatusFooter } from '@/features/ai/runtime/thread-panel/ThreadStatusFooter'

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
          inputTokens: 1500,
          outputTokens: 2500,
          cacheReadTokens: 1000,
          cacheWriteTokens: 200,
          cacheWriteLongTokens: 50,
          cacheEligibleRecordCount: 2,
          cacheHitRecordCount: 1,
          cacheHitRatio: '0.5',
          costs: [{ total: '0.3' }],
        }}
      />,
    )
    const footer = screen.getByLabelText('会话状态')
    const line = footer.textContent ?? ''
    expect(line).toContain('agent:assistant · YOLO')
    expect(line).toContain('minimax/MiniMax · default')
    expect(line).not.toContain('(minimax)')
    expect(line).toContain('CH50.0%')
    expect(line).toContain(' · ')
    // 三段都在（布局装箱依赖真实宽度；jsdom 下不一定同行，故不强依赖 sep 数量）
    expect(footer.querySelectorAll('.thread-status-seg').length).toBe(3)
    expect(line).toMatch(/4\.0k\/100k · \$0\.300|4k\/100k · \$0\.300/)
  })

  it('shows zero usage before any conversation turn', () => {
    render(
      <ThreadStatusFooter
        agentName="-"
        providerName={null as never}
        modelName="undefined"
        contextWindow={205000}
      />,
    )
    const line = screen.getByLabelText('会话状态').textContent ?? ''
    expect(line).toContain('agent:agent')
    expect(line).toContain('unknown-model')
    expect(line).not.toContain('YOLO')
    // 未开对话也展示零用量 + 上下文上限
    expect(line).toContain('↑0')
    expect(line).toContain('↓0')
    expect(line).toContain('CH0.0%')
    expect(line).toMatch(/0\/205k|0\/205\.0k/)
    expect(line).toContain('$0.000')
  })

  it('uses cache token ratio and large token formatting branches', () => {
    render(
      <ThreadStatusFooter
        usage={{
          inputTokens: 12_000,
          outputTokens: 2_000_000,
          cacheReadTokens: 3000,
          cacheWriteTokens: 0,
          cacheWriteLongTokens: 0,
          cacheEligibleRecordCount: 0,
          cacheHitRecordCount: 0,
          cacheHitRatio: 0,
          costs: [],
        }}
      />,
    )
    const line = screen.getByLabelText('会话状态').textContent ?? ''
    expect(line).toMatch(/CH\d+\.\d+%/)
    expect(line).toMatch(/2\.0M|2M/)
  })

  it('uses request-level cache hits, accepts percentage ratios, and normalizes malformed numbers', () => {
    const { rerender } = render(
      <ThreadStatusFooter
        usage={{
          inputTokens: 'bad' as never,
          outputTokens: 12_000_000,
          cacheReadTokens: -20,
          cacheWriteTokens: 0,
          cacheWriteLongTokens: 0,
          cacheEligibleRecordCount: 4,
          cacheHitRecordCount: 1,
          cacheHitRatio: 75,
          costs: [{ total: 'bad' }],
        }}
        contextWindow={-1}
      />,
    )
    let line = screen.getByLabelText('会话状态').textContent ?? ''
    expect(line).toContain('CH75.0%')
    expect(line).toContain('12M')
    expect(line).toContain('$0.000')
    expect(line).toContain('unknown-variant')

    rerender(
      <ThreadStatusFooter
        usage={{
          inputTokens: 10,
          outputTokens: 1,
          cacheReadTokens: 0,
          cacheWriteTokens: 0,
          cacheWriteLongTokens: 0,
          cacheEligibleRecordCount: 4,
          cacheHitRecordCount: 1,
          cacheHitRatio: 0,
          costs: [],
        }}
      />,
    )
    line = screen.getByLabelText('会话状态').textContent ?? ''
    expect(line).toContain('CH25.0%')
  })

  it('packs long segments onto separate rows when the footer is narrow', () => {
    const descriptor = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'clientWidth')
    Object.defineProperty(HTMLElement.prototype, 'clientWidth', {
      configurable: true,
      get: () => 80,
    })
    try {
      render(
        <ThreadStatusFooter
          agentName="a-very-long-agent-name"
          providerName="provider"
          modelName="a-very-long-model-name"
          variantName="quality"
        />,
      )
      expect(screen.getByLabelText('会话状态').querySelectorAll('.thread-status-row')).toHaveLength(3)
    } finally {
      if (descriptor) {
        Object.defineProperty(HTMLElement.prototype, 'clientWidth', descriptor)
      }
    }
  })
})
