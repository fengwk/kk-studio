import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
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
    listSessionThreads: vi.fn(),
    listSessionEntries: vi.fn(),
    getThreadSnapshot: vi.fn(),
    compactThread: vi.fn(),
    getSystemPromptPreview: vi.fn(),
    setThreadYolo: vi.fn(),
    stopThread: vi.fn(),
    decideApproval: vi.fn(),
  },
}))

const assistantAgent = {
  name: 'assistant',
  description: null,
  systemPrompt: null,
  model: 'minimax/MiniMax',
  variant: 'default',
  config: { tools: [], skills: [], subagents: [] },
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
    yoloEnabled: false,
    nextCommandSequence: '1',
    version: '0',
    status: 'IDLE',
    processing: false,
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
  vi.mocked(environmentService.listEnvironments).mockResolvedValue([])
  vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(snapshot())
  vi.mocked(harnessService.listSessionEntries).mockResolvedValue([])
  vi.mocked(harnessService.acceptCommandBatch).mockResolvedValue(acceptedResponse())
  vi.mocked(harnessService.getSystemPromptPreview).mockResolvedValue({ text: '' })
})

describe('ChatWorkspacePage', () => {
  it('renders the Chat title and changes the shared pane layout', async () => {
    const user = userEvent.setup()
    renderWorkspace()
    expect(await screen.findByRole('heading', { name: 'Workspace' })).toBeInTheDocument()
    expect(document.querySelector('.chat-pane-grid.layout-single')).not.toBeNull()

    await user.click(screen.getByRole('button', { name: '2' }))
    expect(document.querySelector('.chat-pane-grid.layout-split-2')).not.toBeNull()
    expect(screen.getAllByLabelText('给 AI 发送消息')).toHaveLength(2)
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
    const [request] = vi.mocked(harnessService.acceptCommandBatch).mock.calls[0]!
    expect(request.owner).toEqual({ type: 'CHAT', id: CHAT_ID })
    expect(request.target.type).toBe('NEW_SESSION')
    expect(request.commands).toHaveLength(1)
    expect(request.commands[0]?.type).toBe('USER_MESSAGE')
    await waitFor(() =>
      expect(localStorage.getItem(
        `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
      )).toContain('BOUND_THREAD'),
    )
  })

  it('sends an existing BOUND_THREAD through the same single command-batches endpoint', async () => {
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

    await waitFor(() => expect(harnessService.acceptCommandBatch).toHaveBeenCalledTimes(1))
    const [request] = vi.mocked(harnessService.acceptCommandBatch).mock.calls[0]!
    expect(request.target).toMatchObject({
      type: 'THREAD',
      threadId: THREAD_ID,
      expectedHeadEntryId: 'head-1',
      expectedNextCommandSequence: '1',
    })
  })

  it('keeps independent target persistence for two visible Chat panes', async () => {
    const user = userEvent.setup()
    localStorage.setItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
      JSON.stringify({ kind: 'BOUND_THREAD', threadId: THREAD_ID }),
    )
    renderWorkspace()
    await screen.findByRole('heading', { name: 'Workspace' })
    await user.click(screen.getByRole('button', { name: '2' }))
    await waitFor(() => expect(screen.getAllByLabelText('给 AI 发送消息')).toHaveLength(2))
    expect(localStorage.getItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-2`,
    )).toContain('NEW_SESSION_DRAFT')
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
