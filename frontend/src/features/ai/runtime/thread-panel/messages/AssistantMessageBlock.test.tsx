import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { AssistantMessageBlock } from '@/features/ai/runtime/thread-panel/messages/AssistantMessageBlock'
import type { TextDialogueMessage } from '@/features/ai/runtime/thread-timeline-types'

function message(overrides: Partial<TextDialogueMessage> = {}): TextDialogueMessage {
  return {
    id: 'msg-1',
    role: 'assistant',
    subjectEntryId: 'entry-1',
    text: 'partial answer',
    thinking: '',
    status: 'done',
    createdAt: null,
    ...overrides,
  }
}

describe('AssistantMessageBlock', () => {
  it('renders text and thinking content for normal assistant turns', () => {
    render(
      <AssistantMessageBlock
        message={message({ text: 'final answer', thinking: 'why', aborted: false })}
      />,
    )
    expect(screen.getByText('final answer')).toBeInTheDocument()
    expect(screen.getByText('why')).toBeInTheDocument()
    expect(screen.queryByText('已停止')).not.toBeInTheDocument()
  })

  it('renders the 已停止 affordance and applies the aborted treatment when aborted is true', () => {
    const { container } = render(
      <AssistantMessageBlock
        message={message({ text: 'partial answer', aborted: true })}
      />,
    )
    expect(screen.getByText('已停止')).toBeInTheDocument()
    expect(container.querySelector('.thread-turn-assistant.aborted')).toBeInTheDocument()
  })

  it('does not show the 已停止 affordance for non-aborted assistant turns', () => {
    render(<AssistantMessageBlock message={message({ aborted: false })} />)
    expect(screen.queryByText('已停止')).not.toBeInTheDocument()
  })
})
