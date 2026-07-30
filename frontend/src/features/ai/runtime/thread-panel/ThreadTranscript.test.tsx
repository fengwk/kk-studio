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

  it('hides empty completed assistant rows', () => {
    const message: DialogueMessage = {
      id: 'a2',
      role: 'assistant',
      subjectEntryId: 'r1',
      text: '   ',
      createdAt: '2026-07-18T00:00:00',
      status: 'done',
    }
    expect(isVisibleDialogueMessage(message)).toBe(false)
  })
})

describe('AssistantMessageBlock', () => {
  it('removes provider boundary whitespace while preserving internal paragraphs', () => {
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

    // Markdown 将空行拆成段落；外层 trim 去掉边界空白，段内内容仍在
    expect(screen.getByText('第一段')).toBeInTheDocument()
    expect(screen.getByText('第二段')).toBeInTheDocument()
  })
})

describe('ThreadTranscript', () => {
  it('renders a portable durable Entry event without a Thread controller or query dependency', () => {
    render(
      <ThreadTranscript
        messages={[
          {
            id: 'config',
            role: 'entry',
            kind: 'runtime_config',
            title: '运行配置已记录',
            text: 'Agent：reviewer\n模型：minimax/MiniMax-M2.7 · high',
            rawPayloadJson: '{"agent":{"name":"reviewer"}}',
            subjectEntryId: 'config',
            createdAt: null,
            status: 'done',
          },
        ]}
        loading={false}
        error={null}
        bodyRef={createRef<HTMLDivElement>()}
      />,
    )

    expect(screen.getByText('运行配置已记录')).toBeInTheDocument()
    expect(screen.getByText(/MiniMax-M2.7/)).toBeInTheDocument()
    expect(screen.getByText('查看原始 Entry')).toBeInTheDocument()
  })
})
