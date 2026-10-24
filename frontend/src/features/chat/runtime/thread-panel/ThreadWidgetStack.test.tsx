import { act, render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ThreadWidgetStack } from '@/features/chat/runtime/thread-panel/ThreadWidgetStack'
import { formatThreadStatusLabel } from '@/features/chat/runtime/thread-panel/thread-status-format'
import { setLocale } from '@/shared/i18n'

describe('ThreadWidgetStack', () => {
  it('shows the generic working strip when working without custom retry copy', () => {
    render(
      <ThreadWidgetStack
        working
        queuedMessages={[{ idempotencyKey: 'input-1', role: 'user', text: '下一条消息', sequence: '1' }]}
      />,
    )

    expect(screen.getByText('执行中')).toBeInTheDocument()
    expect(screen.getByText('queued')).toBeInTheDocument()
    expect(screen.getByText('下一条消息')).toBeInTheDocument()
    expect(screen.queryByText('本次请求已停止；发送新消息可重新开始。')).not.toBeInTheDocument()
    const workingBox = screen.getByText('执行中').closest('.thread-working')
    const queueBox = screen.getByRole('list', { name: '等待处理的消息' })
    expect(workingBox).not.toBeNull()
    expect(workingBox?.nextElementSibling).toBe(queueBox)
    expect(workingBox).not.toContainElement(queueBox)
  })

  it('localizes the working strip copy (zh-CN 执行中 / en-US Working...)', () => {
    render(<ThreadWidgetStack working />)
    expect(screen.getByText('执行中')).toBeInTheDocument()
    act(() => setLocale('en-US'))
    expect(screen.getByText('Working...')).toBeInTheDocument()
    act(() => setLocale('zh-CN'))
  })

  it('hides the working strip when idle with no queue', () => {
    const { container } = render(<ThreadWidgetStack working={false} queuedMessages={[]} />)
    expect(container).toBeEmptyDOMElement()
  })

  it('scrolls an overflowing queue to the latest message when new input arrives', () => {
    const firstMessage = {
      idempotencyKey: 'input-1',
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
            idempotencyKey: 'input-2',
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
      idempotencyKey: 'input-1',
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
            idempotencyKey: 'input-2',
            role: 'user',
            text: '最新消息',
            sequence: '2',
          },
        ]}
      />,
    )

    expect(queue.scrollTop).toBe(12)
  })

  it('renders localized status text when working with workingLabel', () => {
    // 测试意图：当线程处于等待异步结果/运行中等 busy 状态时，若 working=true，ThreadWidgetStack 必须优先展示格式化后的本地化文案。
    const waitingLabel = formatThreadStatusLabel('WAITING')
    const { rerender } = render(
      <ThreadWidgetStack
        working
        workingLabel={waitingLabel}
        queuedMessages={[]}
      />,
    )
    expect(screen.getByText('等待异步结果')).toBeInTheDocument()

    const runningLabel = formatThreadStatusLabel('RUNNING')
    rerender(
      <ThreadWidgetStack
        working
        workingLabel={runningLabel}
        queuedMessages={[]}
      />,
    )
    expect(screen.getByText('运行中')).toBeInTheDocument()
  })

  it('keeps stack empty when not working even if workingLabel is provided', () => {
    // 测试意图：即使传入了 workingLabel，如果 working=false 且无队列消息，组件仍应处于空闲隐藏状态，不展示工作条。
    const label = formatThreadStatusLabel('WAITING')
    const { container } = render(
      <ThreadWidgetStack
        working={false}
        workingLabel={label}
        queuedMessages={[]}
      />,
    )
    expect(container).toBeEmptyDOMElement()
    expect(screen.queryByText('等待异步结果')).not.toBeInTheDocument()
  })

  it('展示只读待处理补充要求，支持中英 i18n 且无任何写/删重排操作入口', () => {
    // 测试意图：QUEUED CUSTOM_MESSAGE 派生的补充要求按只读呈现，展示真实文本并带有稳定 sequence，无删除重排等写交互。
    const customMessages = [
      {
        id: 'cmd-custom-1',
        sequence: '5',
        text: '请优先补充单元测试',
      },
    ]

    const { rerender } = render(
      <ThreadWidgetStack
        working
        queuedMessages={[]}
        queuedCustomMessages={customMessages}
      />,
    )

    expect(screen.getByText('待处理补充要求')).toBeInTheDocument()
    expect(screen.getByText('请优先补充单元测试')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /delete|remove|cancel|删除|取消/i })).not.toBeInTheDocument()

    // 验证稳定 sequence 属性
    const item = screen.getByText('请优先补充单元测试').closest('li')
    expect(item).toHaveAttribute('data-sequence', '5')

    // 验证中英切换
    act(() => setLocale('en-US'))
    expect(screen.getByText('Pending supplementary requirement')).toBeInTheDocument()
    expect(screen.getByText('请优先补充单元测试')).toBeInTheDocument()
    act(() => setLocale('zh-CN'))

    // 模拟应用后快照不再包含 QUEUED CUSTOM_MESSAGE
    rerender(
      <ThreadWidgetStack
        working
        queuedMessages={[]}
        queuedCustomMessages={[]}
      />,
    )
    expect(screen.queryByText('待处理补充要求')).not.toBeInTheDocument()
    expect(screen.queryByText('请优先补充单元测试')).not.toBeInTheDocument()
  })
})
