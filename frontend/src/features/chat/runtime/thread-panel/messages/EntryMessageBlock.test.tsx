import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { EntryMessageBlock } from '@/features/chat/runtime/thread-panel/messages/EntryMessageBlock'
import { MessageList } from '@/features/chat/runtime/thread-panel/messages/MessageList'
import { EntryBranchContext } from '@/features/chat/runtime/thread-panel/entry-branch-context'
import type { DialogueMessage, EntryEventDialogueMessage } from '@/features/chat/runtime/thread-timeline-types'

function event(
  kind: EntryEventDialogueMessage['kind'],
  title: string,
): EntryEventDialogueMessage {
  return {
    id: `entry-${kind}`,
    role: 'entry',
    kind,
    title,
    text: `${title} 的摘要`,
    subjectEntryId: `entry-${kind}`,
    createdAt: null,
    status: 'done',
  }
}

describe('EntryMessageBlock', () => {
  it('renders an empty assistant as one line while preserving its turn usage and branch action', () => {
    // 空输出仍是独立事实；关闭回合的统计及分支必须绑定真实 TURN_END。
    const messages: DialogueMessage[] = [
      {
        ...event('empty_message', '模型未返回可展示内容。'),
        id: 'entry:assistant-empty',
        subjectEntryId: 'assistant-empty',
        text: '',
      },
      {
        id: 'usage', role: 'meta', kind: 'turn_usage', subjectEntryId: 'assistant-empty',
        endEntryId: 'turn-end', text: '↑10 · ↓0 · R0 · W0 · — · cache 0.00% · 0 tok/s',
        turnUsage: {
          input: 10, output: 0, cacheRead: 0, cacheWrite: 0, reasoning: null,
          providerTotal: null, cost: null, decodeTokens: 0, decodeDurationMillis: 1000,
        },
        createdAt: 1000,
      },
    ]
    const request = vi.fn()
    const { container } = render(
      <EntryBranchContext.Provider value={request}>
        <MessageList messages={messages} />
      </EntryBranchContext.Provider>,
    )
    const empty = container.querySelector('[data-entry-kind="empty_message"]')
    expect(empty).toHaveClass('is-inline')
    expect(empty?.querySelectorAll('.thread-entry-title')).toHaveLength(1)
    expect(empty).toHaveTextContent('模型未返回可展示内容。')
    expect(empty).not.toHaveTextContent('ASSISTANT')
    expect(empty).not.toHaveTextContent('Entry')
    expect(empty?.querySelector('.thread-entry-text')).toBeNull()
    expect(empty?.querySelector('button')).toBeNull()
    expect(container.querySelector('[data-turn-end="turn-end"]')).toHaveTextContent('↑10 · ↓0')
    fireEvent.click(screen.getByTestId('thread-turn-end-branch'))
    expect(request).toHaveBeenCalledExactlyOnceWith('turn-end')
  })

  it('shows setting events as a single accessible line', () => {
    // 测试意图：配置变更只占一行，完整值保留在 title 属性以便悬停查看。
    const { container } = render(<EntryMessageBlock message={event('settings_change', '模型改为 p/m (high)')} />)
    expect(screen.getByText('模型改为 p/m (high)')).toHaveAttribute('title', '模型改为 p/m (high)')
    expect(container.querySelector('.thread-entry-text')).not.toBeInTheDocument()
    expect(container.querySelector('.thread-entry-payload')).not.toBeInTheDocument()
  })

  it('renders semantic Entry variants through the portable timeline contract', () => {
    const { container } = render(
      <>
        <EntryMessageBlock message={event('root', '会话开始')} />
        <EntryMessageBlock message={event('unsupported_message', '无法识别消息 Entry')} />
        <EntryMessageBlock message={event('unknown_entry', '未识别 Entry')} />
      </>,
    )

    expect(screen.getByText('会话开始')).toBeInTheDocument()
    expect(screen.getByText('无法识别消息 Entry')).toBeInTheDocument()
    expect(screen.getByText('未识别 Entry')).toBeInTheDocument()
    expect(container.querySelector('[data-entry-kind="root"] svg')).toBeInTheDocument()
    expect(container.querySelector('[data-entry-kind="unsupported_message"] svg')).toBeInTheDocument()
    expect(container.querySelector('[data-entry-kind="unknown_entry"] svg')).toBeInTheDocument()
  })

  it('never renders a conversation raw payload toggle', () => {
    // 测试意图：会话 timeline 的 raw toggle 已彻底移除；原始事实只在独立 Debug events 中查看。
    const { container } = render(
      <EntryMessageBlock message={event('unsupported_message', '无法识别消息 Entry')} />,
    )

    expect(container.querySelector('.thread-entry-payload')).toBeNull()
    expect(screen.queryByText('查看原始数据')).toBeNull()
    expect(screen.queryByText('View raw data')).toBeNull()
    expect(screen.getByText('无法识别消息 Entry 的摘要')).toBeInTheDocument()
  })

  it('renders FORK entries as system message cards with the fixed English notice', () => {
    const notice = 'This thread was forked. Earlier tool calls and background tasks will not resume or report here. Subagent thread IDs inherited from history are no longer valid.'
    const { container } = render(
      <EntryMessageBlock
        message={{
          ...event('fork', '已从历史切点复制为新会话'),
          text: notice,
          fork: { mode: 'SESSION', sourceEntryId: 'e-cut', sourceThreadId: 't-src' },
        }}
      />,
    )

    const card = container.querySelector('[data-entry-kind="fork"]')
    expect(card).toBeInTheDocument()
    expect(card).toHaveAttribute('data-fork-mode', 'SESSION')
    expect(screen.getByText('已从历史切点复制为新会话')).toBeInTheDocument()
    expect(screen.getByText(notice)).toBeInTheDocument()
  })
})
