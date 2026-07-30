import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ThreadWidgetStack } from '@/features/ai/runtime/thread-panel/ThreadWidgetStack'

describe('ThreadWidgetStack', () => {
  it('shows the generic working strip when working without custom retry copy', () => {
    render(
      <ThreadWidgetStack
        working
        queuedMessages={[{ inputId: 'input-1', role: 'user', text: '下一条消息', sequence: 1 }]}
      />,
    )

    expect(screen.getByText('Working...')).toBeInTheDocument()
    expect(screen.getByText('queued')).toBeInTheDocument()
    expect(screen.getByText('下一条消息')).toBeInTheDocument()
    expect(screen.queryByText('本次请求已停止；发送新消息可重新开始。')).not.toBeInTheDocument()
  })

  it('hides the working strip when idle with no queue', () => {
    const { container } = render(<ThreadWidgetStack working={false} queuedMessages={[]} />)
    expect(container).toBeEmptyDOMElement()
  })
})
