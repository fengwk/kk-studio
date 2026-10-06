import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { EntryMessageBlock } from '@/features/ai/runtime/thread-panel/messages/EntryMessageBlock'
import type { EntryEventDialogueMessage } from '@/features/ai/runtime/thread-timeline-types'

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
})
