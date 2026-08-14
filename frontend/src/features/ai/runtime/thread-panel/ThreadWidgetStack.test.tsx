import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ThreadWidgetStack } from '@/features/ai/runtime/thread-panel/ThreadWidgetStack'

describe('ThreadWidgetStack', () => {
  it('shows the generic working strip when working without custom retry copy', () => {
    render(
      <ThreadWidgetStack
        working
        queuedMessages={[{ clientCommandId: 'input-1', role: 'user', text: '下一条消息', sequence: 1 }]}
      />,
    )

    expect(screen.getByText('Working...')).toBeInTheDocument()
    expect(screen.getByText('queued')).toBeInTheDocument()
    expect(screen.getByText('下一条消息')).toBeInTheDocument()
    expect(screen.queryByText('本次请求已停止；发送新消息可重新开始。')).not.toBeInTheDocument()
    const workingBox = screen.getByText('Working...').closest('.thread-working')
    const queueBox = screen.getByRole('list', { name: '等待处理的消息' })
    expect(workingBox).not.toBeNull()
    expect(workingBox?.nextElementSibling).toBe(queueBox)
    expect(workingBox).not.toContainElement(queueBox)
  })

  it('hides the working strip when idle with no queue', () => {
    const { container } = render(<ThreadWidgetStack working={false} queuedMessages={[]} />)
    expect(container).toBeEmptyDOMElement()
  })

  it('scrolls an overflowing queue to the latest message when new input arrives', () => {
    const firstMessage = {
      clientCommandId: 'input-1',
      role: 'user' as const,
      text: '第一条消息',
      sequence: '1',
    }
    const { rerender } = render(
      <ThreadWidgetStack working queuedMessages={[firstMessage]} />,
    )
    const queue = screen.getByRole('list', { name: '等待处理的消息' })
    Object.defineProperties(queue, {
      scrollHeight: { configurable: true, value: 240 },
      clientHeight: { configurable: true, value: 120 },
    })
    queue.scrollTop = 24

    rerender(
      <ThreadWidgetStack
        working
        queuedMessages={[
          firstMessage,
          {
            clientCommandId: 'input-2',
            role: 'user',
            text: '最新消息',
            sequence: '2',
          },
        ]}
      />,
    )

    expect(queue.scrollTop).toBe(240)
  })

  it('does not change the scroll position when the queue does not overflow', () => {
    const firstMessage = {
      clientCommandId: 'input-1',
      role: 'user' as const,
      text: '第一条消息',
      sequence: '1',
    }
    const { rerender } = render(
      <ThreadWidgetStack working queuedMessages={[firstMessage]} />,
    )
    const queue = screen.getByRole('list', { name: '等待处理的消息' })
    Object.defineProperties(queue, {
      scrollHeight: { configurable: true, value: 100 },
      clientHeight: { configurable: true, value: 120 },
    })
    queue.scrollTop = 12

    rerender(
      <ThreadWidgetStack
        working
        queuedMessages={[
          firstMessage,
          {
            clientCommandId: 'input-2',
            role: 'user',
            text: '最新消息',
            sequence: '2',
          },
        ]}
      />,
    )

    expect(queue.scrollTop).toBe(12)
  })
})
