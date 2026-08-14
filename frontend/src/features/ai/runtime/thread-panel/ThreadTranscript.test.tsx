import { createRef } from 'react'
import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { AssistantMessageBlock } from '@/features/ai/runtime/thread-panel/messages/AssistantMessageBlock'
import { ThreadTranscript } from '@/features/ai/runtime/thread-panel/ThreadTranscript'
import { isVisibleDialogueMessage } from '@/features/ai/runtime/thread-panel/visibility'
import type { DialogueMessage } from '@/features/ai/runtime/thread-timeline-types'

describe('isVisibleDialogueMessage', () => {
  it('keeps thinking-only assistant messages visible', () => {
    const message: DialogueMessage = {
      id: 'a1',
      role: 'assistant',
      subjectEntryId: 'r1',
      text: '',
      thinking: 'plan',
      createdAt: '2026-07-18T00:00:00',
      status: 'done',
    }
    expect(isVisibleDialogueMessage(message)).toBe(true)
  })

  it('keeps whitespace-only completed assistant rows visible', () => {
    const message: DialogueMessage = {
      id: 'a2',
      role: 'assistant',
      subjectEntryId: 'r1',
      text: '   ',
      createdAt: '2026-07-18T00:00:00',
      status: 'done',
    }
    expect(isVisibleDialogueMessage(message)).toBe(true)
  })
})

describe('AssistantMessageBlock', () => {
  it('renders internal Markdown paragraphs', () => {
    render(
      <AssistantMessageBlock
        message={{
          id: 'a1',
          role: 'assistant',
          subjectEntryId: '1',
          text: '\n\n第一段\n\n第二段\n',
          createdAt: '2026-07-18T00:00:00',
          status: 'done',
        }}
      />,
    )

    // Markdown 将空行拆成独立段落。
    expect(screen.getByText('第一段')).toBeInTheDocument()
    expect(screen.getByText('第二段')).toBeInTheDocument()
  })

  it('keeps whitespace-only provider text in the copyable assistant shell', () => {
    const { container } = render(
      <AssistantMessageBlock
        message={{
          id: 'a2',
          role: 'assistant',
          subjectEntryId: '2',
          text: '  ',
          createdAt: '2026-07-18T00:00:00',
          status: 'done',
        }}
      />,
    )

    expect(container.querySelector('.thread-assistant-shell')).toBeInTheDocument()
    expect(container.querySelector('.thread-assistant-copy')).toBeInTheDocument()
  })
})

describe('ThreadTranscript', () => {
  it('renders a portable durable Entry event without a Thread controller or query dependency', () => {
    render(
      <ThreadTranscript
        messages={[
          {
            id: 'root',
            role: 'entry',
            kind: 'root',
            title: '会话开始',
            text: '已创建会话树根节点。',
            rawPayloadJson: '{}',
            subjectEntryId: 'root',
            createdAt: null,
            status: 'done',
          },
        ]}
        loading={false}
        error={null}
        bodyRef={createRef<HTMLDivElement>()}
      />,
    )

    expect(screen.getByText('会话开始')).toBeInTheDocument()
    expect(screen.getByText('已创建会话树根节点。')).toBeInTheDocument()
    expect(screen.getByText('查看原始 Entry')).toBeInTheDocument()
  })
})
