import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, renderHook, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AgentPane } from '@/features/ai/runtime/AgentPane'
import {
  branchDraftFromEntry,
  branchDraftFromEntryPath,
  sessionSelectionItem,
  threadSelectionItem,
  useAgentPaneController,
} from '@/features/ai/runtime/useAgentPaneController'
import type { BranchDraft } from '@/features/ai/chat/branch-draft'
import { createTextPart, partsToText } from '@/features/ai/composer/composer-parts'
import { agentService } from '@/shared/api/agent-service'
import { chatService } from '@/shared/api/chat-service'
import { ApiError } from '@/shared/api/client'
import { harnessService } from '@/shared/api/harness-service'
import type {
  HarnessModelRequestDebugDTO,
  HarnessSessionDTO,
  HarnessSessionEntryDTO,
  HarnessThreadCommandDTO,
  HarnessThreadDTO,
  HarnessThreadSnapshotDTO,
  ModelInvocationDTO,
  ProviderRequestPreviewDTO,
  ToolInvocationDTO,
} from '@/shared/api/contracts/ai-runtime'
import type { EnvironmentCardDTO } from '@/shared/api/contracts/ai-environment'
import type { ThreadCommand } from '@/features/ai/runtime/thread-panel/thread-commands'
import { queryKeys } from '@/shared/lib/query-keys'
import { setLocale } from '@/shared/i18n'

const CHAT_ID = 'chat-1'
const THREAD_ID = '11111111-2222-4333-8444-555555555555'

const { fakeApplicationEvents } = vi.hoisted(() => {
  const manager = { subscribe: vi.fn(() => vi.fn()) }
  return { fakeApplicationEvents: { useApplicationEvents: () => manager } }
})

/**
 * 附件上传默认走真实 storage 单例与真实 Web Worker 哈希；这里整体替换成
 * 内存实现，保留组件的 reserve → put → complete 调用路径，但禁止任何真实网络。
 */
const { fakeStorage } = vi.hoisted(() => {
  const reserveUpload = vi.fn(async () => ({
    id: '99999999-8888-4777-8666-555555555555',
    state: 'PENDING' as const,
    blobId: null,
    presignedPut: { method: 'PUT' as const, url: 'https://storage.test/put', headers: {} },
    expiresAt: null,
  }))
  const completeUpload = vi.fn(async (uploadId: string) => ({
    id: uploadId,
    state: 'READY' as const,
    blobId: `blob-${uploadId}`,
    presignedPut: null,
    expiresAt: null,
  }))
  const deleteUpload = vi.fn(async () => undefined)
  const uploadFile = vi.fn(async () => undefined)
  return {
    fakeStorage: {
      reserveUpload,
      completeUpload,
      deleteUpload,
      uploadFile,
      getBlobDownloadUrl: vi.fn(async () => ({ url: 'https://storage.test/blob', expiresAt: null })),
      getBlobPreviewUrl: vi.fn(async () => null),
    },
  }
})

vi.mock('@/shared/api/storage-service', () => ({
  storageService: fakeStorage,
  createStorageService: () => fakeStorage,
}))

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
vi.mock('@/shared/api/harness-service', () => ({
  harnessService: {
    acceptCommandBatch: vi.fn(),
    listSessionThreads: vi.fn(),
    listSessionEntries: vi.fn(),
    getThreadSnapshot: vi.fn(),
    getModelRequestDebug: vi.fn(),
    compactThread: vi.fn(),
    setThreadYolo: vi.fn(),
    stopThread: vi.fn(),
    decideApproval: vi.fn(),
    renameSession: vi.fn(),
    renameThread: vi.fn(),
    previewProviderRequest: vi.fn(),
    getThreadTree: vi.fn(),
  },
}))

const agents: AgentDefinitionDTO[] = [{
  name: 'assistant',
  description: null,
  systemPrompt: null,
  model: 'minimax/MiniMax',
  variant: 'default',
  environmentId: 'env-local-1',
  config: { inheritParentEnvironment: true, tools: [], skills: [], subagents: [] },
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
    parentThreadId: null,
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
  // Debug 结构化投影默认不可用：只有显式声明的用例才渲染 Debug 区（含预览入口）。
  vi.mocked(harnessService.getModelRequestDebug).mockReset()
  vi.mocked(harnessService.previewProviderRequest).mockReset()
  vi.mocked(harnessService.listSessionEntries).mockResolvedValue([])
  vi.mocked(harnessService.getThreadTree).mockResolvedValue([])
  vi.mocked(harnessService.acceptCommandBatch).mockResolvedValue(acceptedResponse())
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

afterEach(() => {
  // 内存 Worker 只服务附件用例，不污染同文件其它用例
  vi.unstubAllGlobals()
})

describe('AgentPane orchestration', () => {
  it('sends NEW_SESSION as one atomic command-batches request', async () => {
    const user = userEvent.setup()
    renderPane({ type: 'CHAT', chatId: CHAT_ID })
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
    renderPane({ type: 'CHAT', chatId: CHAT_ID })
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
    renderPane({ type: 'CHAT', chatId: CHAT_ID })
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
    const firstRender = renderPane({ type: 'CHAT', chatId: CHAT_ID })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.type(composer, 'persist me')
    await user.click(screen.getByRole('button', { name: '发送消息' }))
    await screen.findByRole('button', { name: '重试' })
    const firstRequest = vi.mocked(harnessService.acceptCommandBatch).mock.calls[0]?.[0]
    firstRender.unmount()

    renderPane({ type: 'CHAT', chatId: CHAT_ID })
    const retry = await screen.findByRole('button', { name: '重试' })
    await user.click(retry)
    await waitFor(() => expect(harnessService.acceptCommandBatch).toHaveBeenCalledTimes(2))
    expect(vi.mocked(harnessService.acceptCommandBatch).mock.calls[1]?.[0]).toEqual(firstRequest)
  })

  it('abandons an unknown outcome by restoring the frozen composer parts', async () => {
    const user = userEvent.setup()
    vi.mocked(harnessService.acceptCommandBatch).mockRejectedValueOnce(new Error('timeout'))
    renderPane({ type: 'CHAT', chatId: CHAT_ID })
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
    renderPane({ type: 'CHAT', chatId: CHAT_ID })
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
    renderPane({ type: 'CHAT', chatId: CHAT_ID })
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
    renderPane({ type: 'CHAT', chatId: CHAT_ID })
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
    renderPane({ type: 'CHAT', chatId: CHAT_ID })
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
    renderPane({ type: 'CHAT', chatId: CHAT_ID })
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
    renderPane({ type: 'CHAT', chatId: CHAT_ID })
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
    renderPane({ type: 'CHAT', chatId: CHAT_ID })
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
          owner={{ type: 'CHAT', chatId: CHAT_ID }}
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
    renderPane({ type: 'CHAT', chatId: CHAT_ID })
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
    renderPane({ type: 'CHAT', chatId: CHAT_ID })
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
    renderPane({ type: 'CHAT', chatId: CHAT_ID })
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
    renderPane({ type: 'CHAT', chatId: CHAT_ID }, [{
      id: 'env-local-1',
      name: 'local',
      userName: null,
      homeDirectory: null,
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
    renderPane({ type: 'CHAT', chatId: CHAT_ID })
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
      processing: false,
      model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'default' },
      headMessagePreview: 'thread preview',
    }])
    renderPane({ type: 'CHAT', chatId: CHAT_ID })
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
    renderPane({ type: 'CHAT', chatId: CHAT_ID })
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
    renderPane({ type: 'CHAT', chatId: CHAT_ID })
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
    expect(hook.result.current.error).toContain('等待完成或重试原操作')

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
    const view = renderPane({ type: 'CHAT', chatId: CHAT_ID })
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
      processing: true,
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
    const view = renderPane({ type: 'CHAT', chatId: CHAT_ID })
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
        <AgentPane owner={{ type: 'CHAT', chatId: CHAT_ID }} paneId="pane-1" agents={agents} focused />
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

  it('keeps Chat thread navigation available', async () => {
    const hook = renderController({ owner: { type: 'CHAT', chatId: CHAT_ID } })
    await waitFor(() => expect(hook.result.current.activeDraft).not.toBeNull())
    act(() => hook.result.current.composer.onCommand(testCommand('thread')))
    expect(hook.result.current.interaction).toBe('thread-sessions')
  })

  it('shows required names as primary selection titles and falls back previews only', async () => {
    const user = userEvent.setup()
    vi.mocked(harnessService.acceptCommandBatch).mockRejectedValueOnce({})
    renderPane({ type: 'CHAT', chatId: CHAT_ID })
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
      processing: false,
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
    const emptyPane = renderPane({ type: 'CHAT', chatId: CHAT_ID })
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
    renderPane({ type: 'CHAT', chatId: CHAT_ID })
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
    renderPane({ type: 'CHAT', chatId: CHAT_ID })
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
    expect(harnessService.getThreadTree).not.toHaveBeenCalled()
  })

  it('opens the agent relationship tree only for a bound thread and keeps pane toggles independent', async () => {
    // 关系树不是 slash 面板；打开才请求，关闭后不再轮询，多个 Pane 不共享展开状态。
    const user = userEvent.setup()
    vi.useFakeTimers({ shouldAdvanceTime: true })
    localStorage.setItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
      JSON.stringify({ kind: 'BOUND_THREAD', threadId: THREAD_ID }),
    )
    vi.mocked(harnessService.getThreadTree).mockResolvedValue([{
      threadId: THREAD_ID,
      parentThreadId: null,
      name: 'thread-name',
      agentName: 'assistant',
      model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'default' },
      status: 'IDLE',
      processing: false,
      turnCount: 1,
      toolCallCount: 0,
      outcome: 'COMPLETED',
    }])
    const bound = renderPane({ type: 'CHAT', chatId: CHAT_ID })
    const toggle = await screen.findByRole('button', { name: 'Agent 关系' })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    expect(harnessService.getThreadTree).not.toHaveBeenCalled()
    await user.click(toggle)
    expect(await screen.findByRole('region', { name: 'Agent 关系' })).toBeInTheDocument()
    await waitFor(() => expect(harnessService.getThreadTree).toHaveBeenCalledWith(THREAD_ID))
    expect(await screen.findByText('已完成')).toBeInTheDocument()
    expect(screen.getByRole('textbox', { name: '给 AI 发送消息' })).toBeInTheDocument()

    const callsAfterOpen = vi.mocked(harnessService.getThreadTree).mock.calls.length
    await user.click(toggle)
    expect(screen.queryByRole('region', { name: 'Agent 关系' })).not.toBeInTheDocument()
    await act(async () => {
      await vi.advanceTimersByTimeAsync(6_000)
    })
    expect(vi.mocked(harnessService.getThreadTree).mock.calls.length).toBe(callsAfterOpen)
    bound.unmount()

    const draft = renderPane({ type: 'CHAT', chatId: 'chat-draft' })
    await screen.findByLabelText('给 AI 发送消息')
    expect(screen.queryByRole('button', { name: 'Agent 关系' })).not.toBeInTheDocument()
    draft.unmount()

    const client = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    render(
      <QueryClientProvider client={client}>
        {['pane-a', 'pane-b'].map((paneId) => (
          <AgentPane
            key={paneId}
            owner={{ type: 'CHAT', chatId: CHAT_ID }}
            paneId={paneId}
            agents={agents}
            initialTarget={{ kind: 'BOUND_THREAD', threadId: THREAD_ID }}
          />
        ))}
      </QueryClientProvider>,
    )
    const toggles = await screen.findAllByRole('button', { name: 'Agent 关系' })
    expect(toggles).toHaveLength(2)
    await user.click(toggles[0]!)
    expect(toggles[0]).toHaveAttribute('aria-expanded', 'true')
    expect(toggles[1]).toHaveAttribute('aria-expanded', 'false')
    expect(screen.getAllByRole('region', { name: 'Agent 关系' })).toHaveLength(1)
    vi.useRealTimers()
  })

  it('isolates an open agent tree when the bound thread changes', async () => {
    // 打开后的关系树跟随当前绑定；切换 Thread 时不得继续展示上一棵树。
    const user = userEvent.setup()
    const threadB = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'
    localStorage.setItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
      JSON.stringify({ kind: 'BOUND_THREAD', threadId: THREAD_ID }),
    )
    vi.mocked(harnessService.getThreadTree).mockImplementation(async (threadId) => [{
      threadId,
      parentThreadId: null,
      name: threadId === THREAD_ID ? 'tree A' : 'tree B',
      agentName: 'assistant',
      model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'default' },
      status: 'IDLE',
      processing: false,
      turnCount: 1,
      toolCallCount: 0,
      outcome: 'COMPLETED',
    }])
    vi.mocked(harnessService.getThreadSnapshot).mockImplementation(async (threadId) =>
      snapshot(thread({ threadId, name: threadId === THREAD_ID ? 'thread A' : 'thread B' })),
    )
    vi.mocked(chatService.listChatSessions).mockResolvedValue([{
      sessionId: 'session-1',
      name: 'session',
      createdAt: null,
      lastActivityAt: null,
      firstMessagePreview: null,
      threadCount: 2,
    }])
    vi.mocked(harnessService.listSessionThreads).mockResolvedValue([{
      threadId: threadB,
      name: 'thread B',
      status: 'IDLE',
      processing: false,
      createdAt: null,
      updatedAt: null,
      model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'default' },
      headMessagePreview: null,
    }])
    renderPane({ type: 'CHAT', chatId: CHAT_ID })
    await user.click(await screen.findByRole('button', { name: 'Agent 关系' }))
    expect(await screen.findByRole('link', { name: 'tree A' })).toBeInTheDocument()

    const composer = screen.getByRole('textbox', { name: '给 AI 发送消息' })
    await user.click(composer)
    await user.keyboard('/thread{Enter}')
    await user.click(await screen.findByRole('option', { name: /session/ }))
    await user.click(await screen.findByRole('option', { name: /thread B/ }))

    expect(await screen.findByRole('link', { name: 'tree B' })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'tree A' })).not.toBeInTheDocument()
    expect(harnessService.getThreadTree).toHaveBeenCalledWith(threadB)
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
    renderPane({ type: 'CHAT', chatId: CHAT_ID })
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
    renderPane({ type: 'CHAT', chatId: CHAT_ID })
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
    renderPane({ type: 'CHAT', chatId: CHAT_ID })
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
    renderPane({ type: 'CHAT', chatId: CHAT_ID })
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
    renderPane({ type: 'CHAT', chatId: CHAT_ID })
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
    renderPane({ type: 'CHAT', chatId: CHAT_ID })
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
      processing: false,
      model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'default' },
      headMessagePreview: null,
    }])
    renderPane({ type: 'CHAT', chatId: CHAT_ID })
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
    expect(hook.result.current.error).toContain('等待完成或重试原操作')

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
    renderPane({ type: 'CHAT', chatId: CHAT_ID })
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

  it('renders localized WAITING_CHILDREN status label when bound thread is waiting for child threads', async () => {
    // 测试意图：当绑定的 Thread 处于 WAITING_CHILDREN 状态时，AgentPane 必须将 workingLabel 传给 ChatPanel 并展示“等待子 Thread”。
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(
      snapshot(threadFixture(THREAD_ID, { status: 'WAITING_CHILDREN', processing: false })),
    )
    localStorage.setItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
      JSON.stringify({ kind: 'BOUND_THREAD', threadId: THREAD_ID }),
    )
    renderPane({ type: 'CHAT', chatId: CHAT_ID })
    await waitFor(() => {
      expect(screen.getByText('等待子 Thread')).toBeInTheDocument()
    })
  })

  it('renders localized QUEUED status label when bound thread is queued', async () => {
    // 测试意图：当绑定的 Thread 处于 QUEUED 排队状态时，AgentPane 必须将 workingLabel 传给 ChatPanel 并展示“排队中”。
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(
      snapshot(threadFixture(THREAD_ID, { status: 'QUEUED', processing: false })),
    )
    localStorage.setItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
      JSON.stringify({ kind: 'BOUND_THREAD', threadId: THREAD_ID }),
    )
    renderPane({ type: 'CHAT', chatId: CHAT_ID })
    await waitFor(() => {
      expect(screen.getByText('排队中')).toBeInTheDocument()
    })
  })

  it('isolates ISSUE_AGENT owner storage and routing from independent thread pane without crosstalk', async () => {
    // 测试意图：验证当 owner 为 ISSUE_AGENT 时，其生命周期与无 owner 的子线程路由或 CHAT owner 完全隔离，
    // 不会向 CHAT 存储命名空间写入 target，也不会调用 chat 专有服务，两套架构 owner 语义不混淆。
    const issueOwner: AgentRuntimeOwnerDTO = {
      type: 'ISSUE_AGENT',
      issueId: 'issue-42',
      agentName: 'architect',
    }
    const issueThreadId = '00000000-0000-0000-0000-000000000042'
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(
      snapshot(threadFixture(issueThreadId, { name: 'Architect Thread' })),
    )

    const client = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })

    render(
      <QueryClientProvider client={client}>
        <AgentPane
          owner={issueOwner}
          paneId="pane-issue"
          agents={agents}
          initialTarget={{ kind: 'BOUND_THREAD', threadId: issueThreadId }}
          focused
        />
      </QueryClientProvider>,
    )

    await waitFor(() => {
      expect(screen.getAllByText('Architect Thread').length).toBeGreaterThan(0)
    })

    expect(chatService.listChatSessions).not.toHaveBeenCalled()
    expect(localStorage.getItem('kk-studio.agent-pane-target.CHAT:chat-1:pane-issue')).toBeNull()
  })
})

function threadFixture(threadId: string, overrides: Partial<HarnessThreadDTO> = {}) {
  return thread({ threadId, ...overrides })
}

function renderPane(
  owner: { type: 'CHAT'; chatId: string },
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
  owner = { type: 'CHAT' as const, chatId: CHAT_ID },
}: {
  agents?: typeof agents
  owner?: { type: 'CHAT'; chatId: string }
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
      userName: null,
      homeDirectory: null,
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
          owner: { type: 'CHAT', chatId: CHAT_ID },
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

  it('rejects submissions with attachments when controlled by onSubmitInstruction', async () => {
    // 测试意图：验证在宿主受控指令模式下，提交含有附件的草稿会被明确拦截拒绝并设置 actionError，而非静默丢弃附件
    const onSubmitInstruction = vi.fn()
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    })

    const { result } = renderHook(
      () =>
        useAgentPaneController({
          owner: { type: 'ISSUE_AGENT', issueId: 'issue-1', agentName: 'coder' },
          paneId: 'p-controlled',
          initialTarget: { kind: 'BOUND_THREAD', threadId: THREAD_ID },
          agents: [],
          environments: [],
          defaults: {},
          onSubmitInstruction,
        }),
      {
        wrapper: ({ children }) => (
          <QueryClientProvider client={client}>{children}</QueryClientProvider>
        ),
      },
    )

    act(() => {
      result.current.composer.onSubmit([
        { type: 'text', partId: 'p-1', text: 'Here is document' },
        { type: 'attachment', partId: 'p-2', uploadId: 'up-123', filename: 'doc.pdf' },
      ])
    })

    expect(result.current.error).toBe(
      '受控 Issue 模式暂不支持附件上传，请通过公开证据上传或在正文中说明',
    )
    expect(onSubmitInstruction).not.toHaveBeenCalled()
  })

  it('does not admit unbound Issue creation or generic commands even through controller callbacks', () => {
    // 测试意图：绕开按钮直接调用回调时，受控 Issue 仍不能创建 Session、发送批次或预览。
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const { result } = renderHook(() => useAgentPaneController({
      owner: { type: 'ISSUE_AGENT', issueId: 'issue-1', agentName: 'coder' },
      paneId: 'issue-probe',
      agents,
      environments: [],
      defaults: {},
      focused: false,
      onSubmitInstruction: vi.fn(),
    }), {
      wrapper: ({ children }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>,
    })
    expect(result.current.composer.disabled).toBe(true)
    // 新契约：预览只由 Debug 标题按钮触发，受控 Issue 的标题按钮恒为不可用。
    expect(result.current.previewDisabled).toBe(true)
    expect(result.current.previewDisabledReason).toBe('当前不支持预览')
    act(() => {
      void result.current.handlePreview()
      result.current.composer.onSubmit([createTextPart('not a new session')])
      result.current.composer.onCommand(testCommand('new'))
      result.current.composer.onCommand(testCommand('compact'))
      result.current.composer.onCommand(testCommand('rename-thread'))
    })
    expect(result.current.target.kind).toBe('NEW_SESSION_DRAFT')
    expect(harnessService.acceptCommandBatch).not.toHaveBeenCalled()
    expect(harnessService.previewProviderRequest).not.toHaveBeenCalled()
    expect(harnessService.compactThread).not.toHaveBeenCalled()
    expect(harnessService.renameThread).not.toHaveBeenCalled()
  })

/** Debug 视图的结构化投影：它就绪时 Debug 区才渲染「下一次请求预览」标题按钮。 */
function modelRequestDebug(): HarnessModelRequestDebugDTO {
  return {
    kind: 'NEXT_REQUEST_PREVIEW',
    generatedAt: '2026-09-27T05:00:00Z',
    model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'default' },
    environmentName: null,
    systemInstruction: 'system prompt',
    tools: [],
    skills: [],
    subagents: [],
    cacheControl: null,
    planningError: null,
    frozenInvocation: null,
  }
}

function mockDebugProjection() {
  vi.mocked(harnessService.getModelRequestDebug).mockResolvedValue(modelRequestDebug())
}

/** 预览入口只属于绑定 Thread 的 Debug 视图，因此必须先落到 BOUND_THREAD 目标。 */
function bindPaneTarget() {
  localStorage.setItem(
    `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
    JSON.stringify({ kind: 'BOUND_THREAD', threadId: THREAD_ID }),
  )
}

/** 用真实的 /debug 斜杠命令切到 Debug 视图，返回仍挂载的 composer。 */
async function openDebugView(user: ReturnType<typeof userEvent.setup>) {
  const composer = await screen.findByLabelText('给 AI 发送消息')
  await user.click(composer)
  await user.keyboard('/debug{Enter}')
  await screen.findByRole('listbox', { name: '事件' })
  return composer
}

/**
 * Thread 快照读取闸门：默认持续提供面板已见的旧快照；
 * serveFresh() 之后的每次读取（含预览前的 fresh GET）改由用例决定，可返回推进后的快照或悬挂的 Promise。
 */
function snapshotGate(initial: HarnessThreadSnapshotDTO = snapshot()) {
  let fresh: (() => Promise<HarnessThreadSnapshotDTO>) | null = null
  vi.mocked(harnessService.getThreadSnapshot)
    .mockImplementation(async () => (fresh ? fresh() : initial))
  return (serve: () => Promise<HarnessThreadSnapshotDTO>) => {
    fresh = serve
  }
}

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((complete, fail) => {
    resolve = complete
    reject = fail
  })
  return { promise, resolve, reject }
}

function previewResponse(overrides: Partial<ProviderRequestPreviewDTO> = {}): ProviderRequestPreviewDTO {
  return {
    kind: 'DRAFT_REQUEST_PREVIEW',
    providerType: 'OPENAI',
    modelName: 'MiniMax',
    bodyByteSize: 120,
    bodyJson: '{"messages":[{"role":"user","content":"draft"}]}',
    sourceHeadEntryId: 'head-1',
    generatedAt: '2026-09-27T05:00:00Z',
    ...overrides,
  }
}

function queuedCommand(): HarnessThreadCommandDTO {
  return {
    threadId: THREAD_ID,
    sequence: '1',
    type: 'USER_MESSAGE',
    state: 'QUEUED',
    idempotencyKey: 'cmd-queued-1',
    payloadJson: '{}',
    cancelledAt: null,
    createTime: '2026-09-27T05:00:00Z',
  }
}

function activeModelInvocation(): ModelInvocationDTO {
  return {
    id: 'inv-1',
    threadId: THREAD_ID,
    turnStartEntryId: 'turn-1',
    requestHeadEntryId: 'head-1',
    status: 'STREAMING',
    attempt: 1,
    streamCheckpointJson: null,
    resultJson: null,
    errorJson: null,
    resultEntryId: null,
    createTime: '2026-09-27T05:00:00Z',
    updateTime: '2026-09-27T05:00:01Z',
  }
}

function activeToolInvocation(): ToolInvocationDTO {
  return {
    id: 'tool-1',
    modelInvocationId: 'inv-1',
    assistantEntryId: 'assistant-1',
    callIndex: 0,
    status: 'RUNNING',
    attempt: 1,
    toolCallId: 'call-1',
    toolName: 'read',
    rendererKey: 'tool',
    environmentId: null,
    argumentsJson: '{}',
    approvalJson: null,
    resultJson: null,
    errorJson: null,
    createTime: '2026-09-27T05:00:00Z',
    updateTime: '2026-09-27T05:00:01Z',
  }
}

/** 预览失败同时投影到 Debug 预览区与活动错误区，按文案断言以免依赖提示条数。 */
async function expectAlertText(text: string) {
  await waitFor(() => {
    expect(screen.getAllByRole('alert').some((node) => node.textContent?.includes(text))).toBe(true)
  })
}

/**
 * 点击 Debug 标题按钮并确认预览前的 fresh GET 真的发出去了。
 * 之后的“不得 POST”断言因此不会因为流程根本没启动而空转通过。
 */
async function clickPreviewAndAwaitFreshGet(
  user: ReturnType<typeof userEvent.setup>,
  trigger: HTMLElement,
) {
  const before = vi.mocked(harnessService.getThreadSnapshot).mock.calls.length
  await user.click(trigger)
  await waitFor(() =>
    expect(vi.mocked(harnessService.getThreadSnapshot).mock.calls.length).toBeGreaterThan(before))
}

/** fakeStorage 分配的服务端 upload 句柄；草稿里的客户端 localId 永远不等于它。 */
const SERVER_UPLOAD_ID = '99999999-8888-4777-8666-555555555555'

/**
 * 真实组件默认用 Web Worker 算 sha256（jsdom 无 Worker，且会拉真实模块资源）。
 * 这里用同协议的内存 Worker 顶替，保留 hashFile 的调用路径。
 */
function stubHashWorker() {
  class FakeHashWorker {
    private listeners: Record<string, ((event: unknown) => void)[]> = {}
    addEventListener(type: string, listener: (event: unknown) => void) {
      this.listeners[type] = [...(this.listeners[type] ?? []), listener]
    }
    removeEventListener(type: string, listener: (event: unknown) => void) {
      this.listeners[type] = (this.listeners[type] ?? []).filter((fn) => fn !== listener)
    }
    postMessage(data: { requestId: string }) {
      for (const listener of this.listeners.message ?? []) {
        listener({ data: { requestId: data.requestId, sha256: 'a'.repeat(64) } })
      }
    }
    terminate() {}
  }
  vi.stubGlobal('Worker', FakeHashWorker)
}

/** 通过 Composer 的隐藏 file input 走真实上传管线（hash → reserve → put → complete）。 */
async function uploadAttachment(
  user: ReturnType<typeof userEvent.setup>,
  file: File,
): Promise<string> {
  const input = document.querySelector<HTMLInputElement>('input[type="file"]')
  expect(input).not.toBeNull()
  await user.upload(input!, file)
  await waitFor(() => expect(fakeStorage.completeUpload).toHaveBeenCalledWith(SERVER_UPLOAD_ID))
  const pill = document.querySelector<HTMLElement>('[data-part-type="attachment"]')
  return pill?.dataset.uploadId ?? ''
}

/** 用真实的 Composer 权限菜单改变 branch draft 设置，消息草稿一个字都不动。 */
async function toggleYolo(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole('button', { name: '权限模式' }))
  await user.click(await screen.findByRole('option', { name: 'YOLO' }))
  // 权限控件读的就是 activeDraft：文案翻转即证明 branch draft 真的变了
  await waitFor(() =>
    expect(screen.getByRole('button', { name: '权限模式' })).toHaveTextContent('YOLO'))
}

describe('previewProviderRequest in AgentPane / useAgentPaneController', () => {
    it('keeps the Debug preview trigger disabled for a controlled Issue even on its bound thread', async () => {
      // 测试意图：受控 Issue 宿主既不暴露预览回调也不允许预览，
      // Debug 标题按钮必须以「当前不支持预览」禁用，且点击不产生任何预览请求。
      const user = userEvent.setup()
      mockDebugProjection()
      const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
      render(
        <QueryClientProvider client={client}>
          <AgentPane
            owner={{ type: 'ISSUE_AGENT', issueId: 'issue-1', agentName: 'coder' }}
            paneId="issue-pane"
            agents={agents}
            initialTarget={{ kind: 'BOUND_THREAD', threadId: THREAD_ID }}
            capabilities={{ allowGenericChat: false, allowBranching: false }}
            onSubmitInstruction={vi.fn()}
          />
        </QueryClientProvider>,
      )
      const composer = await screen.findByLabelText('给 AI 发送消息')
      await user.click(composer)
      await user.keyboard('/debug{Enter}')

      const trigger = await screen.findByRole('button', { name: '下一次请求预览 (当前不支持预览)' })
      expect(trigger).toBeDisabled()
      await user.click(trigger)
      expect(harnessService.previewProviderRequest).not.toHaveBeenCalled()
    })

    it('hides the Debug preview trigger for a new session draft target', async () => {
      // 测试意图：新建草稿没有可预览的绑定 Thread，即使切到 Debug 也没有预览入口。
      const user = userEvent.setup()
      mockDebugProjection()
      renderPane({ type: 'CHAT', chatId: CHAT_ID })
      const composer = await screen.findByLabelText('给 AI 发送消息')
      await user.type(composer, 'no bound thread yet')
      await user.click(composer)
      await user.keyboard('/debug{Enter}')

      expect(screen.queryByRole('button', { name: /下一次请求预览/ })).not.toBeInTheDocument()
      expect(harnessService.previewProviderRequest).not.toHaveBeenCalled()
    })

    it('enables the Debug preview trigger only once the bound draft is previewable', async () => {
      // 测试意图：空草稿按「草稿为空」禁用标题按钮；草稿可预览后才允许点击，
      // 禁用期间既不发 fresh GET 也不发预览 POST。
      const user = userEvent.setup()
      bindPaneTarget()
      mockDebugProjection()
      renderPane({ type: 'CHAT', chatId: CHAT_ID })
      const composer = await openDebugView(user)

      expect(await screen.findByRole('button', { name: '下一次请求预览 (草稿为空)' })).toBeDisabled()
      expect(harnessService.getThreadSnapshot).toHaveBeenCalledTimes(1)
      expect(harnessService.previewProviderRequest).not.toHaveBeenCalled()

      await user.click(composer)
      await user.type(composer, 'ready draft')
      expect(await screen.findByRole('button', { name: '下一次请求预览' })).toBeEnabled()
    })

    it('posts the live draft with the fresh thread cursor and keeps the draft on success', async () => {
      // 测试意图：预览先读 fresh 快照再用它重建游标，因此 POST 里的
      // expectedHeadEntryId/expectedNextCommandSequence 只能来自 fresh GET，
      // 而面板缓存仍停在旧游标；成功后草稿必须原样保留。
      const user = userEvent.setup()
      bindPaneTarget()
      mockDebugProjection()
      const serveFresh = snapshotGate()
      vi.mocked(harnessService.previewProviderRequest).mockResolvedValueOnce(previewResponse({
        bodyJson: '{"messages":[{"role":"user","content":"preview test message"}]}',
        sourceHeadEntryId: 'head-2',
        snapshotNotice: 'Draft preview snapshot',
      }))

      renderPane({ type: 'CHAT', chatId: CHAT_ID })
      const composer = await openDebugView(user)
      await user.click(composer)
      await user.type(composer, 'preview test message')
      const trigger = await screen.findByRole('button', { name: '下一次请求预览' })
      serveFresh(() => Promise.resolve(snapshot(thread({ headEntryId: 'head-2', nextCommandSequence: '7' }))))
      await user.click(trigger)

      await waitFor(() => expect(harnessService.previewProviderRequest).toHaveBeenCalledTimes(1))
      const [threadId, request] = vi.mocked(harnessService.previewProviderRequest).mock.calls[0]!
      expect(threadId).toBe(THREAD_ID)
      expect(request.owner).toEqual({ type: 'CHAT', chatId: CHAT_ID })
      expect(request.target).toEqual({
        type: 'THREAD',
        threadId: THREAD_ID,
        expectedHeadEntryId: 'head-2',
        expectedNextCommandSequence: '7',
      })
      expect(request.commands).toHaveLength(1)
      expect(request.commands[0]).toMatchObject({
        type: 'USER_MESSAGE',
        contents: [{ type: 'TEXT', text: 'preview test message' }],
      })

      // Debug 检查器展示回包
      expect(await screen.findByRole('heading', { level: 3, name: '请求预览' })).toBeInTheDocument()
      expect(screen.getByText('DRAFT_REQUEST_PREVIEW')).toBeInTheDocument()
      expect(screen.getByTestId('preview-request-body')).toHaveTextContent('preview test message')
      // 成功预览不清空草稿
      expect(composer).toHaveTextContent('preview test message')
    })

    it('blocks the preview POST and refreshes the cached snapshot when the fresh branch settings changed', async () => {
      // 测试意图：fresh 快照的 branch settings 与冻结的 effectiveBase 不一致时，
      // 本地明确报「会话设置已变化」并把 fresh 快照写回缓存，绝不发出预览 POST。
      const user = userEvent.setup()
      bindPaneTarget()
      mockDebugProjection()
      const serveFresh = snapshotGate()
      const view = renderPane({ type: 'CHAT', chatId: CHAT_ID })
      const composer = await openDebugView(user)
      await user.click(composer)
      await user.type(composer, 'settings moved')
      const trigger = await screen.findByRole('button', { name: '下一次请求预览' })
      serveFresh(() => Promise.resolve(snapshot(thread({
        headEntryId: 'head-3',
        branchSettings: {
          agentName: 'coder',
          model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'default' },
          environmentName: null,
        },
      }))))
      await user.click(trigger)

      await expectAlertText('会话设置已变化，请确认后重试')
      expect(harnessService.previewProviderRequest).not.toHaveBeenCalled()
      await waitFor(() => expect(
        view.client.getQueryData(queryKeys.threads.snapshot(THREAD_ID))?.thread.headEntryId,
      ).toBe('head-3'))
      expect(composer).toHaveTextContent('settings moved')
    })

    it.each([
      [
        'a busy thread',
        () => snapshot(thread({ status: 'MODEL_STREAMING', processing: true })),
        '会话正在运行中，无法预览',
      ],
      [
        'queued commands',
        () => snapshot(thread(), { queuedCommands: [queuedCommand()] }),
        '队列中有未处理命令，无法预览',
      ],
      [
        'an active model invocation',
        () => snapshot(thread(), { modelInvocation: activeModelInvocation() }),
        '会话正在运行中，无法预览',
      ],
      [
        'active tool invocations',
        () => snapshot(thread(), { toolInvocations: [activeToolInvocation()] }),
        '会话正在运行中，无法预览',
      ],
    ])('blocks the preview POST when the fresh snapshot has %s', async (_case, serveSnapshot, message) => {
      // 测试意图：在途执行与排队只出现在 fresh 快照里，本地投影仍显示空闲；
      // 预览必须以 fresh 判定为准并给出明确原因，绝不发出 POST。
      const user = userEvent.setup()
      bindPaneTarget()
      mockDebugProjection()
      const serveFresh = snapshotGate()
      renderPane({ type: 'CHAT', chatId: CHAT_ID })
      const composer = await openDebugView(user)
      await user.click(composer)
      await user.type(composer, 'fresh state moved on')
      const trigger = await screen.findByRole('button', { name: '下一次请求预览' })
      serveFresh(serveSnapshot)
      await user.click(trigger)

      await expectAlertText(message)
      expect(harnessService.previewProviderRequest).not.toHaveBeenCalled()
      expect(composer).toHaveTextContent('fresh state moved on')
    })

    it('drops the fresh GET result when the draft changed while the snapshot was loading', async () => {
      // 测试意图：fresh GET 在途时草稿被继续编辑，回包不再可信：
      // 既不发 POST，也不把旧游标的快照投进检查器。
      const user = userEvent.setup()
      bindPaneTarget()
      mockDebugProjection()
      const serveFresh = snapshotGate()
      const gate = deferred<HarnessThreadSnapshotDTO>()
      renderPane({ type: 'CHAT', chatId: CHAT_ID })
      const composer = await openDebugView(user)
      await user.click(composer)
      await user.type(composer, 'initial text')
      const trigger = await screen.findByRole('button', { name: '下一次请求预览' })
      serveFresh(() => gate.promise)
      await clickPreviewAndAwaitFreshGet(user, trigger)

      await user.type(composer, ' edited')
      await act(async () => {
        gate.resolve(snapshot(thread({ headEntryId: 'head-2', nextCommandSequence: '7' })))
        await gate.promise
      })

      expect(harnessService.previewProviderRequest).not.toHaveBeenCalled()
      expect(screen.queryByTestId('preview-request-body')).not.toBeInTheDocument()
      expect(screen.queryByRole('alert')).not.toBeInTheDocument()
      // 草稿已被继续编辑（插入点落在开头），两段文字都必须保留
      expect(composer).toHaveTextContent('initial text')
      expect(composer).toHaveTextContent('edited')
    })

    it('drops the fresh GET result when the target changed while the snapshot was loading', async () => {
      // 测试意图：fresh GET 在途时 /new 把目标切离绑定 Thread，
      // 迟到的快照不得再触发 POST 或渲染任何预览检查器。
      const user = userEvent.setup()
      bindPaneTarget()
      mockDebugProjection()
      const serveFresh = snapshotGate()
      const gate = deferred<HarnessThreadSnapshotDTO>()
      renderPane({ type: 'CHAT', chatId: CHAT_ID })
      const composer = await openDebugView(user)
      await user.click(composer)
      await user.type(composer, 'moving target')
      const trigger = await screen.findByRole('button', { name: '下一次请求预览' })
      serveFresh(() => gate.promise)
      await clickPreviewAndAwaitFreshGet(user, trigger)

      await user.click(composer)
      await user.clear(composer)
      await user.keyboard('/new{Enter}')
      await act(async () => {
        gate.resolve(snapshot(thread({ headEntryId: 'head-2', nextCommandSequence: '7' })))
        await gate.promise
      })

      expect(harnessService.previewProviderRequest).not.toHaveBeenCalled()
      expect(screen.queryByTestId('preview-request-body')).not.toBeInTheDocument()
      expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    })

    it('drops the fresh GET result when the pane unmounted while the snapshot was loading', async () => {
      // 测试意图：fresh GET 在途时面板卸载，迟到的快照不得继续请求或更新已卸载的状态。
      const user = userEvent.setup()
      bindPaneTarget()
      mockDebugProjection()
      const serveFresh = snapshotGate()
      const gate = deferred<HarnessThreadSnapshotDTO>()
      const view = renderPane({ type: 'CHAT', chatId: CHAT_ID })
      const composer = await openDebugView(user)
      await user.click(composer)
      await user.type(composer, 'unmount me')
      const trigger = await screen.findByRole('button', { name: '下一次请求预览' })
      serveFresh(() => gate.promise)
      await clickPreviewAndAwaitFreshGet(user, trigger)

      view.unmount()
      await act(async () => {
        gate.resolve(snapshot(thread({ headEntryId: 'head-2', nextCommandSequence: '7' })))
        await gate.promise
      })

      expect(harnessService.previewProviderRequest).not.toHaveBeenCalled()
    })

    it('shows the mapped 409 reason without echoing the raw detail and keeps the draft', async () => {
      // 测试意图：409 只按白名单 reason 呈现本地文案，绝不回显 detail；
      // 预览失败也不清空草稿，用户可以直接改后再试。
      const user = userEvent.setup()
      bindPaneTarget()
      mockDebugProjection()
      vi.mocked(harnessService.previewProviderRequest).mockRejectedValueOnce(
        new ApiError('thread cursor moved', 409, 'CONFLICT', { reason: 'PREVIEW_STALE_CURSOR' }),
      )

      renderPane({ type: 'CHAT', chatId: CHAT_ID })
      const composer = await openDebugView(user)
      await user.click(composer)
      await user.type(composer, 'draft to keep')
      await user.click(await screen.findByRole('button', { name: '下一次请求预览' }))

      await expectAlertText('会话游标已过期，请刷新状态后重试')
      expect(harnessService.previewProviderRequest).toHaveBeenCalledTimes(1)
      expect(screen.queryByText(/thread cursor moved/)).not.toBeInTheDocument()
      expect(composer).toHaveTextContent('draft to keep')
    })

    it('drops the preview response when the draft changed while the POST was in flight', async () => {
      // 测试意图：POST 在途时草稿被继续编辑，回包属于旧草稿：
      // 不得写进 Debug 检查器，也不得把新草稿的输入当成已预览。
      const user = userEvent.setup()
      bindPaneTarget()
      mockDebugProjection()
      const gate = deferred<ProviderRequestPreviewDTO>()
      vi.mocked(harnessService.previewProviderRequest).mockReturnValue(gate.promise)

      renderPane({ type: 'CHAT', chatId: CHAT_ID })
      const composer = await openDebugView(user)
      await user.click(composer)
      await user.type(composer, 'initial text')
      await user.click(await screen.findByRole('button', { name: '下一次请求预览' }))
      await waitFor(() => expect(harnessService.previewProviderRequest).toHaveBeenCalledTimes(1))

      await user.type(composer, ' edited')
      await act(async () => {
        gate.resolve(previewResponse({ bodyJson: '{"messages":[{"role":"user","content":"initial text"}]}' }))
        await gate.promise
      })

      expect(screen.queryByTestId('preview-request-body')).not.toBeInTheDocument()
      expect(screen.queryByRole('heading', { level: 3, name: '请求预览' })).not.toBeInTheDocument()
      // 草稿已被继续编辑（插入点落在开头），两段文字都必须保留
      expect(composer).toHaveTextContent('initial text')
      expect(composer).toHaveTextContent('edited')
    })

    it('drops the preview response when the target changed while the POST was in flight', async () => {
      // 测试意图：POST 在途时 /new 把目标切离绑定 Thread，
      // 迟到的回包既不渲染检查器也不写入新目标的错误通道。
      const user = userEvent.setup()
      bindPaneTarget()
      mockDebugProjection()
      const gate = deferred<ProviderRequestPreviewDTO>()
      vi.mocked(harnessService.previewProviderRequest).mockReturnValue(gate.promise)

      renderPane({ type: 'CHAT', chatId: CHAT_ID })
      const composer = await openDebugView(user)
      await user.click(composer)
      await user.type(composer, 'moving target')
      await user.click(await screen.findByRole('button', { name: '下一次请求预览' }))
      await waitFor(() => expect(harnessService.previewProviderRequest).toHaveBeenCalledTimes(1))

      await user.click(composer)
      await user.clear(composer)
      await user.keyboard('/new{Enter}')
      await act(async () => {
        gate.resolve(previewResponse())
        await gate.promise
      })

      expect(screen.queryByTestId('preview-request-body')).not.toBeInTheDocument()
      expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    })

    it('drops the preview response when the pane unmounted while the POST was in flight', async () => {
      // 测试意图：POST 在途时面板卸载，迟到的回包既不能触发渲染也不能抛出未处理错误。
      const user = userEvent.setup()
      bindPaneTarget()
      mockDebugProjection()
      const gate = deferred<ProviderRequestPreviewDTO>()
      const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {})
      vi.mocked(harnessService.previewProviderRequest).mockReturnValue(gate.promise)

      const view = renderPane({ type: 'CHAT', chatId: CHAT_ID })
      const composer = await openDebugView(user)
      await user.click(composer)
      await user.type(composer, 'unmount me')
      await user.click(await screen.findByRole('button', { name: '下一次请求预览' }))
      await waitFor(() => expect(harnessService.previewProviderRequest).toHaveBeenCalledTimes(1))

      view.unmount()
      await act(async () => {
        gate.resolve(previewResponse())
        await gate.promise
      })

      expect(harnessService.previewProviderRequest).toHaveBeenCalledTimes(1)
      expect(consoleError).not.toHaveBeenCalled()
      consoleError.mockRestore()
    })

    it('accepts only one preview request for repeated clicks on the Debug title button', async () => {
      // 测试意图：请求期间按钮禁用；用 fireEvent 绕过 DOM 禁用语义重复点击，
      // 验证 in-flight 栅栏（而不是 disabled 属性）挡住了第二、三次预览。
      const user = userEvent.setup()
      bindPaneTarget()
      mockDebugProjection()
      const gate = deferred<ProviderRequestPreviewDTO>()
      vi.mocked(harnessService.previewProviderRequest).mockReturnValue(gate.promise)

      renderPane({ type: 'CHAT', chatId: CHAT_ID })
      const composer = await openDebugView(user)
      await user.click(composer)
      await user.type(composer, 'single flight')
      const trigger = await screen.findByRole('button', { name: '下一次请求预览' })

      // 同一个 React batch 内重复触发，尚未渲染 disabled，真正验证 ref 单飞栅栏。
      act(() => {
        fireEvent.click(trigger)
        fireEvent.click(trigger)
        fireEvent.click(trigger)
      })
      await waitFor(() => expect(harnessService.previewProviderRequest).toHaveBeenCalledTimes(1))
      expect(trigger).toBeDisabled()
      fireEvent.click(trigger)
      fireEvent.click(trigger)
      expect(harnessService.previewProviderRequest).toHaveBeenCalledTimes(1)

      await act(async () => {
        gate.resolve(previewResponse())
        await gate.promise
      })
      expect(harnessService.previewProviderRequest).toHaveBeenCalledTimes(1)
      expect(await screen.findByTestId('preview-request-body')).toBeInTheDocument()
    })

    it('previews a ready attachment through the Debug title button with the server upload handle', async () => {
      // 测试意图：草稿 pill 永远持有客户端 localId，POST 必须换成服务端 upload 句柄；
      // 预览成功既不提交，也不消费或释放草稿里的附件。
      const user = userEvent.setup()
      stubHashWorker()
      bindPaneTarget()
      mockDebugProjection()
      vi.mocked(harnessService.previewProviderRequest).mockResolvedValueOnce(previewResponse({
        bodyJson: '{"messages":[{"role":"user","content":"look at this"}]}',
      }))

      renderPane({ type: 'CHAT', chatId: CHAT_ID })
      const composer = await openDebugView(user)
      await user.click(composer)
      await user.type(composer, 'look at this')
      const localId = await uploadAttachment(user, new File(['bytes'], 'shot.png', { type: 'image/png' }))
      expect(localId).toBeTruthy()
      expect(localId).not.toBe(SERVER_UPLOAD_ID)

      const trigger = await screen.findByRole('button', { name: '下一次请求预览' })
      expect(trigger).toBeEnabled()
      await user.click(trigger)

      await waitFor(() => expect(harnessService.previewProviderRequest).toHaveBeenCalledTimes(1))
      const contents = vi.mocked(harnessService.previewProviderRequest).mock.calls[0]![1]
        .commands.at(-1)?.contents
      expect(contents?.find((content) => content.type === 'ATTACHMENT'))
        .toMatchObject({ type: 'ATTACHMENT', uploadId: SERVER_UPLOAD_ID })
      expect(contents?.find((content) => content.type === 'TEXT'))
        .toMatchObject({ type: 'TEXT', text: 'look at this' })

      // 成功预览：不提交、不重新上传、不释放
      expect(harnessService.acceptCommandBatch).not.toHaveBeenCalled()
      expect(fakeStorage.completeUpload).toHaveBeenCalledTimes(1)
      expect(fakeStorage.deleteUpload).not.toHaveBeenCalled()
      expect(await screen.findByTestId('preview-request-body')).toBeInTheDocument()
      // 草稿与 pill 都原样保留
      expect(composer).toHaveTextContent('look at this')
      expect(document.querySelector<HTMLElement>('[data-part-type="attachment"]')?.dataset.uploadId)
        .toBe(localId)
    })

    it('accepts only one preview request for repeated clicks with a ready attachment', async () => {
      // 测试意图：附件就绪后连点标题按钮，in-flight 栅栏必须同时挡住重复预览与重复上传/释放。
      const user = userEvent.setup()
      stubHashWorker()
      bindPaneTarget()
      mockDebugProjection()
      const gate = deferred<ProviderRequestPreviewDTO>()
      vi.mocked(harnessService.previewProviderRequest).mockReturnValue(gate.promise)

      renderPane({ type: 'CHAT', chatId: CHAT_ID })
      const composer = await openDebugView(user)
      await user.click(composer)
      await user.type(composer, 'single flight attachment')
      await uploadAttachment(user, new File(['bytes'], 'once.png', { type: 'image/png' }))
      const trigger = await screen.findByRole('button', { name: '下一次请求预览' })

      // 尚未重新渲染按钮的同一 batch 内连点，确保附件预览同样由 ref 单飞。
      act(() => {
        fireEvent.click(trigger)
        fireEvent.click(trigger)
        fireEvent.click(trigger)
      })
      await waitFor(() => expect(harnessService.previewProviderRequest).toHaveBeenCalledTimes(1))
      expect(trigger).toBeDisabled()
      fireEvent.click(trigger)
      fireEvent.click(trigger)
      expect(harnessService.previewProviderRequest).toHaveBeenCalledTimes(1)

      await act(async () => {
        gate.resolve(previewResponse())
        await gate.promise
      })
      expect(harnessService.previewProviderRequest).toHaveBeenCalledTimes(1)
      expect(fakeStorage.completeUpload).toHaveBeenCalledTimes(1)
      expect(fakeStorage.deleteUpload).not.toHaveBeenCalled()
      expect(composer).toHaveTextContent('single flight attachment')
    })

    it('drops the fresh GET result when the branch draft settings changed while the snapshot was loading', async () => {
      // 测试意图：消息草稿一个字都没动，只有 branch draft 的权限设置变了。
      // 过期判定必须同时比较 branch draft，否则会用旧设置去发预览。
      const user = userEvent.setup()
      bindPaneTarget()
      mockDebugProjection()
      const serveFresh = snapshotGate()
      const gate = deferred<HarnessThreadSnapshotDTO>()
      renderPane({ type: 'CHAT', chatId: CHAT_ID })
      const composer = await openDebugView(user)
      await user.click(composer)
      await user.type(composer, 'settings moved')
      const trigger = await screen.findByRole('button', { name: '下一次请求预览' })
      serveFresh(() => gate.promise)
      await clickPreviewAndAwaitFreshGet(user, trigger)

      await toggleYolo(user)
      expect(harnessService.setThreadYolo).toHaveBeenCalledTimes(1)
      await act(async () => {
        gate.resolve(snapshot(thread({ headEntryId: 'head-2', nextCommandSequence: '7' })))
        await gate.promise
      })

      expect(harnessService.previewProviderRequest).not.toHaveBeenCalled()
      expect(screen.queryByTestId('preview-request-body')).not.toBeInTheDocument()
      expect(screen.queryByRole('alert')).not.toBeInTheDocument()
      // 消息草稿未变，说明拦截来自设置变化而不是输入变化
      expect(composer).toHaveTextContent('settings moved')
    })

    it('drops the preview response when the branch draft settings changed while the POST was in flight', async () => {
      // 测试意图：POST 在途时只改 branch draft 设置（消息草稿不变），
      // 迟到的回包属于旧设置组合，不得写进检查器或错误通道。
      const user = userEvent.setup()
      bindPaneTarget()
      mockDebugProjection()
      const gate = deferred<ProviderRequestPreviewDTO>()
      vi.mocked(harnessService.previewProviderRequest).mockReturnValue(gate.promise)

      renderPane({ type: 'CHAT', chatId: CHAT_ID })
      const composer = await openDebugView(user)
      await user.click(composer)
      await user.type(composer, 'settings moved')
      await user.click(await screen.findByRole('button', { name: '下一次请求预览' }))
      await waitFor(() => expect(harnessService.previewProviderRequest).toHaveBeenCalledTimes(1))

      await toggleYolo(user)
      expect(harnessService.setThreadYolo).toHaveBeenCalledTimes(1)
      await act(async () => {
        gate.resolve(previewResponse())
        await gate.promise
      })

      expect(screen.queryByTestId('preview-request-body')).not.toBeInTheDocument()
      expect(screen.queryByRole('alert')).not.toBeInTheDocument()
      expect(composer).toHaveTextContent('settings moved')
    })

    it('does not display a stale 409 after the draft changes', async () => {
      // 测试意图：POST 在途时草稿变化，随后到达的 409 只属于旧请求，
      // 不能覆盖当前草稿的错误提示。
      const user = userEvent.setup()
      bindPaneTarget()
      mockDebugProjection()
      const gate = deferred<ProviderRequestPreviewDTO>()
      vi.mocked(harnessService.previewProviderRequest)
        .mockReturnValue(gate.promise)

      renderPane({ type: 'CHAT', chatId: CHAT_ID })
      const composer = await openDebugView(user)
      await user.click(composer)
      await user.type(composer, 'before')
      await user.click(await screen.findByRole('button', { name: '下一次请求预览' }))
      await waitFor(() => expect(harnessService.previewProviderRequest).toHaveBeenCalledTimes(1))

      await user.type(composer, ' after')
      await act(async () => {
        gate.reject(new ApiError('obsolete conflict', 409, 'CONFLICT', { reason: 'PREVIEW_STALE_CURSOR' }))
        await gate.promise.catch(() => undefined)
      })

      expect(screen.queryByRole('alert')).not.toBeInTheDocument()
      // 草稿已被继续编辑（插入点落在开头），两段文字都必须保留
      expect(composer).toHaveTextContent('before')
      expect(composer).toHaveTextContent('after')
    })
  })

  describe('null owner and new owner union access control barriers', () => {
    it('defaults to bound thread target without crashing on null owner and keeps read-only state', () => {
      // 测试意图：当 owner 未传时，不调用 ownerIdentity/访问 owner.type，避免崩溃；
      // 目标默认为只读已知 Thread，composer 处于禁用状态。
      const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
      const { result } = renderHook(
        () =>
          useAgentPaneController({
            paneId: THREAD_ID,
            agents,
            environments: [],
            defaults: {},
            focused: false,
          }),
        {
          wrapper: ({ children }) => (
            <QueryClientProvider client={client}>{children}</QueryClientProvider>
          ),
        },
      )

      expect(result.current.target).toEqual({ kind: 'BOUND_THREAD', threadId: THREAD_ID })
      expect(result.current.composer.disabled).toBe(true)
      expect(result.current.previewDisabled).toBe(true)
      expect(result.current.previewDisabledReason).toBe('当前不支持预览')
      expect(result.current.composer.settings).toBeUndefined()
    })

    it('enforces read-only controls on null owner: composer disabled, no preview, no settings', async () => {
      // 测试意图：null owner 必须处于只读受控状态，禁用 composer，
      // Debug 标题按钮不可预览，且直接调用预览回调也不会发请求。
      const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
      const { result } = renderHook(
        () =>
          useAgentPaneController({
            paneId: THREAD_ID,
            agents,
            environments: [],
            defaults: {},
            focused: false,
          }),
        {
          wrapper: ({ children }) => (
            <QueryClientProvider client={client}>{children}</QueryClientProvider>
          ),
        },
      )

      expect(result.current.composer.disabled).toBe(true)
      expect(result.current.previewDisabled).toBe(true)
      expect(result.current.previewDisabledReason).toBe('当前不支持预览')
      expect(result.current.composer.settings).toBeUndefined()
      await act(async () => {
        await result.current.handlePreview()
      })
      expect(harnessService.previewProviderRequest).not.toHaveBeenCalled()
    })

    it('blocks all write and navigation attempts under null owner: onSubmit, submitGoal, selectAgent, branching and rename', async () => {
      // 测试意图：绕开 UI 直接调用 controller 方法时，null owner 严禁任何写请求与分叉，绝不发起 batch。
      const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
      const { result } = renderHook(
        () =>
          useAgentPaneController({
            paneId: THREAD_ID,
            agents,
            environments: [],
            defaults: {},
            focused: false,
          }),
        {
          wrapper: ({ children }) => (
            <QueryClientProvider client={client}>{children}</QueryClientProvider>
          ),
        },
      )

      // 1. 发送消息尝试
      act(() => {
        result.current.composer.onSubmit([createTextPart('blocked message')])
      })
      expect(harnessService.acceptCommandBatch).not.toHaveBeenCalled()

      // 2. 提交 Goal 尝试
      act(() => {
        result.current.submitGoal('blocked goal')
      })
      expect(harnessService.acceptCommandBatch).not.toHaveBeenCalled()

      // 3. 切换 Agent 尝试
      act(() => {
        result.current.selectAgent('assistant')
      })
      expect(result.current.error).toBe('当前模式不支持切换 Agent')

      // 4. 分支切换与导航尝试
      act(() => {
        result.current.selectEntry({
          entryId: 'e-1',
          sessionId: 's-1',
          parentEntryId: null,
          entryType: 'MESSAGE',
          payloadJson: '{}',
          createTime: null,
        })
      })
      expect(result.current.error).toBe('当前模式不支持分支切换或分叉')

      act(() => {
        result.current.selectSession({
          sessionId: 's-1',
          name: 'Session 1',
          createdAt: null,
          lastActivityAt: null,
          firstMessagePreview: null,
          threadCount: 1,
        })
      })
      expect(result.current.error).toBe('当前模式不支持分支切换或分叉')

      act(() => {
        result.current.selectThread({
          threadId: 't-2',
          sessionId: 's-1',
          name: 'Thread 2',
          status: 'IDLE',
          createdAt: null,
          updatedAt: null,
          headMessagePreview: null,
        })
      })
      expect(result.current.error).toBe('当前模式不支持分支切换或分叉')

      // 5. 目标切换与重命名尝试
      expect(result.current.target.kind).toBe('BOUND_THREAD')
      await act(async () => {
        await result.current.submitRename('renamed-attempt')
      })
      expect(harnessService.renameThread).not.toHaveBeenCalled()
    })

    it('allows debug and shortcuts commands under null owner while blocking mutating commands', () => {
      // 测试意图：null owner 保持界面历史与 debug/shortcuts 可用，但拦截 new、yolo 等写操作。
      const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
      const { result } = renderHook(
        () =>
          useAgentPaneController({
            paneId: THREAD_ID,
            agents,
            environments: [],
            defaults: {},
            focused: false,
          }),
        {
          wrapper: ({ children }) => (
            <QueryClientProvider client={client}>{children}</QueryClientProvider>
          ),
        },
      )

      // debug 切换正常可用
      expect(result.current.boundViews.mode).toBe('conversation')
      act(() => {
        result.current.composer.onCommand(testCommand('debug'))
      })
      expect(result.current.boundViews.mode).toBe('debug')

      // shortcuts 正常可用
      act(() => {
        result.current.composer.onCommand(testCommand('shortcuts'))
      })
      expect(result.current.interaction).toBe('shortcuts')

      // new 命令被拦截，target 不变
      act(() => {
        result.current.composer.onCommand(testCommand('new'))
      })
      expect(result.current.target).toEqual({ kind: 'BOUND_THREAD', threadId: THREAD_ID })

      // yolo 命令被拦截，不改变状态且不调用服务端
      act(() => {
        result.current.composer.onCommand(testCommand('yolo'))
      })
      expect(harnessService.setThreadYolo).not.toHaveBeenCalled()
    })

    it('renders AgentPane safely without owner: rename button is disabled and header renders title', async () => {
      // 测试意图：AgentPane 接收 undefined owner 时，标题重命名按钮自动禁用，避免无保护访问 owner.type 崩溃。
      const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
      render(
        <QueryClientProvider client={client}>
          <AgentPane
            paneId={THREAD_ID}
            agents={agents}
            environments={[]}
            initialTarget={{ kind: 'BOUND_THREAD', threadId: THREAD_ID }}
            capabilities={{ readOnly: true }}
            focused
          />
        </QueryClientProvider>,
      )

      await screen.findByRole('heading', { level: 2, name: 'thread-name' })
      const renameButton = screen.getByRole('button', { name: '重命名' })
      expect(renameButton).toBeDisabled()
    })

    it('distinguishes CHAT, ISSUE_AGENT, and null owner barrier behaviors', async () => {
      // 测试意图：矩阵级门禁验证——CHAT 的 Debug 标题按钮只被草稿就绪度限制，
      // ISSUE_AGENT 与 NULL 完全不开放预览（不支持预览）且不暴露 settings。
      const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })

      // 1. CHAT owner (等待快照加载以就绪 draft)
      const { result: chatResult } = renderHook(
        () =>
          useAgentPaneController({
            owner: { type: 'CHAT', chatId: CHAT_ID },
            paneId: 'p-chat',
            initialTarget: { kind: 'BOUND_THREAD', threadId: THREAD_ID },
            agents,
            environments: [],
            defaults: {},
            focused: false,
          }),
        { wrapper: ({ children }) => <QueryClientProvider client={client}>{children}</QueryClientProvider> },
      )
      await waitFor(() => expect(chatResult.current.activeDraft).not.toBeNull())
      // 未挂载 composer 时就绪度为「草稿为空」，而不是被宿主屏障拒绝
      expect(chatResult.current.previewDisabledReason).toBe('草稿为空')
      expect(chatResult.current.composer.settings).toBeDefined()

      // 2. ISSUE_AGENT owner
      const { result: issueResult } = renderHook(
        () =>
          useAgentPaneController({
            owner: { type: 'ISSUE_AGENT', issueId: 'issue-99', agentName: 'coder' },
            paneId: 'p-issue',
            initialTarget: { kind: 'BOUND_THREAD', threadId: THREAD_ID },
            agents,
            environments: [],
            defaults: {},
            focused: false,
          }),
        { wrapper: ({ children }) => <QueryClientProvider client={client}>{children}</QueryClientProvider> },
      )
      expect(issueResult.current.previewDisabled).toBe(true)
      expect(issueResult.current.previewDisabledReason).toBe('当前不支持预览')
      expect(issueResult.current.composer.settings).toBeUndefined()

      // 3. NULL owner
      const { result: nullResult } = renderHook(
        () =>
          useAgentPaneController({
            paneId: 'p-null',
            initialTarget: { kind: 'BOUND_THREAD', threadId: THREAD_ID },
            agents,
            environments: [],
            defaults: {},
            focused: false,
          }),
        { wrapper: ({ children }) => <QueryClientProvider client={client}>{children}</QueryClientProvider> },
      )
      expect(nullResult.current.composer.disabled).toBe(true)
      expect(nullResult.current.previewDisabled).toBe(true)
      expect(nullResult.current.previewDisabledReason).toBe('当前不支持预览')
      expect(nullResult.current.composer.settings).toBeUndefined()
    })
  })

  describe('Bound thread replay persistence and real UI controls', () => {
    it('renders retry/abandon controls on unknown post failure and retries exact command batch via real button click', async () => {
      const user = userEvent.setup()
      localStorage.setItem(
        `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
        JSON.stringify({ kind: 'BOUND_THREAD', threadId: THREAD_ID }),
      )
      vi.mocked(harnessService.acceptCommandBatch)
        .mockRejectedValueOnce(new Error('Network disconnected'))
        .mockResolvedValueOnce([] as HarnessThreadCommandDTO[])

      renderPane({ type: 'CHAT', chatId: CHAT_ID })

      const composer = await screen.findByLabelText('给 AI 发送消息')
      await user.click(composer)
      await user.type(composer, 'Hello bound unknown')
      const sendButton = screen.getByRole('button', { name: '发送消息' })
      await user.click(sendButton)

      // 验证初次发送
      await waitFor(() => expect(harnessService.acceptCommandBatch).toHaveBeenCalledTimes(1))
      const firstBatch = vi.mocked(harnessService.acceptCommandBatch).mock.calls[0]?.[0]
      expect(firstBatch).toBeDefined()
      const firstKey = firstBatch?.commands[0]?.idempotencyKey

      // 界面渲染出真实的 bound-pending-controls
      const controls = await screen.findByTestId('bound-pending-controls')
      expect(controls).toBeInTheDocument()

      // 输入框被禁用（aria-disabled="true" 且 contenteditable="false"），防止在 unknown 状态下修改身份
      expect(composer).toHaveAttribute('aria-disabled', 'true')
      expect(composer).toHaveAttribute('contenteditable', 'false')

      // 验证 localStorage 存在冻结的 pending 消息
      const pendingKey = `kk-studio.agent-thread-pending.${THREAD_ID}`
      expect(localStorage.getItem(pendingKey)).not.toBeNull()

      // 点击真实的“重试”按钮
      const retryButton = screen.getByRole('button', { name: '重试' })
      await user.click(retryButton)

      // 验证重试发送了完全相同的 batch（逐字节复用相同 idempotencyKey）
      await waitFor(() => expect(harnessService.acceptCommandBatch).toHaveBeenCalledTimes(2))
      const secondBatch = vi.mocked(harnessService.acceptCommandBatch).mock.calls[1]?.[0]
      expect(secondBatch?.commands[0]?.idempotencyKey).toBe(firstKey)

      // 成功后，controls 消失，localStorage 被清空，输入框解锁
      await waitFor(() => expect(screen.queryByTestId('bound-pending-controls')).toBeNull())
      expect(localStorage.getItem(pendingKey)).toBeNull()
      expect(composer).toHaveAttribute('aria-disabled', 'false')
    })

    it('cleans up local storage pending message and unlocks composer on abandon click', async () => {
      const user = userEvent.setup()
      localStorage.setItem(
        `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
        JSON.stringify({ kind: 'BOUND_THREAD', threadId: THREAD_ID }),
      )
      vi.mocked(harnessService.acceptCommandBatch)
        .mockRejectedValueOnce(new Error('Gateway timeout'))

      renderPane({ type: 'CHAT', chatId: CHAT_ID })

      const composer = await screen.findByLabelText('给 AI 发送消息')
      await user.click(composer)
      await user.type(composer, 'Abandon me')
      await user.click(screen.getByRole('button', { name: '发送消息' }))

      await waitFor(() => expect(harnessService.acceptCommandBatch).toHaveBeenCalledTimes(1))
      await screen.findByTestId('bound-pending-controls')
      const pendingKey = `kk-studio.agent-thread-pending.${THREAD_ID}`
      expect(localStorage.getItem(pendingKey)).not.toBeNull()
      expect(composer).toHaveAttribute('aria-disabled', 'true')

      // 点击真实的“取消/放弃”按钮
      const abandonButton = screen.getByRole('button', { name: '取消' })
      await user.click(abandonButton)

      // 验证控制栏消失，本地 pending 清理，输入框解锁，且未发起新的网络请求
      await waitFor(() => expect(screen.queryByTestId('bound-pending-controls')).toBeNull())
      expect(localStorage.getItem(pendingKey)).toBeNull()
      expect(composer).toHaveAttribute('aria-disabled', 'false')
      expect(harnessService.acceptCommandBatch).toHaveBeenCalledTimes(1)
    })

    it('aborts sending before POST when localStorage persistence fails (fail-closed)', async () => {
      const user = userEvent.setup()
      localStorage.setItem(
        `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
        JSON.stringify({ kind: 'BOUND_THREAD', threadId: THREAD_ID }),
      )
      const originalSetItem = localStorage.setItem.bind(localStorage)
      const storageSpy = vi.spyOn(Storage.prototype, 'setItem').mockImplementation((key, val) => {
        if (key.includes('agent-thread-pending')) {
          throw new Error('QuotaExceeded')
        }
        return originalSetItem(key, val)
      })

      try {
        renderPane({ type: 'CHAT', chatId: CHAT_ID })

        const composer = await screen.findByLabelText('给 AI 发送消息')
        await user.click(composer)
        await user.type(composer, 'Fail closed attempt')
        await user.click(screen.getByRole('button', { name: '发送消息' }))

        // 验证 fail-closed：未调用 acceptCommandBatch
        expect(harnessService.acceptCommandBatch).not.toHaveBeenCalled()
        // 出现明确的存储失败可重试错误提示
        await waitFor(() => {
          expect(screen.getByText(/无法保存发送记录/)).toBeInTheDocument()
        })
      } finally {
        storageSpy.mockRestore()
      }
    })

    it('restores unknown pending message from localStorage on reload and allows retry via real button', async () => {
      const user = userEvent.setup()
      localStorage.setItem(
        `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
        JSON.stringify({ kind: 'BOUND_THREAD', threadId: THREAD_ID }),
      )
      const pendingKey = `kk-studio.agent-thread-pending.${THREAD_ID}`
      // 模拟 POST 刚发出后页面被立即刷新（unknownOutcome 初始存为 false）
      localStorage.setItem(
        pendingKey,
        JSON.stringify({
          threadId: THREAD_ID,
          unknownOutcome: false,
          targetDraft: {
            agentName: 'assistant',
            model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'default' },
            environmentName: null,
            yoloEnabled: false,
          },
          localDraft: [{ type: 'text', partId: 'p-reload', text: 'reload test message' }],
          request: {
            owner: { type: 'CHAT', chatId: CHAT_ID },
            target: {
              type: 'THREAD',
              threadId: THREAD_ID,
              expectedHeadEntryId: 'h1',
              expectedNextCommandSequence: '1',
            },
            commands: [
              {
                type: 'USER_MESSAGE',
                idempotencyKey: 'cmd-reloaded-key-1',
                contents: [{ type: 'TEXT', text: 'reload test message' }],
              },
            ],
          },
        }),
      )
      vi.mocked(harnessService.acceptCommandBatch).mockResolvedValueOnce([] as HarnessThreadCommandDTO[])

      renderPane({ type: 'CHAT', chatId: CHAT_ID })

      // 验证刷新挂载后自动恢复 unknownOutcome = true，并展示 controls
      const controls = await screen.findByTestId('bound-pending-controls')
      expect(controls).toBeInTheDocument()

      const composer = await screen.findByLabelText('给 AI 发送消息')
      expect(composer).toHaveAttribute('aria-disabled', 'true')

      // 点击真实的“重试”按钮
      const retryButton = screen.getByRole('button', { name: '重试' })
      await user.click(retryButton)

      // 验证使用原请求 command idempotencyKey 重发
      await waitFor(() => expect(harnessService.acceptCommandBatch).toHaveBeenCalledTimes(1))
      const batch = vi.mocked(harnessService.acceptCommandBatch).mock.calls[0]?.[0]
      expect(batch?.commands[0]?.idempotencyKey).toBe('cmd-reloaded-key-1')

      // 重试成功后状态清理
      await waitFor(() => expect(screen.queryByTestId('bound-pending-controls')).toBeNull())
      expect(localStorage.getItem(pendingKey)).toBeNull()
      expect(composer).toHaveAttribute('aria-disabled', 'false')
    })

    it('removes retry/abandon controls while a retry is in flight and accepts only one retry request', async () => {
      // 测试意图：unknown 重试会同步把 unknownOutcome 置为 false，控件随之从 DOM 移除；
      // 连点因此无法再次触发重试。同步 in-flight ref 另由 thread controller 单测覆盖。
      const user = userEvent.setup()
      localStorage.setItem(
        `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
        JSON.stringify({ kind: 'BOUND_THREAD', threadId: THREAD_ID }),
      )
      let resolveRetry!: (val: HarnessThreadCommandDTO[]) => void
      const retryPromise = new Promise<HarnessThreadCommandDTO[]>((resolve) => {
        resolveRetry = resolve
      })

      vi.mocked(harnessService.acceptCommandBatch)
        .mockRejectedValueOnce(new Error('Network disconnected'))
        .mockReturnValueOnce(retryPromise)

      renderPane({ type: 'CHAT', chatId: CHAT_ID })

      const composer = await screen.findByLabelText('给 AI 发送消息')
      await user.click(composer)
      await user.type(composer, 'Hello rapid click test')
      await user.click(screen.getByRole('button', { name: '发送消息' }))

      await screen.findByTestId('bound-pending-controls')
      const retryBtn = screen.getByRole('button', { name: '重试' })
      const cancelBtn = screen.getByRole('button', { name: '取消' })
      expect(retryBtn).not.toBeDisabled()
      expect(cancelBtn).not.toBeDisabled()

      await user.click(retryBtn)
      await waitFor(() => expect(screen.queryByTestId('bound-pending-controls')).toBeNull())
      expect(screen.queryByRole('button', { name: '重试' })).toBeNull()
      expect(screen.queryByRole('button', { name: '取消' })).toBeNull()
      await user.click(composer)
      expect(harnessService.acceptCommandBatch).toHaveBeenCalledTimes(2)

      resolveRetry([])
      await waitFor(() => expect(harnessService.acceptCommandBatch).toHaveBeenCalledTimes(2))
      expect(screen.queryByTestId('bound-pending-controls')).toBeNull()
    })

    it('keeps an externally updated draft after an in-flight submission succeeds', async () => {
      // 测试意图：绑定 composer 在 pending 期间对用户输入禁用，不能靠 user.type 改草稿。
      // 通过真实 onPartsChange 模拟飞行中的外部草稿更新，成功返回后不得被清空。
      let resolveFirst!: (val: HarnessThreadCommandDTO[]) => void
      const firstCallPromise = new Promise<HarnessThreadCommandDTO[]>((resolve) => {
        resolveFirst = resolve
      })
      vi.mocked(harnessService.acceptCommandBatch).mockReturnValueOnce(firstCallPromise)

      const client = new QueryClient({
        defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
      })
      const { result } = renderHook(
        () =>
          useAgentPaneController({
            owner: { type: 'CHAT', chatId: CHAT_ID },
            paneId: 'pane-draft',
            initialTarget: { kind: 'BOUND_THREAD', threadId: THREAD_ID },
            agents,
            environments: [],
            defaults: {},
            focused: true,
          }),
        { wrapper: ({ children }) => <QueryClientProvider client={client}>{children}</QueryClientProvider> },
      )
      await waitFor(() => expect(result.current.composer.disabled).toBe(false))

      act(() => result.current.composer.onPartsChange([createTextPart('first message')]))
      let submission!: Promise<void>
      act(() => {
        submission = result.current.composer.onSubmit()
      })
      await waitFor(() => expect(harnessService.acceptCommandBatch).toHaveBeenCalledTimes(1))
      expect(result.current.composer.disabled).toBe(true)

      act(() => result.current.composer.onPartsChange([createTextPart('typing next message while in flight')]))
      expect(result.current.composer.parts).toEqual([
        expect.objectContaining({ type: 'text', text: 'typing next message while in flight' }),
      ])

      await act(async () => {
        resolveFirst([])
        await submission
      })
      await waitFor(() => expect(result.current.composer.pending).toBe(false))
      expect(result.current.composer.parts).toEqual([
        expect.objectContaining({ type: 'text', text: 'typing next message while in flight' }),
      ])
    })

    it('rejects interleaved pane from overwriting first pane pending and protects first pending on stop', async () => {
      // 测试意图：第二 pane 先挂载并持有自己的 request，第一 pane 随后写入不同身份。
      // storage 必须拒绝覆盖；第二 pane 的 stop 因身份不匹配不得清理第一 pane 的 pending。
      const user = userEvent.setup()
      localStorage.setItem(
        `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
        JSON.stringify({ kind: 'BOUND_THREAD', threadId: THREAD_ID }),
      )
      let resolvePane2!: (val: HarnessThreadCommandDTO[]) => void
      const pane2Request = new Promise<HarnessThreadCommandDTO[]>((resolve) => {
        resolvePane2 = resolve
      })
      vi.mocked(harnessService.acceptCommandBatch)
        .mockReturnValueOnce(pane2Request)
        .mockRejectedValueOnce(new Error('Network error on pane 1'))
      vi.mocked(harnessService.stopThread).mockResolvedValueOnce({
        status: 'IDLE',
        thread: thread(),
        stoppedTurnEndEntryId: null,
        cancelledCommandCount: 0,
        cancelledUserMessages: [],
      })

      const client2 = new QueryClient({
        defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
      })
      const { result: controller2 } = renderHook(
        () =>
          useAgentPaneController({
            owner: { type: 'CHAT', chatId: CHAT_ID },
            paneId: 'pane-2',
            initialTarget: { kind: 'BOUND_THREAD', threadId: THREAD_ID },
            agents,
            environments: [],
            defaults: {},
            focused: false,
          }),
        { wrapper: ({ children }) => <QueryClientProvider client={client2}>{children}</QueryClientProvider> },
      )
      await waitFor(() => expect(controller2.current.composer.disabled).toBe(false))
      expect(controller2.current.pendingMessage).toBeNull()

      act(() => controller2.current.composer.onPartsChange([createTextPart('pane 2 message')]))
      act(() => {
        void controller2.current.composer.onSubmit()
      })
      await waitFor(() => expect(harnessService.acceptCommandBatch).toHaveBeenCalledTimes(1))
      const pendingKey = `kk-studio.agent-thread-pending.${THREAD_ID}`
      const pane2Stored = localStorage.getItem(pendingKey)
      expect(pane2Stored).not.toBeNull()
      expect(controller2.current.pendingMessage?.request).toBeDefined()

      await act(async () => {
        resolvePane2([])
      })
      await waitFor(() => expect(controller2.current.pendingMessage).toBeNull())
      expect(localStorage.getItem(pendingKey)).toBeNull()

      const pane1 = renderPane({ type: 'CHAT', chatId: CHAT_ID })
      const composer1 = await screen.findByLabelText('给 AI 发送消息')
      await user.click(composer1)
      await user.type(composer1, 'pane 1 message')
      await user.click(screen.getByRole('button', { name: '发送消息' }))
      await screen.findByTestId('bound-pending-controls')

      const pane1Stored = localStorage.getItem(pendingKey)
      expect(pane1Stored).not.toBeNull()
      expect(pane1Stored).not.toBe(pane2Stored)
      expect(harnessService.acceptCommandBatch).toHaveBeenCalledTimes(2)

      act(() => controller2.current.composer.onPartsChange([createTextPart('pane 2 retry')]))
      await act(async () => {
        await controller2.current.composer.onSubmit()
      })
      expect(controller2.current.error).toMatch(/无法保存发送记录/)
      expect(harnessService.acceptCommandBatch).toHaveBeenCalledTimes(2)
      expect(localStorage.getItem(pendingKey)).toBe(pane1Stored)
      expect(controller2.current.pendingMessage).toBeNull()

      await act(async () => {
        const stopped = controller2.current.controller.stopThread()
        await Promise.resolve()
        await stopped
      })
      expect(harnessService.stopThread).toHaveBeenCalledTimes(1)
      expect(localStorage.getItem(pendingKey)).toBe(pane1Stored)
      expect(controller2.current.pendingMessage).toBeNull()

      pane1.unmount()
    })
  })

  describe('selectAgent model follow & validation behavior', () => {
    const claudeModel = {
      providerName: 'anthropic',
      name: 'Claude',
      description: null,
      config: {
        limit: { context: 200000, output: 4096 },
        abilities: { tools: true, reasoning: false, inputModalities: ['TEXT'] },
        pricing: {
          currency: 'USD',
          pricingTier: 'default',
          serviceTier: 'standard',
          serviceTierMultiplier: 1,
        },
        defaultVariant: 'v1',
        variants: [{ id: 'v1' }, { id: 'fast' }],
      },
      version: '0',
      createTime: null,
      updateTime: null,
    }

    const coderAgent: AgentDefinitionDTO = {
      name: 'coder',
      description: null,
      systemPrompt: null,
      model: 'anthropic/Claude',
      variant: 'fast',
      environmentId: null,
      config: { inheritParentEnvironment: true, tools: [], skills: [], subagents: [] },
      version: '0',
      createTime: null,
      updateTime: null,
    }

    const brokenAgent: AgentDefinitionDTO = {
      name: 'broken-agent',
      description: null,
      systemPrompt: null,
      model: 'nonexistent/model',
      variant: null,
      environmentId: null,
      config: { inheritParentEnvironment: true, tools: [], skills: [], subagents: [] },
      version: '0',
      createTime: null,
      updateTime: null,
    }

    // 两类入口均需先验证完整预设；拒绝选择不得清空正文、附件或关闭选择面板。
    it.each(['blank', 'bound'])('preserves draft and interaction on invalid agent selection in %s mode', async (mode) => {
      vi.mocked(agentService.listAgents).mockResolvedValue({
        pageNumber: 1,
        pageSize: 50,
        totalCount: 3,
        results: [agents[0]!, coderAgent, brokenAgent],
      })
      vi.mocked(agentService.listModels).mockResolvedValue({
        pageNumber: 1,
        pageSize: 50,
        totalCount: 2,
        results: [models[0]!, claudeModel],
      })

      if (mode === 'bound') {
        localStorage.setItem(
          `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:probe`,
          JSON.stringify({ kind: 'BOUND_THREAD', threadId: THREAD_ID }),
        )
      }
      const hook = renderController({ agents: [agents[0]!, coderAgent, brokenAgent] })
      await waitFor(() => expect(hook.result.current.activeDraft).not.toBeNull())
      const initialDraft = { ...hook.result.current.activeDraft! }

      act(() => {
        hook.result.current.composer.onPartsChange([
          createTextPart('preserved draft text'),
          { type: 'attachment', partId: 'attachment-1', uploadId: 'local-upload-1', filename: 'notes.txt' },
        ])
        hook.result.current.openInteraction('agent')
      })
      const preservedParts = hook.result.current.composer.parts
      expect(hook.result.current.interaction).toBe('agent')

      // 选择无法解析模型的 agent
      act(() => {
        hook.result.current.selectAgent('broken-agent')
      })

      // 验证：报错、保留 interaction、保留原 draft，绝不将 localDraft 置 null
      expect(hook.result.current.error).toBe('Agent broken-agent 的模型或变体无法解析，请选择其他 Agent。')
      expect(hook.result.current.interaction).toBe('agent')
      expect(hook.result.current.activeDraft).toEqual(initialDraft)
      expect(partsToText(hook.result.current.composer.parts)).toBe('preserved draft text')
      expect(hook.result.current.composer.parts).toEqual(preservedParts)

      // 成功选择 coder agent：关闭交互并原子更新 agentName 和 model
      act(() => {
        hook.result.current.selectAgent('coder')
      })
      expect(hook.result.current.error).toBeNull()
      expect(hook.result.current.interaction).toBeNull()
      expect(hook.result.current.activeDraft?.agentName).toBe('coder')
      expect(hook.result.current.activeDraft?.model).toEqual({
        providerName: 'anthropic',
        modelName: 'Claude',
        variant: 'fast',
      })
      expect(partsToText(hook.result.current.composer.parts)).toBe('preserved draft text')
      expect(hook.result.current.composer.parts).toEqual(preservedParts)

      // 手动修改模型后，再次选择同一 agent 显式重置为该 agent 的预设模型
      act(() => {
        hook.result.current.composer.settings?.onModelChange({
          providerName: 'custom',
          modelName: 'Custom',
          variant: 'v1',
        })
      })
      expect(hook.result.current.activeDraft?.model.modelName).toBe('Custom')

      act(() => {
        hook.result.current.selectAgent('coder')
      })
      expect(hook.result.current.activeDraft?.model).toEqual({
        providerName: 'anthropic',
        modelName: 'Claude',
        variant: 'fast',
      })
    })

    it('atomically follows target agent model in bound mode, sending SET_AGENT+SET_MODEL+USER_MESSAGE on submit', async () => {
      // 发送必须把 Agent 与模型设置放在同一消息批次，不能只更新 UI 标签。
      vi.mocked(agentService.listAgents).mockResolvedValue({
        pageNumber: 1,
        pageSize: 50,
        totalCount: 2,
        results: [agents[0]!, coderAgent],
      })
      vi.mocked(agentService.listModels).mockResolvedValue({
        pageNumber: 1,
        pageSize: 50,
        totalCount: 2,
        results: [models[0]!, claudeModel],
      })

      localStorage.setItem(
        `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:probe`,
        JSON.stringify({ kind: 'BOUND_THREAD', threadId: THREAD_ID }),
      )
      const hook = renderController({ agents: [agents[0]!, coderAgent] })
      await waitFor(() => expect(hook.result.current.activeDraft).not.toBeNull())

      act(() => {
        hook.result.current.selectAgent('coder')
      })
      expect(hook.result.current.activeDraft?.agentName).toBe('coder')
      expect(hook.result.current.activeDraft?.model).toEqual({
        providerName: 'anthropic',
        modelName: 'Claude',
        variant: 'fast',
      })

      await act(async () => {
        await hook.result.current.composer.onSubmit([createTextPart('bound message')])
      })

      expect(harnessService.acceptCommandBatch).toHaveBeenCalledTimes(1)
      const [batchRequest] = vi.mocked(harnessService.acceptCommandBatch).mock.calls[0]!
      expect(batchRequest.commands.map((c) => c.type)).toEqual([
        'SET_AGENT',
        'SET_MODEL',
        'USER_MESSAGE',
      ])
      expect(batchRequest.commands[0]).toMatchObject({ agentName: 'coder' })
      expect(batchRequest.commands[1]).toMatchObject({
        model: { providerName: 'anthropic', modelName: 'Claude', variant: 'fast' },
      })
    })

    it('includes SET_AGENT and SET_MODEL for the new agent model in the bound preview request batch', async () => {
      // 预览与发送使用同一草稿，预览不得遗留旧模型。
      vi.mocked(agentService.listAgents).mockResolvedValue({
        pageNumber: 1,
        pageSize: 50,
        totalCount: 2,
        results: [agents[0]!, coderAgent],
      })
      vi.mocked(agentService.listModels).mockResolvedValue({
        pageNumber: 1,
        pageSize: 50,
        totalCount: 2,
        results: [models[0]!, claudeModel],
      })

      localStorage.setItem(
        `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:probe`,
        JSON.stringify({ kind: 'BOUND_THREAD', threadId: THREAD_ID }),
      )
      mockDebugProjection()
      const serveFresh = snapshotGate()
      vi.mocked(harnessService.previewProviderRequest).mockResolvedValueOnce(previewResponse({
        providerName: 'anthropic',
        modelName: 'Claude',
        bodyJson: '{"messages":[{"role":"user","content":"preview test message"}]}',
        sourceHeadEntryId: 'head-1',
      }))

      const hook = renderController({ agents: [agents[0]!, coderAgent] })
      await waitFor(() => expect(hook.result.current.activeDraft).not.toBeNull())

      // 切换到 coder agent
      act(() => {
        hook.result.current.selectAgent('coder')
        hook.result.current.composer.onPartsChange([createTextPart('preview test message')])
        hook.result.current.composer.onPreviewReadinessChange?.({ canPreview: true, reason: null })
      })
      expect(hook.result.current.activeDraft?.agentName).toBe('coder')
      expect(hook.result.current.activeDraft?.model.modelName).toBe('Claude')

      // 挂载 composerRef handle
      hook.result.current.composer.composerRef.current = {
        preparePreview: () => ({
          payload: [createTextPart('preview test message')],
          localDraft: [createTextPart('preview test message')],
        }),
      }

      serveFresh(() => Promise.resolve(snapshot(thread({ headEntryId: 'head-1', nextCommandSequence: '1' }))))

      await act(async () => {
        await hook.result.current.handlePreview()
      })

      await waitFor(() => expect(harnessService.previewProviderRequest).toHaveBeenCalledTimes(1))
      const [threadId, request] = vi.mocked(harnessService.previewProviderRequest).mock.calls[0]!
      expect(threadId).toBe(THREAD_ID)
      expect(request.commands.map((c) => c.type)).toEqual([
        'SET_AGENT',
        'SET_MODEL',
        'USER_MESSAGE',
      ])
      expect(request.commands[0]).toMatchObject({ agentName: 'coder' })
      expect(request.commands[1]).toMatchObject({
        model: { providerName: 'anthropic', modelName: 'Claude', variant: 'fast' },
      })
    })
  })
})
