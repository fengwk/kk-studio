import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ChatWorkspacePane } from '@/features/ai/ChatWorkspacePane'
import { agentService } from '@/shared/api/agent-service'
import { chatService } from '@/shared/api/chat-service'
import { harnessService } from '@/shared/api/harness-service'

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
    updateChat: vi.fn(),
  },
}))
vi.mock('@/shared/api/harness-service', () => ({
  harnessService: {
    getThread: vi.fn(),
    getSession: vi.fn(),
    listSessionThreads: vi.fn(),
    listThreadEntries: vi.fn(),
    listThreadInputs: vi.fn(),
    listThreadEvents: vi.fn(),
    listThreadToolInvocations: vi.fn(),
    getThreadUsage: vi.fn(),
    listRootActivities: vi.fn(),
    listSessionTasks: vi.fn(),
    createThreadEventStream: vi.fn(),
    createThreadRealtimeStream: vi.fn(),
    setThreadAgent: vi.fn(),
    setThreadModel: vi.fn(),
    setThreadYolo: vi.fn(),
    submitThreadMessage: vi.fn(),
  },
}))

class FakeEventSource {
  close = vi.fn()
  addEventListener = vi.fn()
}

const page = <T,>(results: T[]) => ({ pageNumber: 1, pageSize: 50, totalCount: results.length, results })

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
  {
    id: 'a2',
    name: 'coder',
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

describe('ChatWorkspacePane commands', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(agentService.listAgents).mockResolvedValue(page(agents))
    vi.mocked(agentService.listModels).mockResolvedValue(page([{
      id: 'm1', providerId: 'p1', providerName: 'minimax', name: 'MiniMax', description: null,
      config: {
        limit: { context: 128000, output: 8192 },
        abilities: { tools: true, reasoning: false, inputModalities: ['TEXT'] },
        pricing: {
          currency: 'USD',
          pricingTier: 'default',
          serviceTier: 'default',
          serviceTierMultiplier: 1,
          version: 'v1',
          inputPerMillionTokens: 0,
          outputPerMillionTokens: 0,
          cacheReadPerMillionTokens: 0,
          cacheWritePerMillionTokens: 0,
          cacheWriteLongPerMillionTokens: 0,
          reasoningPerMillionTokens: 0,
        },
        defaultVariant: 'default',
        variants: [{ id: 'default' }],
      },
      createTime: null, updateTime: null,
    }]))
    vi.mocked(agentService.listProviders).mockResolvedValue(page([{
      id: 'p1', name: 'minimax', description: null, providerType: 'openai', baseUrl: null, configured: true,
      modelCallTimeoutMillis: 1, modelCallIdleTimeoutMillis: 1, createTime: null, updateTime: null,
    }]))
    vi.mocked(harnessService.createThreadEventStream).mockReturnValue(new FakeEventSource() as EventSource)
    vi.mocked(harnessService.createThreadRealtimeStream).mockReturnValue(new FakeEventSource() as EventSource)
    vi.mocked(harnessService.getThread).mockResolvedValue({
      threadId: 't1', sessionId: 's1', sessionTitle: 'S1', headEntryId: null, status: 'RUNNING',
      inputSequence: 1, activeAgentDefinitionId: 'a1', activeAgentName: 'assistant', modelId: 'm1',
      variant: 'default', yoloEnabled: false, processing: true, createTime: '2026-01-01T00:00:00Z',
      updateTime: '2026-01-02T00:00:00Z',
    })
    vi.mocked(harnessService.getSession).mockResolvedValue({
      sessionId: 's1', title: 'S1', mainThreadId: 't1', rootSessionId: 's1', parentSessionId: null,
      parentInvocationId: null, depth: 0, createTime: null, updateTime: null,
    })
    vi.mocked(harnessService.listSessionThreads).mockResolvedValue([
      {
        threadId: 't1', sessionId: 's1', sessionTitle: 'S1', headEntryId: null, status: 'RUNNING',
        inputSequence: 1, activeAgentDefinitionId: 'a1', activeAgentName: 'assistant', modelId: 'm1',
        variant: 'default', yoloEnabled: false, processing: true, createTime: '2026-01-01T00:00:00Z',
        updateTime: '2026-01-02T00:00:00Z',
      },
      {
        threadId: 't2', sessionId: 's1', sessionTitle: 'S1', headEntryId: null, status: 'IDLE',
        inputSequence: 0, activeAgentDefinitionId: 'a1', activeAgentName: 'assistant', modelId: 'm1',
        variant: 'default', yoloEnabled: false, processing: false, createTime: '2026-01-03T00:00:00Z',
        updateTime: '2026-01-01T00:00:00Z',
      },
    ])
    vi.mocked(chatService.listChatSessions).mockResolvedValue([
      {
        sessionId: 's1', title: 'S1', mainThreadId: 't1', rootSessionId: 's1', parentSessionId: null,
        parentInvocationId: null, depth: 0, createTime: '2026-01-01T00:00:00Z', updateTime: '2026-01-02T00:00:00Z',
      },
      {
        sessionId: 's2', title: 'S2', mainThreadId: 't9', rootSessionId: 's2', parentSessionId: null,
        parentInvocationId: null, depth: 0, createTime: '2026-01-04T00:00:00Z', updateTime: '2026-01-01T00:00:00Z',
      },
    ])
    vi.mocked(harnessService.listThreadEntries).mockResolvedValue([])
    vi.mocked(harnessService.listThreadInputs).mockResolvedValue([])
    vi.mocked(harnessService.listThreadEvents).mockResolvedValue([])
    vi.mocked(harnessService.listThreadToolInvocations).mockResolvedValue([])
    vi.mocked(harnessService.getThreadUsage).mockResolvedValue({
      scopeType: 'thread', scopeId: 't1', recordCount: 0, inputTokens: 0, outputTokens: 0,
      cacheReadTokens: 0, cacheWriteTokens: 0, cacheWriteLongTokens: 0, reasoningTokens: 0,
      providerTotalTokens: 0, cacheEligibleRecordCount: 0, cacheHitRecordCount: 0, cacheHitRatio: 0,
      tokenReadRatio: 0, unamortizedCacheWriteTokens: 0, costs: [],
    })
    vi.mocked(harnessService.listRootActivities).mockResolvedValue([])
    vi.mocked(harnessService.listSessionTasks).mockResolvedValue([])
    vi.mocked(harnessService.setThreadAgent).mockResolvedValue({
      inputId: 'i', threadId: 't1', sequence: 1, inputType: 'SET_AGENT', payloadJson: '{}',
      clientMessageId: 'c', status: 'QUEUED', appliedEntryId: null, resolvedAt: null,
      cancelledByStopId: null, createTime: null,
    })
    vi.mocked(harnessService.setThreadModel).mockResolvedValue({
      inputId: 'm', threadId: 't1', sequence: 2, inputType: 'SET_MODEL', payloadJson: '{}',
      clientMessageId: 'c', status: 'QUEUED', appliedEntryId: null, resolvedAt: null,
      cancelledByStopId: null, createTime: null,
    })
  })

  it('switches session/thread targets and queues setThreadAgent', async () => {
    const user = userEvent.setup()
    const onThreadChange = vi.fn()
    const onSessionSortChange = vi.fn()
    const onThreadSortChange = vi.fn()
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
    render(
      <QueryClientProvider client={queryClient}>
        <ChatWorkspacePane
          chatId="chat-1"
          chat={{ id: 'chat-1', title: 'C', defaultAgentId: 'a1', version: 1, createTime: null, updateTime: null }}
          agents={agents}
          pane={{ id: 'pane-1', threadId: 't1' }}
          focused
          sessionSort="recent"
          threadSort="recent"
          onFocus={() => undefined}
          onThreadChange={onThreadChange}
          onSessionSortChange={onSessionSortChange}
          onThreadSortChange={onThreadSortChange}
          onDefaultAgentChange={async () => undefined}
        />
      </QueryClientProvider>,
    )
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.click(composer)
    await user.keyboard('/session{Enter}')
    expect(await screen.findByText('选择 Session')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '创建时间' }))
    expect(onSessionSortChange).toHaveBeenCalledWith('created')
    await user.click(screen.getByRole('button', { name: /S2/ }))
    expect(onThreadChange).toHaveBeenCalledWith('t9')

    await user.click(screen.getByLabelText('给 AI 发送消息'))
    await user.keyboard('/thread{Enter}')
    expect(await screen.findByText('选择 Thread')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '最近更新' }))
    expect(onThreadSortChange).toHaveBeenCalledWith('recent')
    await user.click(screen.getByRole('button', { name: /t2/ }))
    expect(onThreadChange).toHaveBeenCalledWith('t2')

    await user.click(screen.getByLabelText('给 AI 发送消息'))
    await user.keyboard('/agent{Enter}')
    expect(await screen.findByText('选择 Agent')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /coder/ }))
    await waitFor(() => expect(harnessService.setThreadAgent).toHaveBeenCalled())
    expect(harnessService.setThreadAgent.mock.calls[0][0]).toBe('t1')
    expect(harnessService.setThreadAgent.mock.calls[0][1].agentDefinitionId).toBe('a2')

    await user.click(screen.getByLabelText('给 AI 发送消息'))
    await user.keyboard('/model{Enter}')
    expect(await screen.findByText('选择 Model')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '最近更新' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'minimax/MiniMax' }))
    await waitFor(() =>
      expect(harnessService.setThreadModel).toHaveBeenCalledWith(
        't1',
        expect.objectContaining({ modelId: 'm1', variant: 'default' }),
      ),
    )

    await user.click(screen.getByLabelText('给 AI 发送消息'))
    await user.keyboard('/variant{Enter}')
    expect(await screen.findByText('选择 Variant')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '最近更新' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /^default/ }))
    await waitFor(() =>
      expect(harnessService.setThreadModel).toHaveBeenLastCalledWith(
        't1',
        expect.objectContaining({ modelId: 'm1', variant: 'default' }),
      ),
    )
  })
})
