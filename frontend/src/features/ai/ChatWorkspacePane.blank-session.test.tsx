import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { BLANK_PANE_COMMANDS, ChatWorkspacePane } from '@/features/ai/ChatWorkspacePane'
import { chatService } from '@/shared/api/chat-service'
import { harnessService } from '@/shared/api/harness-service'
import { agentService } from '@/shared/api/agent-service'

vi.mock('@/shared/api/agent-service', () => ({
  agentService: {
    listAgents: vi.fn(),
    listModels: vi.fn(),
    listProviders: vi.fn(),
  },
}))
vi.mock('@/shared/api/chat-service', () => ({
  chatService: {
    listChatSessions: vi.fn(),
    attachChatSession: vi.fn(),
  },
}))
vi.mock('@/shared/api/harness-service', () => ({
  harnessService: {
    listSessionThreads: vi.fn(),
    createSession: vi.fn(),
    setThreadAgent: vi.fn(),
    submitThreadMessage: vi.fn(),
    getThread: vi.fn(),
    getSession: vi.fn(),
    listThreadEntries: vi.fn(),
    listThreadInputs: vi.fn(),
    listThreadEvents: vi.fn(),
    listThreadToolInvocations: vi.fn(),
    getThreadUsage: vi.fn(),
    listRootActivities: vi.fn(),
    listSessionTasks: vi.fn(),
    createThreadEventStream: vi.fn(),
  },
}))

class FakeEventSource {
  close = vi.fn()
  addEventListener = vi.fn()
}

const agents = [
  {
    id: 'a1',
    name: 'assistant',
    description: null,
    systemPrompt: null,
    modelId: 'm1',
    variant: 'default',
    config: {
      environmentName: null,
      tools: [],
      skills: [],
      allowedSubagents: [],
      executionPolicy: {},
    },
    createTime: null,
    updateTime: null,
  },
]

function renderBlankPane(overrides?: {
  onTargetChange?: (target: { sessionId?: string; threadId?: string }) => void
  onDefaultAgentChange?: (agentId: string) => Promise<void>
  defaultAgentId?: string | null
}) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  const onTargetChange = overrides?.onTargetChange ?? vi.fn()
  const onDefaultAgentChange = overrides?.onDefaultAgentChange ?? vi.fn(async () => undefined)
  render(
    <QueryClientProvider client={queryClient}>
      <ChatWorkspacePane
        chatId="chat-1"
        chat={{
          id: 'chat-1',
          title: 'C',
          defaultAgentId: overrides?.defaultAgentId === undefined ? 'a1' : overrides.defaultAgentId,
          version: 1,
          createTime: null,
          updateTime: null,
        }}
        agents={agents}
        pane={{ id: 'pane-1', target: {} }}
        focused
        sessionSort="recent"
        threadSort="recent"
        onFocus={() => undefined}
        onTargetChange={onTargetChange}
        onSessionSortChange={() => undefined}
        onThreadSortChange={() => undefined}
        onDefaultAgentChange={onDefaultAgentChange}
      />
    </QueryClientProvider>,
  )
  return { onTargetChange, onDefaultAgentChange }
}

describe('BlankComposerPane /session and agent error handling', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(agentService.listAgents).mockResolvedValue({
      pageNumber: 1,
      pageSize: 50,
      totalCount: agents.length,
      results: agents,
    })
    vi.mocked(harnessService.createThreadEventStream).mockReturnValue(new FakeEventSource() as EventSource)
  })

  it('exposes only the session slash command on blank panes', () => {
    expect(BLANK_PANE_COMMANDS.map((command) => command.id)).toEqual(['session'])
  })

  it('opens /session picker and replaces blank pane target with selected Session mainThreadId', async () => {
    const user = userEvent.setup()
    vi.mocked(chatService.listChatSessions).mockResolvedValue([
      {
        sessionId: 's-idle',
        title: 'Idle',
        mainThreadId: 't-idle',
        rootSessionId: 's-idle',
        parentSessionId: null,
        parentInvocationId: null,
        depth: 0,
        createTime: '2026-07-20T12:00:00Z',
        updateTime: '2026-07-20T12:00:00Z',
      },
      {
        sessionId: 's-running',
        title: 'Running',
        mainThreadId: 't-running-main',
        rootSessionId: 's-running',
        parentSessionId: null,
        parentInvocationId: null,
        depth: 0,
        createTime: '2026-07-19T12:00:00Z',
        updateTime: '2026-07-19T12:00:00Z',
      },
    ])
    vi.mocked(harnessService.listSessionThreads).mockImplementation(async (sessionId: string) => {
      if (sessionId === 's-running') {
        return [
          {
            threadId: 't-running-main',
            sessionId: 's-running',
            sessionTitle: 'Running',
            headEntryId: null,
            status: 'IDLE',
            inputSequence: 0,
            activeAgentDefinitionId: null,
            activeAgentName: null,
            modelId: null,
            variant: null,
            yoloEnabled: false,
            processing: false,
            createTime: null,
            updateTime: null,
          },
          {
            threadId: 't-running-secondary',
            sessionId: 's-running',
            sessionTitle: 'Running',
            headEntryId: null,
            status: 'RUNNING',
            inputSequence: 1,
            activeAgentDefinitionId: null,
            activeAgentName: null,
            modelId: null,
            variant: null,
            yoloEnabled: false,
            processing: true,
            createTime: null,
            updateTime: null,
          },
        ]
      }
      return [
        {
          threadId: 't-idle',
          sessionId: 's-idle',
          sessionTitle: 'Idle',
          headEntryId: null,
          status: 'IDLE',
          inputSequence: 0,
          activeAgentDefinitionId: null,
          activeAgentName: null,
          modelId: null,
          variant: null,
          yoloEnabled: false,
          processing: false,
          createTime: null,
          updateTime: null,
        },
      ]
    })

    const { onTargetChange } = renderBlankPane()
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.click(composer)
    await user.keyboard('/session{Enter}')

    expect(await screen.findByText('选择 Session')).toBeInTheDocument()
    // Running session (older) must sort above newer idle session.
    const items = await screen.findAllByRole('button', { name: /Running|Idle/ })
    expect(items[0]).toHaveTextContent('Running')
    expect(items[0]).toHaveTextContent('RUNNING')
    expect(items[1]).toHaveTextContent('Idle')

    await user.click(screen.getByRole('button', { name: /Running/ }))
    expect(onTargetChange).toHaveBeenCalledWith({
      sessionId: 's-running',
      threadId: 't-running-main',
    })
    // First-send path must not run for explicit session reuse.
    expect(harnessService.createSession).not.toHaveBeenCalled()
  })

  it('surfaces rejected default-agent update without unhandled rejection', async () => {
    const user = userEvent.setup()
    const onDefaultAgentChange = vi.fn(async () => {
      throw new Error('default agent update failed')
    })
    renderBlankPane({ defaultAgentId: null, onDefaultAgentChange })

    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.type(composer, 'hello need agent')
    await user.click(screen.getByRole('button', { name: '发送消息' }))
    expect(await screen.findByText('选择 Agent')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /assistant/ }))

    await waitFor(() => {
      expect(screen.getByText('default agent update failed')).toBeInTheDocument()
    })
    expect(onDefaultAgentChange).toHaveBeenCalledWith('a1')
    expect(harnessService.createSession).not.toHaveBeenCalled()
  })
})
