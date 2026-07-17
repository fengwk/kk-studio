import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { CanvasAgentMessage } from '@/features/canvas/agent/messages/CanvasAgentMessage'
import type { AgentRunNode } from '@/features/canvas/types'

const baseRun: AgentRunNode = {
  id: 'run',
  type: 'run',
  x: 0,
  y: 0,
  width: 280,
  height: 180,
  title: '研究竞品',
  status: 'running',
  progress: 1,
  total: 4,
}

describe('CanvasAgentMessage dispatcher', () => {
  // Ensures kind-based dispatch stays modular and does not collapse into a monolithic dock.
  it('renders user, agent, generation, and run message kinds', async () => {
    const user = userEvent.setup()
    const onRunAction = vi.fn()

    const { rerender } = render(
      <CanvasAgentMessage
        message={{ kind: 'user', text: '请整理研究结论' }}
        run={baseRun}
        onRunAction={onRunAction}
      />,
    )
    expect(screen.getByText('请整理研究结论')).toHaveClass('user')

    rerender(
      <CanvasAgentMessage
        message={{ kind: 'agent', text: '我会先读取选区' }}
        run={baseRun}
        onRunAction={onRunAction}
      />,
    )
    expect(screen.getByText('我会先读取选区')).toBeInTheDocument()

    rerender(
      <CanvasAgentMessage
        message={{
          kind: 'generation',
          mode: 'text',
          parameters: '中文 · 简洁',
          text: '生成已完成',
        }}
        run={baseRun}
        onRunAction={onRunAction}
      />,
    )
    expect(screen.getByText(/文本生成/)).toBeInTheDocument()
    expect(screen.getByText('生成已完成')).toBeInTheDocument()

    rerender(
      <CanvasAgentMessage
        message={{ kind: 'run' }}
        run={baseRun}
        onRunAction={onRunAction}
      />,
    )
    expect(screen.getByText(/Agent Run/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '暂停' }))
    expect(onRunAction).toHaveBeenCalledWith('pause')
  })
})
