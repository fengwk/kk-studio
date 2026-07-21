import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ThreadWidgetStack } from '@/features/ai/thread-panel/ThreadWidgetStack'

describe('ThreadWidgetStack', () => {
  it('shows a stopped retry notice and restart queue without a misleading Working strip', () => {
    render(
      <ThreadWidgetStack
        working={false}
        stoppedNotice="本次请求已停止；发送新消息可重新开始。"
        queueLabel="等待重启"
        queuedMessages={[{ inputId: 'input-1', role: 'user', text: '下一条消息', sequence: 1 }]}
      />,
    )

    expect(screen.getByText('本次请求已停止；发送新消息可重新开始。')).toBeInTheDocument()
    expect(screen.getByText('等待重启')).toBeInTheDocument()
    expect(screen.queryByText('Working...')).not.toBeInTheDocument()
  })
})
