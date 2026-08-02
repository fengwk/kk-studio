import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ChatWorkspacePage } from '@/features/ai/chat/ChatWorkspacePage'
import { agentService } from '@/shared/api/agent-service'
import { chatService } from '@/shared/api/chat-service'
import { environmentService } from '@/shared/api/environment-service'
import { harnessService } from '@/shared/api/harness-service'
import { applyChatLayout, loadChatPaneState, saveChatPaneState } from '@/features/ai/chat/chat-pane-state'
import { ApiError } from '@/shared/api/client'
import type { HarnessThreadDTO } from '@/shared/api/contracts/ai-runtime'
import type { ChatDTO } from '@/shared/api/contracts/ai-chat'
import { setLocale } from '@/shared/i18n'

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
    associateThread: vi.fn(async () => undefined),
    createChatThread: vi.fn(),
    listChatThreads: vi.fn(),
  },
}))
vi.mock('@/shared/api/environment-service', () => ({
  environmentService: {
    listEnvironments: vi.fn(),
  },
}))
vi.mock('@/shared/api/harness-service', () => ({
  harnessService: {
    getThreadSnapshot: vi.fn(),
    listSessions: vi.fn(),
    listThreads: vi.fn(),
    listSessionEntries: vi.fn(),
    createThreadRealtimeStream: vi.fn(),
    submitThreadMessage: vi.fn(),
    updateThreadHead: vi.fn(),
  },
}))

const page = <T,>(results: T[]) => ({ pageNumber: 1, pageSize: 50, totalCount: results.length, results })

const assistantAgent = {
  name: 'assistant',
  description: null,
  systemPrompt: null,
  model: 'minimax/MiniMax',
  variant: 'default',
  config: {
    tools: [],
    skills: [],
  },
  version: '0',
  createTime: null,
  updateTime: null,
}

class FakeEventSource {
  close = vi.fn()
  addEventListener = vi.fn()
}

function snapshot(thread: HarnessThreadDTO) {
  return {
    revision: thread.revision,
    thread,
    entries: [],
    inputs: [],
    modelInvocations: [],
    toolInvocations: [],
    openInteractions: [],
    usage: {
      scopeType: 'thread',
      scopeId: thread.threadId,
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
    },
  }
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
    setLocale('zh-CN')
    vi.mocked(agentService.listAgents).mockResolvedValue(page([assistantAgent]))
    vi.mocked(agentService.listModels).mockResolvedValue(page([]))
    vi.mocked(agentService.listProviders).mockResolvedValue(page([]))
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([
      { name: 'local', status: 'READY', lastSeen: null, tools: [], skills: [] },
    ])
    vi.mocked(chatService.getChat).mockResolvedValue({
      id: 'chat-1',
      title: 'Workspace',
      agentName: 'assistant',
      environmentName: null,
      yoloEnabled: false,
      version: '1',
      createTime: null,
      updateTime: null,
    })
    vi.mocked(chatService.updateChat).mockResolvedValue({
      id: 'chat-1',
      title: 'Workspace',
      agentName: 'assistant',
      environmentName: null,
      yoloEnabled: false,
      version: '2',
      createTime: null,
      updateTime: null,
    })
    vi.mocked(harnessService.createThreadRealtimeStream).mockReturnValue(new FakeEventSource() as EventSource)
  })

  it('renders a blank pane with the Chat Agent and retains pane targets across layouts', async () => {
    const user = userEvent.setup()
    const seeded = applyChatLayout(loadChatPaneState('chat-1'), 'split-2')
    seeded.panes[0].threadId = 't1'
    saveChatPaneState('chat-1', seeded)

    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(
      snapshot({
        threadId: 't1',
        sessionId: 's1',
        sessionTitle: 's',
        headEntryId: 'h1',
        executionEpoch: 4,
        revision: '0',
        status: 'IDLE',
        inputSequence: 0,
        processing: false,
        createTime: null,
        updateTime: null,
      }),
    )
    vi.mocked(harnessService.listThreads).mockResolvedValue({ items: [], nextCursor: null })
    vi.mocked(harnessService.listSessions).mockResolvedValue([])
    vi.mocked(harnessService.listSessionEntries).mockResolvedValue([])

    renderWorkspace()
    expect(await screen.findByRole('heading', { name: 'Workspace' })).toBeInTheDocument()
    expect(screen.queryByRole('combobox', { name: '语言' })).not.toBeInTheDocument()
    expect(document.querySelector('.chat-workspace .locale-selector')).not.toBeInTheDocument()
    expect(document.querySelector('.chat-pane-grid.layout-split-2')).toBeTruthy()
    expect(screen.getByText('新对话')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '6' }))
    expect(document.querySelector('.chat-pane-grid.layout-grid-6')).toBeTruthy()
    await user.click(screen.getByRole('button', { name: '1' }))
    expect(document.querySelector('.chat-pane-grid.layout-single')).toBeTruthy()
  })

  it('opens the Agent selector when the blank pane references a missing Agent', async () => {
    const user = userEvent.setup()
    vi.mocked(chatService.getChat).mockResolvedValue({
      id: 'chat-1',
      title: 'Workspace',
      agentName: 'missing',
      environmentName: null,
      yoloEnabled: false,
      version: '1',
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

  it('updates the visible Chat Environment with the exact nullable request shape', async () => {
    const user = userEvent.setup()
    renderWorkspace()

    await screen.findByLabelText('给 AI 发送消息')
    await user.click(screen.getByRole('button', { name: '环境：（无）' }))
    await user.click(screen.getByRole('button', { name: 'local' }))
    await waitFor(() =>
      expect(chatService.updateChat).toHaveBeenCalledWith('chat-1', {
        environmentName: 'local',
        expectedVersion: '1',
      }),
    )

    await user.click(screen.getByRole('button', { name: '环境：（无）' }))
    await user.click(
      within(screen.getByLabelText('选择 Environment')).getByRole('button', {
        name: /（无）/,
      }),
    )
    await waitFor(() =>
      expect(chatService.updateChat).toHaveBeenLastCalledWith('chat-1', {
        environmentName: null,
        expectedVersion: '1',
      }),
    )
  })

  it('reselects a stale Chat Agent before creating the first Chat Thread', async () => {
    const user = userEvent.setup()
    const events: string[] = []
    vi.mocked(chatService.getChat).mockResolvedValue({
      id: 'chat-1',
      title: 'Workspace',
      agentName: 'missing',
      environmentName: null,
      yoloEnabled: false,
      version: '1',
      createTime: null,
      updateTime: null,
    })
    vi.mocked(chatService.updateChat).mockImplementation(async () => {
      events.push('update-agent')
      return {
        id: 'chat-1',
        title: 'Workspace',
        agentName: 'assistant',
        environmentName: null,
        version: '2',
        createTime: null,
        updateTime: null,
      }
    })
    vi.mocked(chatService.createChatThread).mockImplementation(async () => {
      events.push('create-thread')
      return {
        threadId: 't-stale-agent',
        sessionId: 's-stale-agent',
        sessionTitle: null,
        headEntryId: 'e-root',
        executionEpoch: 1,
        revision: '0',
        status: 'IDLE',
        inputSequence: 0,
        processing: false,
        createTime: null,
        updateTime: null,
      }
    })
    vi.mocked(harnessService.submitThreadMessage).mockImplementation(async () => {
      events.push('message')
      return {
        inputId: 'i-stale-agent',
        threadId: 't-stale-agent',
        sequence: 1,
        inputType: 'USER_MESSAGE',
        payloadJson: '{}',
        clientMessageId: 'client-stale-agent',
        status: 'QUEUED',
        resolvedAt: null,
        createTime: null,
      }
    })
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(
      snapshot({
        threadId: 't-stale-agent',
        sessionId: 's-stale-agent',
        sessionTitle: null,
        headEntryId: 'e-root',
        executionEpoch: 1,
        revision: '0',
        status: 'IDLE',
        inputSequence: 1,
        processing: false,
        createTime: null,
        updateTime: null,
      }),
    )
    vi.mocked(harnessService.listThreads).mockResolvedValue({ items: [], nextCursor: null })
    vi.mocked(harnessService.listSessions).mockResolvedValue([])
    vi.mocked(harnessService.listSessionEntries).mockResolvedValue([])

    renderWorkspace()
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.type(composer, 'hello after Agent deletion')
    await user.click(screen.getByRole('button', { name: '发送消息' }))
    await user.click(await screen.findByRole('button', { name: 'assistant' }))

    await waitFor(() => expect(events).toEqual(['update-agent', 'create-thread', 'message']))
    expect(chatService.updateChat).toHaveBeenCalledWith('chat-1', {
      agentName: 'assistant',
      expectedVersion: '1',
    })
    expect(harnessService.submitThreadMessage).toHaveBeenCalledWith(
      't-stale-agent',
      expect.objectContaining({ agentName: 'assistant' }),
    )
  })

  it('blocks pane sends until an Agent update is confirmed and then sends the confirmed setting', async () => {
    const user = userEvent.setup()
    const state = applyChatLayout(loadChatPaneState('chat-1'), 'split-2')
    state.panes[0].threadId = 't1'
    state.panes[1].threadId = 't2'
    saveChatPaneState('chat-1', state)
    vi.mocked(agentService.listAgents).mockResolvedValue(
      page([
        assistantAgent,
        {
          name: 'coder',
          description: null,
          systemPrompt: null,
          model: 'minimax/MiniMax',
          variant: 'default',
          config: { tools: [], skills: [] },
          version: '0',
          createTime: null,
          updateTime: null,
        },
      ]),
    )
    vi.mocked(harnessService.getThreadSnapshot).mockImplementation(async (threadId) =>
      snapshot({
        threadId,
        sessionId: `session-${threadId}`,
        sessionTitle: null,
        headEntryId: `head-${threadId}`,
        executionEpoch: 1,
        revision: '0',
        status: 'IDLE',
        inputSequence: 0,
        processing: false,
        createTime: null,
        updateTime: null,
      }),
    )
    vi.mocked(harnessService.submitThreadMessage).mockResolvedValue({
      inputId: 'input-1',
      threadId: 't2',
      sequence: 1,
      inputType: 'USER_MESSAGE',
      payloadJson: '{}',
      clientMessageId: 'client-1',
      status: 'QUEUED',
      resolvedAt: null,
      createTime: null,
    })
    let currentChat = await vi.mocked(chatService.getChat)('chat-1')
    vi.mocked(chatService.getChat).mockImplementation(async () => currentChat)
    let resolveUpdate!: (chat: ChatDTO) => void
    const updatePromise = new Promise<ChatDTO>((resolve) => {
      resolveUpdate = resolve
    })
    vi.mocked(chatService.updateChat).mockReturnValue(updatePromise)

    renderWorkspace()
    const composers = await screen.findAllByLabelText('给 AI 发送消息')
    await user.type(composers[0], '/agent{Enter}')
    await user.click(await screen.findByRole('button', { name: 'coder' }))

    await waitFor(() => {
      expect(composers[0]).toBeDisabled()
      expect(composers[1]).toBeDisabled()
    })
    expect(harnessService.submitThreadMessage).not.toHaveBeenCalled()

    const updatedChat = { ...currentChat, agentName: 'coder', version: '2' }
    currentChat = updatedChat
    await act(async () => {
      resolveUpdate(updatedChat)
      await updatePromise
    })
    await waitFor(() => expect(composers[1]).not.toBeDisabled())
    await user.type(composers[1], 'confirmed agent{Enter}')

    await waitFor(() =>
      expect(harnessService.submitThreadMessage).toHaveBeenCalledWith(
        't2',
        expect.objectContaining({
          content: 'confirmed agent',
          agentName: 'coder',
        }),
      ),
    )
  })

  it('detaches a stale persisted thread when its snapshot is not found', async () => {
    const seeded = loadChatPaneState('chat-1')
    seeded.panes[0].threadId = 'missing-thread'
    saveChatPaneState('chat-1', seeded)
    vi.mocked(harnessService.getThreadSnapshot).mockRejectedValue(
      new ApiError('Thread not found', 404, 'NOT_FOUND'),
    )
    vi.mocked(harnessService.listThreads).mockResolvedValue({ items: [], nextCursor: null })

    renderWorkspace()

    expect(await screen.findByText('新对话')).toBeInTheDocument()
    expect(screen.queryByText('会话加载失败')).not.toBeInTheDocument()
    await waitFor(() => {
      expect(loadChatPaneState('chat-1').panes[0].threadId).toBeNull()
    })
  })

  it('keeps a persisted thread bound when its snapshot fails for a non-404 reason', async () => {
    const seeded = loadChatPaneState('chat-1')
    seeded.panes[0].threadId = 'temporarily-unavailable'
    saveChatPaneState('chat-1', seeded)
    vi.mocked(harnessService.getThreadSnapshot).mockRejectedValue(
      new ApiError('Service unavailable', 503, 'SERVICE_UNAVAILABLE'),
    )

    renderWorkspace()

    expect(await screen.findByText('会话加载失败')).toBeInTheDocument()
    expect(loadChatPaneState('chat-1').panes[0].threadId).toBe('temporarily-unavailable')
  })

  it('performs atomic createThread/message order for a blank pane', async () => {
    const user = userEvent.setup()
    const order: string[] = []
    vi.mocked(chatService.createChatThread).mockImplementation(async () => {
      order.push('createChatThread')
      return {
        threadId: 't-new',
        sessionId: 's-new',
        sessionTitle: null,
        headEntryId: 'e-root',
        executionEpoch: 1,
        revision: '0',
        status: 'IDLE',
        inputSequence: 0,
        processing: false,
        createTime: null,
        updateTime: null,
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
        resolvedAt: null,
        createTime: null,
      }
    })
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(
      snapshot({
        threadId: 't-new',
        sessionId: 's-new',
        sessionTitle: null,
        headEntryId: 'e-root',
        executionEpoch: 1,
        revision: '0',
        status: 'IDLE',
        inputSequence: 2,
        processing: false,
        createTime: null,
        updateTime: null,
      }),
    )
    vi.mocked(harnessService.listThreads).mockResolvedValue({ items: [], nextCursor: null })
    vi.mocked(harnessService.listSessions).mockResolvedValue([])
    vi.mocked(harnessService.listSessionEntries).mockResolvedValue([])

    renderWorkspace()
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.type(composer, 'first message')
    await user.click(screen.getByRole('button', { name: '发送消息' }))
    // Atomic Chat-scoped creation precedes the first message.
    await waitFor(() => expect(order).toEqual(['createChatThread', 'message']))
    expect(chatService.createChatThread).toHaveBeenCalledWith('chat-1')
    // The message is fenced by the epoch returned by atomic Thread creation.
    expect(harnessService.submitThreadMessage).toHaveBeenCalledWith('t-new', {
      content: 'first message',
      agentName: 'assistant',
      environmentName: null,
      yoloEnabled: false,
      clientMessageId: expect.any(String),
      expectedExecutionEpoch: 1,
    })
    // The pane binds to the atomically created Thread and leaves the blank state behind.
    await waitFor(() => expect(screen.queryByText('新对话')).not.toBeInTheDocument())
    await waitFor(() => expect(harnessService.getThreadSnapshot).toHaveBeenCalledWith('t-new'))
  })

  it('moves a failed first send to the created Thread and retries with the same clientMessageId', async () => {
    const user = userEvent.setup()
    const createdThread = {
      threadId: 't-replay',
      sessionId: 's-replay',
      sessionTitle: null,
      headEntryId: 'e-root',
      executionEpoch: 1,
      revision: '0',
      status: 'IDLE' as const,
      inputSequence: 0,
      processing: false,
      createTime: null,
      updateTime: null,
    }
    vi.mocked(chatService.createChatThread).mockResolvedValue(createdThread)
    vi.mocked(harnessService.submitThreadMessage)
      .mockRejectedValueOnce(new Error('first message failed'))
      .mockResolvedValueOnce({
        inputId: 'retry-input',
        threadId: 't-replay',
        sequence: 2,
        inputType: 'USER_MESSAGE',
        payloadJson: '{}',
        clientMessageId: 'cid-replay',
        status: 'QUEUED',
        resolvedAt: null,
        createTime: null,
      })
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(
      snapshot(createdThread),
    )
    vi.mocked(harnessService.listThreads).mockResolvedValue({ items: [], nextCursor: null })
    vi.mocked(harnessService.listSessions).mockResolvedValue([])
    vi.mocked(harnessService.listSessionEntries).mockResolvedValue([])

    renderWorkspace()
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.type(composer, 'retry me')
    await user.click(screen.getByRole('button', { name: '发送消息' }))

    await waitFor(() => expect(screen.queryByText('新对话')).not.toBeInTheDocument())
    expect(chatService.createChatThread).toHaveBeenCalledOnce()
    expect(await screen.findByDisplayValue('retry me')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '发送消息' }))
    await waitFor(() => expect(harnessService.submitThreadMessage).toHaveBeenCalledTimes(2))
    const calls = vi.mocked(harnessService.submitThreadMessage).mock.calls
    expect(calls[0][0]).toBe('t-replay')
    expect(calls[1][0]).toBe('t-replay')
    expect(calls[1][1].clientMessageId).toBe(calls[0][1].clientMessageId)
    expect(calls[1][1].content).toBe('retry me')
    expect(chatService.createChatThread).toHaveBeenCalledOnce()
  })

  it('does not infer Chat associations from persisted pane state', async () => {
    const user = userEvent.setup()
    const state = loadChatPaneState('chat-1')
    state.panes[0].threadId = 'stored-1'
    state.panes[1].threadId = 'stored-1'
    state.panes[2].threadId = 'stored-2'
    saveChatPaneState('chat-1', state)
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(
      snapshot({
        threadId: 'stored-1',
        sessionId: 's-stored',
        sessionTitle: 'Stored',
        headEntryId: 'h-stored',
        executionEpoch: 1,
        revision: '0',
        status: 'IDLE',
        inputSequence: 0,
        processing: false,
        createTime: null,
        updateTime: null,
      }),
    )

    renderWorkspace()
    expect(await screen.findByRole('heading', { name: 'Workspace' })).toBeInTheDocument()
    expect(chatService.associateThread).not.toHaveBeenCalled()

    await user.click(screen.getByRole('button', { name: '6' }))
    await user.click(screen.getByRole('button', { name: '1' }))
    await user.click(screen.getByRole('button', { name: '8' }))
    expect(chatService.associateThread).not.toHaveBeenCalled()
  })
})
