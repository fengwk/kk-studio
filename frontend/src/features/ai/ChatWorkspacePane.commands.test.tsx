import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ChatWorkspacePane } from '@/features/ai/ChatWorkspacePane'
import { agentService } from '@/shared/api/agent-service'
import { ApiError } from '@/shared/api/client'
import { harnessService } from '@/shared/api/harness-service'
import type { HarnessSessionEntryDTO, HarnessThreadDTO } from '@/shared/api/contracts'

vi.mock('@/shared/api/agent-service', () => ({
  agentService: {
    listAgents: vi.fn(),
    listModels: vi.fn(),
    listProviders: vi.fn(),
  },
}))
vi.mock('@/shared/api/harness-service', () => ({
  harnessService: {
    getThread: vi.fn(),
    getSession: vi.fn(),
    listSessions: vi.fn(),
    listThreads: vi.fn(),
    listSessionEntries: vi.fn(),
    listThreadEntries: vi.fn(),
    listThreadInputs: vi.fn(),
    listThreadToolInvocations: vi.fn(),
    getThreadUsage: vi.fn(),
    createThreadRealtimeStream: vi.fn(),
    updateThreadHead: vi.fn(),
    setThreadAgent: vi.fn(),
    setThreadModel: vi.fn(),
    setThreadYolo: vi.fn(),
    submitThreadMessage: vi.fn(),
    stopThread: vi.fn(),
  },
}))

class FakeEventSource {
  close = vi.fn()
  addEventListener = vi.fn()
}

const page = <T,>(results: T[]) => ({ pageNumber: 1, pageSize: 50, totalCount: results.length, results })

function thread(overrides: Partial<HarnessThreadDTO>): HarnessThreadDTO {
  return {
    threadId: 't1',
    sessionId: 's1',
    sessionTitle: 'S1',
    headEntryId: 'e-assistant',
    executionEpoch: 5,
    status: 'IDLE',
    inputSequence: 1,
    activeAgentDefinitionId: 'a1',
    activeAgentName: 'assistant',
    modelId: 'm1',
    variant: 'default',
    yoloEnabled: false,
    processing: false,
    createTime: '2026-01-01T00:00:00Z',
    updateTime: '2026-01-02T00:00:00Z',
    ...overrides,
  }
}

function entry(
  entryId: string,
  parentEntryId: string | null,
  role: string,
  text: string,
): HarnessSessionEntryDTO {
  return {
    entryId,
    parentEntryId,
    entryType: 'MESSAGE',
    payloadJson: JSON.stringify({ message: { role, contents: [{ type: 'text', text }] } }),
    createTime: null,
  }
}

/** ROOT -> USER -> ASSISTANT, so the USER row has a parent and is a valid rebind target. */
function sessionEntries(sessionId: string): HarnessSessionEntryDTO[] {
  return [
    {
      entryId: `${sessionId}-root`,
      parentEntryId: null,
      entryType: 'ROOT',
      payloadJson: '{}',
      createTime: null,
    },
    entry(`${sessionId}-user`, `${sessionId}-root`, 'USER', `${sessionId} prompt`),
    entry(`${sessionId}-assistant`, `${sessionId}-user`, 'ASSISTANT', `${sessionId} reply`),
  ]
}

const agents = [
  {
    id: 'a1',
    name: 'assistant',
    description: null,
    systemPrompt: null,
    modelId: 'm1',
    variant: 'default',
    config: { environmentName: null, tools: [], skills: [] },
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
    config: { environmentName: null, tools: [], skills: [] },
    createTime: null,
    updateTime: null,
  },
]

function renderBoundPane(overrides?: { onThreadChange?: (threadId: string | null) => void }) {
  const onThreadChange = overrides?.onThreadChange ?? vi.fn()
  const onSessionSortChange = vi.fn()
  const onThreadSortChange = vi.fn()
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}>
      <ChatWorkspacePane
        chat={{ id: 'chat-1', title: 'C', defaultAgentId: 'a1', version: '1', createTime: null, updateTime: null }}
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
  return { onThreadChange, onSessionSortChange, onThreadSortChange }
}

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
    vi.mocked(harnessService.createThreadRealtimeStream).mockReturnValue(new FakeEventSource() as EventSource)
    vi.mocked(harnessService.getThread).mockResolvedValue(thread({}))
    vi.mocked(harnessService.getSession).mockResolvedValue({
      sessionId: 's1', title: 'S1', createTime: null, updateTime: null,
    })
    vi.mocked(harnessService.listSessions).mockResolvedValue([
      {
        sessionId: 's1', title: 'S1', createTime: '2026-01-01T00:00:00Z', updateTime: '2026-01-02T00:00:00Z',
      },
      {
        sessionId: 's2', title: 'S2', createTime: '2026-01-04T00:00:00Z', updateTime: '2026-01-01T00:00:00Z',
      },
    ])
    vi.mocked(harnessService.listThreads).mockResolvedValue([
      thread({}),
      thread({
        threadId: 't2', status: 'IDLE', inputSequence: 0, executionEpoch: 1,
        createTime: '2026-01-03T00:00:00Z', updateTime: '2026-01-01T00:00:00Z',
      }),
    ])
    vi.mocked(harnessService.listSessionEntries).mockImplementation(async (sessionId: string) =>
      sessionEntries(sessionId),
    )
    vi.mocked(harnessService.listThreadEntries).mockResolvedValue([])
    vi.mocked(harnessService.listThreadInputs).mockResolvedValue([])
    vi.mocked(harnessService.listThreadToolInvocations).mockResolvedValue([])
    vi.mocked(harnessService.getThreadUsage).mockResolvedValue({
      scopeType: 'thread', scopeId: 't1', recordCount: 0, inputTokens: 0, outputTokens: 0,
      cacheReadTokens: 0, cacheWriteTokens: 0, cacheWriteLongTokens: 0, reasoningTokens: 0,
      providerTotalTokens: 0, cacheEligibleRecordCount: 0, cacheHitRecordCount: 0, cacheHitRatio: 0,
      tokenReadRatio: 0, unamortizedCacheWriteTokens: 0, costs: [],
    })
    vi.mocked(harnessService.updateThreadHead).mockResolvedValue(thread({ executionEpoch: 6 }))
    vi.mocked(harnessService.setThreadAgent).mockResolvedValue({
      inputId: 'i', threadId: 't1', sequence: 1, inputType: 'SET_AGENT', payloadJson: '{}',
      clientMessageId: 'c', status: 'QUEUED', resolvedAt: null, createTime: null,
    })
    vi.mocked(harnessService.setThreadModel).mockResolvedValue({
      inputId: 'm', threadId: 't1', sequence: 2, inputType: 'SET_MODEL', payloadJson: '{}',
      clientMessageId: 'c', status: 'QUEUED', resolvedAt: null, createTime: null,
    })
  })

  it('queues epoch-fenced setThreadAgent / setThreadModel from the config pickers', async () => {
    const user = userEvent.setup()
    const { onThreadSortChange } = renderBoundPane()
    const composer = await screen.findByLabelText('给 AI 发送消息')

    await user.click(composer)
    await user.keyboard('/thread{Enter}')
    expect(await screen.findByText('选择 Thread')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '最近更新' }))
    expect(onThreadSortChange).toHaveBeenCalledWith('recent')
    await user.click(screen.getByRole('button', { name: /t2/ }))

    await user.click(screen.getByLabelText('给 AI 发送消息'))
    await user.keyboard('/agent{Enter}')
    expect(await screen.findByText('选择 Agent')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /coder/ }))
    await waitFor(() => expect(harnessService.setThreadAgent).toHaveBeenCalled())
    expect(harnessService.setThreadAgent.mock.calls[0][0]).toBe('t1')
    expect(harnessService.setThreadAgent.mock.calls[0][1]).toMatchObject({
      agentDefinitionId: 'a2',
      expectedExecutionEpoch: 5,
    })

    await user.click(screen.getByLabelText('给 AI 发送消息'))
    await user.keyboard('/model{Enter}')
    expect(await screen.findByText('选择 Model')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '最近更新' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'minimax/MiniMax' }))
    await waitFor(() =>
      expect(harnessService.setThreadModel).toHaveBeenCalledWith(
        't1',
        expect.objectContaining({ modelId: 'm1', variant: 'default', expectedExecutionEpoch: 5 }),
      ),
    )

    await user.click(screen.getByLabelText('给 AI 发送消息'))
    await user.keyboard('/variant{Enter}')
    expect(await screen.findByText('选择 Variant')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /^default/ }))
    await waitFor(() =>
      expect(harnessService.setThreadModel).toHaveBeenLastCalledWith(
        't1',
        expect.objectContaining({ modelId: 'm1', variant: 'default', expectedExecutionEpoch: 5 }),
      ),
    )
  })

  it('/thread only switches the pane target and never mutates a Thread', async () => {
    const user = userEvent.setup()
    const { onThreadChange } = renderBoundPane()
    const composer = await screen.findByLabelText('给 AI 发送消息')

    await user.click(composer)
    await user.keyboard('/thread{Enter}')
    expect(await screen.findByText('选择 Thread')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /t2/ }))

    expect(onThreadChange).toHaveBeenCalledWith('t2')
    expect(harnessService.updateThreadHead).not.toHaveBeenCalled()
    // Threads are listed globally, not per Session.
    expect(harnessService.listThreads).toHaveBeenCalled()
  })

  it('/tree rebinds the current Thread head with PUT /head and keeps the pane target', async () => {
    const user = userEvent.setup()
    const { onThreadChange } = renderBoundPane()
    const composer = await screen.findByLabelText('给 AI 发送消息')

    await user.click(composer)
    await user.keyboard('/tree{Enter}')
    expect(await screen.findByRole('dialog', { name: '历史分支' })).toBeInTheDocument()
    // /tree derives the Entry Tree from the current Thread's Session.
    await waitFor(() => expect(harnessService.listSessionEntries).toHaveBeenCalledWith('s1'))

    await user.click(await screen.findByRole('button', { name: /用户 · s1 prompt/ }))
    await user.click(screen.getByRole('button', { name: '从这里继续当前 Thread' }))

    // Selecting an editable USER Entry rewinds the head to its parent (ROOT) so the prompt
    // can be re-sent, and fences the write with the Thread's current epoch.
    await waitFor(() =>
      expect(harnessService.updateThreadHead).toHaveBeenCalledWith('t1', {
        headEntryId: 's1-root',
        expectedExecutionEpoch: 5,
      }),
    )
    // The editable source text is restored into the composer for re-submission.
    expect(await screen.findByDisplayValue('s1 prompt')).toBeInTheDocument()
    // Rebinding never creates a Thread and never re-targets the pane.
    expect(onThreadChange).not.toHaveBeenCalled()
  })

  it('/session picks a foreign Session then rebinds the same Thread across Sessions', async () => {
    const user = userEvent.setup()
    const { onThreadChange, onSessionSortChange } = renderBoundPane()
    const composer = await screen.findByLabelText('给 AI 发送消息')

    await user.click(composer)
    await user.keyboard('/session{Enter}')
    expect(await screen.findByText('选择 Session')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '创建时间' }))
    expect(onSessionSortChange).toHaveBeenCalledWith('created')

    await user.click(await screen.findByRole('button', { name: /S2/ }))
    // Selecting a Session opens *its* Entry Tree instead of jumping to a main Thread.
    expect(await screen.findByRole('dialog', { name: '历史分支' })).toBeInTheDocument()
    await waitFor(() => expect(harnessService.listSessionEntries).toHaveBeenCalledWith('s2'))

    await user.click(await screen.findByRole('button', { name: /助手 · s2 reply/ }))
    await user.click(screen.getByRole('button', { name: '从这里继续当前 Thread' }))

    // Cross-Session move keeps the same Thread and fences with its current epoch.
    await waitFor(() =>
      expect(harnessService.updateThreadHead).toHaveBeenCalledWith('t1', {
        headEntryId: 's2-assistant',
        expectedExecutionEpoch: 5,
      }),
    )
    expect(onThreadChange).not.toHaveBeenCalled()
  })

  it('refuses to open /session or /tree while the Thread is ACTIVE', async () => {
    const user = userEvent.setup()
    vi.mocked(harnessService.getThread).mockResolvedValue(thread({ status: 'RUNNING', processing: true }))
    renderBoundPane()
    const composer = await screen.findByLabelText('给 AI 发送消息')

    await user.click(composer)
    await user.keyboard('/tree{Enter}')
    expect(await screen.findByText(/当前 Thread 正在运行，无法重定位/)).toBeInTheDocument()
    expect(screen.queryByRole('dialog', { name: '历史分支' })).not.toBeInTheDocument()

    await user.click(screen.getByLabelText('给 AI 发送消息'))
    await user.keyboard('/session{Enter}')
    expect(screen.queryByText('选择 Session')).not.toBeInTheDocument()
    expect(harnessService.updateThreadHead).not.toHaveBeenCalled()
  })

  it('surfaces a 409 from PUT /head inside the history panel instead of closing it', async () => {
    const user = userEvent.setup()
    vi.mocked(harnessService.updateThreadHead).mockRejectedValue(
      new ApiError('expected execution epoch mismatch', 409),
    )
    renderBoundPane()
    const composer = await screen.findByLabelText('给 AI 发送消息')

    await user.click(composer)
    await user.keyboard('/tree{Enter}')
    await user.click(await screen.findByRole('button', { name: /用户 · s1 prompt/ }))
    await user.click(screen.getByRole('button', { name: '从这里继续当前 Thread' }))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('无法重定位 Thread')
    expect(alert).toHaveTextContent('expected execution epoch mismatch')
    // The panel stays open so the user can retry after the Thread refreshes.
    expect(screen.getByRole('dialog', { name: '历史分支' })).toBeInTheDocument()
  })
})
