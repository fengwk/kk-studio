import { createRef } from 'react'
import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { ChatPanel } from '@/features/ai/ChatPanel'

describe('ChatPanel', () => {
  it('renders thread transcript footer without sidebar or tool-approval UX', () => {
    render(
      <ChatPanel
        timeline={{
          messages: [{ id: 'm1', role: 'user', text: 'hello', subjectEntryId: 'e1', createdAt: null }],
          queuedMessages: [],
          hasPendingInputs: false,
        }}
        runtimeLabels={{
          agentName: 'assistant',
          providerName: 'minimax',
          modelName: 'MiniMax',
          variantName: 'default',
        }}
        working={false}
        messagesLoading={false}
        messagesError={null}
        bodyRef={createRef<HTMLDivElement>()}
        draft=""
        pending={false}
        disabled={false}
        observability={{
          yolo: { enabled: true },
          usage: undefined,
          observabilityError: null,
          yoloPending: false,
          setYolo: vi.fn(),
        }}
        onDraftChange={vi.fn()}
        onSubmit={vi.fn()}
        onCommand={vi.fn()}
      />,
    )
    expect(screen.getByText('hello')).toBeInTheDocument()
    expect(screen.getByText(/assistant/)).toBeInTheDocument()
    expect(screen.queryByRole('complementary')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '允许' })).not.toBeInTheDocument()
  })
})
