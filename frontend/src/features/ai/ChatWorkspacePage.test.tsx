import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ChatWorkspacePage } from '@/features/ai/ChatWorkspacePage'
import { agentService } from '@/shared/api/agent-service'
import { chatService } from '@/shared/api/chat-service'
import { harnessService } from '@/shared/api/harness-service'
import { applyChatLayout, createDefaultChatPaneState, saveChatPaneState } from '@/features/ai/chat-pane-state'

vi.mock('@/shared/api/agent-service', () => ({
  agentService: {
    listAgents: vi.fn(),
    listModels: vi.fn(),
    listProviders: vi.fn(),
  },
}))
vi.mock('@/shared/api/chat-service', () => ({
  chatService: {
    getChat: vi.fn(),
    updateChat: vi.fn(),
    listChatSessions: vi.fn(),
    attachChatSession: vi.fn(),
  },
}))
vi.mock('@/shared/api/harness-service', () => ({
  harnessService: {
    createSession: vi.fn(),
    getSession: vi.fn(),
    listSessionThreads: vi.fn(),
    getThread: vi.fn(),
    listThreadEntries: vi.fn(),
    listThreadInputs: vi.fn(),
    listThreadEvents: vi.fn(),
    listThreadToolInvocations: vi.fn(),
    getThreadUsage: vi.fn(),
    listRootActivities: vi.fn(),
    listSessionTasks: vi.fn(),
    createThreadEventStream: vi.fn(),
    setThreadAgent: vi.fn(),
    submitThreadMessage: vi.fn(),
    setThreadYolo: vi.fn(),
  },
}))

const page = <T,>(results: T[]) => ({ pageNumber: 1, pageSize: 50, totalCount: results.length, results })

class FakeEventSource {
  close = vi.fn()
  addEventListener = vi.fn()
}

function renderWorkspace(chatId = 'chat-1') {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[`/chats/${chatId}`]}>
        <Routes>
          <Route path="/chats/:chatId" element={<ChatWorkspacePage />} />
          <Route path="/chats" element={<div>list</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('ChatWorkspacePage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    vi.mocked(agentService.listAgents).mockResolvedValue(
      page([
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
      ]),
    )
    vi.mocked(agentService.listModels).mockResolvedValue(page([]))
    vi.mocked(agentService.listProviders).mockResolvedValue(page([]))
    vi.mocked(chatService.getChat).mockResolvedValue({
      id: 'chat-1',
      title: 'Workspace',
      defaultAgentId: 'a1',
      version: 1,
      createTime: null,
      updateTime: null,
    })
    vi.mocked(chatService.updateChat).mockResolvedValue({
      id: 'chat-1',
      title: 'Workspace',
      defaultAgentId: 'a1',
      version: 2,
      createTime: null,
      updateTime: null,
    })
    vi.mocked(chatService.listChatSessions).mockResolvedValue([])
    vi.mocked(harnessService.createThreadEventStream).mockReturnValue(new FakeEventSource() as EventSource)
  })

  it('renders blank pane with chat default agent and switches layouts while retaining targets', async () => {
    const user = userEvent.setup()
    const seeded = applyChatLayout(createDefaultChatPaneState(), 'split-2')
    seeded.panes[0].threadId = 't1'
    saveChatPaneState('chat-1', seeded)

    vi.mocked(harnessService.getThread).mockResolvedValue({
      threadId: 't1',
      sessionId: 's1',
      sessionTitle: 's',
      headEntryId: null,
      status: 'IDLE',
      inputSequence: 0,
      activeAgentDefinitionId: 'a1',
      activeAgentName: 'assistant',
      modelId: 'm1',
      variant: 'default',
      yoloEnabled: false,
      processing: false,
      createTime: null,
      updateTime: null,
    })
    vi.mocked(harnessService.getSession).mockResolvedValue({
      sessionId: 's1',
      title: 's',
      mainThreadId: 't1',
      rootSessionId: 's1',
      parentSessionId: null,
      parentInvocationId: null,
      depth: 0,
      createTime: null,
      updateTime: null,
    })
    vi.mocked(harnessService.listSessionThreads).mockResolvedValue([])
    vi.mocked(harnessService.listThreadEntries).mockResolvedValue([])
    vi.mocked(harnessService.listThreadInputs).mockResolvedValue([])
    vi.mocked(harnessService.listThreadEvents).mockResolvedValue([])
    vi.mocked(harnessService.listThreadToolInvocations).mockResolvedValue([])
    vi.mocked(harnessService.getThreadUsage).mockResolvedValue({
      scopeType: 'thread',
      scopeId: 't1',
      recordCount: 0,
      inputTokens: 0,
      outputTokens: 0,
      cacheReadTokens: 0,
      cacheWriteTokens: 0,
      cacheWriteLongTokens: 0,
      reasoningTokens: 0,
      providerTotalTokens: 0,
      cacheEligibleRecordCount: 0,
      cacheHitRecordCount: 0,
      cacheHitRatio: 0,
      tokenReadRatio: 0,
      unamortizedCacheWriteTokens: 0,
      costs: [],
    })
    vi.mocked(harnessService.listRootActivities).mockResolvedValue([])
    vi.mocked(harnessService.listSessionTasks).mockResolvedValue([])

    renderWorkspace()
    expect(await screen.findByRole('heading', { name: 'Workspace' })).toBeInTheDocument()
    expect(document.querySelector('.chat-pane-grid.layout-split-2')).toBeTruthy()
    expect(screen.getByText('新对话')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '6' }))
    expect(document.querySelector('.chat-pane-grid.layout-grid-6')).toBeTruthy()
    await user.click(screen.getByRole('button', { name: '1' }))
    expect(document.querySelector('.chat-pane-grid.layout-single')).toBeTruthy()
  })

  it('opens agent selector when blank pane has missing default agent', async () => {
    const user = userEvent.setup()
    vi.mocked(chatService.getChat).mockResolvedValue({
      id: 'chat-1',
      title: 'Workspace',
      defaultAgentId: 'missing',
      version: 1,
      createTime: null,
      updateTime: null,
    })
    renderWorkspace()
    expect(await screen.findByText(/Agent 已删除\/缺失/)).toBeInTheDocument()
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.type(composer, 'hello world')
    await user.click(screen.getByRole('button', { name: '发送消息' }))
    expect(await screen.findByRole('button', { name: 'assistant' })).toBeInTheDocument()
  })

  it('performs first-send create/attach/setAgent/message order for blank pane with default agent', async () => {
    const user = userEvent.setup()
    const order: string[] = []
    vi.mocked(harnessService.createSession).mockImplementation(async () => {
      order.push('create')
      return {
        sessionId: 's-new',
        title: null,
        mainThreadId: 't-new',
        rootSessionId: 's-new',
        parentSessionId: null,
        parentInvocationId: null,
        depth: 0,
        createTime: null,
        updateTime: null,
      }
    })
    vi.mocked(chatService.attachChatSession).mockImplementation(async () => {
      order.push('attach')
      return {
        sessionId: 's-new',
        title: null,
        mainThreadId: 't-new',
        rootSessionId: 's-new',
        parentSessionId: null,
        parentInvocationId: null,
        depth: 0,
        createTime: null,
        updateTime: null,
      }
    })
    vi.mocked(harnessService.setThreadAgent).mockImplementation(async () => {
      order.push('agent')
      return {
        inputId: 'i1',
        threadId: 't-new',
        sequence: 1,
        inputType: 'SET_AGENT',
        payloadJson: '{}',
        clientMessageId: 'c1',
        status: 'QUEUED',
        appliedEntryId: null,
        resolvedAt: null,
        cancelledByStopId: null,
        createTime: null,
      }
    })
    vi.mocked(harnessService.submitThreadMessage).mockImplementation(async () => {
      order.push('message')
      return {
        inputId: 'i2',
        threadId: 't-new',
        sequence: 2,
        inputType: 'USER_MESSAGE',
        payloadJson: '{}',
        clientMessageId: 'c2',
        status: 'QUEUED',
        appliedEntryId: null,
        resolvedAt: null,
        cancelledByStopId: null,
        createTime: null,
      }
    })
    vi.mocked(harnessService.getThread).mockResolvedValue({
      threadId: 't-new',
      sessionId: 's-new',
      sessionTitle: null,
      headEntryId: null,
      status: 'IDLE',
      inputSequence: 2,
      activeAgentDefinitionId: 'a1',
      activeAgentName: 'assistant',
      modelId: 'm1',
      variant: 'default',
      yoloEnabled: false,
      processing: false,
      createTime: null,
      updateTime: null,
    })
    vi.mocked(harnessService.getSession).mockResolvedValue({
      sessionId: 's-new',
      title: null,
      mainThreadId: 't-new',
      rootSessionId: 's-new',
      parentSessionId: null,
      parentInvocationId: null,
      depth: 0,
      createTime: null,
      updateTime: null,
    })
    vi.mocked(harnessService.listSessionThreads).mockResolvedValue([])
    vi.mocked(harnessService.listThreadEntries).mockResolvedValue([])
    vi.mocked(harnessService.listThreadInputs).mockResolvedValue([])
    vi.mocked(harnessService.listThreadEvents).mockResolvedValue([])
    vi.mocked(harnessService.listThreadToolInvocations).mockResolvedValue([])
    vi.mocked(harnessService.getThreadUsage).mockResolvedValue({
      scopeType: 'thread',
      scopeId: 't-new',
      recordCount: 0,
      inputTokens: 0,
      outputTokens: 0,
      cacheReadTokens: 0,
      cacheWriteTokens: 0,
      cacheWriteLongTokens: 0,
      reasoningTokens: 0,
      providerTotalTokens: 0,
      cacheEligibleRecordCount: 0,
      cacheHitRecordCount: 0,
      cacheHitRatio: 0,
      tokenReadRatio: 0,
      unamortizedCacheWriteTokens: 0,
      costs: [],
    })
    vi.mocked(harnessService.listRootActivities).mockResolvedValue([])
    vi.mocked(harnessService.listSessionTasks).mockResolvedValue([])

    renderWorkspace()
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.type(composer, 'first message')
    await user.click(screen.getByRole('button', { name: '发送消息' }))
    await waitFor(() => expect(order).toEqual(['create', 'attach', 'agent', 'message']))
    expect(harnessService.createSession).toHaveBeenCalledWith({})
    expect(chatService.attachChatSession).toHaveBeenCalledWith('chat-1', { sessionId: 's-new' })
  })
})
