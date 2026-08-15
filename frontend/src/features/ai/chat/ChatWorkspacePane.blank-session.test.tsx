import { useState } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ChatWorkspacePane } from '@/features/ai/chat/ChatWorkspacePane'
import { BLANK_PANE_COMMANDS } from '@/features/ai/chat/chat-workspace-pane/commands'
import { composerDraftStorageKey } from '@/features/ai/composer/composer-draft'
import { ApplicationSettingsProvider } from '@/features/settings/application-settings'
import { agentService } from '@/shared/api/agent-service'
import { ApiError } from '@/shared/api/client'
import { chatService } from '@/shared/api/chat-service'
import { environmentService } from '@/shared/api/environment-service'
import { harnessService } from '@/shared/api/harness-service'
import type {
  HarnessThreadCommandDTO,
  HarnessThreadDTO,
  HarnessThreadSnapshotDTO,
} from '@/shared/api/contracts/ai-runtime'
import type {
  EnvironmentBindingDTO,
  EnvironmentDirectoryDTO,
} from '@/shared/api/contracts/ai-environment'

const { fakeApplicationEvents } = vi.hoisted(() => {
  const manager = { subscribe: () => () => undefined }
  return { fakeApplicationEvents: { useApplicationEvents: () => manager } }
})

vi.mock('@/shared/app-events', () => ({
  useApplicationEvents: fakeApplicationEvents.useApplicationEvents,
}))

const storageMocks = vi.hoisted(() => ({
  reserveUpload: vi.fn(),
  completeUpload: vi.fn(),
  deleteUpload: vi.fn(),
  getBlobOriginalUrl: vi.fn(),
  getBlobPreviewUrl: vi.fn(),
  uploadFile: vi.fn(),
}))
vi.mock('@/shared/api/storage-service', () => ({
  storageService: storageMocks,
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
vi.mock('@/shared/api/harness-service', () => ({
  harnessService: {
    getThreadSnapshot: vi.fn(),
    enqueueCommands: vi.fn(),
    updateThreadHead: vi.fn(),
    stopThread: vi.fn(),
    decideApproval: vi.fn(),
  },
}))
vi.mock('@/shared/api/environment-service', () => ({
  environmentService: {
    listEnvironments: vi.fn().mockResolvedValue([]),
    listDirectories: vi.fn(),
  },
}))

const page = <T,>(results: T[]) => ({
  pageNumber: 1,
  pageSize: 50,
  totalCount: results.length,
  results,
})

/** 默认单层目录响应：root 下有一个直属子目录 proj。 */
const ROOT_DIRECTORY: EnvironmentDirectoryDTO = {
  path: '.',
  displayPath: '.',
  parentPath: '.',
  truncated: false,
  gitBranch: null,
  entries: [{ name: 'proj', path: 'proj' }],
}

function thread(overrides: Partial<HarnessThreadDTO> = {}): HarnessThreadDTO {
  return {
    threadId: 't1',
    sessionId: 's1',
    headEntryId: 'e-root',
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
    createTime: '2026-01-01T00:00:00Z',
    updateTime: '2026-01-02T00:00:00Z',
    ...overrides,
  }
}

function snapshotOf(currentThread: HarnessThreadDTO): HarnessThreadSnapshotDTO {
  return {
    revision: currentThread.revision,
    thread: currentThread,
    entries: [],
    queuedCommands: [],
    modelInvocation: null,
    toolInvocations: [],
    modelAttemptFailures: [],
  }
}

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

function modelEntry() {
  return {
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
        version: 'v1',
        inputPerMillionTokens: 0,
        outputPerMillionTokens: 0,
        cacheReadPerMillionTokens: 0,
        cacheWritePerMillionTokens: 0,
        cacheWriteLongPerMillionTokens: 0,
        reasoningPerMillionTokens: 0,
      },
      defaultVariant: 'default',
      variants: [{ id: 'default', reasoningEffort: null }],
    },
    version: '0',
    createTime: null,
    updateTime: null,
  }
}

function renderBlankPane(overrides?: {
  onThreadChange?: (threadId: string | null) => void
  onAgentChange?: (agentName: string) => Promise<void>
  onYoloChange?: (yoloEnabled: boolean) => Promise<void>
  onEnvironmentChange?: (environment: EnvironmentBindingDTO | null) => Promise<void>
  agentName?: string | null
  yoloEnabled?: boolean
  environment?: EnvironmentBindingDTO | null
  initialThreadId?: string | null
  title?: string | null
  agents?: Array<typeof assistantAgent>
}) {
  const onThreadChange = overrides?.onThreadChange ?? vi.fn()
  const onAgentChange = overrides?.onAgentChange ?? vi.fn(async () => undefined)
  const onYoloChange = overrides?.onYoloChange ?? vi.fn(async () => undefined)
  const onEnvironmentChange = overrides?.onEnvironmentChange ?? vi.fn(async () => undefined)
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  // 有状态包装器：面板状态在 onThreadChange 时推进，使面板在测试中可以完成
  // blank → bound 切换（与 ChatWorkspacePage 的 saveChatPaneState 行为一致）。
  function Harness() {
    const [threadId, setThreadId] = useState<string | null>(
      overrides?.initialThreadId ?? null,
    )
    return (
      <ChatWorkspacePane
        chat={{
          id: 'chat-1',
          title: overrides?.title === undefined ? 'C' : overrides.title,
          agentName: overrides?.agentName === undefined ? 'assistant' : overrides.agentName,
          environment: overrides?.environment ?? null,
          yoloEnabled: overrides?.yoloEnabled ?? false,
          version: '1',
          createTime: null,
          updateTime: null,
        }}
        agents={overrides?.agents ?? [assistantAgent]}
        environments={[
          { name: 'local', ready: true, status: 'READY', lastSeen: null, tools: [], skills: [] },
          {
            name: 'connecting',
            ready: false,
            status: 'CONNECTING',
            lastSeen: null,
            tools: [],
            skills: [],
          },
        ]}
        pane={{ id: 'pane-1', threadId }}
        focused
        threadSort="recent"
        onFocus={() => undefined}
        onThreadChange={(next) => {
          onThreadChange(next)
          setThreadId(next)
        }}
        onThreadSortChange={() => undefined}
        onAgentChange={onAgentChange}
        onYoloChange={onYoloChange}
        onEnvironmentChange={onEnvironmentChange}
      />
    )
  }
  render(
    <ApplicationSettingsProvider>
      <QueryClientProvider client={queryClient}>
        <Harness />
      </QueryClientProvider>
    </ApplicationSettingsProvider>,
  )
  return { onThreadChange, onAgentChange, onYoloChange, onEnvironmentChange }
}

describe('BlankComposerPane /thread and agent error handling', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(agentService.listAgents).mockResolvedValue(page([assistantAgent]))
    vi.mocked(agentService.listModels).mockResolvedValue(page([modelEntry()]))
    vi.mocked(agentService.listProviders).mockResolvedValue(page([]))
    vi.mocked(environmentService.listDirectories).mockResolvedValue(ROOT_DIRECTORY)
    vi.mocked(chatService.listChatThreads).mockResolvedValue([
      thread({ threadId: 't-idle' }),
      thread({ threadId: 't-running', status: 'RUNNING', processing: true }),
    ])
    vi.mocked(harnessService.enqueueCommands).mockResolvedValue([] as HarnessThreadCommandDTO[])
  })

  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('exposes full command table on blank panes with unsupported entries disabled', () => {
    expect(BLANK_PANE_COMMANDS.map((command) => command.id)).toEqual([
      'thread',
      'agent',
      'environment',
      'yolo',
      'tree',
      'stop',
      'new',
      'upload',
      'events',
      'conversation',
      'shortcuts',
    ])
    // /thread 仅切换面板，因此在任何 Thread 存在之前就能生效；/tree、/events 与
    // /conversation 依赖已绑定的 Thread，因此在空面板上保持禁用。/session 已彻底移除。
    expect(BLANK_PANE_COMMANDS.filter((command) => !command.disabled).map((command) => command.id)).toEqual([
      'thread',
      'agent',
      'environment',
      'yolo',
      'upload',
      'shortcuts',
    ])
    expect(BLANK_PANE_COMMANDS.some((command) => command.id === 'session')).toBe(false)
    expect(BLANK_PANE_COMMANDS.find((command) => command.id === 'tree')?.disabled).toBe(true)
    expect(BLANK_PANE_COMMANDS.find((command) => command.id === 'new')?.disabled).toBe(true)
    expect(BLANK_PANE_COMMANDS.find((command) => command.id === 'events')?.disabled).toBe(true)
    expect(BLANK_PANE_COMMANDS.find((command) => command.id === 'conversation')?.disabled).toBe(true)
  })

  it('restores the pane-scoped browser draft before the first Thread exists', async () => {
    localStorage.setItem(
      composerDraftStorageKey('chat:chat-1:pane:pane-1'),
      'restored blank draft',
    )

    // 空面板没有历史消息，仍必须把本地草稿作为队列末位恢复到 composer。
    renderBlankPane()

    expect(await screen.findByLabelText('给 AI 发送消息')).toHaveTextContent(
      'restored blank draft',
    )
  })

  it('keeps Environment selection as a local blank-pane draft (confirm full binding)', async () => {
    const user = userEvent.setup()
    renderBlankPane()
    const composer = await screen.findByLabelText('给 AI 发送消息')

    await user.click(composer)
    await user.keyboard('/environment{Enter}')
    const envModal = await screen.findByLabelText('选择 Environment')
    expect(within(envModal).getByRole('option', { name: /^local/ })).toBeInTheDocument()
    expect(within(envModal).queryByRole('option', { name: /connecting/ })).not.toBeInTheDocument()
    // 选择 local 进入目录浏览（root '.'），确认后才提交完整 binding。
    await user.click(within(envModal).getByRole('option', { name: /^local/ }))
    const dirPanel = await screen.findByLabelText('local 目录')
    expect(within(dirPanel).getByText('当前：.')).toBeInTheDocument()
    await user.click(within(dirPanel).getByRole('button', { name: '使用当前 Workspace' }))

    // 空面板 footer 立即反映 draft（binding name + workspacePath）。
    expect(await screen.findByRole('button', { name: /env:local · ws:\./ })).toBeInTheDocument()

    // 已有 binding：再次打开直接进入目录模式；返回列表后选择“无环境”提交 null。
    await user.click(screen.getByRole('button', { name: /env:local/ }))
    const dirAgain = await screen.findByLabelText('local 目录')
    await user.click(within(dirAgain).getByRole('button', { name: '返回 Environment 列表' }))
    const listAgain = await screen.findByLabelText('选择 Environment')
    await user.click(within(listAgain).getByRole('option', { name: /\uff08\u65e0\uff09/ }))
    expect(await screen.findByRole('button', { name: /env:none/ })).toBeInTheDocument()
  })

  it('closing the workspace picker cancels without mutating the blank pane draft', async () => {
    const user = userEvent.setup()
    const onEnvironmentChange = vi.fn(async () => undefined)
    renderBlankPane({ onEnvironmentChange })
    const composer = await screen.findByLabelText('给 AI 发送消息')

    await user.click(composer)
    await user.keyboard('/environment{Enter}')
    const envModal = await screen.findByLabelText('选择 Environment')
    await user.click(within(envModal).getByRole('option', { name: /^local/ }))
    await screen.findByLabelText('local 目录')
    // Escape 只取消：不调用 onSelect，footer 保持 env:none，Chat 默认值未同步。
    await user.keyboard('{Escape}')
    expect(screen.queryByLabelText('local 目录')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: /env:none/ })).toBeInTheDocument()
    expect(onEnvironmentChange).not.toHaveBeenCalled()
    expect(chatService.createChatThread).not.toHaveBeenCalled()
    expect(harnessService.enqueueCommands).not.toHaveBeenCalled()
  })

  it('materializes the blank pane frozen draft from the catalog (footer shows agent/model)', async () => {
    renderBlankPane()

    // Footer 应反映 catalog 派生的 frozen draft：agent=assistant，model 来自
    // minimax/MiniMax，使用 default variant。Catalog 查询立即解析完成。
    expect(await screen.findByRole('button', { name: /agent:assistant/ })).toBeInTheDocument()
    expect(screen.getByText(/minimax\/MiniMax · default/)).toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: /env:none/ }),
    ).toBeInTheDocument()
    expect(agentService.listModels).toHaveBeenCalled()
    // draft 编辑没有触发任何 service 调用。
    expect(chatService.createChatThread).not.toHaveBeenCalled()
    expect(harnessService.enqueueCommands).not.toHaveBeenCalled()
  })

  it('starts the blank pane draft from the Chat default Environment binding and allows changing it before first send', async () => {
    const user = userEvent.setup()
    renderBlankPane({ environment: { name: 'local', workspacePath: '.' } })
    const composer = await screen.findByLabelText('给 AI 发送消息')

    // Chat 默认 binding 是空面板草稿的起点：footer 立即反映 env:local · ws:.。
    expect(await screen.findByRole('button', { name: /env:local/ })).toBeInTheDocument()

    // 已有 binding：初次打开直接浏览该 environment/path（root '.'）。
    await user.click(screen.getByRole('button', { name: /env:local/ }))
    let dirPanel = await screen.findByLabelText('local 目录')
    expect(within(dirPanel).getByText('当前：.')).toBeInTheDocument()

    // 发送前可清空：返回列表选择（无）=> env:none。
    await user.click(within(dirPanel).getByRole('button', { name: '返回 Environment 列表' }))
    let envModal = await screen.findByLabelText('选择 Environment')
    await user.click(within(envModal).getByRole('option', { name: /\uff08\u65e0\uff09/ }))
    expect(await screen.findByRole('button', { name: /env:none/ })).toBeInTheDocument()

    // 发送前可重新更改：选回 local 并确认 root workspace，首次发送携带完整 binding。
    await user.click(screen.getByRole('button', { name: /env:none/ }))
    envModal = await screen.findByLabelText('选择 Environment')
    await user.click(within(envModal).getByRole('option', { name: /^local/ }))
    dirPanel = await screen.findByLabelText('local 目录')
    await user.click(within(dirPanel).getByRole('button', { name: '使用当前 Workspace' }))
    const createdThread = thread({ threadId: 't-created' })
    vi.mocked(chatService.createChatThread).mockResolvedValue(snapshotOf(createdThread))
    await user.click(composer)
    await user.type(composer, 'hello with default env')
    await user.click(screen.getByRole('button', { name: '发送消息' }))
    await waitFor(() => expect(chatService.createChatThread).toHaveBeenCalled())
    const createArgs = vi.mocked(chatService.createChatThread).mock.calls.at(-1)!
    expect(createArgs[1].branchSettings.environment).toEqual({ name: 'local', workspacePath: '.' })
  })

  it('syncs the Chat default Environment binding when selected or explicitly cleared in the blank pane', async () => {
    const user = userEvent.setup()
    const onEnvironmentChange = vi.fn(async () => undefined)
    renderBlankPane({ onEnvironmentChange })

    await user.click(screen.getByRole('button', { name: /env:none/ }))
    let envModal = await screen.findByLabelText('选择 Environment')
    await user.click(within(envModal).getByRole('option', { name: /^local/ }))
    const dirPanel = await screen.findByLabelText('local 目录')
    await user.click(within(dirPanel).getByRole('button', { name: '使用当前 Workspace' }))
    expect(await screen.findByRole('button', { name: /env:local/ })).toBeInTheDocument()
    expect(onEnvironmentChange).toHaveBeenCalledWith({ name: 'local', workspacePath: '.' })

    // 显式清空：null 同步为 Chat 默认（清空语义，绝不能被当作缺省忽略）。
    await user.click(screen.getByRole('button', { name: /env:local/ }))
    const dirAgain = await screen.findByLabelText('local 目录')
    await user.click(within(dirAgain).getByRole('button', { name: '返回 Environment 列表' }))
    envModal = await screen.findByLabelText('选择 Environment')
    await user.click(within(envModal).getByRole('option', { name: /\uff08\u65e0\uff09/ }))
    expect(await screen.findByRole('button', { name: /env:none/ })).toBeInTheDocument()
    expect(onEnvironmentChange).toHaveBeenCalledWith(null)
    expect(chatService.createChatThread).not.toHaveBeenCalled()
  })

  it('uses the pane-local Environment binding for the first send even when the Chat default update fails', async () => {
    const user = userEvent.setup()
    const onEnvironmentChange = vi.fn(async () => {
      throw new Error('update failed')
    })
    renderBlankPane({ onEnvironmentChange })
    const composer = await screen.findByLabelText('给 AI 发送消息')

    await user.click(screen.getByRole('button', { name: /env:none/ }))
    const envModal = await screen.findByLabelText('选择 Environment')
    await user.click(within(envModal).getByRole('option', { name: /^local/ }))
    const dirPanel = await screen.findByLabelText('local 目录')
    await user.click(within(dirPanel).getByRole('button', { name: '使用当前 Workspace' }))
    expect(await screen.findByRole('button', { name: /env:local/ })).toBeInTheDocument()
    // 更新失败仅提示（错误 banner）；不阻塞发送。
    expect(await screen.findByText('update failed')).toBeInTheDocument()

    const createdThread = thread({ threadId: 't-created' })
    vi.mocked(chatService.createChatThread).mockResolvedValue(snapshotOf(createdThread))
    await user.click(composer)
    await user.type(composer, 'hello with local env')
    await user.click(screen.getByRole('button', { name: '发送消息' }))
    await waitFor(() => expect(chatService.createChatThread).toHaveBeenCalled())
    const createArgs = vi.mocked(chatService.createChatThread).mock.calls.at(-1)!
    expect(createArgs[1].branchSettings.environment).toEqual({ name: 'local', workspacePath: '.' })
  })

  it('performs atomic createChatThread + enqueueCommands first send', async () => {
    const user = userEvent.setup()
    const createdThread = thread({ threadId: 't-created', environment: null })
    vi.mocked(chatService.createChatThread).mockResolvedValue(snapshotOf(createdThread))
    renderBlankPane()
    const composer = await screen.findByLabelText('给 AI 发送消息')

    await user.click(composer)
    await user.type(composer, 'first message')
    await user.click(screen.getByRole('button', { name: '发送消息' }))

    await waitFor(() => expect(chatService.createChatThread).toHaveBeenCalledTimes(1))
    expect(chatService.createChatThread).toHaveBeenCalledWith(
      'chat-1',
      expect.objectContaining({
        title: 'C',
        yoloEnabled: false,
        branchSettings: expect.objectContaining({
          agentName: 'assistant',
          environment: null,
          model: expect.objectContaining({
            providerName: 'minimax',
            modelName: 'MiniMax',
            variant: 'default',
          }),
        }),
      }),
    )

    await waitFor(() => expect(harnessService.enqueueCommands).toHaveBeenCalledTimes(1))
    const [threadIdArg, batchArg] = vi.mocked(harnessService.enqueueCommands).mock.calls[0]!
    expect(threadIdArg).toBe('t-created')
    expect(batchArg.expectedHeadEntryId).toBe('e-root')
    expect(batchArg.expectedNextCommandSequence).toBe('1')
    expect(batchArg.commands).toHaveLength(1)
    expect(batchArg.commands[0]?.type).toBe('USER_MESSAGE')
    expect(batchArg.commands[0]).toMatchObject({ contents: [{ type: 'TEXT', text: 'first message' }] })
    // 严格 wire：USER_MESSAGE 绝不携带 role。
    expect(batchArg.commands[0]).not.toHaveProperty('role')
    expect(batchArg.commands[0]?.clientCommandId).toBeTruthy()
    // 首次发送的 command batch 中没有 environment：binding 已在创建 Thread 时
    // 通过 branchSettings.environment 烘焙进去。
    expect(batchArg.commands[0]).not.toHaveProperty('environment')

    // 面板绑定到已创建的 Thread 后，空面板正文随即消失。
    await waitFor(() =>
      expect(screen.queryByRole('heading', { name: /新对话/ })).not.toBeInTheDocument(),
    )
  })

  it('clears the stored draft when first send starts and restores it on failure', async () => {
    const user = userEvent.setup()
    let rejectCreate: (reason?: unknown) => void = () => undefined
    vi.mocked(chatService.createChatThread).mockImplementation(
      () => new Promise<HarnessThreadSnapshotDTO>((_resolve, reject) => {
        rejectCreate = reject
      }),
    )
    renderBlankPane()
    const composer = await screen.findByLabelText('给 AI 发送消息')
    const storageKey = composerDraftStorageKey('chat:chat-1:pane:pane-1')

    await user.click(composer)
    await user.type(composer, 'pending first send')
    expect(localStorage.getItem(storageKey)).toBe('pending first send')

    await user.click(screen.getByRole('button', { name: '发送消息' }))
    await waitFor(() => expect(chatService.createChatThread).toHaveBeenCalled())
    expect(localStorage.getItem(storageKey)).toBeNull()

    await act(async () => {
      rejectCreate(new Error('create failed'))
    })
    await waitFor(() =>
      expect(localStorage.getItem(storageKey)).toBe('pending first send'),
    )
  })

  it('binds the pane to the created Thread via onFirstSendRecovery and replays the same USER_MESSAGE', async () => {
    const user = userEvent.setup()
    const createdThread = thread({ threadId: 't-replay' })
    const createdSnapshot = snapshotOf(createdThread)
    vi.mocked(chatService.createChatThread).mockResolvedValue(createdSnapshot)
    let enqueueCalls = 0
    vi.mocked(harnessService.enqueueCommands).mockImplementation(async () => {
      enqueueCalls += 1
      if (enqueueCalls === 1) {
        // 首次发送失败 → 绑定面板应重试同一 USER_MESSAGE。
        throw new ApiError('temporary failure', 503)
      }
      return [] as HarnessThreadCommandDTO[]
    })
    // 恢复后的绑定面板的 snapshot（必须与已创建 Thread 的 revision/head 一致）。
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(createdSnapshot)

    const onThreadChange = vi.fn()
    renderBlankPane({ onThreadChange })
    const composer = await screen.findByLabelText('给 AI 发送消息')

    await user.click(composer)
    await user.type(composer, 'retry me')
    await user.click(screen.getByRole('button', { name: '发送消息' }))

    // 首次发送：create + enqueue（失败）。Draft 已被恢复以便用户重试。
    await waitFor(() => expect(chatService.createChatThread).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(harnessService.enqueueCommands).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(onThreadChange).toHaveBeenCalledWith('t-replay'))
    await waitFor(() => expect(composer).toHaveTextContent('retry me'))

    await user.click(screen.getByRole('button', { name: '发送消息' }))
    await waitFor(() => expect(harnessService.enqueueCommands).toHaveBeenCalledTimes(2))
    expect(chatService.createChatThread).toHaveBeenCalledTimes(1)

    const calls = vi.mocked(harnessService.enqueueCommands).mock.calls
    const firstBatch = calls[0]?.[1]
    const secondBatch = calls[1]?.[1]
    expect(secondBatch).toBeDefined()
    // 稳定的重试 identity：重试逐字节复用 EXACT 首次发送 batch
    //（相同的 command ID/payload/顺序和原始 expected cursor）。已创建的 Thread 作为
    // effective base，因此语义 identity 匹配，message command id 在 blank->bound 恢复
    // 过程中得以保留。
    expect(secondBatch).toEqual(firstBatch)
  })

  it('binds on a known 409 without replaying the stale batch and rebuilds fresh cursors', async () => {
    const user = userEvent.setup()
    const createdThread = thread({ threadId: 't-conflict' })
    vi.mocked(chatService.createChatThread).mockResolvedValue(snapshotOf(createdThread))
    // 已知 409：服务器明确拒绝了过期 batch（cursor 已移动）。
    vi.mocked(harnessService.enqueueCommands).mockRejectedValue(
      new ApiError('stale cursors', 409),
    )
    // 恢复后的绑定面板重新拉取并看到已推进的 snapshot（服务器已前进）。
    const advanced = thread({
      threadId: 't-conflict',
      headEntryId: 'e-advanced',
      nextCommandSequence: '2',
      revision: '1',
    })
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(snapshotOf(advanced))

    const onThreadChange = vi.fn()
    renderBlankPane({ onThreadChange })
    const composer = await screen.findByLabelText('给 AI 发送消息')

    await user.click(composer)
    await user.type(composer, 'fresh retry')
    await user.click(screen.getByRole('button', { name: '发送消息' }))

    // 首次发送：create + enqueue（409）。面板仍会绑定已创建的 Thread
    // 并恢复 composer 文本，以便用户重试。
    await waitFor(() => expect(chatService.createChatThread).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(harnessService.enqueueCommands).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(onThreadChange).toHaveBeenCalledWith('t-conflict'))
    await waitFor(() => expect(composer).toHaveTextContent('fresh retry'))

    // 当恢复后的 draft 文本相同时重试：已知 409 不会装配 exact replay——
    // 下一次提交必须基于刷新的 snapshot，用全新 cursor 和全新 command id 重新构建。
    await waitFor(() =>
      expect(screen.getByRole('button', { name: '发送消息' })).toBeEnabled(),
    )
    await user.click(screen.getByRole('button', { name: '发送消息' }))
    await waitFor(() => expect(harnessService.enqueueCommands).toHaveBeenCalledTimes(2))
    expect(chatService.createChatThread).toHaveBeenCalledTimes(1)

    const calls = vi.mocked(harnessService.enqueueCommands).mock.calls
    const firstBatch = calls[0]?.[1]
    const secondBatch = calls[1]?.[1]
    expect(secondBatch).toBeDefined()
    expect(secondBatch?.expectedHeadEntryId).toBe('e-advanced')
    expect(secondBatch?.expectedNextCommandSequence).toBe('2')
    expect(secondBatch?.commands[0]?.clientCommandId).not.toBe(
      firstBatch?.commands[0]?.clientCommandId,
    )
  })

  it('opens the chat-scoped /thread picker and only rebinds the pane', async () => {
    const user = userEvent.setup()
    const events: string[] = []
    vi.mocked(chatService.listChatThreads).mockResolvedValue([
      thread({
        threadId: 't-idle',
        updateTime: '2026-07-20T12:00:00Z',
        createTime: '2026-07-20T12:00:00Z',
      }),
      thread({
        threadId: 't-running',
        status: 'RUNNING',
        processing: true,
        updateTime: '2026-07-19T12:00:00Z',
        createTime: '2026-07-19T12:00:00Z',
      }),
    ])
    vi.mocked(chatService.associateThread).mockImplementation(async () => {
      events.push('associate')
    })
    const onThreadChange = vi.fn(() => {
      events.push('bind')
    })
    renderBlankPane({ onThreadChange })
    const composer = await screen.findByLabelText('给 AI 发送消息')

    await user.click(composer)
    await user.keyboard('/thread{Enter}')
    expect(await screen.findByText('选择 Thread')).toBeInTheDocument()
    await waitFor(() => expect(chatService.listChatThreads).toHaveBeenCalledWith('chat-1'))

    const items = await screen.findAllByRole('option', { name: /^t-/ })
    // recent 模式下较新的 IDLE Thread 排在前面。
    expect(items[0]).toHaveTextContent('t-idle')

    await user.click(screen.getByRole('option', { name: /t-running/ }))
    expect(onThreadChange).toHaveBeenCalledWith('t-running')
    // chat-scoped picker 上没有 association API。
    expect(chatService.associateThread).not.toHaveBeenCalled()
    expect(events).toEqual(['bind'])
    // 选中 Thread 永远不会走 first-send 路径。
    expect(chatService.createChatThread).not.toHaveBeenCalled()
    expect(harnessService.enqueueCommands).not.toHaveBeenCalled()
  })


  it('keeps a null Chat title null on the createChatThread payload', async () => {
    const user = userEvent.setup()
    const createdThread = thread({ threadId: 't-null-title' })
    vi.mocked(chatService.createChatThread).mockResolvedValue(snapshotOf(createdThread))
    renderBlankPane({ title: null })
    const composer = await screen.findByLabelText('给 AI 发送消息')

    await user.click(composer)
    await user.type(composer, 'hi')
    await user.click(screen.getByRole('button', { name: '发送消息' }))

    await waitFor(() => expect(chatService.createChatThread).toHaveBeenCalledTimes(1))
    const payload = vi.mocked(chatService.createChatThread).mock.calls[0]![1]
    // Session 拒绝空标题；Chat title 可空，保持 null（不写 ?? ''）。
    expect(payload.title).toBeNull()
    expect(payload).toEqual(
      expect.objectContaining({
        title: null,
        yoloEnabled: false,
        branchSettings: expect.objectContaining({
          agentName: 'assistant',
          environment: null,
          model: expect.objectContaining({
            providerName: 'minimax',
            modelName: 'MiniMax',
            variant: 'default',
          }),
          activeTools: [],
        }),
      }),
    )
  })

  it('keeps Chat runtime defaults when a stale Chat agent triggers full materialization', async () => {
    const user = userEvent.setup()
    const createdThread = thread({ threadId: 't-materialized-yolo' })
    vi.mocked(chatService.createChatThread).mockResolvedValue(snapshotOf(createdThread))
    const tooledAgent = {
      ...assistantAgent,
      config: { tools: ['web-search'], skills: [] },
    }
    // 已过期的 Chat agent（catalog 无匹配）：完整 materialization 必须保留 Chat 默认
    // yolo 与整个 Environment binding，而不是悄悄回退为 false/null。
    renderBlankPane({
      agentName: 'ghost',
      yoloEnabled: true,
      environment: { name: 'local', workspacePath: 'proj/app' },
      agents: [tooledAgent],
    })
    const composer = await screen.findByLabelText('给 AI 发送消息')

    await user.click(composer)
    await user.type(composer, 'hi')
    await user.click(screen.getByRole('button', { name: '发送消息' }))
    await user.click(await screen.findByRole('option', { name: /assistant/ }))

    await waitFor(() => expect(chatService.createChatThread).toHaveBeenCalledTimes(1))
    const payload = vi.mocked(chatService.createChatThread).mock.calls[0]![1]
    expect(payload.yoloEnabled).toBe(true)
    expect(payload.branchSettings.agentName).toBe('assistant')
    expect(payload.branchSettings.environment).toEqual({
      name: 'local',
      workspacePath: 'proj/app',
    })
    expect(payload.branchSettings.activeTools).toEqual(['web-search'])
  })

  it('fully materializes the draft from the selected Agent when the Chat agent is unresolvable', async () => {
    const user = userEvent.setup()
    const createdThread = thread({ threadId: 't-materialized' })
    vi.mocked(chatService.createChatThread).mockResolvedValue(snapshotOf(createdThread))
    const tooledAgent = {
      ...assistantAgent,
      config: { tools: ['web-search'], skills: [] },
    }
    renderBlankPane({ agentName: 'ghost', agents: [tooledAgent] })
    const composer = await screen.findByLabelText('给 AI 发送消息')

    // Chat agent 无法从 catalog 解析：draft 保持不冻结，
    // composer 展示明确的 agent 缺失状态。
    expect(await screen.findByText(/Agent 已删除\/缺失/)).toBeInTheDocument()

    // 提交操作会打开 agent picker，而不是用空
    // provider/model/variant 去创建 Thread，否则会被 strict mapper 拒绝。
    await user.click(composer)
    await user.type(composer, 'hi')
    await user.click(screen.getByRole('button', { name: '发送消息' }))
    expect(await screen.findByText('选择 Agent')).toBeInTheDocument()
    expect(chatService.createChatThread).not.toHaveBeenCalled()

    // 选中 Agent 后，draft 被完整 materialize（name + activeTools + model），
    // 挂起消息立即发送。
    await user.click(screen.getByRole('option', { name: /assistant/ }))
    await waitFor(() => expect(chatService.createChatThread).toHaveBeenCalledTimes(1))
    const payload = vi.mocked(chatService.createChatThread).mock.calls[0]![1]
    expect(payload.branchSettings).toEqual({
      environment: null,
      agentName: 'assistant',
      model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'default' },
      activeTools: ['web-search'],
    })
    expect(payload.yoloEnabled).toBe(false)
    const batchArg = vi.mocked(harnessService.enqueueCommands).mock.calls[0]![1]
    expect(batchArg.commands).toHaveLength(1)
    expect(batchArg.commands[0]).toMatchObject({ contents: [{ type: 'TEXT', text: 'hi' }] })
    expect(batchArg.commands[0]).not.toHaveProperty('role')
  })


  it('establishes the pane-local baseline on first picker materialization so later edits are dirty', async () => {
    const user = userEvent.setup()
    vi.mocked(chatService.listChatThreads).mockResolvedValue([
      thread({ threadId: 't-idle' }),
      thread({ threadId: 't-other' }),
    ])
    const tooledAgent = {
      ...assistantAgent,
      config: { tools: ['web-search'], skills: [] },
    }
    const onThreadChange = vi.fn()
    // 已过期的 Chat agent（'ghost' 不在 catalog 中）：无法从 Chat 默认值
    // materialize draft，因此首次 materialization 通过 picker 完成。
    renderBlankPane({ agentName: 'ghost', agents: [tooledAgent], onThreadChange })
    const composer = await screen.findByLabelText('给 AI 发送消息')

    // 首次 picker materialization：选中 Agent 必须建立不可变的
    // 面板本地基线（之前 initialFrozenDraft 一直为 null，
    // 后续编辑从未被判定为 dirty）。
    await user.click(composer)
    await user.keyboard('/agent{Enter}')
    await user.click(await screen.findByRole('option', { name: /assistant/ }))

    // 之后的面板本地编辑（environment）相对于该基线必须被判定为 dirty。
    await user.click(screen.getByLabelText('给 AI 发送消息'))
    await user.keyboard('/environment{Enter}')
    const envModal = await screen.findByLabelText('选择 Environment')
    await user.click(within(envModal).getByRole('option', { name: /^local/ }))
    const dirPanel = await screen.findByLabelText('local 目录')
    await user.click(within(dirPanel).getByRole('button', { name: '使用当前 Workspace' }))

    await user.click(screen.getByLabelText('给 AI 发送消息'))
    await user.keyboard('/thread{Enter}')
    const picker = await screen.findByLabelText('选择 Thread')
    const items = await within(picker).findAllByRole('option', { name: /^t-/ })
    const otherItem = items.find((item) => item.textContent?.includes('t-other'))
    expect(otherItem).toBeDefined()
    await user.click(otherItem!)

    expect(
      await screen.findByRole('alertdialog', { name: '丢弃未发送的修改？' }),
    ).toBeInTheDocument()
    expect(onThreadChange).not.toHaveBeenCalled()
  })

  it('confirms discarding a modified pane-local frozen draft before switching Threads from /thread', async () => {
    const user = userEvent.setup()
    vi.mocked(chatService.listChatThreads).mockResolvedValue([
      thread({ threadId: 't-idle' }),
      thread({ threadId: 't-other' }),
    ])
    const onThreadChange = vi.fn()
    renderBlankPane({ onThreadChange })
    const composer = await screen.findByLabelText('给 AI 发送消息')

    // 对 frozen branch draft 的面板本地编辑（environment 选择）会让面板
    // 相对于其初始 materialize 值变为 dirty。
    await user.click(composer)
    await user.keyboard('/environment{Enter}')
    expect(await screen.findByLabelText('选择 Environment')).toBeInTheDocument()
    await user.click(within(screen.getByLabelText('选择 Environment')).getByRole('option', { name: /^local/ }))
    const dirPanel = await screen.findByLabelText('local 目录')
    await user.click(within(dirPanel).getByRole('button', { name: '使用当前 Workspace' }))

    await user.click(screen.getByLabelText('给 AI 发送消息'))
    await user.keyboard('/thread{Enter}')
    const picker = await screen.findByLabelText('选择 Thread')
    const items = await within(picker).findAllByRole('option', { name: /^t-/ })
    const otherItem = items.find((item) => item.textContent?.includes('t-other'))
    expect(otherItem).toBeDefined()
    await user.click(otherItem!)

    const discardDialog = await screen.findByRole(
      'alertdialog',
      { name: '丢弃未发送的修改？' },
    )
    await waitFor(() =>
      expect(within(discardDialog).getByRole('button', { name: '取消' })).toHaveFocus(),
    )
    await user.keyboard('{Escape}')
    expect(onThreadChange).not.toHaveBeenCalled()

    // 取消丢弃后仍停留在 picker，不需要重新输入命令。
    const pickerAgain = screen.getByLabelText('选择 Thread')
    await waitFor(() =>
      expect(within(pickerAgain).getByRole('searchbox', { name: '搜索' })).toHaveFocus(),
    )
    const itemsAgain = await within(pickerAgain).findAllByRole('option', { name: /^t-/ })
    const otherAgain = itemsAgain.find((item) => item.textContent?.includes('t-other'))
    expect(otherAgain).toBeDefined()
    await user.click(otherAgain!)
    await user.click(await screen.findByRole('button', { name: '丢弃修改' }))
    await waitFor(() => expect(onThreadChange).toHaveBeenCalledWith('t-other'))
    expect(chatService.createChatThread).not.toHaveBeenCalled()
  })

  it('keeps an unmodified blank pane draft confirmation-free for /thread', async () => {
    const user = userEvent.setup()
    vi.mocked(chatService.listChatThreads).mockResolvedValue([
      thread({ threadId: 't-idle' }),
      thread({ threadId: 't-other' }),
    ])
    const onThreadChange = vi.fn()
    renderBlankPane({ onThreadChange })
    const composer = await screen.findByLabelText('给 AI 发送消息')

    // 面板本地 draft 等于其初始 materialize 值：无需确认。
    await user.click(composer)
    await user.keyboard('/thread{Enter}')
    expect(await screen.findByText('选择 Thread')).toBeInTheDocument()
    await user.click(screen.getByRole('option', { name: /t-other/ }))

    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(onThreadChange).toHaveBeenCalledWith('t-other')
  })

  it('handles a rejected Chat Agent update without an unhandled rejection', async () => {
    const user = userEvent.setup()
    const unhandled: unknown[] = []
    const onUnhandled = (event: PromiseRejectionEvent) => {
      unhandled.push(event.reason)
    }
    window.addEventListener('unhandledrejection', onUnhandled)
    const onAgentChange = vi.fn(async () => {
      throw new Error('Chat Agent update failed')
    })
    // 让 createChatThread 也失败，使空面板保持挂载，
    // 让 action 错误在空面板 footer 中可见。
    vi.mocked(chatService.createChatThread).mockRejectedValue(
      new Error('agent update failed upstream'),
    )
    renderBlankPane({ agentName: null, onAgentChange })

    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.type(composer, 'hello need agent')
    await user.click(screen.getByRole('button', { name: '发送消息' }))
    expect(await screen.findByText('选择 Agent')).toBeInTheDocument()
    await user.click(screen.getByRole('option', { name: /assistant/ }))

    await waitFor(() => expect(onAgentChange).toHaveBeenCalledWith('assistant'))
    // 等待 microtask 清空，以便观察到任何 unhandled rejection。
    await new Promise((resolve) => setTimeout(resolve, 0))
    window.removeEventListener('unhandledrejection', onUnhandled)
    expect(unhandled).toEqual([])
  })

  it('restores the draft and reports the failure when atomic Thread creation rejects', async () => {
    const user = userEvent.setup()
    vi.mocked(chatService.createChatThread).mockRejectedValue(
      new Error('unknown agent definition: assistant'),
    )

    renderBlankPane()
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.type(composer, 'first message')
    await user.click(screen.getByRole('button', { name: '发送消息' }))

    await waitFor(() =>
      expect(screen.getByText('unknown agent definition: assistant')).toBeInTheDocument(),
    )
    expect(harnessService.enqueueCommands).not.toHaveBeenCalled()
    // 面板保持空白，让用户在修复 Agent 后重试。
    expect(screen.getByRole('heading', { name: /新对话/ })).toBeInTheDocument()
    await waitFor(() => expect(composer).toHaveTextContent('first message'))
  })

  it('recovers the local-id draft, not server upload ids, after a first-send failure with an attachment', async () => {
    const user = userEvent.setup()
    // jsdom 无 Worker：用同步回显 sha256 的 fake 满足 composer 默认 hasher。
    class FakeHashWorker {
      onmessage: ((event: MessageEvent) => void) | null = null
      addEventListener(_type: string, listener: (event: MessageEvent) => void) {
        this.onmessage = listener
      }
      removeEventListener() {}
      postMessage(event: { requestId: string }) {
        setTimeout(() => {
          this.onmessage?.({ data: { requestId: event.requestId, sha256: 'a'.repeat(64) } } as MessageEvent)
        }, 0)
      }
      terminate() {}
    }
    vi.stubGlobal('Worker', FakeHashWorker)
    // PENDING 直传 + complete：upload 句柄 up-1 在 complete 后保持不变。
    storageMocks.reserveUpload.mockResolvedValue({
      id: 'up-1',
      state: 'PENDING',
      blobId: null,
      presignedPut: { method: 'PUT', url: 'https://s3.test/up-1', headers: {} },
      expiresAt: null,
    })
    storageMocks.uploadFile.mockResolvedValue(undefined)
    storageMocks.completeUpload.mockResolvedValue({
      id: 'up-1',
      state: 'READY',
      blobId: 'blob-up-1',
      presignedPut: null,
      expiresAt: null,
    })
    storageMocks.deleteUpload.mockResolvedValue(undefined)
    storageMocks.getBlobOriginalUrl.mockResolvedValue({ url: 'https://s3.test/orig', expiresAt: null })
    storageMocks.getBlobPreviewUrl.mockResolvedValue({ url: 'https://s3.test/prev', expiresAt: null })

    const createdThread = thread({ threadId: 't-replay' })
    vi.mocked(chatService.createChatThread).mockResolvedValue(snapshotOf(createdThread))
    vi.mocked(harnessService.enqueueCommands).mockRejectedValue(new Error('queue down'))
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(snapshotOf(createdThread))

    const onThreadChange = vi.fn()
    renderBlankPane({ onThreadChange })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.click(composer)
    await user.type(composer, 'with file')
    fireEvent.paste(composer, {
      clipboardData: {
        files: [new File([new Uint8Array(8)], 'doc.pdf', { type: 'application/pdf' })],
        getData: () => '',
      },
    })
    await waitFor(() => expect(document.querySelectorAll('.composer-pill')).toHaveLength(1))
    await waitFor(() => expect(storageMocks.completeUpload).toHaveBeenCalledWith('up-1'))
    await user.click(screen.getByRole('button', { name: '发送消息' }))

    // 首次发送失败：payload 走 HTTP（含服务端句柄），恢复给绑定面板的是本地草稿。
    await waitFor(() => expect(harnessService.enqueueCommands).toHaveBeenCalledTimes(1))
    const batch = vi.mocked(harnessService.enqueueCommands).mock.calls[0]?.[1]
    const message = batch?.commands[batch.commands.length - 1] as {
      contents?: Array<Record<string, unknown>>
    }
    expect(message.contents).toEqual([
      { type: 'TEXT', text: 'with file' },
      { type: 'ATTACHMENT', uploadId: 'up-1' },
    ])
    await waitFor(() => expect(onThreadChange).toHaveBeenCalledWith('t-replay'))

    // 绑定面板恢复本地草稿：pill 携带客户端 localId（UUID），绝不是服务端句柄。
    await waitFor(() => expect(document.querySelectorAll('.composer-pill')).toHaveLength(1))
    const uploadId = document.querySelector('.composer-pill')?.getAttribute('data-upload-id')
    expect(uploadId).not.toBe('up-1')
    expect(uploadId).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-/)
    expect(document.querySelector('.composer-pill')?.textContent).toContain('doc.pdf')
    // 附件对象没有被释放（恢复期间绝不 DELETE）。
    expect(storageMocks.deleteUpload).not.toHaveBeenCalled()
  })
})
