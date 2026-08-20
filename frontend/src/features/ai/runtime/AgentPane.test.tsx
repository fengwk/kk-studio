import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, renderHook, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AgentPane } from '@/features/ai/runtime/AgentPane'
import {
  sessionSelectionItem,
  threadSelectionItem,
  useAgentPaneController,
} from '@/features/ai/runtime/useAgentPaneController'
import { createTextPart } from '@/features/ai/composer/composer-parts'
import { agentPaneService } from '@/shared/api/agent-pane-service'
import { agentService } from '@/shared/api/agent-service'
import { ApiError } from '@/shared/api/client'
import { harnessService } from '@/shared/api/harness-service'
import type {
  HarnessSessionEntryDTO,
  HarnessThreadDTO,
  HarnessThreadSnapshotDTO,
} from '@/shared/api/contracts/ai-runtime'
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
    listDirectories: vi.fn(),
  },
}))
vi.mock('@/shared/api/harness-service', () => ({
  harnessService: {
    getSystemPromptPreview: vi.fn(),
    setThreadYolo: vi.fn(),
    stopThread: vi.fn(),
    decideApproval: vi.fn(),
  },
}))
vi.mock('@/shared/api/agent-pane-service', () => ({
  agentPaneService: {
    acceptCommandBatch: vi.fn(),
    listChatSessions: vi.fn(),
    listCanvasSessions: vi.fn(),
    listSessionThreads: vi.fn(),
    listSessionEntries: vi.fn(),
    getThreadSnapshot: vi.fn(),
    compactThread: vi.fn(),
  },
}))

const agents = [{
  name: 'assistant',
  description: null,
  systemPrompt: null,
  model: 'minimax/MiniMax',
  variant: 'default',
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
    threadId: THREAD_ID,
    sessionId: 'session-1',
    headEntryId: 'head-1',
    yoloEnabled: false,
    nextCommandSequence: '1',
    revision: '0',
    status: 'IDLE',
    processing: false,
    branchSettings: {
      environment: null,
      agentName: 'assistant',
      model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'default' },
      activeTools: [],
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
    revision: currentThread.revision,
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
  vi.mocked(agentPaneService.getThreadSnapshot).mockResolvedValue(snapshot())
  vi.mocked(agentPaneService.listSessionEntries).mockResolvedValue([])
  vi.mocked(agentPaneService.acceptCommandBatch).mockResolvedValue(acceptedResponse())
  vi.mocked(harnessService.getSystemPromptPreview).mockResolvedValue({ text: '' })
  vi.mocked(harnessService.setThreadYolo).mockImplementation((threadId, data) =>
    Promise.resolve(threadFixture(threadId, { yoloEnabled: data.yoloEnabled, revision: '1' })),
  )
})

describe('AgentPane orchestration', () => {
  it('sends NEW_SESSION as one atomic command-batches request', async () => {
    const user = userEvent.setup()
    renderPane({ type: 'CHAT', id: CHAT_ID })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.type(composer, 'first message')
    await user.click(screen.getByRole('button', { name: '发送消息' }))

    await waitFor(() => expect(agentPaneService.acceptCommandBatch).toHaveBeenCalledTimes(1))
    const [request] = vi.mocked(agentPaneService.acceptCommandBatch).mock.calls[0]!
    expect(request.target.type).toBe('NEW_SESSION')
    expect(request.target).not.toHaveProperty('kind')
    expect(request.commands).toHaveLength(1)
    expect(request.commands[0]?.type).toBe('USER_MESSAGE')
  })

  it('sends an ENTRY target with zero settings writes when the draft is unchanged', async () => {
    const user = userEvent.setup()
    localStorage.setItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
      JSON.stringify({ kind: 'ENTRY_DRAFT', sessionId: 'session-1', startEntryId: 'entry-1' }),
    )
    renderPane({ type: 'CHAT', id: CHAT_ID })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.type(composer, 'branch message')
    await user.click(screen.getByRole('button', { name: '发送消息' }))

    await waitFor(() => expect(agentPaneService.acceptCommandBatch).toHaveBeenCalledTimes(1))
    const [request] = vi.mocked(agentPaneService.acceptCommandBatch).mock.calls[0]!
    expect(request.target.type).toBe('ENTRY')
    expect(request.commands.map((command) => command.type)).toEqual(['USER_MESSAGE'])
  })

  it('retries an unknown outcome with the exact request and clears the old action error', async () => {
    const user = userEvent.setup()
    vi.mocked(agentPaneService.acceptCommandBatch)
      .mockRejectedValueOnce(new Error('connection lost'))
      .mockResolvedValueOnce(acceptedResponse())
    renderPane({ type: 'CHAT', id: CHAT_ID })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.type(composer, 'retry me')
    await user.click(screen.getByRole('button', { name: '发送消息' }))
    const retry = await screen.findByRole('button', { name: '重试' })
    const firstRequest = vi.mocked(agentPaneService.acceptCommandBatch).mock.calls[0]?.[0]

    await user.click(retry)
    await waitFor(() => expect(agentPaneService.acceptCommandBatch).toHaveBeenCalledTimes(2))
    expect(vi.mocked(agentPaneService.acceptCommandBatch).mock.calls[1]?.[0]).toBe(firstRequest)
    expect(screen.queryByText('connection lost')).not.toBeInTheDocument()
  })

  it('restores an unknown pending acceptance after remount with the same frozen request', async () => {
    const user = userEvent.setup()
    vi.mocked(agentPaneService.acceptCommandBatch).mockRejectedValueOnce(new Error('timeout'))
    const firstRender = renderPane({ type: 'CHAT', id: CHAT_ID })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.type(composer, 'persist me')
    await user.click(screen.getByRole('button', { name: '发送消息' }))
    await screen.findByRole('button', { name: '重试' })
    const firstRequest = vi.mocked(agentPaneService.acceptCommandBatch).mock.calls[0]?.[0]
    firstRender.unmount()

    renderPane({ type: 'CHAT', id: CHAT_ID })
    const retry = await screen.findByRole('button', { name: '重试' })
    await user.click(retry)
    await waitFor(() => expect(agentPaneService.acceptCommandBatch).toHaveBeenCalledTimes(2))
    expect(vi.mocked(agentPaneService.acceptCommandBatch).mock.calls[1]?.[0]).toEqual(firstRequest)
  })

  it('abandons an unknown outcome by restoring the frozen composer parts', async () => {
    const user = userEvent.setup()
    vi.mocked(agentPaneService.acceptCommandBatch).mockRejectedValueOnce(new Error('timeout'))
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
    vi.mocked(agentPaneService.acceptCommandBatch).mockRejectedValueOnce(
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
    vi.mocked(agentPaneService.acceptCommandBatch).mockImplementationOnce(
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
    expect(vi.mocked(agentPaneService.acceptCommandBatch)).toHaveBeenCalledTimes(1)
  })

  it('ignores a late rejection after abandon without restoring a stale error', async () => {
    const user = userEvent.setup()
    let reject: ((error: Error) => void) | null = null
    vi.mocked(agentPaneService.acceptCommandBatch).mockImplementationOnce(
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
    vi.mocked(agentPaneService.acceptCommandBatch).mockRejectedValueOnce(
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
    vi.mocked(agentPaneService.acceptCommandBatch).mockRejectedValueOnce(
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
      new ApiError('yolo state changed', 409, 'CONFLICT', { reason: 'STALE_REVISION' }),
    )
    renderPane({ type: 'CHAT', id: CHAT_ID })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.click(composer)
    await user.keyboard('/yolo{Enter}')

    const dialog = await screen.findByRole('alertdialog')
    expect(dialog).toHaveTextContent('STALE_REVISION')
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
    vi.mocked(agentPaneService.compactThread).mockResolvedValueOnce({
      thread: thread({ revision: '1' }),
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
      expect(agentPaneService.compactThread).toHaveBeenCalledWith(
        THREAD_ID,
        { expectedRevision: '0' },
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
    vi.mocked(agentPaneService.acceptCommandBatch).mockRejectedValueOnce(new Error('network lost'))
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
    vi.mocked(agentPaneService.getThreadSnapshot).mockResolvedValue(
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
      name: 'local',
      ready: true,
      status: 'READY',
      lastSeen: null,
      tools: [],
      skills: [],
    }])
    const composer = await screen.findByLabelText('给 AI 发送消息')

    await user.click(composer)
    await user.keyboard('/agent{Enter}')
    expect(await screen.findByText('assistant')).toBeInTheDocument()
    await user.keyboard('{Escape}')

    await user.click(composer)
    await user.keyboard('/environment{Enter}')
    expect(await screen.findByText('local')).toBeInTheDocument()
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
    vi.mocked(agentPaneService.listChatSessions).mockResolvedValue([{
      sessionId: 'session-1',
      createdAt: null,
      lastActivityAt: null,
      firstMessagePreview: 'first',
      threadCount: 1,
    }])
    vi.mocked(agentPaneService.listSessionThreads).mockResolvedValue([{
      threadId: THREAD_ID,
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
    await waitFor(() => expect(agentPaneService.getThreadSnapshot).toHaveBeenCalledWith(THREAD_ID))
    expect(localStorage.getItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
    )).toContain('BOUND_THREAD')
  })

  it('opens the on-demand Entry Tree for an ENTRY_DRAFT target', async () => {
    const user = userEvent.setup()
    localStorage.setItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
      JSON.stringify({ kind: 'ENTRY_DRAFT', sessionId: 'session-1', startEntryId: 'entry-1' }),
    )
    vi.mocked(agentPaneService.listSessionEntries).mockResolvedValue([{
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
    expect(agentPaneService.listSessionEntries).toHaveBeenCalledWith('session-1')
    await user.click(document.querySelector<HTMLButtonElement>('.history-branch-entry')!)
    await user.click(screen.getByRole('button', { name: '从这里继续当前 Thread' }))
    expect(localStorage.getItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
    )).toContain('ENTRY_DRAFT')
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
    expect(agentPaneService.listSessionEntries).toHaveBeenCalledWith('session-1')
  })

  it('keeps a background realtime revision from changing the persisted target', async () => {
    const user = userEvent.setup()
    vi.mocked(agentPaneService.getThreadSnapshot).mockResolvedValue(
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
    handlers?.onEvent?.('revision', {})
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
    const summary = (threadId: string, preview: string) => ({
      threadId,
      createdAt: null,
      updatedAt: null,
      status: 'MODEL_STREAMING' as const,
      model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'default' },
      headMessagePreview: preview,
    })
    vi.mocked(agentPaneService.getThreadSnapshot).mockImplementation(async (threadId) =>
      snapshot(thread({ threadId, status: 'MODEL_STREAMING', processing: true })),
    )
    vi.mocked(agentPaneService.listChatSessions).mockResolvedValue([{
      sessionId: 'session-1',
      createdAt: null,
      lastActivityAt: null,
      firstMessagePreview: 'session',
      threadCount: 1,
    }])
    vi.mocked(agentPaneService.listSessionThreads)
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
      createdAt: null,
      lastActivityAt: null,
      firstMessagePreview: '',
      threadCount: 0,
    }))
    expect(hook.result.current.interaction).toBe('tree')
    act(() => hook.result.current.selectSession({
      sessionId: 'threaded-session',
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
    act(() => hook.result.current.selectEnvironment({
      name: 'local',
      workspacePath: '.',
    }))
    expect(hook.result.current.activeDraft?.environment).toEqual({
      name: 'local',
      workspacePath: '.',
    })
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
          environment: { name: 'local', workspacePath: '.' },
          agentName: 'assistant',
          model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'default' },
          activeTools: ['search', 1],
        },
      }),
      createTime: null,
    }))
    expect(hook.result.current.target.kind).toBe('ENTRY_DRAFT')
    act(() => hook.result.current.selectEntry({
      entryId: 'entry-null-environment',
      sessionId: 'session-1',
      parentEntryId: null,
      entryType: 'ROOT',
      payloadJson: JSON.stringify({ settings: { environment: null } }),
      createTime: null,
    }))
    act(() => hook.result.current.selectEntry({
      entryId: 'entry-missing-environment',
      sessionId: 'session-1',
      parentEntryId: null,
      entryType: 'ROOT',
      payloadJson: JSON.stringify({ settings: {} }),
      createTime: null,
    }))
    act(() => hook.result.current.selectEntry({
      entryId: 'entry-invalid-environment',
      sessionId: 'session-1',
      parentEntryId: null,
      entryType: 'ROOT',
      payloadJson: JSON.stringify({
        settings: { environment: { name: 1, workspacePath: '' } },
      }),
      createTime: null,
    }))
    await hook.result.current.refreshPaneProjection()
  })

  it('keeps pending acceptance fenced while commands and a direct composer callback race', async () => {
    let resolve: ((value: ReturnType<typeof acceptedResponse>) => void) | null = null
    vi.mocked(agentPaneService.acceptCommandBatch).mockImplementationOnce(
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
    expect(agentPaneService.acceptCommandBatch).not.toHaveBeenCalled()
  })

  it('uses stored composer parts when the submit callback omits an explicit payload', async () => {
    const hook = renderController()
    await waitFor(() => expect(hook.result.current.activeDraft).not.toBeNull())
    act(() => hook.result.current.composer.onPartsChange([createTextPart('stored parts')]))
    act(() => hook.result.current.composer.onSubmit())
    await waitFor(() => expect(agentPaneService.acceptCommandBatch).toHaveBeenCalledTimes(1))
    expect(agentPaneService.acceptCommandBatch.mock.calls[0]?.[0].commands[0]?.type)
      .toBe('USER_MESSAGE')
  })

  it('restores the browser-local draft rather than the resolved submit payload', async () => {
    vi.mocked(agentPaneService.acceptCommandBatch).mockRejectedValueOnce(
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
    vi.mocked(agentPaneService.listCanvasSessions).mockResolvedValue([])
    const hook = renderController({ owner: { type: 'CANVAS', id: CANVAS_ID } })
    await waitFor(() => expect(hook.result.current.activeDraft).not.toBeNull())
    act(() => hook.result.current.composer.onCommand(testCommand('thread')))
    expect(hook.result.current.interaction).toBe('thread-sessions')
    await waitFor(() => expect(agentPaneService.listCanvasSessions).toHaveBeenCalledWith(CANVAS_ID))
  })

  it('falls back for unknown errors and selection previews without hiding the failure', async () => {
    const user = userEvent.setup()
    vi.mocked(agentPaneService.acceptCommandBatch).mockRejectedValueOnce({})
    renderPane({ type: 'CHAT', id: CHAT_ID })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.type(composer, 'unknown error')
    await user.click(screen.getByRole('button', { name: '发送消息' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('请求失败')
    expect(sessionSelectionItem({
      sessionId: 'session-fallback',
      createdAt: null,
      lastActivityAt: null,
      firstMessagePreview: '',
      threadCount: 0,
    }).title).toBe('session-fallback')
    expect(threadSelectionItem({
      threadId: THREAD_ID,
      createdAt: null,
      updatedAt: null,
      status: 'IDLE',
      model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'default' },
      headMessagePreview: null,
    }).title).toBe(THREAD_ID)
  })

  it('does not retain a background subscription for a terminal previous thread', async () => {
    const user = userEvent.setup()
    vi.mocked(agentPaneService.getThreadSnapshot).mockResolvedValue(
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
    act(() => hook.result.current.selectEnvironment({ name: 'local', workspacePath: '.' }))
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
    act(() => hook.result.current.selectEnvironment({ name: 'local', workspacePath: '.' }))
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
  environments: Array<{
    name: string
    ready: boolean
    status: string
    lastSeen: string | null
    tools: string[]
    skills: string[]
  }> = [],
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
