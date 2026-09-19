import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, renderHook, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AgentPane } from '@/features/ai/runtime/AgentPane'
import {
  branchDraftFromEntry,
  branchDraftFromEntryPath,
  sessionSelectionItem,
  threadSelectionItem,
  useAgentPaneController,
} from '@/features/ai/runtime/useAgentPaneController'
import type { BranchDraft } from '@/features/ai/chat/branch-draft'
import { createTextPart } from '@/features/ai/composer/composer-parts'
import { agentService } from '@/shared/api/agent-service'
import { chatService } from '@/shared/api/chat-service'
import { listCanvasSessions } from '@/shared/api/studio-service'
import { ApiError } from '@/shared/api/client'
import { harnessService } from '@/shared/api/harness-service'
import type {
  HarnessSessionDTO,
  HarnessSessionEntryDTO,
  HarnessThreadDTO,
  HarnessThreadSnapshotDTO,
} from '@/shared/api/contracts/ai-runtime'
import type { EnvironmentCardDTO } from '@/shared/api/contracts/ai-environment'
import type { ThreadCommand } from '@/features/ai/runtime/thread-panel/thread-commands'
import { queryKeys } from '@/shared/lib/query-keys'
import { setLocale } from '@/shared/i18n'

const CHAT_ID = 'chat-1'
const CANVAS_ID = 'canvas-1'
const THREAD_ID = '11111111-2222-4333-8444-555555555555'

const { fakeApplicationEvents } = vi.hoisted(() => {
  const manager = { subscribe: vi.fn(() => vi.fn()) }
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
vi.mock('@/shared/api/environment-service', () => ({
  environmentService: {
    listEnvironments: vi.fn(),
  },
}))
vi.mock('@/shared/api/chat-service', () => ({
  chatService: {
    listChatSessions: vi.fn(),
  },
}))
vi.mock('@/shared/api/studio-service', () => ({
  listCanvasSessions: vi.fn(),
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
    renameSession: vi.fn(),
    renameThread: vi.fn(),
  },
}))

const agents: AgentDefinitionDTO[] = [{
  name: 'assistant',
  description: null,
  systemPrompt: null,
  model: 'minimax/MiniMax',
  variant: 'default',
  environmentId: 'env-local-1',
  config: { tools: [], skills: [], subagents: [] },
  version: '0',
  createTime: null,
  updateTime: null,
}]

const models = [{
  providerName: 'minimax',
  name: 'MiniMax',
  description: null,
  config: {
    limit: { context: 128000, output: 8192 },
    abilities: { tools: true, reasoning: false, inputModalities: ['TEXT'] },
    pricing: {
      currency: 'USD',
      pricingTier: 'default',
      serviceTier: 'standard',
      serviceTierMultiplier: 1,
    },
    defaultVariant: 'default',
    variants: [{ id: 'default' }],
  },
  version: '0',
  createTime: null,
  updateTime: null,
}]

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

function snapshot(
  currentThread = thread(),
  overrides: Partial<HarnessThreadSnapshotDTO> = {},
): HarnessThreadSnapshotDTO {
  return {
    version: currentThread.version,
    thread: currentThread,
    entries: [],
    queuedCommands: [],
    modelInvocation: null,
    toolInvocations: [],
    modelAttemptFailures: [],
    manualCompaction: { available: false, disabledReason: 'not available' },
    ...overrides,
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
    session: {
      sessionId: currentThread.sessionId,
      /** Session 名称（服务端权威必填非空）。 */
      name: currentThread.name,
      createdAt: null,
    },
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
  vi.mocked(agentService.listAgents).mockResolvedValue({
    pageNumber: 1,
    pageSize: 50,
    totalCount: agents.length,
    results: agents,
  })
  vi.mocked(agentService.listModels).mockResolvedValue({
    pageNumber: 1,
    pageSize: 50,
    totalCount: models.length,
    results: models,
  })
  vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(snapshot())
  vi.mocked(harnessService.listSessionEntries).mockResolvedValue([])
  vi.mocked(harnessService.acceptCommandBatch).mockResolvedValue(acceptedResponse())
  vi.mocked(harnessService.getSystemPromptPreview).mockResolvedValue({ text: '' })
  vi.mocked(harnessService.setThreadYolo).mockImplementation((threadId, data) =>
    Promise.resolve(threadFixture(threadId, { yoloEnabled: data.yoloEnabled, version: '1' })),
  )
  vi.mocked(harnessService.renameSession).mockImplementation(async (sessionId, data) =>
    thread({ sessionId, name: data.name }),
  )
  vi.mocked(harnessService.renameThread).mockImplementation(async (threadId, data) =>
    thread({ threadId, name: data.name }),
  )
})

describe('AgentPane orchestration', () => {
  it('sends NEW_SESSION as one atomic command-batches request', async () => {
    const user = userEvent.setup()
    renderPane({ type: 'CHAT', id: CHAT_ID })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.type(composer, 'first message')
    await user.click(screen.getByRole('button', { name: '发送消息' }))

    await waitFor(() => expect(harnessService.acceptCommandBatch).toHaveBeenCalledTimes(1))
    const [request] = vi.mocked(harnessService.acceptCommandBatch).mock.calls[0]!
    expect(request.target.type).toBe('NEW_SESSION')
    expect(request.target).not.toHaveProperty('kind')
    expect(request.commands).toHaveLength(1)
    expect(request.commands[0]?.type).toBe('USER_MESSAGE')
  })

  it('sends a NEW_THREAD target with zero settings writes when the draft is unchanged', async () => {
    const user = userEvent.setup()
    localStorage.setItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
      JSON.stringify({ kind: 'NEW_THREAD_DRAFT', sessionId: 'session-1', startEntryId: 'entry-1' }),
    )
    renderPane({ type: 'CHAT', id: CHAT_ID })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.type(composer, 'branch message')
    await user.click(screen.getByRole('button', { name: '发送消息' }))

    await waitFor(() => expect(harnessService.acceptCommandBatch).toHaveBeenCalledTimes(1))
    const [request] = vi.mocked(harnessService.acceptCommandBatch).mock.calls[0]!
    expect(request.target.type).toBe('NEW_THREAD')
    expect(request.commands.map((command) => command.type)).toEqual(['USER_MESSAGE'])
  })

  it('retries an unknown outcome with the exact request and clears the old action error', async () => {
    const user = userEvent.setup()
    vi.mocked(harnessService.acceptCommandBatch)
      .mockRejectedValueOnce(new Error('connection lost'))
      .mockResolvedValueOnce(acceptedResponse())
    renderPane({ type: 'CHAT', id: CHAT_ID })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.type(composer, 'retry me')
    await user.click(screen.getByRole('button', { name: '发送消息' }))
    const retry = await screen.findByRole('button', { name: '重试' })
    const firstRequest = vi.mocked(harnessService.acceptCommandBatch).mock.calls[0]?.[0]

    await user.click(retry)
    await waitFor(() => expect(harnessService.acceptCommandBatch).toHaveBeenCalledTimes(2))
    expect(vi.mocked(harnessService.acceptCommandBatch).mock.calls[1]?.[0]).toBe(firstRequest)
    expect(screen.queryByText('connection lost')).not.toBeInTheDocument()
  })

  it('restores an unknown pending acceptance after remount with the same frozen request', async () => {
    const user = userEvent.setup()
    vi.mocked(harnessService.acceptCommandBatch).mockRejectedValueOnce(new Error('timeout'))
    const firstRender = renderPane({ type: 'CHAT', id: CHAT_ID })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.type(composer, 'persist me')
    await user.click(screen.getByRole('button', { name: '发送消息' }))
    await screen.findByRole('button', { name: '重试' })
    const firstRequest = vi.mocked(harnessService.acceptCommandBatch).mock.calls[0]?.[0]
    firstRender.unmount()

    renderPane({ type: 'CHAT', id: CHAT_ID })
    const retry = await screen.findByRole('button', { name: '重试' })
    await user.click(retry)
    await waitFor(() => expect(harnessService.acceptCommandBatch).toHaveBeenCalledTimes(2))
    expect(vi.mocked(harnessService.acceptCommandBatch).mock.calls[1]?.[0]).toEqual(firstRequest)
  })

  it('abandons an unknown outcome by restoring the frozen composer parts', async () => {
    const user = userEvent.setup()
    vi.mocked(harnessService.acceptCommandBatch).mockRejectedValueOnce(new Error('timeout'))
    renderPane({ type: 'CHAT', id: CHAT_ID })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.type(composer, 'frozen draft')
    await user.click(screen.getByRole('button', { name: '发送消息' }))
    await screen.findByRole('button', { name: '重试' })

    await user.click(screen.getByRole('button', { name: '取消' }))
    await waitFor(() => expect(composer).toHaveTextContent('frozen draft'))
  })

  it('restores the frozen request on a definite 409 and presents its reason', async () => {
    const user = userEvent.setup()
    vi.mocked(harnessService.acceptCommandBatch).mockRejectedValueOnce(
      new ApiError('entry changed', 409, 'CONFLICT', { reason: 'STALE_COMMAND_CURSOR' }),
    )
    renderPane({ type: 'CHAT', id: CHAT_ID })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.type(composer, 'restore after conflict')
    await user.click(screen.getByRole('button', { name: '发送消息' }))

    const dialog = await screen.findByRole('alertdialog')
    expect(dialog).toHaveTextContent('STALE_COMMAND_CURSOR')
    await waitFor(() => expect(composer).toHaveTextContent('restore after conflict'))
  })

  it('fences a late success after abandon without changing the target', async () => {
    const user = userEvent.setup()
    let resolve: ((value: ReturnType<typeof acceptedResponse>) => void) | null = null
    vi.mocked(harnessService.acceptCommandBatch).mockImplementationOnce(
      () => new Promise((complete) => {
        resolve = complete
      }),
    )
    renderPane({ type: 'CHAT', id: CHAT_ID })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.type(composer, 'late response')
    await user.click(screen.getByRole('button', { name: '发送消息' }))
    await user.click(await screen.findByRole('button', { name: '取消' }))
    resolve?.(acceptedResponse())

    await waitFor(() => expect(localStorage.getItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
    )).toContain('NEW_SESSION_DRAFT'))
    expect(vi.mocked(harnessService.acceptCommandBatch)).toHaveBeenCalledTimes(1)
  })

  it('ignores a late rejection after abandon without restoring a stale error', async () => {
    const user = userEvent.setup()
    let reject: ((error: Error) => void) | null = null
    vi.mocked(harnessService.acceptCommandBatch).mockImplementationOnce(
      () => new Promise((_, fail) => {
        reject = fail
      }),
    )
    renderPane({ type: 'CHAT', id: CHAT_ID })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.type(composer, 'late rejection')
    await user.click(screen.getByRole('button', { name: '发送消息' }))
    await user.click(await screen.findByRole('button', { name: '取消' }))
    reject?.(new Error('stale rejection'))
    await waitFor(() => expect(screen.queryByText('stale rejection')).not.toBeInTheDocument())
  })

  it('uses the action error path for a definite non-conflict acceptance failure', async () => {
    const user = userEvent.setup()
    vi.mocked(harnessService.acceptCommandBatch).mockRejectedValueOnce(
      new ApiError('locked', 400, 'BAD_REQUEST'),
    )
    renderPane({ type: 'CHAT', id: CHAT_ID })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.type(composer, 'locked request')
    await user.click(screen.getByRole('button', { name: '发送消息' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('locked')
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
  })

  it('projects bound 409s through the single structured conflict presenter', async () => {
    const user = userEvent.setup()
    localStorage.setItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
      JSON.stringify({ kind: 'BOUND_THREAD', threadId: THREAD_ID }),
    )
    vi.mocked(harnessService.acceptCommandBatch).mockRejectedValueOnce(
      new ApiError('state changed', 409, 'CONFLICT', { reason: 'STALE_COMMAND_CURSOR' }),
    )
    renderPane({ type: 'CHAT', id: CHAT_ID })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.type(composer, 'stale')
    await user.click(screen.getByRole('button', { name: '发送消息' }))

    const dialog = await screen.findByRole('alertdialog')
    expect(dialog).toHaveTextContent('STALE_COMMAND_CURSOR')
    expect(dialog).toHaveTextContent('state changed')
    expect(screen.getAllByRole('alertdialog')).toHaveLength(1)
    await user.click(screen.getByRole('button', { name: '刷新' }))
    await user.click(screen.getByRole('button', { name: '取消' }))
  })

  it('projects a YOLO 409 through the same presenter instead of actionError', async () => {
    const user = userEvent.setup()
    localStorage.setItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
      JSON.stringify({ kind: 'BOUND_THREAD', threadId: THREAD_ID }),
    )
    vi.mocked(harnessService.setThreadYolo).mockRejectedValueOnce(
      new ApiError('yolo state changed', 409, 'CONFLICT', { reason: 'STALE_VERSION' }),
    )
    renderPane({ type: 'CHAT', id: CHAT_ID })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.click(composer)
    await user.keyboard('/yolo{Enter}')

    const dialog = await screen.findByRole('alertdialog')
    expect(dialog).toHaveTextContent('STALE_VERSION')
    expect(screen.queryByText('yolo state changed')).toBeInTheDocument()
    expect(screen.getAllByRole('alertdialog')).toHaveLength(1)
  })

  it('keeps a definite non-conflict YOLO failure in the bound action error channel', async () => {
    const user = userEvent.setup()
    localStorage.setItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
      JSON.stringify({ kind: 'BOUND_THREAD', threadId: THREAD_ID }),
    )
    vi.mocked(harnessService.setThreadYolo).mockRejectedValueOnce(
      new ApiError('yolo locked', 400, 'BAD_REQUEST'),
    )
    renderPane({ type: 'CHAT', id: CHAT_ID })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.click(composer)
    await user.keyboard('/yolo{Enter}')
    expect(await screen.findByRole('alert')).toHaveTextContent('yolo locked')
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
  })

  it('disables and enables compact from the advisory snapshot sidecar', async () => {
    const user = userEvent.setup()
    vi.mocked(harnessService.compactThread).mockResolvedValueOnce({
      thread: thread({ version: '1' }),
      turnStartEntryId: 'turn-start-1',
      modelInvocationId: 'model-1',
    })
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    localStorage.setItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
      JSON.stringify({ kind: 'BOUND_THREAD', threadId: THREAD_ID }),
    )
    render(
      <QueryClientProvider client={client}>
        <AgentPane
          owner={{ type: 'CHAT', id: CHAT_ID }}
          paneId="pane-1"
          agents={agents}
          focused
        />
      </QueryClientProvider>,
    )
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.click(composer)
    await user.keyboard('/')
    expect(screen.getByRole('option', { name: /^compact/ })).toHaveAttribute('aria-disabled', 'true')
    await user.keyboard('{Escape}')

    client.setQueryData(
      queryKeys.threads.snapshot(THREAD_ID),
      snapshot(thread(), { manualCompaction: { available: true, disabledReason: null } }),
    )
    await user.click(composer)
    await user.keyboard('/')
    await waitFor(() =>
      expect(screen.getByRole('option', { name: /^compact/ })).toHaveAttribute('aria-disabled', 'false'),
    )
    await user.click(screen.getByRole('option', { name: /^compact/ }))
    await waitFor(() =>
      expect(harnessService.compactThread).toHaveBeenCalledWith(
        THREAD_ID,
        { expectedVersion: '0' },
      ),
    )
  })

  it('toggles the debug view without duplicating the target or presenter', async () => {
    const user = userEvent.setup()
    localStorage.setItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
      JSON.stringify({ kind: 'BOUND_THREAD', threadId: THREAD_ID }),
    )
    renderPane({ type: 'CHAT', id: CHAT_ID })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.click(composer)
    await user.keyboard('/debug{Enter}')
    expect(await screen.findByRole('listbox', { name: '事件' })).toBeInTheDocument()
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    await user.click(composer)
    await user.keyboard('/debug{Enter}')
    expect(screen.queryByRole('listbox', { name: '事件' })).not.toBeInTheDocument()
  })

  it('dismisses a non-conflict action error through the shared panel callback', async () => {
    const user = userEvent.setup()
    vi.mocked(harnessService.acceptCommandBatch).mockRejectedValueOnce(new Error('network lost'))
    renderPane({ type: 'CHAT', id: CHAT_ID })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.type(composer, 'network error')
    await user.click(screen.getByRole('button', { name: '发送消息' }))
    await screen.findByRole('alert')
    await user.click(screen.getByRole('button', { name: '关闭错误' }))
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('opens and closes a selected debug event detail without another presenter', async () => {
    const user = userEvent.setup()
    localStorage.setItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
      JSON.stringify({ kind: 'BOUND_THREAD', threadId: THREAD_ID }),
    )
    const rootEntry: HarnessSessionEntryDTO = {
      entryId: 'root-1',
      sessionId: 'session-1',
      parentEntryId: null,
      entryType: 'ROOT',
      payloadJson: '{}',
      createTime: null,
    }
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(
      snapshot(thread(), { entries: [rootEntry] }),
    )
    renderPane({ type: 'CHAT', id: CHAT_ID })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.click(composer)
    await user.keyboard('/debug{Enter}')
    const event = await screen.findByRole('option')
    await user.click(event)
    expect(await screen.findByRole('region', { name: '事件详情' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '关闭事件详情' }))
    expect(screen.queryByRole('region', { name: '事件详情' })).not.toBeInTheDocument()
  })

  it('renders the Agent, Environment, and Shortcuts interactions from the shared command menu', async () => {
    const user = userEvent.setup()
    renderPane({ type: 'CHAT', id: CHAT_ID }, [{
      id: 'env-local-1',
      name: 'local',
      rootPath: null,
      ready: true,
      status: 'READY',
      lastSeen: null,
      capabilities: [],
      version: '1',
      createTime: null,
      updateTime: null,
    }])
    const composer = await screen.findByLabelText('给 AI 发送消息')

    await user.click(composer)
    await user.keyboard('/agent{Enter}')
    expect(await screen.findByText('assistant')).toBeInTheDocument()
    await user.keyboard('{Escape}')

    await user.click(composer)
    await user.keyboard('/shortcuts{Enter}')
    expect(await screen.findByRole('region', { name: '键盘快捷键' })).toBeInTheDocument()
  })

  it('applies unbound YOLO and agent selections locally before first send', async () => {
    const user = userEvent.setup()
    renderPane({ type: 'CHAT', id: CHAT_ID })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.click(composer)
    await user.keyboard('/yolo{Enter}')
    await user.click(composer)
    await user.keyboard('/agent{Enter}')
    await user.click(await screen.findByRole('option', { name: /assistant/ }))
    expect(screen.getByRole('button', { name: '权限模式' })).toHaveTextContent('YOLO')
  })

  it('navigates Session -> Thread through the owner-scoped queries', async () => {
    const user = userEvent.setup()
    vi.mocked(chatService.listChatSessions).mockResolvedValue([{
      sessionId: 'session-1',
      name: 'Session Name',
      createdAt: null,
      lastActivityAt: null,
      firstMessagePreview: 'first',
      threadCount: 1,
    }])
    vi.mocked(harnessService.listSessionThreads).mockResolvedValue([{
      threadId: THREAD_ID,
      name: 'Thread Name',
      createdAt: null,
      updatedAt: null,
      status: 'IDLE',
      model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'default' },
      headMessagePreview: 'thread preview',
    }])
    renderPane({ type: 'CHAT', id: CHAT_ID })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.click(composer)
    await user.keyboard('/thread{Enter}')
    await user.click(await screen.findByRole('option', { name: /first/ }))
    await user.click(screen.getByRole('button', { name: '关闭' }))
    await user.click(await screen.findByRole('option', { name: /first/ }))
    await user.click(await screen.findByRole('option', { name: /thread preview/ }))
    await waitFor(() => expect(harnessService.getThreadSnapshot).toHaveBeenCalledWith(THREAD_ID))
    expect(localStorage.getItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
    )).toContain('BOUND_THREAD')
  })

  it('opens the on-demand Entry Tree for a NEW_THREAD_DRAFT target', async () => {
    const user = userEvent.setup()
    localStorage.setItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
      JSON.stringify({ kind: 'NEW_THREAD_DRAFT', sessionId: 'session-1', startEntryId: 'entry-1' }),
    )
    vi.mocked(harnessService.listSessionEntries).mockResolvedValue([{
      entryId: 'entry-1',
      sessionId: 'session-1',
      parentEntryId: null,
      entryType: 'ROOT',
      payloadJson: '{}',
      createTime: null,
    }])
    renderPane({ type: 'CHAT', id: CHAT_ID })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.click(composer)
    await user.keyboard('/tree{Enter}')
    expect(await screen.findByRole('region', { name: '历史分支' })).toBeInTheDocument()
    expect(harnessService.listSessionEntries).toHaveBeenCalledWith('session-1')
    await user.click(document.querySelector<HTMLButtonElement>('.history-branch-entry')!)
    await user.click(screen.getByRole('button', { name: '从这里继续当前 Thread' }))
    expect(localStorage.getItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
    )).toContain('NEW_THREAD_DRAFT')
  })

  it('opens the on-demand Entry Tree for a bound Thread target', async () => {
    const user = userEvent.setup()
    localStorage.setItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
      JSON.stringify({ kind: 'BOUND_THREAD', threadId: THREAD_ID }),
    )
    renderPane({ type: 'CHAT', id: CHAT_ID })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.click(composer)
    await user.keyboard('/tree{Enter}')
    expect(await screen.findByRole('region', { name: '历史分支' })).toBeInTheDocument()
    expect(harnessService.listSessionEntries).toHaveBeenCalledWith('session-1')
  })

  it('blocks target navigation while an exact Stop replay is pending', async () => {
    localStorage.setItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:probe`,
      JSON.stringify({ kind: 'BOUND_THREAD', threadId: THREAD_ID }),
    )
    localStorage.setItem(
      `kkstudio.ai.pending-stop.v1:${THREAD_ID}`,
      JSON.stringify({
        stopRequestId: 'stop-1',
        expectedVersion: '0',
        requestHeadEntryId: 'head-1',
        basisVersion: '0',
      }),
    )
    const hook = renderController()
    await waitFor(() => expect(hook.result.current.controller.stopReplayPending).toBe(true))
    expect(hook.result.current.pending).toBe(true)
    expect(hook.result.current.composer.pending).toBe(true)

    act(() => hook.result.current.composer.onCommand(testCommand('new')))
    expect(hook.result.current.target).toEqual({
      kind: 'BOUND_THREAD',
      threadId: THREAD_ID,
    })
    expect(hook.result.current.error).toContain('等待完成或精确重试')

    act(() => hook.result.current.composer.onCommand(testCommand('thread')))
    expect(hook.result.current.interaction).toBeNull()
  })

  it('keeps a background realtime version from changing the persisted target', async () => {
    const user = userEvent.setup()
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(
      snapshot(thread({ status: 'MODEL_STREAMING', processing: true })),
    )
    localStorage.setItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
      JSON.stringify({ kind: 'BOUND_THREAD', threadId: THREAD_ID }),
    )
    const view = renderPane({ type: 'CHAT', id: CHAT_ID })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.click(composer)
    await user.keyboard('/new{Enter}')
    await waitFor(() => expect(localStorage.getItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
    )).toContain('NEW_SESSION_DRAFT'))
    const calls = fakeApplicationEvents.useApplicationEvents().subscribe.mock.calls
    const handlers = calls.at(-1)?.[1]
    handlers?.onSubscribed?.()
    handlers?.onEvent?.('unrelated', {})
    handlers?.onResync?.()
    handlers?.onError?.()
    view.client.setQueryData(
      queryKeys.threads.snapshot(THREAD_ID),
      snapshot(thread({ status: 'IDLE', processing: false })),
    )
    handlers?.onEvent?.('version', {})
    await waitFor(() => expect(
      fakeApplicationEvents.useApplicationEvents().subscribe.mock.results.at(-1)?.value,
    ).toHaveBeenCalled())
    expect(localStorage.getItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
    )).toContain('NEW_SESSION_DRAFT')
  })

  it('cleans and reuses the single background subscription across thread switches', async () => {
    const user = userEvent.setup()
    const threadA = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
    const threadB = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'
    const summary = (threadId: string, name: string) => ({
      threadId,
      name,
      createdAt: null,
      updatedAt: null,
      status: 'MODEL_STREAMING' as const,
      model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'default' },
      headMessagePreview: null,
    })
    vi.mocked(harnessService.getThreadSnapshot).mockImplementation(async (threadId) =>
      snapshot(thread({ threadId, status: 'MODEL_STREAMING', processing: true })),
    )
    vi.mocked(chatService.listChatSessions).mockResolvedValue([{
      sessionId: 'session-1',
      name: 'session',
      createdAt: null,
      lastActivityAt: null,
      firstMessagePreview: null,
      threadCount: 1,
    }])
    vi.mocked(harnessService.listSessionThreads)
      .mockResolvedValueOnce([summary(threadB, 'thread B')])
      .mockResolvedValueOnce([summary(threadA, 'thread A')])
    localStorage.setItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
      JSON.stringify({ kind: 'BOUND_THREAD', threadId: threadA }),
    )
    const view = renderPane({ type: 'CHAT', id: CHAT_ID })
    const composer = await screen.findByLabelText('给 AI 发送消息')

    await user.click(composer)
    await user.keyboard('/thread{Enter}')
    await user.click(await screen.findByRole('option', { name: /session/ }))
    await user.click(await screen.findByRole('option', { name: /thread B/ }))
    await waitFor(() => expect(fakeApplicationEvents.useApplicationEvents().subscribe).toHaveBeenCalled())
    const subscribe = fakeApplicationEvents.useApplicationEvents().subscribe
    const firstBackgroundUnsubscribe = subscribe.mock.results.at(-1)?.value as ReturnType<typeof vi.fn>
    const replacementClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    view.rerender(
      <QueryClientProvider client={replacementClient}>
        <AgentPane owner={{ type: 'CHAT', id: CHAT_ID }} paneId="pane-1" agents={agents} focused />
      </QueryClientProvider>,
    )

    await user.click(composer)
    await user.keyboard('/thread{Enter}')
    await user.click(await screen.findByRole('option', { name: /session/ }))
    await user.click(await screen.findByRole('option', { name: /thread A/ }))
    await waitFor(() => expect(firstBackgroundUnsubscribe).toHaveBeenCalled())
    expect(subscribe.mock.calls.length).toBeGreaterThan(3)
  })

  it('covers unbound command guards and draft controls through the real pane controller', async () => {
    const hook = renderController()
    await waitFor(() => expect(hook.result.current.activeDraft).not.toBeNull())

    act(() => hook.result.current.composer.onCommand(testCommand('tree')))
    expect(hook.result.current.error).toBeTruthy()
    act(() => hook.result.current.dismissActionError())

    act(() => hook.result.current.composer.onCommand(testCommand('debug')))
    expect(hook.result.current.interaction).toBeNull()
    act(() => hook.result.current.composer.onCommand(testCommand('models')))
    act(() => hook.result.current.composer.onCommand(testCommand('upload')))
    expect(hook.result.current.error).toBeNull()

    act(() => hook.result.current.composer.onCommand(testCommand('disabled', {
      disabled: true,
      disabledReason: 'disabled for test',
    })))
    expect(hook.result.current.error).toBe('disabled for test')
    act(() => hook.result.current.dismissActionError())
    act(() => hook.result.current.composer.onCommand(testCommand('disabled', {
      disabled: true,
    })))
    act(() => hook.result.current.retryAcceptance())

    act(() => hook.result.current.composer.onCommand(testCommand('thread')))
    expect(hook.result.current.interaction).toBe('thread-sessions')
    act(() => hook.result.current.closeInteraction())
    act(() => hook.result.current.selectSession({
      sessionId: 'empty-session',
      name: 'Empty Session',
      createdAt: null,
      lastActivityAt: null,
      firstMessagePreview: '',
      threadCount: 0,
    }))
    expect(hook.result.current.interaction).toBe('tree')
    act(() => hook.result.current.selectSession({
      sessionId: 'threaded-session',
      name: 'Threaded Session',
      createdAt: null,
      lastActivityAt: null,
      firstMessagePreview: '',
      threadCount: 1,
    }))
    expect(hook.result.current.interaction).toBe('thread-threads')

    act(() => hook.result.current.selectAgent('missing-agent'))
    expect(hook.result.current.error).toBeTruthy()
    act(() => hook.result.current.selectAgent('assistant'))
    expect(hook.result.current.error).toBeNull()
    act(() => hook.result.current.composer.settings?.onModelChange({
      providerName: 'minimax',
      modelName: 'MiniMax',
      variant: 'default',
    }))
    act(() => hook.result.current.composer.settings?.onYoloChange(true))
    expect(hook.result.current.activeDraft?.yoloEnabled).toBe(true)

    act(() => hook.result.current.selectEntry({
      entryId: 'entry-settings',
      sessionId: 'session-1',
      parentEntryId: null,
      entryType: 'ROOT',
      payloadJson: JSON.stringify({
        settings: {
          agentName: 'assistant',
          model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'default' },
        },
      }),
      createTime: null,
    }))
    expect(hook.result.current.target.kind).toBe('NEW_THREAD_DRAFT')
    act(() => hook.result.current.selectEntry({
      entryId: 'entry-missing-settings',
      sessionId: 'session-1',
      parentEntryId: null,
      entryType: 'ROOT',
      payloadJson: JSON.stringify({ settings: {} }),
      createTime: null,
    }))
    await hook.result.current.refreshPaneProjection()
  })

  it('keeps pending acceptance fenced while commands and a direct composer callback race', async () => {
    let resolve: ((value: ReturnType<typeof acceptedResponse>) => void) | null = null
    vi.mocked(harnessService.acceptCommandBatch).mockImplementationOnce(
      () => new Promise((complete) => {
        resolve = complete
      }),
    )
    const hook = renderController()
    await waitFor(() => expect(hook.result.current.activeDraft).not.toBeNull())
    act(() => hook.result.current.composer.onSubmit([createTextPart('first')]))
    await waitFor(() => expect(hook.result.current.pendingAcceptance).not.toBeNull())

    act(() => hook.result.current.composer.onCommand(testCommand('new')))
    expect(hook.result.current.error).toBeTruthy()
    act(() => hook.result.current.composer.onCommand(testCommand('thread')))
    act(() => hook.result.current.composer.onCommand(testCommand('tree')))
    act(() => hook.result.current.composer.onSubmit([createTextPart('second')]))
    act(() => hook.result.current.composer.onPartsChange([createTextPart('typed during request')]))
    resolve?.(acceptedResponse())
    await waitFor(() => expect(hook.result.current.pendingAcceptance).toBeNull())
    expect(hook.result.current.target.kind).toBe('BOUND_THREAD')
    act(() => hook.result.current.abandonPendingAcceptance())
  })

  it('takes the unbound submit early returns and yolo null-draft path', async () => {
    const hook = renderController({ agents: [] })
    await waitFor(() => expect(hook.result.current.activeDraft).toBeNull())
    act(() => hook.result.current.composer.onSubmit([createTextPart('/unknown-command')]))
    act(() => hook.result.current.composer.onSubmit([]))
    act(() => hook.result.current.composer.onCommand(testCommand('yolo')))
    expect(harnessService.acceptCommandBatch).not.toHaveBeenCalled()
  })

  it('uses stored composer parts when the submit callback omits an explicit payload', async () => {
    const hook = renderController()
    await waitFor(() => expect(hook.result.current.activeDraft).not.toBeNull())
    act(() => hook.result.current.composer.onPartsChange([createTextPart('stored parts')]))
    act(() => hook.result.current.composer.onSubmit())
    await waitFor(() => expect(harnessService.acceptCommandBatch).toHaveBeenCalledTimes(1))
    expect(harnessService.acceptCommandBatch.mock.calls[0]?.[0].commands[0]?.type)
      .toBe('USER_MESSAGE')
  })

  it('restores the browser-local draft rather than the resolved submit payload', async () => {
    vi.mocked(harnessService.acceptCommandBatch).mockRejectedValueOnce(
      new ApiError('rejected', 400, 'BAD_REQUEST'),
    )
    const hook = renderController()
    await waitFor(() => expect(hook.result.current.activeDraft).not.toBeNull())

    act(() =>
      hook.result.current.composer.onSubmit(
        [createTextPart('resolved payload')],
        [createTextPart('browser-local draft')],
      ),
    )

    await waitFor(() =>
      expect(hook.result.current.composer.parts).toEqual([
        expect.objectContaining({ type: 'text', text: 'browser-local draft' }),
      ]),
    )
  })

  it('uses the Canvas session query when opening thread navigation', async () => {
    vi.mocked(listCanvasSessions).mockResolvedValue([])
    const hook = renderController({ owner: { type: 'CANVAS', id: CANVAS_ID } })
    await waitFor(() => expect(hook.result.current.activeDraft).not.toBeNull())
    act(() => hook.result.current.composer.onCommand(testCommand('thread')))
    expect(hook.result.current.interaction).toBe('thread-sessions')
    await waitFor(() => expect(listCanvasSessions).toHaveBeenCalledWith(CANVAS_ID))
  })

  it('shows required names as primary selection titles and falls back previews only', async () => {
    const user = userEvent.setup()
    vi.mocked(harnessService.acceptCommandBatch).mockRejectedValueOnce({})
    renderPane({ type: 'CHAT', id: CHAT_ID })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.type(composer, 'unknown error')
    await user.click(screen.getByRole('button', { name: '发送消息' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('请求失败')
    expect(sessionSelectionItem({
      sessionId: 'session-fallback',
      name: 'Named Session',
      createdAt: null,
      lastActivityAt: null,
      firstMessagePreview: 'preview text',
      threadCount: 0,
    })).toMatchObject({
      id: 'session-fallback',
      title: 'Named Session',
      subtitle: expect.stringContaining('0 Threads'),
    })
    expect(threadSelectionItem({
      threadId: THREAD_ID,
      name: 'Named Thread',
      createdAt: null,
      updatedAt: null,
      status: 'IDLE',
      model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'default' },
      headMessagePreview: null,
    })).toMatchObject({
      id: THREAD_ID,
      title: 'Named Thread',
      subtitle: expect.stringContaining('IDLE'),
    })
    // 名称为主展示，绝不回退为 id 或 preview。
    expect(sessionSelectionItem({
      sessionId: 'session-fallback',
      name: 'Session With Preview',
      createdAt: null,
      lastActivityAt: null,
      firstMessagePreview: 'some other preview',
      threadCount: 0,
    }).title).toBe('Session With Preview')
  })

  it('renders Session/Thread pickers with the existing ai.chat translations', async () => {
    const user = userEvent.setup()
    vi.mocked(chatService.listChatSessions).mockResolvedValue([])
    vi.mocked(harnessService.listSessionThreads).mockResolvedValue([])
    const emptyPane = renderPane({ type: 'CHAT', id: CHAT_ID })
    const emptyComposer = await screen.findByLabelText('给 AI 发送消息')
    await user.click(emptyComposer)
    await user.keyboard('/thread{Enter}')
    // 复用 ai.chat.* 既有 key，而不是未注册的 ai.runtime.* 缺失消息。
    expect(await screen.findByRole('region', { name: '选择 Session' })).toBeInTheDocument()
    expect(screen.getByText('暂无 Session')).toBeInTheDocument()
    emptyPane.unmount()

    vi.mocked(chatService.listChatSessions).mockResolvedValue([{
        sessionId: 'session-1',
        name: 'Session 1',
        createdAt: null,
        lastActivityAt: null,
        firstMessagePreview: 'session-1',
        threadCount: 1,
      }])
    localStorage.setItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
      JSON.stringify({ kind: 'BOUND_THREAD', threadId: THREAD_ID }),
    )
    renderPane({ type: 'CHAT', id: CHAT_ID })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.click(composer)
    await user.keyboard('/thread{Enter}')
    expect(await screen.findByRole('region', { name: '选择 Session' })).toBeInTheDocument()
    expect(document.querySelector('.thread-composer')).toHaveAttribute('hidden')
    await user.click(await screen.findByRole('option', { name: /session-1/ }))
    expect(await screen.findByRole('region', { name: '选择 Thread' })).toBeInTheDocument()
    expect(screen.getByText('暂无 Thread')).toBeInTheDocument()
    expect(screen.queryByText(/⟦missing:/)).not.toBeInTheDocument()
  })

  it('shows the bound Thread name in the pane heading and renames it from the pencil action', async () => {
    const user = userEvent.setup()
    localStorage.setItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
      JSON.stringify({ kind: 'BOUND_THREAD', threadId: THREAD_ID }),
    )
    vi.mocked(harnessService.renameThread).mockImplementation(async (threadId, data) =>
      thread({ threadId, name: data.name, version: '1' }),
    )
    // 重命名成功后 snapshot 被失效并重新拉取，返回带新名称的 Thread。
    let snapshotCalls = 0
    vi.mocked(harnessService.getThreadSnapshot).mockImplementation(async () => {
      snapshotCalls += 1
      return snapshot(snapshotCalls > 1
        ? thread({ name: 'renamed thread', version: '1' })
        : thread())
    })
    renderPane({ type: 'CHAT', id: CHAT_ID })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    expect(await screen.findByRole('heading', { name: 'thread-name' })).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '重命名' }))
    await screen.findByRole('region', { name: '重命名 Thread' })
    const input = screen.getByRole('textbox', { name: '名称' })
    expect(input).toHaveValue('thread-name')
    await user.clear(input)
    await user.type(input, 'renamed thread')
    await user.click(screen.getByRole('button', { name: '保存' }))

    await waitFor(() =>
      expect(harnessService.renameThread).toHaveBeenCalledWith(THREAD_ID, { name: 'renamed thread' }))
    await waitFor(() => expect(screen.queryByRole('region', { name: '重命名 Thread' })).not.toBeInTheDocument())
    expect(await screen.findByRole('heading', { name: 'renamed thread' })).toBeInTheDocument()
    expect(composer).toBeInTheDocument()
  })

  it('renames the bound Thread from the /rename-thread slash command', async () => {
    const user = userEvent.setup()
    localStorage.setItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
      JSON.stringify({ kind: 'BOUND_THREAD', threadId: THREAD_ID }),
    )
    vi.mocked(harnessService.renameThread).mockImplementation(async (threadId, data) =>
      thread({ threadId, name: data.name, version: '1' }),
    )
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(snapshot(thread({ name: 'old name' })))
    renderPane({ type: 'CHAT', id: CHAT_ID })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.click(composer)
    await user.keyboard('/rename-thread{Enter}')

    await screen.findByRole('region', { name: '重命名 Thread' })
    const input = screen.getByRole('textbox', { name: '名称' })
    expect(input).toHaveValue('old name')
    await user.clear(input)
    await user.type(input, 'slash renamed')
    await user.click(screen.getByRole('button', { name: '保存' }))

    await waitFor(() =>
      expect(harnessService.renameThread).toHaveBeenCalledWith(THREAD_ID, { name: 'slash renamed' }))
    await waitFor(() => expect(screen.queryByRole('region', { name: '重命名 Thread' })).not.toBeInTheDocument())
  })

  it('keeps the rename input and shows the error when a rename request fails', async () => {
    const user = userEvent.setup()
    localStorage.setItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
      JSON.stringify({ kind: 'BOUND_THREAD', threadId: THREAD_ID }),
    )
    vi.mocked(harnessService.renameThread).mockRejectedValueOnce(new Error('rename rejected'))
    renderPane({ type: 'CHAT', id: CHAT_ID })
    await user.click(screen.getByRole('button', { name: '重命名' }))
    const input = await screen.findByRole('textbox', { name: '名称' })
    await user.clear(input)
    await user.type(input, 'keep me')
    await user.click(screen.getByRole('button', { name: '保存' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('rename rejected')
    expect(screen.getByRole('textbox', { name: '名称' })).toHaveValue('keep me')
    expect(screen.getByRole('region', { name: '重命名 Thread' })).toBeInTheDocument()
  })

  it('renames the parent Session from a NEW_THREAD_DRAFT via /rename-session', async () => {
    const user = userEvent.setup()
    localStorage.setItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
      JSON.stringify({
        kind: 'NEW_THREAD_DRAFT',
        sessionId: 'session-1',
        startEntryId: 'entry-1',
      }),
    )
    vi.mocked(harnessService.listSessionEntries).mockResolvedValue([{
      entryId: 'entry-1',
      sessionId: 'session-1',
      parentEntryId: null,
      entryType: 'ROOT',
      payloadJson: '{}',
      createTime: null,
    }])
    vi.mocked(chatService.listChatSessions).mockResolvedValue([{
      sessionId: 'session-1',
      name: 'original session',
      createdAt: null,
      lastActivityAt: null,
      firstMessagePreview: 'entry-1',
      threadCount: 1,
    }])
    renderPane({ type: 'CHAT', id: CHAT_ID })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.click(composer)
    await user.keyboard('/rename-session{Enter}')

    // rename-session 打开时按需拉取 owner Sessions 摘要并预填当前名称。
    const sessionInput = screen.getByRole('textbox', { name: '名称' })
    await waitFor(() => expect(sessionInput).toHaveValue('original session'))
    await user.clear(sessionInput)
    await user.type(sessionInput, 'renamed parent session')
    await user.click(screen.getByRole('button', { name: '保存' }))

    await waitFor(() => expect(harnessService.renameSession).toHaveBeenCalledWith(
      'session-1',
      { name: 'renamed parent session' },
    ))
    await waitFor(() => expect(screen.queryByRole('region', { name: '重命名 Session' })).not.toBeInTheDocument())
  })

  it('consumes the canonical Session name returned by the server in the loaded picker cache', async () => {
    // 输入与返回不同：提交连续空白名称，服务端返回规范化后的权威名称；证明
    // 列表立即消费返回值（而非回显用户输入）。失效后的重查同样返回权威值，
    // 避免陈旧 mock 覆盖已确认的规范化名称。
    const user = userEvent.setup()
    let canonicalName = 'old session'
    vi.mocked(chatService.listChatSessions).mockImplementation(async () => [{
      sessionId: 'session-1',
      name: canonicalName,
      createdAt: null,
      lastActivityAt: null,
      firstMessagePreview: 'preview',
      threadCount: 1,
    }])
    vi.mocked(harnessService.renameSession).mockImplementation(async (sessionId, data) => {
      canonicalName = data.name.replace(/\s+/gu, ' ').trim().toUpperCase()
      return {
        sessionId,
        name: canonicalName,
        createdAt: null,
      }
    })
    renderPane({ type: 'CHAT', id: CHAT_ID })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.click(composer)
    await user.keyboard('/thread{Enter}')

    // 进入 Session picker，从行内铅笔打开重命名面板（cache 已加载）。
    const sessionRow = await screen.findByRole('option', { name: /old session/ })
    const sessionRenameButton = sessionRow.parentElement?.querySelector('.thread-selection-rename')
    await user.click(sessionRenameButton as HTMLElement)
    const input = await screen.findByRole('textbox', { name: '名称' })
    expect(input).toHaveValue('old session')
    await user.clear(input)
    await user.type(input, '  renamed   session  ')
    await user.click(screen.getByRole('button', { name: '保存' }))

    await waitFor(() => expect(harnessService.renameSession).toHaveBeenCalledWith(
      'session-1',
      { name: 'renamed   session' },
    ))
    // 面板关闭返回 Session picker：行标题立即显示服务端权威规范化名称
    //（大写化 + 单空格），而不是用户输入的原始字符串。
    expect(await screen.findByRole('region', { name: '选择 Session' })).toBeInTheDocument()
    expect(await screen.findByRole('option', { name: /RENAMED SESSION/ })).toBeInTheDocument()
    expect(screen.queryByRole('option', { name: /renamed {3}session/ })).not.toBeInTheDocument()
  })

  it('consumes the canonical Thread name returned by the server in the bound heading', async () => {
    // 输入与返回不同：提交连续空白名称，服务端返回规范化后的权威名称；证明
    // bound 标题立即使用响应值（patch snapshot cache），而非用户输入或回显。
    // 失效后的重查同样返回权威值，避免陈旧 mock 覆盖已确认的规范化名称。
    const user = userEvent.setup()
    let canonicalName = 'ORIGINAL'
    localStorage.setItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
      JSON.stringify({ kind: 'BOUND_THREAD', threadId: THREAD_ID }),
    )
    vi.mocked(harnessService.renameThread).mockImplementation(async (threadId, data) => {
      canonicalName = data.name.replace(/\s+/gu, ' ').trim().toUpperCase()
      return thread({ threadId, name: canonicalName, version: '1' })
    })
    vi.mocked(harnessService.getThreadSnapshot).mockImplementation(async () =>
      snapshot(thread({ name: canonicalName })))
    renderPane({ type: 'CHAT', id: CHAT_ID })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    expect(await screen.findByRole('heading', { name: 'ORIGINAL' })).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '重命名' }))
    const input = await screen.findByRole('textbox', { name: '名称' })
    expect(input).toHaveValue('ORIGINAL')
    await user.clear(input)
    await user.type(input, '  new   thread name  ')
    await user.click(screen.getByRole('button', { name: '保存' }))

    await waitFor(() => expect(harnessService.renameThread).toHaveBeenCalledWith(
      THREAD_ID,
      { name: 'new   thread name' },
    ))
    // 标题立即使用服务端规范化响应（patch cache，无需等待失效后的重新拉取）。
    expect(await screen.findByRole('heading', { name: 'NEW THREAD NAME' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'new thread name' })).not.toBeInTheDocument()
    expect(composer).toBeInTheDocument()
  })

  it('keeps the rename panel open while the rename PUT is in flight even on Escape', async () => {
    // 回归：PUT 在途时按 Escape 不得关闭面板（关闭会清空 renameTargetRef，
    // PUT 成功后跳过 cache patch 与失效，留下本地陈旧名称）；完成后仍需
    // 关闭并展示服务端权威名称。
    const user = userEvent.setup()
    let resolveRename: ((session: HarnessSessionDTO) => void) | null = null
    let canonicalName = 'session one'
    vi.mocked(chatService.listChatSessions).mockImplementation(async () => [{
      sessionId: 'session-1',
      name: canonicalName,
      createdAt: null,
      lastActivityAt: null,
      firstMessagePreview: 'preview',
      threadCount: 1,
    }])
    vi.mocked(harnessService.renameSession).mockImplementation(
      async () => new Promise<HarnessSessionDTO>((resolve) => {
        resolveRename = (session) => resolve(session)
      }),
    )
    renderPane({ type: 'CHAT', id: CHAT_ID })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.click(composer)
    await user.keyboard('/thread{Enter}')

    const sessionRow = await screen.findByRole('option', { name: /session one/ })
    const sessionRenameButton = sessionRow.parentElement?.querySelector('.thread-selection-rename')
    await user.click(sessionRenameButton as HTMLElement)
    const input = await screen.findByRole('textbox', { name: '名称' })
    await user.clear(input)
    await user.type(input, 'renamed session')
    await user.click(screen.getByRole('button', { name: '保存' }))
    await waitFor(() => expect(harnessService.renameSession).toHaveBeenCalled())

    // PUT 在途：Escape 与关闭按钮都无效，面板保持打开。
    await user.keyboard('{Escape}')
    expect(screen.getByRole('region', { name: '重命名 Session' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '保存' })).toBeDisabled()

    // PUT 返回权威名称：面板关闭、picker 行立即展示服务端规范化名称。
    canonicalName = 'RENAMED SESSION'
    resolveRename?.({ sessionId: 'session-1', name: canonicalName, createdAt: null })
    await waitFor(() =>
      expect(screen.queryByRole('region', { name: '重命名 Session' })).not.toBeInTheDocument())
    expect(await screen.findByRole('region', { name: '选择 Session' })).toBeInTheDocument()
    expect(await screen.findByRole('option', { name: /RENAMED SESSION/ })).toBeInTheDocument()
  })

  it('renames a Session row from the Session picker and a Thread row from the Thread picker', async () => {
    const user = userEvent.setup()
    vi.mocked(chatService.listChatSessions).mockResolvedValue([{
      sessionId: 'session-1',
      name: 'session one',
      createdAt: null,
      lastActivityAt: null,
      firstMessagePreview: 'preview',
      threadCount: 1,
    }])
    vi.mocked(harnessService.listSessionThreads).mockResolvedValue([{
      threadId: THREAD_ID,
      name: 'thread one',
      createdAt: null,
      updatedAt: null,
      status: 'IDLE',
      model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'default' },
      headMessagePreview: null,
    }])
    renderPane({ type: 'CHAT', id: CHAT_ID })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.click(composer)
    await user.keyboard('/thread{Enter}')

    // Session 行铅笔 -> 预填的 Session 重命名面板。
    const sessionRow = await screen.findByRole('option', { name: /session one/ })
    const sessionRenameButton = sessionRow.parentElement?.querySelector('.thread-selection-rename')
    expect(sessionRenameButton).not.toBeNull()
    await user.click(sessionRenameButton as HTMLElement)
    expect(await screen.findByRole('textbox', { name: '名称' })).toHaveValue('session one')
    await user.click(screen.getByRole('button', { name: '保存' }))
    await waitFor(() => expect(harnessService.renameSession).toHaveBeenCalledWith(
      'session-1',
      { name: 'session one' },
    ))
    await waitFor(() => expect(screen.queryByRole('region', { name: '重命名 Session' })).not.toBeInTheDocument())
    // 会话选择面板重新展示（返回原交互）。
    expect(await screen.findByRole('region', { name: '选择 Session' })).toBeInTheDocument()

    await user.click(await screen.findByRole('option', { name: /session one/ }))
    const threadRow = await screen.findByRole('option', { name: /thread one/ })
    const threadRenameButton = threadRow.parentElement?.querySelector('.thread-selection-rename')
    expect(threadRenameButton).not.toBeNull()
    await user.click(threadRenameButton as HTMLElement)
    const threadInput = await screen.findByRole('textbox', { name: '名称' })
    expect(threadInput).toHaveValue('thread one')
    await user.clear(threadInput)
    await user.type(threadInput, 'thread renamed')
    await user.click(screen.getByRole('button', { name: '保存' }))
    await waitFor(() => expect(harnessService.renameThread).toHaveBeenCalledWith(
      THREAD_ID,
      { name: 'thread renamed' },
    ))
    await waitFor(() => expect(screen.queryByRole('region', { name: '重命名 Thread' })).not.toBeInTheDocument())
    expect(await screen.findByRole('region', { name: '选择 Thread' })).toBeInTheDocument()
  })

  it('keeps the composer editable with queued commands while blocking target switching', async () => {
    // QUEUED USER_MESSAGE 只保留在 hasPendingOperation 栅栏中：Composer 仍可
    // 编辑并提交新 batch，/thread 切换则必须被拒绝。
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(snapshot(
      thread(),
      {
        queuedCommands: [{
          threadId: THREAD_ID,
          sequence: '1',
          type: 'USER_MESSAGE',
          state: 'QUEUED',
          idempotencyKey: 'queued-1',
          payloadJson: JSON.stringify({
            message: { role: 'USER', contents: [{ type: 'text', text: '排队中' }] },
          }),
          cancelledAt: null,
          createTime: null,
        }],
      },
    ))
    localStorage.setItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:probe`,
      JSON.stringify({ kind: 'BOUND_THREAD', threadId: THREAD_ID }),
    )
    const hook = renderController()
    await waitFor(() => expect(hook.result.current.controller.queuedCommands).toHaveLength(1))

    expect(hook.result.current.composer.disabled).toBe(false)
    expect(hook.result.current.composer.pending).toBe(false)

    act(() => hook.result.current.composer.onCommand(testCommand('thread')))
    expect(hook.result.current.interaction).toBeNull()
    expect(hook.result.current.error).toContain('等待完成或精确重试')

    act(() => hook.result.current.composer.onSubmit([createTextPart('下一条消息')]))
    await waitFor(() =>
      expect(harnessService.acceptCommandBatch).toHaveBeenCalledWith(
        expect.objectContaining({ target: expect.objectContaining({ type: 'THREAD' }) }),
      ),
    )
  })

  it('keeps the stored draft unchanged while navigating recalled history', async () => {
    // 历史导航只更新当前受控内容；刷新恢复的持久草稿仍是用户原始编辑。
    const draftKey = `kkstudio.ai.composer-draft.v1:thread:${THREAD_ID}`
    const stored = JSON.stringify({
      version: 1,
      parts: [{ type: 'text', text: 'original draft' }],
    })
    localStorage.setItem(draftKey, stored)
    localStorage.setItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:probe`,
      JSON.stringify({ kind: 'BOUND_THREAD', threadId: THREAD_ID }),
    )
    const hook = renderController()
    await waitFor(() => expect(hook.result.current.activeDraft).not.toBeNull())

    act(() =>
      hook.result.current.composer.onHistoryPartsChange?.([
        createTextPart('recalled history'),
      ]),
    )

    expect(hook.result.current.composer.parts).toEqual([
      expect.objectContaining({ type: 'text', text: 'recalled history' }),
    ])
    expect(localStorage.getItem(draftKey)).toBe(stored)
  })

  it('does not retain a background subscription for a terminal previous thread', async () => {
    const user = userEvent.setup()
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(
      snapshot(thread({ status: 'IDLE', processing: false })),
    )
    localStorage.setItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
      JSON.stringify({ kind: 'BOUND_THREAD', threadId: THREAD_ID }),
    )
    renderPane({ type: 'CHAT', id: CHAT_ID })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.click(composer)
    await user.keyboard('/new{Enter}')
    await waitFor(() => expect(localStorage.getItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
    )).toContain('NEW_SESSION_DRAFT'))
  })

  it('fails closed when no catalog agent can materialize a new draft', async () => {
    const hook = renderController({ agents: [] })
    await waitFor(() => expect(hook.result.current.activeDraft).toBeNull())
    expect(hook.result.current.composer.disabled).toBe(true)
    act(() => hook.result.current.selectEntry({
      entryId: 'entry-without-draft',
      sessionId: 'session-1',
      parentEntryId: null,
      entryType: 'ROOT',
      payloadJson: '{}',
      createTime: null,
    }))
  })

  it('reports a catalog agent that the bound branch panel cannot resolve', async () => {
    const extraAgent = { ...agents[0]!, name: 'other' }
    localStorage.setItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:probe`,
      JSON.stringify({ kind: 'BOUND_THREAD', threadId: THREAD_ID }),
    )
    const hook = renderController({ agents: [...agents, extraAgent] })
    await waitFor(() => expect(hook.result.current.activeDraft).not.toBeNull())
    act(() => hook.result.current.selectAgent('other'))
    expect(hook.result.current.error).toBeTruthy()
  })

  it('routes bound draft controls through the bound branch panel', async () => {
    localStorage.setItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:probe`,
      JSON.stringify({ kind: 'BOUND_THREAD', threadId: THREAD_ID }),
    )
    const hook = renderController()
    await waitFor(() => expect(hook.result.current.activeDraft).not.toBeNull())
    act(() => hook.result.current.selectAgent('assistant'))
    act(() => hook.result.current.composer.settings?.onModelChange({
      providerName: 'minimax',
      modelName: 'MiniMax',
      variant: 'default',
    }))
    act(() => hook.result.current.composer.settings?.onYoloChange(true))
    expect(hook.result.current.target.kind).toBe('BOUND_THREAD')
  })

  it('surfaces a bound stop failure through the controller action error', async () => {
    vi.mocked(harnessService.stopThread).mockRejectedValueOnce(new Error('stop failed'))
    localStorage.setItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:probe`,
      JSON.stringify({ kind: 'BOUND_THREAD', threadId: THREAD_ID }),
    )
    const hook = renderController()
    await waitFor(() => expect(hook.result.current.activeDraft).not.toBeNull())
    act(() => hook.result.current.composer.onCommand(testCommand('stop')))
    await waitFor(() => expect(hook.result.current.error).toBe('stop failed'))
  })
})

function threadFixture(threadId: string, overrides: Partial<HarnessThreadDTO> = {}) {
  return thread({ threadId, ...overrides })
}

function renderPane(
  owner: { type: 'CHAT' | 'CANVAS'; id: string },
  environments: EnvironmentCardDTO[] = [],
) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  const result = render(
    <QueryClientProvider client={client}>
      <AgentPane
        owner={owner}
        paneId="pane-1"
        agents={agents}
        environments={environments}
        focused
      />
    </QueryClientProvider>,
  )
  return { ...result, client }
}

function renderController({
  agents: controllerAgents = agents,
  owner = { type: 'CHAT' as const, id: CHAT_ID },
}: {
  agents?: typeof agents
  owner?: { type: 'CHAT' | 'CANVAS'; id: string }
} = {}) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return renderHook(() => useAgentPaneController({
    owner,
    paneId: 'probe',
    agents: controllerAgents,
    environments: [],
    defaults: {},
    focused: true,
  }), {
    wrapper: ({ children }) => (
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    ),
  })
}

function testCommand(
  id: ThreadCommand['id'] | 'disabled',
  overrides: Partial<ThreadCommand> = {},
): ThreadCommand {
  return {
    id: id as ThreadCommand['id'],
    label: id,
    description: '',
    ...overrides,
  }
}

describe('branchDraftFromEntry and branchDraftFromEntryPath environment replay', () => {
  const fallbackDraft: BranchDraft = {
    agentName: 'assistant',
    model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'default' },
    environmentName: 'initial-env',
    yoloEnabled: false,
  }

  function createEntry(payload: Record<string, unknown>, entryId = 'e1', parentEntryId: string | null = null): HarnessSessionEntryDTO {
    return {
      entryId,
      sessionId: 's1',
      parentEntryId,
      entryType: 'MESSAGE',
      payloadJson: JSON.stringify(payload),
      createTime: '2026-01-01T00:00:00Z',
    }
  }

  /**
   * 测试意图：验证 branchDraftFromEntry 对 settings.environmentName 的重放防御规则：
   * 1. 文本值选中该环境；
   * 2. 显式 null 清除该环境；
   * 3. 缺少该键时保留 fallback 环境；
   * 4. 非字符串非空（如数值、对象、undefined）等不可信载荷保留 fallback 环境。
   */
  it('handles environmentName correctly on single entry replay', () => {
    // 文本值选中
    const selected = branchDraftFromEntry(
      createEntry({ settings: { environmentName: 'custom-env' } }),
      fallbackDraft,
    )
    expect(selected?.environmentName).toBe('custom-env')

    // 显式 null 清除
    const cleared = branchDraftFromEntry(
      createEntry({ settings: { environmentName: null } }),
      fallbackDraft,
    )
    expect(cleared?.environmentName).toBeNull()

    // 缺少 environmentName 键：保留 fallback
    const missingKey = branchDraftFromEntry(
      createEntry({ settings: { agentName: 'coder' } }),
      fallbackDraft,
    )
    expect(missingKey?.environmentName).toBe('initial-env')

    // 畸形/不可信载荷：保留 fallback
    for (const malformed of [123, true, {}, []]) {
      const result = branchDraftFromEntry(
        createEntry({ settings: { environmentName: malformed } }),
        fallbackDraft,
      )
      expect(result?.environmentName).toBe('initial-env')
    }
  })

  /**
   * 测试意图：验证 branchDraftFromEntryPath 沿 entry 祖先链依序回放时，environmentName 的变更与清除能够正确链式传递。
   */
  it('replays environment changes sequentially along the entry path', () => {
    const entries: HarnessSessionEntryDTO[] = [
      createEntry({ settings: { agentName: 'a1', environmentName: 'env-1' } }, 'root', null),
      createEntry({ settings: { agentName: 'a2' } }, 'child-1', 'root'),
      createEntry({ settings: { environmentName: null } }, 'child-2', 'child-1'),
    ]

    // 到 root：环境为 env-1
    const atRoot = branchDraftFromEntryPath(entries, 'root', fallbackDraft)
    expect(atRoot?.environmentName).toBe('env-1')

    // 到 child-1：未改动环境，继承 env-1
    const atChild1 = branchDraftFromEntryPath(entries, 'child-1', fallbackDraft)
    expect(atChild1?.environmentName).toBe('env-1')

    // 到 child-2：显式 null，环境被清空
    const atChild2 = branchDraftFromEntryPath(entries, 'child-2', fallbackDraft)
    expect(atChild2?.environmentName).toBeNull()
  })

  /**
   * 测试意图：验证 unbound footer 按 activeDraft.environmentName 匹配卡片，
   * 未知 name 显示 unavailable 而不是 none。
   */
  it('projects unbound environment status from the active draft', async () => {
    const envCard: EnvironmentCardDTO = {
      id: 'uuid-env-ready',
      name: 'cluster-ready',
      status: 'READY',
      ready: true,
      lastSeen: '2026-09-19T00:00:00.000Z',
      capabilities: [],
      rootPath: null,
      version: '1',
      createTime: '2026-09-19T00:00:00.000Z',
      updateTime: '2026-09-19T00:00:00.000Z',
    }

    // 1. unbound 面板：未知环境名称显示 unavailable
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    const { result: unboundResult } = renderHook(
      () =>
        useAgentPaneController({
          owner: { type: 'CHAT', id: CHAT_ID },
          paneId: 'p1',
          target: { kind: 'NEW_SESSION_DRAFT' },
          agents,
          environments: [envCard],
          defaults: {},
        }),
      {
        wrapper: ({ children }) => (
          <QueryClientProvider client={client}>{children}</QueryClientProvider>
        ),
      },
    )

    await waitFor(() => expect(unboundResult.current.activeDraft).not.toBeNull())

    // 本地草稿切换为已知环境
    act(() => {
      unboundResult.current.composer.settings?.onEnvironmentChange('cluster-ready')
    })
    expect(unboundResult.current.boundEnvironment?.name).toBe('cluster-ready')
    expect(unboundResult.current.environmentReady).toBe(true)

    // 本地草稿切换为未知环境：不可用且标记 false
    act(() => {
      unboundResult.current.composer.settings?.onEnvironmentChange('unknown-env')
    })
    expect(unboundResult.current.boundEnvironment?.name).toBe('unknown-env')
    expect(unboundResult.current.environmentReady).toBe(false)

    // 本地草稿清空为 None
    act(() => {
      unboundResult.current.composer.settings?.onEnvironmentChange(null)
    })
    expect(unboundResult.current.boundEnvironment).toBeNull()
    expect(unboundResult.current.environmentReady).toBeUndefined()
  })
})
