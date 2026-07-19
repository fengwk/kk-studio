import { createRef } from 'react'
import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { ChatPanel } from '@/features/ai/ChatPanel'

describe('ChatPanel', () => {
  it('renders thread transcript footer and permissions without sidebar', () => {
    render(
      <ChatPanel
        timeline={{
          messages: [{ id: 'm1', role: 'user', text: 'hello', subjectEntryId: 'e1', createdAt: null }],
          queuedMessages: [],
          runtimeContext: {},
          hasPendingInputs: false,
          hasLiveProjection: false,
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
          toolInvocations: [
            {
              id: 'tool-1',
              threadId: 't1',
              assistantEntryId: 'a',
              ordinal: 0,
              toolCallId: 'c1',
              toolName: 'bash',
              toolVersion: '1',
              targetType: 'PLATFORM',
              environmentId: null,
              argumentsJson: '{}',
              status: 'WAITING_APPROVAL',
              permissionAction: 'ASK',
              permissionDecision: null,
              deadlineAt: null,
              leaseOwner: null,
              leaseUntil: null,
              cancelRequestedAt: null,
              resultJson: null,
              errorMessage: null,
              createTime: null,
              startedAt: null,
              finishedAt: null,
              updateTime: null,
            },
          ],
          observabilityError: null,
          yoloPending: false,
          decisionPending: false,
          setYolo: vi.fn(),
          decideTool: vi.fn(),
        }}
        taskTimeline={{
          activities: [],
          taskTree: [],
          relayPermissions: [],
          taskTimelineError: null,
          taskTimelineLoading: false,
          permissionDecisionPending: false,
          decidePermission: vi.fn(),
        }}
        onDraftChange={vi.fn()}
        onSubmit={vi.fn()}
        onCommand={vi.fn()}
      />,
    )
    expect(screen.getByText('hello')).toBeInTheDocument()
    expect(screen.getByText(/assistant/)).toBeInTheDocument()
    expect(screen.queryByRole('complementary')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '允许' })).toBeInTheDocument()
  })
})
