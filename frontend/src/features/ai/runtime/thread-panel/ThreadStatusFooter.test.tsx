import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ThreadStatusFooter } from '@/features/ai/runtime/thread-panel/ThreadStatusFooter'

describe('ThreadStatusFooter', () => {
  it('renders agent, model, and environment segments and joins them with separators', () => {
    render(
      <ThreadStatusFooter
        agentName="assistant"
        providerName="minimax"
        modelName="MiniMax"
        variantName="default"
        environmentName="local"
        yoloEnabled
      />,
    )
    const footer = screen.getByLabelText('会话状态')
    const line = footer.textContent ?? ''
    expect(line).toContain('agent:assistant · YOLO')
    expect(line).toContain('minimax/MiniMax · default')
    expect(line).not.toContain('(minimax)')
    expect(line).toContain('local')
    expect(footer.querySelectorAll('.thread-status-seg').length).toBe(3)
  })

  it('falls back to defaults for blank agent/model/variant/environment names', () => {
    render(
      <ThreadStatusFooter
        agentName="-"
        providerName={null as never}
        modelName="undefined"
      />,
    )
    const line = screen.getByLabelText('会话状态').textContent ?? ''
    expect(line).toContain('agent:agent')
    expect(line).toContain('unknown-model')
    expect(line).toContain('unknown-variant')
    expect(line).toContain('env:none')
    expect(line).not.toContain('YOLO')
  })

  it('renders env:<name> and env:<name> (unavailable) without fallback', () => {
    const { rerender } = render(
      <ThreadStatusFooter
        agentName="assistant"
        environmentName="dev"
        environmentReady
      />,
    )
    const readyLine = screen.getByLabelText('会话状态').textContent ?? ''
    expect(readyLine).toContain('env:dev')
    expect(readyLine).not.toContain('(unavailable)')

    // 选中但不可用（统一可用性规则）=> env:<name> (unavailable)；绝不 fallback 到别的 env。
    rerender(
      <ThreadStatusFooter
        agentName="assistant"
        environmentName="dev"
        environmentReady={false}
      />,
    )
    const unavailableLine = screen.getByLabelText('会话状态').textContent ?? ''
    expect(unavailableLine).toContain('env:dev (unavailable)')
  })

  it('hides YOLO when yoloEnabled is false', () => {
    render(
      <ThreadStatusFooter
        agentName="assistant"
        providerName="minimax"
        modelName="MiniMax"
        variantName="default"
        yoloEnabled={false}
      />,
    )
    const line = screen.getByLabelText('会话状态').textContent ?? ''
    expect(line).toContain('agent:assistant')
    expect(line).not.toContain('YOLO')
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
      // 窄 footer 会将 3 段内容排成多行。
      expect(screen.getByLabelText('会话状态').querySelectorAll('.thread-status-row').length).toBeGreaterThan(1)
    } finally {
      if (descriptor) {
        Object.defineProperty(HTMLElement.prototype, 'clientWidth', descriptor)
      }
    }
  })
})
