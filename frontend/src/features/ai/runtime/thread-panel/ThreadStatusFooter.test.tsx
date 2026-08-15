import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
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
        environment={{ name: 'local', workspacePath: 'proj/a' }}
        yoloEnabled
      />,
    )
    const footer = screen.getByLabelText('会话状态')
    const line = footer.textContent ?? ''
    expect(line).toContain('agent:assistant · YOLO')
    expect(line).toContain('minimax/MiniMax · default')
    expect(line).not.toContain('(minimax)')
    expect(line).toContain('local')
    // binding 的 workspacePath 必须可见。
    expect(line).toContain('ws:proj/a')
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

  it('renders env:<name> · ws:<path> and env:<name> · ws:<path> (unavailable) without fallback', () => {
    const { rerender } = render(
      <ThreadStatusFooter
        agentName="assistant"
        environment={{ name: 'dev', workspacePath: 'src' }}
        environmentReady
      />,
    )
    const readyLine = screen.getByLabelText('会话状态').textContent ?? ''
    expect(readyLine).toContain('env:dev · ws:src')
    expect(readyLine).not.toContain('(unavailable)')

    // 选中但不可用（统一可用性规则）=> env:<name> · ws:<path> (unavailable)；绝不 fallback 到别的 env。
    rerender(
      <ThreadStatusFooter
        agentName="assistant"
        environment={{ name: 'dev', workspacePath: 'src' }}
        environmentReady={false}
      />,
    )
    const unavailableLine = screen.getByLabelText('会话状态').textContent ?? ''
    expect(unavailableLine).toContain('env:dev · ws:src (unavailable)')
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

  it('renders the clickable browser-notification toggle when supplied', async () => {
    const user = userEvent.setup()
    let notificationToggles = 0
    render(
      <ThreadStatusFooter
        agentName="assistant"
        notificationsEnabled={false}
        notificationPermission="default"
        onNotificationsToggle={() => {
          notificationToggles += 1
        }}
      />,
    )

    await user.click(screen.getByRole('button', { name: 'notify:off' }))
    expect(notificationToggles).toBe(1)
  })

  it('renders Branch Usage after environment and before notifications', () => {
    render(
      <ThreadStatusFooter
        agentName="assistant"
        environment={{ name: 'local', workspacePath: '.' }}
        usageText="↑30 · ↓9 · R14 · W17 · $0.500"
        notificationsEnabled={false}
        notificationPermission="default"
        onNotificationsToggle={() => undefined}
      />,
    )

    const segments = [...screen.getByLabelText('会话状态').querySelectorAll('.thread-status-seg')]
    expect(segments.map((segment) => segment.textContent)).toEqual([
      'agent:assistant',
      'unknown-model · unknown-variant',
      'env:local · ws:.',
      '↑30 · ↓9 · R14 · W17 · $0.500',
      'notify:off',
    ])
    expect(segments[3]).toHaveAttribute(
      'title',
      '分支用量：↑30 · ↓9 · R14 · W17 · $0.500',
    )
    expect(segments[3]).toHaveClass('thread-status-usage')
  })

  it('does not render a usage segment for blank text', () => {
    render(<ThreadStatusFooter agentName="assistant" usageText="  " />)
    expect(screen.getByLabelText('会话状态').querySelector('.thread-status-usage')).toBeNull()
  })
})
