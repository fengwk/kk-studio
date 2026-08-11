import { createRef } from 'react'
import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { ChatPanel } from '@/features/ai/runtime/ChatPanel'

describe('ChatPanel', () => {
  it('renders thread transcript footer without sidebar or tool-approval UX', () => {
    render(
      <ChatPanel
        labels={{
          agentName: 'assistant',
          providerName: 'minimax',
          modelName: 'MiniMax',
          variantName: 'default',
        }}
        transcript={{
          timeline: {
            messages: [{ id: 'm1', role: 'user', text: 'hello', subjectEntryId: 'e1', createdAt: null }],
            queuedMessages: [],
            hasPendingInputs: false,
          },
          bodyRef: createRef<HTMLDivElement>(),
          loading: false,
          error: null,
        }}
        composer={{
          parts: [],
          pending: false,
          disabled: false,
          onPartsChange: vi.fn(),
          onSubmit: vi.fn(),
          onCommand: vi.fn(),
        }}
        footer={{ yoloEnabled: true, usage: undefined }}
        activity={{ working: false }}
      />,
    )
    expect(screen.getByText('hello')).toBeInTheDocument()
    expect(screen.getByText(/assistant/)).toBeInTheDocument()
    expect(screen.queryByRole('complementary')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '允许' })).not.toBeInTheDocument()
  })
})