import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { AssistantMessageBlock } from '@/features/ai/thread-panel/messages/AssistantMessageBlock'
import { isVisibleDialogueMessage } from '@/features/ai/thread-panel/visibility'
import type { DialogueMessage } from '@/features/ai/thread-events'

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

    expect(screen.getByText(/第一段/).textContent).toBe('第一段\n\n第二段')
  })
})
