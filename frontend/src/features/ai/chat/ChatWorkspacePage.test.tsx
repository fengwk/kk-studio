import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useEffect } from 'react'
import { MemoryRouter, Route, Routes, useNavigate } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ChatWorkspacePage } from '@/features/ai/chat/ChatWorkspacePage'
import { agentService } from '@/shared/api/agent-service'
import { chatService } from '@/shared/api/chat-service'
import { environmentService } from '@/shared/api/environment-service'
import { harnessService } from '@/shared/api/harness-service'
import {
  applyChatLayout,
  loadChatPaneState,
  saveChatPaneState,
} from '@/features/ai/chat/chat-pane-state'
import { ApiError } from '@/shared/api/client'
import type {
  HarnessBranchSettingsDTO,
  HarnessSessionEntryDTO,
  HarnessThreadCommandDTO,
  HarnessThreadDTO,
  HarnessThreadSnapshotDTO,
} from '@/shared/api/contracts/ai-runtime'
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
    listChats: vi.fn(),
    createChat: vi.fn(),
    getChat: vi.fn(),
    updateChat: vi.fn(),
    deleteChat: vi.fn(),
    listChatThreads: vi.fn(),
    createChatThread: vi.fn(),
    associateThread: vi.fn(),
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
    enqueueCommands: vi.fn(),
    updateThreadHead: vi.fn(),
    stopThread: vi.fn(),
    decideApproval: vi.fn(),
    createThreadRealtimeStream: vi.fn(),
  },
}))

const page = <T,>(results: T[]) => ({
  pageNumber: 1,
  pageSize: 50,
  totalCount: results.length,
  results,
})

const assistantAgent = {
  name: 'assistant',
  description: null,
  systemPrompt: null,
  model: 'minimax/MiniMax',
  variant: 'default',
  config: { tools: [], skills: [] },
  version: '0',
  createTime: null,
  updateTime: null,
}

const coderAgent = {
  name: 'coder',
  description: null,
  systemPrompt: null,
  model: 'minimax/MiniMax',
  variant: 'default',
  config: { tools: [], skills: [] },
  version: '0',
  createTime: null,
  updateTime: null,
}

const miniMaxModel = {
  providerName: 'minimax',
  name: 'MiniMax',
  description: null,
  config: {
    limit: { context: 128000, output: 8192 },
    abilities: { tools: true, reasoning: false, inputModalities: ['TEXT'] },
    pricing: {
      currency: 'USD',
      pricingTier: 'default',
      serviceTier: 'default',
      serviceTierMultiplier: 1,
    },
    defaultVariant: 'default',
    variants: [{ id: 'default', reasoningEffort: 'off' }],
  },
  version: '0',
  createTime: null,
  updateTime: null,
}

const readyEnvironments = [
  { name: 'local', ready: true, status: 'READY', lastSeen: null, tools: [], skills: [] },
  { name: 'remote', ready: true, status: 'READY', lastSeen: null, tools: [], skills: [] },
]

class FakeEventSource {
  close = vi.fn()
  addEventListener = vi.fn()
  removeEventListener = vi.fn()
}

function branchSettings(
  overrides: Partial<HarnessBranchSettingsDTO> = {},
): HarnessBranchSettingsDTO {
  return {
    environmentName: null,
    agentName: 'assistant',
    model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'default' },
    activeTools: [],
    ...overrides,
  }
}

function thread(overrides: Partial<HarnessThreadDTO> = {}): HarnessThreadDTO {
  return {
    threadId: 't1',
    sessionId: 's1',
    headEntryId: 'h1',
    yoloEnabled: false,
    nextCommandSequence: '1',
    revision: '0',
    status: 'IDLE',
    processing: false,
    branchSettings: branchSettings(),
    createTime: null,
    updateTime: null,
    ...overrides,
  }
}

function snapshot(
  currentThread: HarnessThreadDTO,
  extras: Partial<HarnessThreadSnapshotDTO> = {},
): HarnessThreadSnapshotDTO {
  return {
    revision: currentThread.revision,
    thread: currentThread,
    entries: [] as HarnessSessionEntryDTO[],
    queuedCommands: [] as HarnessThreadCommandDTO[],
    modelInvocation: null,
    toolInvocations: [],
    ...extras,
  }
}

function NavigateTo({ to }: { to: string }) {
  const navigate = useNavigate()
  useEffect(() => {
    navigate(to)
  }, [navigate, to])
  return null
}

function renderWorkspace(chatId = 'chat-1') {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  const utils = render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[`/chats/${chatId}`]}>
        <Routes>
          <Route path="/chats/:chatId" element={<ChatWorkspacePage />} />
          <Route path="/chats" element={<div>list</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
  return { queryClient, rerender: utils.rerender }
}

describe('ChatWorkspacePage', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    localStorage.clear()
    setLocale('zh-CN')
    vi.mocked(agentService.listAgents).mockResolvedValue(page([assistantAgent]))
    // 可解析的 catalog，使空面板能够 materialize 自身的 frozen draft。
    vi.mocked(agentService.listModels).mockResolvedValue(page([miniMaxModel]))
    vi.mocked(agentService.listProviders).mockResolvedValue(page([]))
    vi.mocked(environmentService.listEnvironments).mockResolvedValue(readyEnvironments)
    vi.mocked(chatService.getChat).mockResolvedValue({
      id: 'chat-1',
      title: 'Workspace',
      agentName: 'assistant',
      yoloEnabled: false,
      version: '1',
      createTime: null,
      updateTime: null,
    })
    vi.mocked(chatService.updateChat).mockResolvedValue({
      id: 'chat-1',
      title: 'Workspace',
      agentName: 'assistant',
      yoloEnabled: false,
      version: '2',
      createTime: null,
      updateTime: null,
    })
    vi.mocked(chatService.listChatThreads).mockResolvedValue([])
    vi.mocked(chatService.createChatThread).mockResolvedValue(
      snapshot(thread({ threadId: 't-new' })),
    )
    vi.mocked(harnessService.createThreadRealtimeStream).mockReturnValue(
      new FakeEventSource() as unknown as EventSource,
    )
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(snapshot(thread({})))
    vi.mocked(harnessService.enqueueCommands).mockResolvedValue([] as HarnessThreadCommandDTO[])
  })


  it('does not leak blank-pane draft state across Chats that reuse the same pane id', async () => {
    vi.mocked(chatService.getChat).mockImplementation(async (chatId) => ({
      id: chatId,
      title: chatId === 'chat-1' ? 'Chat A' : 'Chat B',
      agentName: 'assistant',
      yoloEnabled: false,
      version: '1',
      createTime: null,
      updateTime: null,
    }))
    vi.mocked(chatService.listChatThreads).mockImplementation(async (chatId) =>
      chatId === 'chat-1' ? [] : [],
    )
    saveChatPaneState('chat-1', {
      layout: 'single',
      focusedPaneId: 'pane-1',
      panes: [{ id: 'pane-1', threadId: null }],
      threadSort: 'recent',
    })
    saveChatPaneState('chat-2', {
      layout: 'single',
      focusedPaneId: 'pane-1',
      panes: [{ id: 'pane-1', threadId: null }],
      threadSort: 'recent',
    })
    const { queryClient, rerender } = renderWorkspace('chat-1')

    // 在 Chat A 的空面板 composer 中输入未发送文本。
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await userEvent.setup().click(composer)
    await userEvent.setup().type(composer, 'stale across chats')

    // 跳转到 Chat B（相同的 pane id 'pane-1'）：面板必须以全新的
    // composer 和 frozen draft 重新挂载——不能跨 Chat 复用状态。
    rerender(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={['/chats/chat-1']}>
          <Routes>
            <Route
              path="/chats/:chatId"
              element={
                <>
                  <NavigateTo to="/chats/chat-2" />
                  <ChatWorkspacePage />
                </>
              }
            />
            <Route path="/chats" element={<div>list</div>} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    )
    const freshComposer = await screen.findByLabelText('给 AI 发送消息')
    await waitFor(() => expect(freshComposer).toHaveValue(''))
    expect(screen.queryByDisplayValue('stale across chats')).not.toBeInTheDocument()
  })

  it('renders a blank pane with the Chat Agent and retains pane targets across layouts', async () => {
    const user = userEvent.setup()
    const seeded = applyChatLayout(loadChatPaneState('chat-1'), 'split-2')
    seeded.panes[0].threadId = 't1'
    saveChatPaneState('chat-1', seeded)

    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(
      snapshot(thread({ threadId: 't1', sessionId: 's1', headEntryId: 'h1', revision: '4' })),
    )

    renderWorkspace()
    expect(await screen.findByRole('heading', { name: 'Workspace' })).toBeInTheDocument()
    expect(document.querySelector('.chat-pane-grid.layout-split-2')).toBeTruthy()
    // 第二个 split 面板是空白的；标题文本是 locale 默认标题。
    expect(screen.getByRole('heading', { name: /新对话/ })).toBeInTheDocument()

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
    expect(await screen.findByRole('button', { name: /^assistant$/ })).toBeInTheDocument()
  })

  it('blank-pane Environment selection syncs the Chat default with version serialization', async () => {
    const user = userEvent.setup()
    let updateCalls = 0
    vi.mocked(chatService.updateChat).mockImplementation(async (_id, data) => {
      updateCalls += 1
      return {
        id: 'chat-1',
        title: 'Workspace',
        agentName: 'assistant',
        environmentName: data.environmentName ?? null,
        yoloEnabled: false,
        version: String(1 + updateCalls),
        createTime: null,
        updateTime: null,
      }
    })
    renderWorkspace()

    await screen.findByLabelText('给 AI 发送消息')
    await user.click(screen.getByRole('button', { name: /env:none/ }))
    const envModal = await screen.findByLabelText('选择 Environment')
    await user.click(within(envModal).getByRole('button', { name: /^local/ }))
    expect(
      screen.getByRole('button', { name: /env:local/ }),
    ).toBeInTheDocument()
    // 选中的名称异步同步为 Chat 默认值（版本化 CAS）；footer 使用面板本地草稿。
    await waitFor(() => expect(chatService.updateChat).toHaveBeenCalledTimes(1))
    expect(chatService.updateChat).toHaveBeenNthCalledWith(1, 'chat-1', {
      environmentName: 'local',
      expectedVersion: '1',
    })

    // 显式清空（无）同步为 Chat 默认 null。
    await user.click(screen.getByRole('button', { name: /env:local/ }))
    const envModalAgain = await screen.findByLabelText('选择 Environment')
    await user.click(within(envModalAgain).getByRole('button', { name: /\uff08\u65e0\uff09/ }))
    expect(
      screen.getByRole('button', { name: /env:none/ }),
    ).toBeInTheDocument()
    await waitFor(() => expect(chatService.updateChat).toHaveBeenCalledTimes(2))
    expect(chatService.updateChat).toHaveBeenNthCalledWith(2, 'chat-1', {
      environmentName: null,
      expectedVersion: '2',
    })
  })

  it('keeps bound pane Environments independent of Chat and each other', async () => {
    const state = applyChatLayout(loadChatPaneState('chat-1'), 'split-2')
    state.panes[0].threadId = 't-local'
    state.panes[1].threadId = 't-remote'
    saveChatPaneState('chat-1', state)
    vi.mocked(harnessService.getThreadSnapshot).mockImplementation(async (threadId) =>
      snapshot(
        thread({
          threadId,
          sessionId: `session-${threadId}`,
          headEntryId: `head-${threadId}`,
          branchSettings: branchSettings({
            environmentName: threadId === 't-local' ? 'local' : 'remote',
          }),
        }),
      ),
    )

    renderWorkspace()
    await waitFor(() => {
      const environmentButtons = screen.getAllByRole('button', { name: /env:/ })
      const labels = environmentButtons.map((button) => button.textContent)
      expect(labels).toEqual(expect.arrayContaining(['env:local', 'env:remote']))
    })
    expect(chatService.updateChat).not.toHaveBeenCalled()
  })

  it('serializes same-tick split-pane agent selections into a single Chat update', async () => {
    const user = userEvent.setup()
    const state = applyChatLayout(loadChatPaneState('chat-1'), 'split-2')
    saveChatPaneState('chat-1', state)
    vi.mocked(agentService.listAgents).mockResolvedValue(page([assistantAgent, coderAgent]))
    let updateCalls = 0
    vi.mocked(chatService.updateChat).mockImplementation(async (_id, data) => {
      updateCalls += 1
      return {
        id: 'chat-1',
        title: 'Workspace',
        agentName: data.agentName ?? 'assistant',
        yoloEnabled: false,
        version: String(1 + updateCalls),
        createTime: null,
        updateTime: null,
      }
    })

    renderWorkspace()
    await screen.findAllByLabelText('给 AI 发送消息')

    const agentButtons = screen.getAllByRole('button', { name: 'agent:assistant' })
    await user.click(agentButtons[0])
    await user.click(agentButtons[1])
    const selectionButtons = await screen.findAllByRole('button', { name: 'coder' })
    await user.click(selectionButtons[0])
    await user.click(selectionButtons[1])

    await waitFor(() => expect(chatService.updateChat).toHaveBeenCalledTimes(2))
    expect(chatService.updateChat).toHaveBeenNthCalledWith(1, 'chat-1', {
      agentName: 'coder',
      expectedVersion: '1',
    })
    expect(chatService.updateChat).toHaveBeenNthCalledWith(2, 'chat-1', {
      agentName: 'coder',
      expectedVersion: '2',
    })
    expect(chatService.createChatThread).not.toHaveBeenCalled()
    expect(harnessService.enqueueCommands).not.toHaveBeenCalled()
  })

  it('reselects a stale Chat Agent before creating the first Chat Thread', async () => {
    const user = userEvent.setup()
    const events: string[] = []
    vi.mocked(chatService.getChat).mockResolvedValue({
      id: 'chat-1',
      title: 'Workspace',
      agentName: 'missing',
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
        yoloEnabled: false,
        version: '2',
        createTime: null,
        updateTime: null,
      }
    })
    vi.mocked(chatService.createChatThread).mockImplementation(async () => {
      events.push('create-thread')
      return snapshot(thread({ threadId: 't-stale-agent' }))
    })
    vi.mocked(harnessService.enqueueCommands).mockImplementation(async () => {
      events.push('message')
      return [] as HarnessThreadCommandDTO[]
    })
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(
      snapshot(thread({ threadId: 't-stale-agent' })),
    )

    renderWorkspace()
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.type(composer, 'hello after Agent deletion')
    await user.click(screen.getByRole('button', { name: '发送消息' }))
    await user.click(await screen.findByRole('button', { name: /^assistant$/ }))

    // react-query 的 mutateAsync 会把 mutationFn 推迟到 microtask，因此空白面板的
    // 首次发送路径（create-thread）会在延迟的 updateChat mutation 触发前运行。
    await waitFor(() =>
      expect(events).toEqual(['create-thread', 'update-agent', 'message']),
    )
    expect(chatService.updateChat).toHaveBeenCalledWith('chat-1', {
      agentName: 'assistant',
      expectedVersion: '1',
    })
    // 首次发送 = createChatThread 返回 HarnessThreadSnapshotDTO；enqueue
    // 以该 snapshot 的 CAS，针对新 Thread 使用仅含 USER_MESSAGE 的 batch。
    expect(harnessService.enqueueCommands).toHaveBeenCalledWith(
      't-stale-agent',
      expect.objectContaining({
        expectedHeadEntryId: 'h1',
        expectedNextCommandSequence: '1',
        commands: [
          expect.objectContaining({ type: 'USER_MESSAGE', content: 'hello after Agent deletion' }),
        ],
      }),
    )
  })

  it('first send uses the pane-local Environment even when the Chat default update fails', async () => {
    const user = userEvent.setup()
    vi.mocked(chatService.updateChat).mockRejectedValue(new ApiError('conflict', 409, 'CONFLICT'))
    vi.mocked(chatService.createChatThread).mockResolvedValue(
      snapshot(thread({ threadId: 't-env-local' })),
    )
    vi.mocked(harnessService.enqueueCommands).mockResolvedValue([])
    renderWorkspace()

    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.click(screen.getByRole('button', { name: /env:none/ }))
    const envModal = await screen.findByLabelText('选择 Environment')
    await user.click(within(envModal).getByRole('button', { name: /^local/ }))
    expect(
      screen.getByRole('button', { name: /env:local/ }),
    ).toBeInTheDocument()

    // Chat 默认更新失败不影响本面板草稿与首次发送：ROOT branchSettings 使用面板本地值。
    await waitFor(() => expect(chatService.updateChat).toHaveBeenCalledTimes(1))
    await user.type(composer, 'hello with pane-local env')
    await user.click(screen.getByRole('button', { name: '发送消息' }))
    await waitFor(() => expect(chatService.createChatThread).toHaveBeenCalled())
    const createArg = vi.mocked(chatService.createChatThread).mock.calls.at(-1)![1]!
    expect(createArg.branchSettings.environmentName).toBe('local')
  })

  it('detaches a stale persisted thread when its snapshot is not found', async () => {
    const seeded = loadChatPaneState('chat-1')
    seeded.panes[0].threadId = 'missing-thread'
    saveChatPaneState('chat-1', seeded)
    vi.mocked(harnessService.getThreadSnapshot).mockRejectedValue(
      new ApiError('Thread not found', 404, 'NOT_FOUND'),
    )

    renderWorkspace()

    expect(await screen.findByRole('heading', { name: /新对话/ })).toBeInTheDocument()
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

  it('performs atomic createChatThread + enqueueCommands first send for a blank pane', async () => {
    const user = userEvent.setup()
    const order: string[] = []
    vi.mocked(chatService.createChatThread).mockImplementation(async () => {
      order.push('createChatThread')
      return snapshot(thread({ threadId: 't-new', headEntryId: 'e-root', nextCommandSequence: '1' }))
    })
    vi.mocked(harnessService.enqueueCommands).mockImplementation(async () => {
      order.push('message')
      return [] as HarnessThreadCommandDTO[]
    })
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(
      snapshot(thread({ threadId: 't-new' })),
    )

    renderWorkspace()
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.type(composer, 'first message')
    await user.click(screen.getByRole('button', { name: '发送消息' }))

    await waitFor(() => expect(order).toEqual(['createChatThread', 'message']))
    expect(chatService.createChatThread).toHaveBeenCalledWith(
      'chat-1',
      expect.objectContaining({
        title: 'Workspace',
        branchSettings: expect.objectContaining({
          agentName: 'assistant',
          environmentName: null,
        }),
        yoloEnabled: false,
      }),
    )
    expect(harnessService.enqueueCommands).toHaveBeenCalledWith(
      't-new',
      expect.objectContaining({
        expectedHeadEntryId: 'e-root',
        expectedNextCommandSequence: '1',
        commands: [
          expect.objectContaining({ type: 'USER_MESSAGE', content: 'first message' }),
        ],
      }),
    )
    const sentBatch = vi.mocked(harnessService.enqueueCommands).mock.calls[0]?.[1]
    expect(sentBatch?.commands[0]).not.toHaveProperty('role')
    await waitFor(() =>
      expect(screen.queryByRole('heading', { name: /新对话/ })).not.toBeInTheDocument(),
    )
  })

  it('does not infer Chat associations from persisted pane state', async () => {
    const state = loadChatPaneState('chat-1')
    state.panes[0].threadId = 'stored-1'
    state.panes[1].threadId = 'stored-1'
    state.panes[2].threadId = 'stored-2'
    saveChatPaneState('chat-1', state)
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(
      snapshot(thread({ threadId: 'stored-1' })),
    )

    renderWorkspace()
    expect(await screen.findByRole('heading', { name: 'Workspace' })).toBeInTheDocument()
    expect(chatService.associateThread).not.toHaveBeenCalled()

    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: '6' }))
    await user.click(screen.getByRole('button', { name: '1' }))
    await user.click(screen.getByRole('button', { name: '8' }))
    expect(chatService.associateThread).not.toHaveBeenCalled()
  })

  it('does not call the global thread list endpoint (chat-scoped list only)', async () => {
    renderWorkspace()
    await screen.findByRole('heading', { name: 'Workspace' })
    // harnessService 的全局列表方法已不再使用；Chat 作用域的 listChatThreads
    // 是唯一的 picker 数据源。
    expect(harnessService.listThreads).toBeUndefined()
    expect(harnessService.listSessions).toBeUndefined()
    expect(harnessService.listSessionEntries).toBeUndefined()
  })
})
