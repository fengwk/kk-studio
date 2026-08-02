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
    rawPayloadJson: '{"entry":true}',
    subjectEntryId: `entry-${kind}`,
    createdAt: null,
    status: 'done',
  }
}

describe('EntryMessageBlock', () => {
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
    expect(screen.getAllByText('查看原始 Entry')).toHaveLength(3)
    expect(container.querySelector('[data-entry-kind="root"] svg')).toBeInTheDocument()
    expect(container.querySelector('[data-entry-kind="unsupported_message"] svg')).toBeInTheDocument()
    expect(container.querySelector('[data-entry-kind="unknown_entry"] svg')).toBeInTheDocument()
  })
})
