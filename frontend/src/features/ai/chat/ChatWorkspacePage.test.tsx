import { useEffect } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useNavigate } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ChatWorkspacePage } from '@/features/ai/chat/ChatWorkspacePage'
import { agentService } from '@/shared/api/agent-service'
import { chatService } from '@/shared/api/chat-service'
import { environmentService } from '@/shared/api/environment-service'
import { harnessService } from '@/shared/api/harness-service'
import type {
  HarnessSessionEntryDTO,
  HarnessThreadDTO,
  HarnessThreadSnapshotDTO,
} from '@/shared/api/contracts/ai-runtime'
import { setLocale } from '@/shared/i18n'

const CHAT_ID = 'chat-1'
const THREAD_ID = '11111111-2222-4333-8444-555555555555'

const { fakeApplicationEvents } = vi.hoisted(() => {
  const manager = { subscribe: vi.fn(() => () => undefined) }
  return { fakeApplicationEvents: { useApplicationEvents: () => manager } }
})

vi.mock('@/shared/app-events', () => ({
  useApplicationEvents: fakeApplicationEvents.useApplicationEvents,
}))
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
    listChatSessions: vi.fn(),
  },
}))
vi.mock('@/shared/api/environment-service', () => ({
  environmentService: {
    listEnvironments: vi.fn(),
  },
}))
vi.mock('@/shared/api/harness-service', () => ({
  harnessService: {
    acceptCommandBatch: vi.fn(),
    acceptThreadCommandBatch: vi.fn(),
    listSessionThreads: vi.fn(),
    listSessionEntries: vi.fn(),
    getThreadSnapshot: vi.fn(),
    compactThread: vi.fn(),
    setThreadYolo: vi.fn(),
    stopThread: vi.fn(),
    decideApproval: vi.fn(),
    previewProviderRequest: vi.fn(),
  },
}))

const assistantAgent = {
  name: 'assistant',
  description: null,
  systemPrompt: null,
  model: 'minimax/MiniMax',
  variant: 'default',
  config: { inheritParentEnvironment: true, tools: [], skills: [], subagents: [] },
  version: '0',
  createTime: null,
  updateTime: null,
}

const model = {
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
    variants: [{ id: 'default' }],
  },
  version: '0',
  createTime: null,
  updateTime: null,
}

function thread(overrides: Partial<HarnessThreadDTO> = {}): HarnessThreadDTO {
  return {
    /** Thread 名称（服务端权威必填非空）。 */
    name: 'thread-name',
    threadId: THREAD_ID,
    sessionId: 'session-1',
    headEntryId: 'head-1',
    parentThreadId: null,
    yoloPolicy: { mode: 'DISABLE', rootThreadId: null },
    nextCommandSequence: '1',
    version: '0',
    status: 'IDLE',
    processing: false,
    executionControl: 'RUNNABLE',
    branchSettings: {
      agentName: 'assistant',
      model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'default' },
      environmentName: null,
    },
    createTime: null,
    updateTime: null,
    ...overrides,
  }
}

function snapshot(currentThread = thread()): HarnessThreadSnapshotDTO {
  return {
    version: currentThread.version,
    thread: currentThread,
    entries: [],
    queuedCommands: [],
    modelInvocation: null,
    toolInvocations: [],
    modelAttemptFailures: [],
    manualCompaction: { available: false, disabledReason: 'not available' },
    stopReceipts: [],
  }
}

function acceptedResponse(currentThread = thread()) {
  const rootEntry: HarnessSessionEntryDTO = {
    entryId: 'root-1',
    sessionId: currentThread.sessionId,
    parentEntryId: null,
    entryType: 'ROOT',
    payloadJson: '{}',
    createTime: null,
  }
  return {
    session: { sessionId: currentThread.sessionId, createdAt: null },
    rootEntry,
    thread: currentThread,
    acceptedCommands: [],
    replayed: false,
  }
}

beforeEach(() => {
  vi.clearAllMocks()
  localStorage.clear()
  setLocale('zh-CN')
  vi.mocked(chatService.getChat).mockResolvedValue({
    id: CHAT_ID,
    title: 'Workspace',
    agentName: 'assistant',
    environment: null,
    yoloEnabled: false,
    version: '1',
    createTime: null,
    updateTime: null,
  })
  vi.mocked(agentService.listAgents).mockResolvedValue({
    pageNumber: 1,
    pageSize: 50,
    totalCount: 1,
    results: [assistantAgent],
  })
  vi.mocked(agentService.listModels).mockResolvedValue({
    pageNumber: 1,
    pageSize: 50,
    totalCount: 1,
    results: [model],
  })
  vi.mocked(chatService.listChatSessions).mockResolvedValue([{
    sessionId: 'session-1',
    name: 'Session One',
    createdAt: null,
    lastActivityAt: null,
    firstMessagePreview: null,
    threadCount: 1,
  }])
  vi.mocked(environmentService.listEnvironments).mockResolvedValue([])
  // 根控制区以已加载的 Thread 身份为准：快照必须回显请求的 threadId，
  // 否则面板会一直停留“身份未加载”而不暴露控制区。
  vi.mocked(harnessService.getThreadSnapshot).mockImplementation(async (threadId: string) => ({
    ...snapshot(),
    thread: { ...snapshot().thread, threadId },
  }))
  vi.mocked(harnessService.listSessionEntries).mockResolvedValue([])
  vi.mocked(harnessService.acceptCommandBatch).mockResolvedValue(acceptedResponse())
  vi.mocked(harnessService.acceptThreadCommandBatch).mockResolvedValue(acceptedResponse())
})

describe('ChatWorkspacePage', () => {
  it('renders the Chat title and changes the shared pane layout', async () => {
    const user = userEvent.setup()
    renderWorkspace()
    expect(await screen.findByRole('heading', { name: 'Workspace' })).toBeInTheDocument()
    expect(document.querySelector('.chat-pane-grid.layout-single')).not.toBeNull()

    const trigger = screen.getByRole('button', { name: '布局' })
    await user.click(trigger)
    await user.click(screen.getByRole('option', { name: '2' }))
    expect(document.querySelector('.chat-pane-grid.layout-split-2')).not.toBeNull()
    expect(screen.getAllByLabelText('给 AI 发送消息')).toHaveLength(2)

    await user.click(trigger)
    await user.click(screen.getByRole('option', { name: '5' }))
    expect(document.querySelector('.chat-pane-grid.layout-grid-5')).not.toBeNull()
    expect(screen.getAllByLabelText('给 AI 发送消息')).toHaveLength(5)
  })

  it('uses the shared AgentPane target store instead of duplicating target fields in Chat layout state', async () => {
    localStorage.setItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
      JSON.stringify({ kind: 'BOUND_THREAD', threadId: THREAD_ID }),
    )
    renderWorkspace()
    await waitFor(() =>
      expect(harnessService.getThreadSnapshot).toHaveBeenCalledWith(THREAD_ID),
    )
    const layout = localStorage.getItem(`kk-studio.chat-pane.${CHAT_ID}`)
    expect(layout).not.toContain('threadId')
    expect(layout).not.toContain('"target"')
  })

  it('creates a NEW_SESSION on first send and binds the pane to its response', async () => {
    const user = userEvent.setup()
    renderWorkspace()
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.type(composer, 'start a Chat thread')
    await user.click(screen.getByRole('button', { name: '发送消息' }))

    await waitFor(() => expect(harnessService.acceptCommandBatch).toHaveBeenCalledTimes(1))
    expect(harnessService.acceptThreadCommandBatch).not.toHaveBeenCalled()
    const [request] = vi.mocked(harnessService.acceptCommandBatch).mock.calls[0]!
    expect(request.owner).toEqual({ type: 'CHAT', chatId: CHAT_ID })
    expect(request.target.type).toBe('NEW_SESSION')
    expect(request.commands).toHaveLength(1)
    expect(request.commands[0]?.type).toBe('USER_MESSAGE')
    await waitFor(() =>
      expect(localStorage.getItem(
        `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
      )).toContain('BOUND_THREAD'),
    )
  })

  it('sends an existing BOUND_THREAD through the per-thread command-batches endpoint', async () => {
    const user = userEvent.setup()
    localStorage.setItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
      JSON.stringify({ kind: 'BOUND_THREAD', threadId: THREAD_ID }),
    )
    renderWorkspace()
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await waitFor(() => expect(harnessService.getThreadSnapshot).toHaveBeenCalledWith(THREAD_ID))
    await user.type(composer, 'continue')
    await user.click(screen.getByRole('button', { name: '发送消息' }))

    // 既有 Thread 走 per-thread 写入口，不再经过创建批次端点。
    await waitFor(() => expect(harnessService.acceptThreadCommandBatch).toHaveBeenCalledTimes(1))
    expect(harnessService.acceptCommandBatch).not.toHaveBeenCalled()
    const [threadId, request] = vi.mocked(harnessService.acceptThreadCommandBatch).mock.calls[0]!
    expect(threadId).toBe(THREAD_ID)
    expect(request).toMatchObject({
      expectedHeadEntryId: 'head-1',
      expectedNextCommandSequence: '1',
    })
    expect(Object.keys(request).sort()).toEqual([
      'commands',
      'expectedHeadEntryId',
      'expectedNextCommandSequence',
    ])
  })

  it('keeps independent target persistence for two visible Chat panes', async () => {
    const user = userEvent.setup()
    localStorage.setItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
      JSON.stringify({ kind: 'BOUND_THREAD', threadId: THREAD_ID }),
    )
    renderWorkspace()
    await screen.findByRole('heading', { name: 'Workspace' })
    const trigger = screen.getByRole('button', { name: '布局' })
    await user.click(trigger)
    await user.click(screen.getByRole('option', { name: '2' }))
    await waitFor(() => expect(screen.getAllByLabelText('给 AI 发送消息')).toHaveLength(2))
    expect(localStorage.getItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-2`,
    )).toContain('NEW_SESSION_DRAFT')
  })

  it('consumes deep-link thread query only once on initial target pane and does not rebind on focus change', async () => {
    // 测试意图：验证 ?thread= deep-link 仅由初始目标 pane 消费一次，多 pane 切换焦点不会重复绑定该 thread
    const user = userEvent.setup()
    localStorage.setItem(
      `kk-studio.chat-pane.${CHAT_ID}`,
      JSON.stringify({ layout: 'split-2', focusedPaneId: 'pane-1', panes: [{ id: 'pane-1' }, { id: 'pane-2' }] }),
    )
    const OTHER_THREAD = '22222222-3333-4444-5555-666666666666'
    localStorage.setItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-2`,
      JSON.stringify({ kind: 'BOUND_THREAD', threadId: OTHER_THREAD }),
    )
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={[`/chats/${CHAT_ID}?thread=${THREAD_ID}`]}>
          <Routes>
            <Route path="/chats/:chatId" element={<ChatWorkspacePage />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    )

    await screen.findByRole('heading', { name: 'Workspace' })
    // 根控制区在各自 Thread 身份加载完成后才出现，因此等待两个 pane 都就绪。
    await waitFor(() => {
      expect(screen.getAllByLabelText('给 AI 发送消息')).toHaveLength(2)
    })
    const composers = screen.getAllByLabelText('给 AI 发送消息')

    // 聚焦 pane-2
    await user.click(composers[1]!)

    // pane-2 保持其原有的 target，不会被 deep-link 的 THREAD_ID 覆盖
    expect(localStorage.getItem(`kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-2`)).toContain(OTHER_THREAD)
  })

  it('supports in-component navigation to new thread via searchParams and consumes it via callback', async () => {
    // 测试意图：验证同组件在 URL 导航至新 thread 时，当前 focused pane 正常消费并通过 callback 确认清理 query
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    const NEW_THREAD = '33333333-4444-5555-6666-777777777777'
    let testNavigate!: (to: string) => void
    function NavTestBridge() {
      const nav = useNavigate()
      useEffect(() => {
        testNavigate = nav
      }, [nav])
      return <ChatWorkspacePage />
    }

    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={[`/chats/${CHAT_ID}`]}>
          <Routes>
            <Route path="/chats/:chatId" element={<NavTestBridge />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    )

    await screen.findByRole('heading', { name: 'Workspace' })
    act(() => {
      testNavigate(`/chats/${CHAT_ID}?thread=${NEW_THREAD}`)
    })

    await waitFor(() => {
      expect(localStorage.getItem(`kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`)).toContain(NEW_THREAD)
    })
  })

  it('blocks deep-link target switch when pane has pending operation', async () => {
    // 测试意图：验证当 pane 处于在途操作（如 acceptCommandBatch 在途）时，
    // URL deep-link 触发 initialTarget 变更会被 hasPendingOperation 门禁拦截，
    // 不切换目标且不消费 URL query
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    const user = userEvent.setup()
    let resolveBatch!: () => void
    const pendingPromise = new Promise<{ thread: HarnessThreadDTO; entries: HarnessSessionEntryDTO[] }>((resolve) => {
      resolveBatch = () => resolve({ thread: thread(), entries: [] })
    })

    vi.mocked(harnessService.acceptCommandBatch).mockImplementation(() => pendingPromise)

    const NEW_THREAD = '44444444-5555-6666-7777-888888888888'
    let testNavigate!: (to: string) => void
    function NavTestBridge() {
      const nav = useNavigate()
      useEffect(() => {
        testNavigate = nav
      }, [nav])
      return <ChatWorkspacePage />
    }

    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={[`/chats/${CHAT_ID}`]}>
          <Routes>
            <Route path="/chats/:chatId" element={<NavTestBridge />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    )

    await screen.findByRole('heading', { name: 'Workspace' })
    const composer = await screen.findByLabelText('给 AI 发送消息')

    // 发送消息，使 acceptCommandBatch 进入在途挂起
    await user.type(composer, 'Hello{enter}')
    await waitFor(() => {
      expect(harnessService.acceptCommandBatch).toHaveBeenCalled()
    })

    // 此时尝试通过 deepLink 切换目标
    act(() => {
      testNavigate(`/chats/${CHAT_ID}?thread=${NEW_THREAD}`)
    })

    // 门禁拦截：目标未切换为 NEW_THREAD，仍保持原有目标
    expect(localStorage.getItem(`kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`)).not.toContain(NEW_THREAD)

    // 释放挂起请求
    act(() => {
      resolveBatch()
    })
  })

  it('isolates layout state across different chats during switching without polluting keys', async () => {
    // 测试意图：验证 chat A 更改为非默认布局（split-2）后切换至预设了不同布局（grid-4）的 chat B，
    // 再切回 chat A 时，两者的 localStorage 状态严格隔离，绝不将 chat A 的布局写入 chat B，也绝不反向污染
    const user = userEvent.setup()
    const CHAT_A = 'chat-A'
    const CHAT_B = 'chat-B'

    vi.mocked(chatService.getChat).mockImplementation(async (id: string) => ({
      id,
      title: `Workspace ${id}`,
      agentName: 'assistant',
      environment: null,
      yoloEnabled: false,
      version: '1',
      createTime: null,
      updateTime: null,
    }))

    // chat-B 预先存好 grid-4 布局
    localStorage.setItem(
      `kk-studio.chat-pane.${CHAT_B}`,
      JSON.stringify({
        layout: 'grid-4',
        focusedPaneId: 'pane-2',
        panes: Array.from({ length: 9 }, (_, i) => ({ id: `pane-${i + 1}` })),
      }),
    )

    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })

    let testNavigate!: (to: string) => void
    function SwitchTestBridge() {
      const nav = useNavigate()
      useEffect(() => {
        testNavigate = nav
      }, [nav])
      return <ChatWorkspacePage />
    }

    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={[`/chats/${CHAT_A}`]}>
          <Routes>
            <Route path="/chats/:chatId" element={<SwitchTestBridge />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    )

    // 确认 chat A 渲染
    expect(await screen.findByRole('heading', { name: 'Workspace chat-A' })).toBeInTheDocument()
    // chat A 默认是 single 布局，将其切换为 split-2
    const trigger = screen.getByRole('button', { name: '布局' })
    await user.click(trigger)
    await user.click(screen.getByRole('option', { name: '2' }))
    expect(document.querySelector('.chat-pane-grid.layout-split-2')).not.toBeNull()

    // 检查 chat A 的持久化确实更新为 split-2
    await waitFor(() => {
      const savedA = JSON.parse(localStorage.getItem(`kk-studio.chat-pane.${CHAT_A}`) || '{}')
      expect(savedA.layout).toBe('split-2')
    })

    // 切换到 chat B
    act(() => {
      testNavigate(`/chats/${CHAT_B}`)
    })

    // 确认 chat B 渲染，并且应用预存的 grid-4 布局
    expect(await screen.findByRole('heading', { name: 'Workspace chat-B' })).toBeInTheDocument()
    expect(document.querySelector('.chat-pane-grid.layout-grid-4')).not.toBeNull()

    // 关键断言：chat B 的持久化存储绝对不能被 chat A 的 split-2 覆盖，必须保持 grid-4
    const savedB = JSON.parse(localStorage.getItem(`kk-studio.chat-pane.${CHAT_B}`) || '{}')
    expect(savedB.layout).toBe('grid-4')
    expect(savedB.focusedPaneId).toBe('pane-2')

    // 切回 chat A
    act(() => {
      testNavigate(`/chats/${CHAT_A}`)
    })

    expect(await screen.findByRole('heading', { name: 'Workspace chat-A' })).toBeInTheDocument()
    expect(document.querySelector('.chat-pane-grid.layout-split-2')).not.toBeNull()

    // 再次断言两者的 key 绝未互相污染
    const finalA = JSON.parse(localStorage.getItem(`kk-studio.chat-pane.${CHAT_A}`) || '{}')
    const finalB = JSON.parse(localStorage.getItem(`kk-studio.chat-pane.${CHAT_B}`) || '{}')
    expect(finalA.layout).toBe('split-2')
    expect(finalB.layout).toBe('grid-4')
  })
})

function renderWorkspace(chatId = CHAT_ID) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[`/chats/${chatId}`]}>
        <Routes>
          <Route path="/chats/:chatId" element={<ChatWorkspacePage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('ChatWorkspacePage 新建分支路由', () => {
  const ROOT_ENTRY: HarnessSessionEntryDTO = {
    entryId: 'entry-1',
    sessionId: 'session-1',
    parentEntryId: null,
    entryType: 'ROOT',
    payloadJson: '{}',
    createTime: null,
  }
  const TURN_END_ENTRY: HarnessSessionEntryDTO = {
    entryId: 'entry-2',
    sessionId: 'session-1',
    parentEntryId: 'entry-1',
    entryType: 'TURN_END',
    payloadJson: JSON.stringify({ outcome: 'COMPLETED' }),
    createTime: null,
  }
  const MESSAGE_ENTRY: HarnessSessionEntryDTO = {
    entryId: 'entry-3',
    sessionId: 'session-1',
    parentEntryId: 'entry-2',
    entryType: 'MESSAGE',
    payloadJson: JSON.stringify({
      message: { role: 'USER', contents: [{ type: 'text', text: 'hello' }] },
    }),
    createTime: null,
  }

  function paneTargetKey(paneId: string) {
    return `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:${paneId}`
  }

  function seedLayout(layout: string, focusedPaneId = 'pane-1') {
    localStorage.setItem(
      `kk-studio.chat-pane.${CHAT_ID}`,
      JSON.stringify({
        layout,
        focusedPaneId,
        panes: Array.from({ length: 9 }, (_, index) => ({ id: `pane-${index + 1}` })),
      }),
    )
  }

  /** 绑定 pane 到既有 Thread，并让快照/entries 反映给定的分支结构。 */
  function bindPaneThread(
    paneId: string,
    entries: HarnessSessionEntryDTO[],
    headEntryId: string,
  ) {
    localStorage.setItem(
      paneTargetKey(paneId),
      JSON.stringify({ kind: 'BOUND_THREAD', threadId: THREAD_ID }),
    )
    vi.mocked(harnessService.getThreadSnapshot).mockImplementation(async (threadId: string) => ({
      ...snapshot(),
      thread: { ...snapshot().thread, threadId, headEntryId, sessionId: 'session-1' },
      entries,
    }))
    vi.mocked(harnessService.listSessionEntries).mockResolvedValue(entries)
  }

  async function openBranchDialogFromTree(user: ReturnType<typeof userEvent.setup>, paneIndex = 0) {
    const composers = await screen.findAllByLabelText('给 AI 发送消息')
    await user.click(composers[paneIndex]!)
    await user.keyboard('/history{Enter}')
    const rows = await waitFor(() => {
      const found = document.querySelectorAll('.history-tree-entry')
      expect(found.length).toBeGreaterThan(0)
      return found
    })
    // 已关闭 TURN_END 行（head 之前的最后一个边界）是合法分叉点。
    await user.click(rows[1] as HTMLElement)
    const fork = document.querySelector<HTMLButtonElement>('.history-tree-actions .btn-primary')!
    expect(fork).toBeEnabled()
    await user.click(fork)
    return await waitFor(() => {
      const dialog = document.querySelector<HTMLElement>('.new-branch-dialog')
      expect(dialog).not.toBeNull()
      return dialog as HTMLElement
    })
  }

  async function chooseDestination(user: ReturnType<typeof userEvent.setup>, position: number) {
    await user.click(document.querySelector<HTMLButtonElement>('.new-branch-dialog .ui-select-trigger')!)
    const options = await waitFor(() => {
      const found = [...document.querySelectorAll<HTMLButtonElement>('.ui-select-option')]
      expect(found.length).toBe(9)
      return found
    })
    await user.click(options.find((option) => option.textContent?.startsWith(String(position)))!)
  }

  async function confirmBranch(user: ReturnType<typeof userEvent.setup>) {
    const dialog = document.querySelector<HTMLElement>('.new-branch-dialog')!
    await user.click(dialog.querySelector<HTMLButtonElement>('button[type="submit"]')!)
  }

  it('opens the shared naming flow from /tree and routes the draft into a hidden pane', async () => {
    const user = userEvent.setup()
    bindPaneThread('pane-1', [ROOT_ENTRY, TURN_END_ENTRY, MESSAGE_ENTRY], 'entry-3')
    renderWorkspace()
    await screen.findByRole('heading', { name: 'Workspace' })
    expect(document.querySelector('.chat-pane-grid.layout-single')).not.toBeNull()

    const dialog = await openBranchDialogFromTree(user)
    // 名称是创建事实：对话框只做规范化提交，绝不预创建 Thread。
    await user.type(dialog.querySelector<HTMLInputElement>('.new-branch-name')!, '  branch-1  ')
    await chooseDestination(user, 3)
    await confirmBranch(user)

    // 隐藏的 pane-3 被显露（布局扩展到刚好容纳它），目标 pane 获得草稿并取得焦点。
    await waitFor(() => {
      expect(document.querySelector('.chat-pane-grid.layout-split-3')).not.toBeNull()
    })
    expect(document.querySelector('.chat-workspace-breadcrumb')?.getAttribute('data-focused-pane'))
      .toBe('pane-3')
    await waitFor(() => {
      expect(localStorage.getItem(paneTargetKey('pane-3')))
        .toContain('"threadName":"branch-1"')
    })
    expect(localStorage.getItem(paneTargetKey('pane-3'))).toContain('NEW_THREAD_DRAFT')
    expect(harnessService.acceptCommandBatch).not.toHaveBeenCalled()
    expect(document.querySelector('.new-branch-dialog')).toBeNull()
    // 顶栏面包屑跟随聚焦 pane 展示分支名（规范化后的权威名称）。
    await waitFor(() => {
      const branch = document.querySelector('[data-breadcrumb="branch"]')
      expect(branch?.textContent).toBe('branch-1')
    })
    expect(await screen.findAllByLabelText('给 AI 发送消息')).toHaveLength(3)
  })

  it('requires a second confirmation before overwriting the destination pane draft', async () => {
    const user = userEvent.setup()
    seedLayout('split-3')
    bindPaneThread('pane-1', [ROOT_ENTRY, TURN_END_ENTRY, MESSAGE_ENTRY], 'entry-3')
    renderWorkspace()
    await screen.findByRole('heading', { name: 'Workspace' })
    const composers = await screen.findAllByLabelText('给 AI 发送消息')
    await user.type(composers[2]!, 'pane three draft')
    // 目标 pane 的草稿摘要由 effect 上报；下面的确认要求就是它生效的判据
    // （若未上报，第一次提交会直接改写目标 pane 而不是要求确认）。

    const dialog = await openBranchDialogFromTree(user)
    await user.type(dialog.querySelector<HTMLInputElement>('.new-branch-name')!, 'branch-1')
    await chooseDestination(user, 3)
    await confirmBranch(user)

    // 第一次提交只要求确认覆盖，绝不静默改写目标 pane 的未发送草稿。
    await waitFor(() =>
      expect(document.querySelector('.new-branch-overwrite')).not.toBeNull())
    expect(document.querySelector('.new-branch-dialog')).not.toBeNull()
    expect(localStorage.getItem(paneTargetKey('pane-3'))).toContain('NEW_SESSION_DRAFT')

    await confirmBranch(user)
    await waitFor(() => {
      expect(localStorage.getItem(paneTargetKey('pane-3')))
        .toContain('"threadName":"branch-1"')
    })
    expect(document.querySelector('.new-branch-dialog')).toBeNull()
  })

  it('blocks a destination pane that has a pending operation', async () => {
    const user = userEvent.setup()
    seedLayout('split-3')
    bindPaneThread('pane-1', [ROOT_ENTRY, TURN_END_ENTRY, MESSAGE_ENTRY], 'entry-3')
    vi.mocked(harnessService.acceptCommandBatch).mockImplementation(
      () => new Promise(() => undefined),
    )
    renderWorkspace()
    await screen.findByRole('heading', { name: 'Workspace' })
    const composers = await screen.findAllByLabelText('给 AI 发送消息')
    await user.type(composers[2]!, 'pending message')
    await user.click(screen.getAllByRole('button', { name: '发送消息' })[2]!)
    await waitFor(() => expect(harnessService.acceptCommandBatch).toHaveBeenCalledTimes(1))

    const dialog = await openBranchDialogFromTree(user)
    await user.type(dialog.querySelector<HTMLInputElement>('.new-branch-name')!, 'branch-1')
    await chooseDestination(user, 3)
    await confirmBranch(user)

    await waitFor(() =>
      expect(document.querySelector('.new-branch-dialog .form-error-banner')).not.toBeNull())
    expect(document.querySelector('.new-branch-dialog')).not.toBeNull()
    expect(localStorage.getItem(paneTargetKey('pane-3'))).not.toContain('NEW_THREAD_DRAFT')
    expect(localStorage.getItem(paneTargetKey('pane-3'))).toContain('NEW_SESSION_DRAFT')
  })

  it('follows the focused pane in the topbar breadcrumb', async () => {
    const user = userEvent.setup()
    seedLayout('split-2')
    bindPaneThread('pane-1', [ROOT_ENTRY, TURN_END_ENTRY, MESSAGE_ENTRY], 'entry-3')
    renderWorkspace()
    await screen.findByRole('heading', { name: 'Workspace' })
    await waitFor(() => expect(screen.getAllByLabelText('给 AI 发送消息')).toHaveLength(2))
    // 绑定的 pane-1：Session 名称 + 分支名（Thread 名称）。
    await waitFor(() => {
      expect(document.querySelector('[data-breadcrumb="session"]')?.textContent).toBe('Session One')
      expect(document.querySelector('[data-breadcrumb="branch"]')?.textContent).toBe('thread-name')
    })

    const composers = screen.getAllByLabelText('给 AI 发送消息')
    await user.click(composers[1]!)
    // 未绑定的 pane-2 还没有 Session/分支身份：面包屑只剩 Chat 层级。
    await waitFor(() => {
      expect(document.querySelector('.chat-workspace-breadcrumb')?.getAttribute('data-focused-pane'))
        .toBe('pane-2')
    })
    expect(document.querySelector('[data-breadcrumb="session"]')).toBeNull()
    expect(document.querySelector('[data-breadcrumb="branch"]')).toBeNull()
  })

  // 回合 footer 的 GitBranch 由 usage-owner 的 MetaMessageBlock 渲染，它消费本切片
  // RootAgentPane 提供的 EntryBranchContext（null = 不渲染）；按钮到达 workspace 的
  // 请求形状与 /tree 完全一致，因此上面的用例同时覆盖两条入口的命名与目标流程。
})
