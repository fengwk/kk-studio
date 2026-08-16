import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import type { ReactNode } from 'react'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ChatWorkspacePane } from '@/features/ai/chat/ChatWorkspacePane'
import { composerDraftStorageKey } from '@/features/ai/composer/composer-draft'
import {
  ApplicationSettingsProvider,
  useApplicationSettings,
} from '@/features/settings/application-settings'
import { agentService } from '@/shared/api/agent-service'
import { ApiError } from '@/shared/api/client'
import { chatService } from '@/shared/api/chat-service'
import { environmentService } from '@/shared/api/environment-service'
import { harnessService } from '@/shared/api/harness-service'
import type {
  HarnessBranchSettingsDTO,
  HarnessModelSelectionDTO,
  HarnessSessionEntryDTO,
  HarnessThreadCommandDTO,
  HarnessThreadDTO,
  HarnessThreadSnapshotDTO,
  ToolInvocationDTO,
} from '@/shared/api/contracts/ai-runtime'

const { fakeApplicationEvents } = vi.hoisted(() => {
  const manager = { subscribe: () => () => undefined }
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
    listThreadEntries: vi.fn(),
    getSystemPromptPreview: vi.fn(),
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

function modelSelection(overrides: Partial<HarnessModelSelectionDTO> = {}): HarnessModelSelectionDTO {
  return {
    providerName: 'minimax',
    modelName: 'MiniMax',
    variant: 'default',
    ...overrides,
  }
}

function branchSettings(
  overrides: Partial<HarnessBranchSettingsDTO> = {},
): HarnessBranchSettingsDTO {
  return {
    environment: null,
    agentName: 'assistant',
    model: modelSelection(),
    activeTools: [],
    ...overrides,
  }
}

function thread(overrides: Partial<HarnessThreadDTO> = {}): HarnessThreadDTO {
  return {
    threadId: 't1',
    sessionId: 's1',
    headEntryId: 'e-assistant',
    yoloEnabled: false,
    nextCommandSequence: '1',
    revision: '0',
    status: 'IDLE',
    processing: false,
    branchSettings: branchSettings(),
    createTime: '2026-01-01T00:00:00Z',
    updateTime: '2026-01-02T00:00:00Z',
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
    entries: [],
    queuedCommands: [],
    modelInvocation: null,
    toolInvocations: [],
    modelAttemptFailures: [],
    ...extras,
  }
}

function entry(
  entryId: string,
  parentEntryId: string | null,
  role: 'USER' | 'ASSISTANT' | 'TOOL' | 'SYSTEM',
  text: string,
): HarnessSessionEntryDTO {
  return {
    entryId,
    sessionId: 's1',
    parentEntryId,
    entryType: 'MESSAGE',
    payloadJson: JSON.stringify({ message: { role, contents: [{ type: 'text', text }] } }),
    createTime: null,
  }
}

function queuedUserCommand(
  sequence: string,
  text: string,
): HarnessThreadCommandDTO {
  return {
    threadId: 't1',
    sequence,
    type: 'USER_MESSAGE',
    state: 'QUEUED',
    clientCommandId: `queued-${sequence}`,
    requestHash: '0123456789abcdef'.repeat(4),
    payloadJson: JSON.stringify({
      message: {
        role: 'USER',
        contents: [{ type: 'text', text }],
      },
    }),
    consumedTurnStartEntryId: null,
    cancelledAt: null,
    createTime: null,
  }
}

function toolCallEntry(
  entryId: string,
  parentEntryId: string,
  toolCallId: string,
  toolName: string,
): HarnessSessionEntryDTO {
  return {
    entryId,
    sessionId: 's1',
    parentEntryId,
    entryType: 'MESSAGE',
    payloadJson: JSON.stringify({
      message: {
        role: 'ASSISTANT',
        contents: [
          { type: 'text', text: 'run' },
          {
            type: 'tool_call',
            toolCallId,
            toolName,
            rendererKey: toolName,
            argumentsJson: '{"command":"ls"}',
          },
        ],
      },
    }),
    createTime: null,
  }
}

function toolInvocation(overrides: Partial<ToolInvocationDTO> = {}): ToolInvocationDTO {
  return {
    id: 'inv-1',
    modelInvocationId: 'm-1',
    assistantEntryId: '40',
    ordinal: 0,
    status: 'WAITING_APPROVAL',
    attempt: 1,
    toolCallId: 'call-1',
    toolName: 'bash',
    toolVersion: '1',
    rendererKey: 'bash',
    toolType: 'shell',
    environment: null,
    argumentsJson: '{"command":"ls"}',
    approvalJson: JSON.stringify({ required: true, decision: null, decisionId: null }),
    resultJson: null,
    errorJson: null,
    resultEntryId: null,
    createTime: '2026-07-28T10:00:00Z',
    updateTime: '2026-07-28T10:00:00Z',
    ...overrides,
  }
}

/** ROOT -> USER -> ASSISTANT，使 USER 行有父节点，可以作为合法的 rebind 目标。 */
function sessionEntries(): HarnessSessionEntryDTO[] {
  return [
    {
      entryId: 's1-root',
      sessionId: 's1',
      parentEntryId: null,
      entryType: 'ROOT',
      payloadJson: '{}',
      createTime: null,
    },
    entry('s1-user', 's1-root', 'USER', 's1 prompt'),
    entry('s1-assistant', 's1-user', 'ASSISTANT', 's1 reply'),
  ]
}

const agents = [
  {
    name: 'assistant',
    description: null,
    systemPrompt: null,
    model: 'minimax/MiniMax',
    variant: 'default',
    config: { tools: [], skills: [] },
    version: '0',
    createTime: null,
    updateTime: null,
  },
  {
    name: 'coder',
    description: null,
    systemPrompt: null,
    model: 'minimax/MiniMax',
    variant: 'default',
    config: { tools: ['web-search'], skills: [] },
    version: '0',
    createTime: null,
    updateTime: null,
  },
]

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
      variants: [{ id: 'default' }],
    },
    version: '0',
    createTime: null,
    updateTime: null,
  }
}

function renderBoundPane(overrides?: {
  onThreadChange?: (threadId: string | null) => void
  onThreadSortChange?: (sort: 'recent' | 'created') => void
  onAgentChange?: (agentName: string) => Promise<void>
  onYoloChange?: (yoloEnabled: boolean) => Promise<void>
  paneThreadId?: string
  /** 与 BoundThreadPane 一起挂进共享 ApplicationSettingsProvider 的额外探针。 */
  children?: ReactNode
}) {
  const onThreadChange = overrides?.onThreadChange ?? vi.fn()
  const onThreadSortChange = overrides?.onThreadSortChange ?? vi.fn()
  const onAgentChange = overrides?.onAgentChange ?? vi.fn(async () => undefined)
  const onYoloChange = overrides?.onYoloChange ?? vi.fn(async () => undefined)
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  const element = (
    <ApplicationSettingsProvider>
      <QueryClientProvider client={queryClient}>
        {overrides?.children}
        <ChatWorkspacePane
          chat={{
            id: 'chat-1',
            title: 'C',
            agentName: 'assistant',
            yoloEnabled: false,
            version: '1',
            createTime: null,
            updateTime: null,
          }}
          agents={agents}
        environments={[
          { name: 'local', ready: true, status: 'READY', lastSeen: null, tools: [], skills: [] },
          { name: 'remote', ready: true, status: 'READY', lastSeen: null, tools: [], skills: [] },
          {
            name: 'env-connecting',
            ready: false,
            status: 'CONNECTING',
            lastSeen: null,
            tools: [],
            skills: [],
          },
        ]}
        pane={{ id: 'pane-1', threadId: overrides?.paneThreadId ?? 't1' }}
        focused
        threadSort="recent"
        onFocus={() => undefined}
        onThreadChange={onThreadChange}
        onThreadSortChange={onThreadSortChange}
        onAgentChange={onAgentChange}
        onYoloChange={onYoloChange}
      />
      </QueryClientProvider>
    </ApplicationSettingsProvider>
  )
  const view = render(element)
  return {
    queryClient,
    onThreadChange,
    onThreadSortChange,
    onAgentChange,
    onYoloChange,
    rerender: (paneThreadId: string) => {
      view.rerender(
        <ApplicationSettingsProvider>
          <QueryClientProvider client={queryClient}>
            {overrides?.children}
            <ChatWorkspacePane
              chat={{
                id: 'chat-1',
                title: 'C',
                agentName: 'assistant',
                yoloEnabled: false,
                version: '1',
                createTime: null,
                updateTime: null,
              }}
              agents={agents}
              environments={[]}
              pane={{ id: 'pane-1', threadId: paneThreadId }}
              focused
              threadSort="recent"
              onFocus={() => undefined}
              onThreadChange={onThreadChange}
              onThreadSortChange={onThreadSortChange}
              onAgentChange={onAgentChange}
              onYoloChange={onYoloChange}
            />
          </QueryClientProvider>
        </ApplicationSettingsProvider>,
      )
    },
  }
}

describe('ChatWorkspacePane commands', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(agentService.listAgents).mockResolvedValue(page(agents))
    vi.mocked(agentService.listModels).mockResolvedValue(page([modelEntry()]))
    vi.mocked(agentService.listProviders).mockResolvedValue(page([]))
    vi.mocked(environmentService.listDirectories).mockResolvedValue({
      path: '.',
      displayPath: '.',
      parentPath: '.',
      truncated: false,
      gitBranch: null,
      entries: [{ name: 'proj', path: 'proj' }],
    })
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(snapshot(thread({})))
    vi.mocked(harnessService.listThreadEntries).mockResolvedValue(sessionEntries())
    vi.mocked(harnessService.getSystemPromptPreview).mockResolvedValue({
      text: 'You are the planner.\n\n<current_environment>\n- date: 2026-08-17\n</current_environment>',
    })
    vi.mocked(chatService.listChatThreads).mockResolvedValue([
      thread({}),
      thread({
        threadId: 't2',
        status: 'IDLE',
        nextCommandSequence: '0',
        updateTime: '2026-01-03T00:00:00Z',
        createTime: '2026-01-03T00:00:00Z',
      }),
    ])
    vi.mocked(harnessService.enqueueCommands).mockResolvedValue([] as HarnessThreadCommandDTO[])
    vi.mocked(harnessService.updateThreadHead).mockImplementation(async (_threadId, _data) =>
      thread({ revision: '1' }),
    )
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('updates agent/environment/yolo as a draft-local pane state without mutating services', async () => {
    const user = userEvent.setup()
    const { onThreadSortChange, onAgentChange, onYoloChange } = renderBoundPane()
    const composer = await screen.findByLabelText('给 AI 发送消息')

    await user.click(composer)
    await user.keyboard('/agent{Enter}')
    expect(await screen.findByText('选择 Agent')).toBeInTheDocument()
    await user.click(screen.getByRole('option', { name: /coder/ }))
    await waitFor(() => expect(onAgentChange).not.toHaveBeenCalled())

    await user.click(screen.getByLabelText('给 AI 发送消息'))
    await user.keyboard('/environment{Enter}')
    // 绑定面板无默认 binding：先看到 Environment 列表，选择 remote 进入目录浏览后确认。
    expect(await screen.findByLabelText('选择 Environment')).toBeInTheDocument()
    await user.click(within(screen.getByLabelText('选择 Environment')).getByRole('option', { name: /^remote/ }))
    const dirPanel = await screen.findByLabelText('remote 目录')
    await user.click(within(dirPanel).getByRole('button', { name: /^使用当前 Workspace/ }))
    await waitFor(() => expect(harnessService.updateThreadHead).not.toHaveBeenCalled())

    await user.click(screen.getByLabelText('给 AI 发送消息'))
    await user.keyboard('/yolo{Enter}')
    await waitFor(() => expect(onYoloChange).not.toHaveBeenCalled())
    expect(harnessService.updateThreadHead).not.toHaveBeenCalled()
    expect(harnessService.enqueueCommands).not.toHaveBeenCalled()
    expect(onThreadSortChange).not.toHaveBeenCalled()
  })

  it('keeps the Environment selector open and only filters READY environments by id', async () => {
    const user = userEvent.setup()
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(
      snapshot(
        thread({
          branchSettings: branchSettings({
            environment: { name: 'local', workspacePath: '.' },
          }),
        }),
      ),
    )
    renderBoundPane()
    const composer = await screen.findByLabelText('给 AI 发送消息')
    expect(await screen.findByLabelText('会话状态')).toHaveTextContent('env:local · @/')

    await user.click(composer)
    await user.keyboard('/environment{Enter}')
    const currentDirectory = await screen.findByLabelText('local 目录')
    await user.click(
      within(currentDirectory).getByRole('button', { name: '返回 Environment 列表' }),
    )
    expect(await screen.findByLabelText('选择 Environment')).toBeInTheDocument()
    // 仅显示 READY 的 environment；CONNECTING 状态的会按 status 被过滤掉。
    const envModal = screen.getByLabelText('选择 Environment')
    expect(within(envModal).getByRole('option', { name: /^remote/ })).toBeInTheDocument()
    expect(within(envModal).getByRole('option', { name: /^local/ })).toBeInTheDocument()
    expect(within(envModal).queryByRole('option', { name: /connecting/ })).not.toBeInTheDocument()
    await user.click(within(envModal).getByRole('option', { name: /^remote/ }))
    const dirPanel = await screen.findByLabelText('remote 目录')
    await user.click(within(dirPanel).getByRole('button', { name: /^使用当前 Workspace/ }))

    // 绑定面板仅更新 draft；发送之前 footer 仍反映 thread snapshot，
    // 暂时不会发起 harness 调用。
    expect(screen.getByLabelText('会话状态')).toHaveTextContent('env:local · @/')
    expect(screen.getByLabelText('会话状态')).not.toHaveTextContent('env:remote')
    expect(harnessService.updateThreadHead).not.toHaveBeenCalled()
    expect(harnessService.enqueueCommands).not.toHaveBeenCalled()
  })

  it('sends a USER_MESSAGE plus a SET_* diff batch using the snapshot CAS cursors', async () => {
    const user = userEvent.setup()
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(
      snapshot(
        thread({
          branchSettings: branchSettings({
            agentName: 'assistant',
            environment: null,
          }),
        }),
      ),
    )
    renderBoundPane()
    const composer = await screen.findByLabelText('给 AI 发送消息')

    // 在 draft 中编辑 agent（SET_AGENT）+ environment（SET_ENVIRONMENT）。
    await user.click(composer)
    await user.keyboard('/agent{Enter}')
    await user.click(await screen.findByRole('option', { name: /coder/ }))
    await user.click(screen.getByLabelText('给 AI 发送消息'))
    await user.keyboard('/environment{Enter}')
    await user.click(within(await screen.findByLabelText('选择 Environment')).getByRole('option', { name: /^remote/ }))
    const dirPanel = await screen.findByLabelText('remote 目录')
    await user.click(within(dirPanel).getByRole('button', { name: /^使用当前 Workspace/ }))

    await user.click(screen.getByLabelText('给 AI 发送消息'))
    await user.type(composer, 'hello world')
    await user.click(screen.getByRole('button', { name: '发送消息' }))

    await waitFor(() => expect(harnessService.enqueueCommands).toHaveBeenCalledTimes(1))
    const [threadIdArg, batchArg] = vi.mocked(harnessService.enqueueCommands).mock.calls[0]!
    expect(threadIdArg).toBe('t1')
    expect(batchArg.expectedHeadEntryId).toBe('e-assistant')
    expect(batchArg.expectedNextCommandSequence).toBe('1')
    const types = batchArg.commands.map((command) => command.type)
    expect(types).toContain('SET_AGENT')
    expect(types).toContain('SET_ENVIRONMENT')
    expect(types[types.length - 1]).toBe('USER_MESSAGE')
    const message = batchArg.commands[batchArg.commands.length - 1]!
    expect(message).toMatchObject({ contents: [{ type: 'TEXT', text: 'hello world' }] })
    // 严格 wire：USER_MESSAGE 绝不携带 role。
    expect(message).not.toHaveProperty('role')
    expect(message).toHaveProperty('clientCommandId')
    const setAgent = batchArg.commands.find((command) => command.type === 'SET_AGENT')!
    expect(setAgent.agentName).toBe('coder')
    const setEnv = batchArg.commands.find((command) => command.type === 'SET_ENVIRONMENT')!
    expect(setEnv.environment).toEqual({ name: 'remote', workspacePath: '.' })
    expect(harnessService.updateThreadHead).not.toHaveBeenCalled()
  })

  it('navigates durable, queued, and locally stored user drafts in one ordered queue', async () => {
    const user = userEvent.setup()
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(
      snapshot(
        thread({ nextCommandSequence: '3' }),
        {
          entries: [
            {
              entryId: 'root',
              sessionId: 's1',
              parentEntryId: null,
              entryType: 'ROOT',
              payloadJson: '{}',
              createTime: null,
            },
            entry('user-1', 'root', 'USER', 'history 1'),
            entry('assistant-1', 'user-1', 'ASSISTANT', 'reply 1'),
            entry('user-2', 'assistant-1', 'USER', 'history 2'),
          ],
          queuedCommands: [
            queuedUserCommand('1', 'queued 1'),
            queuedUserCommand('2', 'queued 2'),
          ],
        },
      ),
    )
    renderBoundPane()
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.click(composer)
    await user.type(composer, 'local draft')

    const storageKey = composerDraftStorageKey('thread:t1')
    expect(localStorage.getItem(storageKey)).toBe('local draft')

    // durable history -> queued mailbox -> local draft，ArrowUp 从末位反向遍历。
    await user.keyboard('{ArrowUp}')
    expect(composer.textContent).toBe('queued 2')
    expect(localStorage.getItem(storageKey)).toBe('local draft')
    await user.keyboard('{ArrowUp}')
    expect(composer.textContent).toBe('queued 1')
    await user.keyboard('{ArrowUp}')
    expect(composer.textContent).toBe('history 2')
    await user.keyboard('{ArrowUp}')
    expect(composer.textContent).toBe('history 1')

    await user.keyboard('{ArrowDown}{ArrowDown}{ArrowDown}{ArrowDown}')
    expect(composer.textContent).toBe('local draft')

    await user.click(screen.getByRole('button', { name: '发送消息' }))
    await waitFor(() => expect(harnessService.enqueueCommands).toHaveBeenCalled())
    expect(localStorage.getItem(storageKey)).toBeNull()
  })

  it('shows /thread as a chat-scoped picker and only rebinds the pane', async () => {
    const user = userEvent.setup()
    const { onThreadChange } = renderBoundPane()
    const composer = await screen.findByLabelText('给 AI 发送消息')

    await user.click(composer)
    await user.keyboard('/thread{Enter}')
    expect(await screen.findByText('选择 Thread')).toBeInTheDocument()
    await waitFor(() => expect(chatService.listChatThreads).toHaveBeenCalledWith('chat-1'))

    await user.click(await screen.findByRole('option', { name: /t2/ }))

    expect(onThreadChange).toHaveBeenCalledWith('t2')
    expect(chatService.associateThread).not.toHaveBeenCalled()
    expect(harnessService.updateThreadHead).not.toHaveBeenCalled()
    expect(harnessService.enqueueCommands).not.toHaveBeenCalled()
  })

  it('opens /shortcuts as a read-only interaction panel and Esc returns focus to the Composer', async () => {
    const user = userEvent.setup()
    renderBoundPane()
    const composer = await screen.findByLabelText('给 AI 发送消息')

    await user.click(composer)
    await user.keyboard('/shortcuts{Enter}')

    const panel = await screen.findByRole('region', { name: '键盘快捷键' })
    expect(panel).toBeInTheDocument()
    expect(panel.getAttribute('aria-busy')).toBe('false')
    // 只读 catalog：不渲染搜索框/选项，只展示快捷键条目。
    expect(within(panel).queryByRole('searchbox')).not.toBeInTheDocument()
    expect(within(panel).getByText('Enter')).toBeInTheDocument()
    expect(within(panel).getByText('发送消息')).toBeInTheDocument()
    // Composer 与 interaction panel 互斥，但草稿保持挂载。
    expect(composer.closest('.thread-composer')).toHaveAttribute('hidden')

    await user.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByRole('region', { name: '键盘快捷键' })).not.toBeInTheDocument())
    await waitFor(() => expect(document.activeElement?.classList.contains('composer-editor')).toBe(true))
  })

  it('keeps the /shortcuts catalog available on blank panes', async () => {
    const user = userEvent.setup()
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    render(
      <QueryClientProvider client={queryClient}>
        <ChatWorkspacePane
          chat={{
            id: 'chat-1',
            title: 'C',
            agentName: 'assistant',
            yoloEnabled: false,
            version: '1',
            createTime: null,
            updateTime: null,
          }}
          agents={agents}
          environments={[]}
          pane={{ id: 'pane-1', threadId: null }}
          focused
          threadSort="recent"
          onFocus={() => undefined}
          onThreadChange={vi.fn()}
          onThreadSortChange={() => undefined}
          onAgentChange={vi.fn(async () => undefined)}
        />
      </QueryClientProvider>,
    )
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.click(composer)
    await user.keyboard('/shortcuts{Enter}')
    expect(await screen.findByRole('region', { name: '键盘快捷键' })).toBeInTheDocument()
    await user.keyboard('{Escape}')
    await waitFor(() =>
      expect(document.activeElement?.classList.contains('composer-editor')).toBe(true),
    )
  })

  it('switches to the events main view, opens a read-only detail, and returns via /events toggle', async () => {
    const user = userEvent.setup()
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(
      snapshot(thread({}), { entries: sessionEntries() }),
    )
    renderBoundPane()
    const composer = await screen.findByLabelText('给 AI 发送消息')

    // /events：唯一主滚动区切换为事件列表，transcript 卸载。
    await user.click(composer)
    await user.keyboard('/events{Enter}')
    const list = await screen.findByRole('listbox', { name: '事件' })
    expect(await screen.findByLabelText('系统提示词')).toHaveTextContent('You are the planner.')
    expect(document.querySelector('.thread-dialogue')).toBeNull()
    const options = within(list).getAllByRole('option')
    expect(options.length).toBeGreaterThanOrEqual(3)
    // Composer 保持 active：事件视图不是 interaction panel。
    expect(composer.closest('.thread-composer')).not.toHaveAttribute('hidden')

    // 点击事件：Composer 上方展示只读 detail widget（原始 payload JSON 有效）。
    const userOption = within(list).getByRole('option', { name: /s1 prompt/ })
    await user.click(userOption)
    await screen.findByLabelText('事件详情')
    const payloadPre = document.querySelector<HTMLElement>('.thread-event-detail-payload')
    expect(payloadPre?.textContent).toMatch(/"role":\s*"USER"/)
    expect(payloadPre?.textContent).toContain('s1 prompt')
    expect(() => JSON.parse(payloadPre?.textContent ?? '')).not.toThrow()
    // detail 不是 InteractionPanel：不隐藏 Composer、不抢焦点。
    expect(composer.closest('.thread-composer')).not.toHaveAttribute('hidden')
    expect(document.querySelector('.thread-interaction-panel')).toBeNull()

    // 再次 /events：toggle 回 transcript，事件视图卸载。
    await user.click(composer)
    await user.keyboard('/events{Enter}')
    await waitFor(() => expect(document.querySelector('.thread-dialogue')).not.toBeNull())
    expect(screen.queryByRole('listbox', { name: '事件' })).not.toBeInTheDocument()
    // 草稿保持：切换不丢 Composer 状态。
    expect(composer.textContent).toBe('')
  })

  it('keeps the open event detail on the currently selected row when navigating with arrows', async () => {
    const user = userEvent.setup()
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(
      snapshot(thread({}), { entries: sessionEntries() }),
    )
    renderBoundPane()
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.click(composer)
    await user.keyboard('/events{Enter}')
    const list = await screen.findByRole('listbox', { name: '事件' })
    await user.click(within(list).getByRole('option', { name: /s1 prompt/ }))
    await screen.findByLabelText('事件详情', { exact: true })
    expect(document.querySelector('.thread-event-detail-payload')?.textContent).toContain('s1 prompt')

    fireEvent.click(list)
    await user.keyboard('{ArrowDown}')
    await waitFor(() => {
      expect(list.getAttribute('aria-activedescendant')).toBe('thread-event-entry:s1-assistant')
      expect(document.querySelector('.thread-event-detail-header h3')?.textContent).toBe('MESSAGE')
      expect(document.querySelector('.thread-event-detail-payload')?.textContent).toContain('s1 reply')
    })
  })

  it('resets the main view back to conversation when the pane rebinds to another Thread', async () => {
    const user = userEvent.setup()
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(
      snapshot(thread({}), { entries: sessionEntries() }),
    )
    const view = renderBoundPane()
    const composer = await screen.findByLabelText('给 AI 发送消息')

    await user.click(composer)
    await user.keyboard('/events{Enter}')
    await screen.findByRole('listbox', { name: '事件' })
    expect(document.querySelector('.thread-dialogue')).toBeNull()

    // threadId 改变（pane 重新绑定）：视图重置回 conversation。
    view.rerender('t2')
    await waitFor(() => expect(document.querySelector('.thread-dialogue')).not.toBeNull())
    expect(screen.queryByRole('listbox', { name: '事件' })).not.toBeInTheDocument()
  })

  it('rebinds via /tree PUT /head with the snapshot revision as the CAS cursor', async () => {
    const user = userEvent.setup()
    const fullSessionEntries = [
      ...sessionEntries(),
      entry('s1-inactive-branch', 's1-user', 'ASSISTANT', 'inactive branch reply'),
    ]
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(
      snapshot(thread({ headEntryId: 's1-assistant', revision: '3' }), {
        entries: sessionEntries(),
      }),
    )
    vi.mocked(harnessService.listThreadEntries).mockResolvedValue(fullSessionEntries)
    renderBoundPane()
    const composer = await screen.findByLabelText('给 AI 发送消息')
    expect(harnessService.listThreadEntries).not.toHaveBeenCalled()

    await user.click(composer)
    await user.keyboard('/tree{Enter}')
    expect(await screen.findByRole('region', { name: '历史分支' })).toBeInTheDocument()
    expect(harnessService.listThreadEntries).toHaveBeenCalledWith('t1')
    const inactiveBranch = await screen.findByRole('button', { name: /inactive branch reply/ })
    expect(inactiveBranch.querySelector('.history-branch-entry-glyphs')).toHaveTextContent('└─')

    await user.click(await screen.findByRole('button', { name: /用户 · s1 prompt/ }))
    await user.click(screen.getByRole('button', { name: '从这里继续当前 Thread' }))

    await waitFor(() =>
      expect(harnessService.updateThreadHead).toHaveBeenCalledWith('t1', {
        targetEntryId: 's1-root',
        expectedRevision: '3',
      }),
    )
  })

  it('uses the app confirmation modal when the draft is dirty and aborts relocation when cancelled', async () => {
    const user = userEvent.setup()
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(
      snapshot(thread({ revision: '0' }), { entries: sessionEntries() }),
    )
    renderBoundPane()
    const composer = await screen.findByLabelText('给 AI 发送消息')

    // 通过编辑 agent 让 draft 变脏。
    await user.click(composer)
    await user.keyboard('/agent{Enter}')
    await user.click(await screen.findByRole('option', { name: /coder/ }))

    await user.click(screen.getByLabelText('给 AI 发送消息'))
    await user.keyboard('/tree{Enter}')
    await user.click(await screen.findByRole('button', { name: /用户 · s1 prompt/ }))
    await user.click(screen.getByRole('button', { name: '从这里继续当前 Thread' }))

    const discardDialog = await screen.findByRole(
      'alertdialog',
      { name: '丢弃未发送的修改？' },
    )
    expect(discardDialog).toHaveTextContent(
      '切换到所选历史位置后会丢弃尚未随消息提交的 Agent、Environment、Model 或 Permission 设置，是否继续？',
    )
    await user.click(within(discardDialog).getByRole('button', { name: '取消' }))
    expect(harnessService.updateThreadHead).not.toHaveBeenCalled()

    await user.click(screen.getByRole('button', { name: '从这里继续当前 Thread' }))
    await user.click(await screen.findByRole('button', { name: '丢弃修改' }))
    await waitFor(() => expect(harnessService.updateThreadHead).toHaveBeenCalled())
  })

  it('blocks relocation while queued commands are pending with the threadRunning reason text', async () => {
    const user = userEvent.setup()
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(
      snapshot(
        thread({}),
        {
          entries: sessionEntries(),
          queuedCommands: [
            {
              threadId: 't1',
              sequence: '1',
              type: 'USER_MESSAGE',
              state: 'QUEUED',
              clientCommandId: 'cid-pending',
              requestHash: '0123456789abcdef'.repeat(4),
              payloadJson: '{}',
              consumedTurnStartEntryId: null,
              cancelledAt: null,
              createTime: null,
            },
          ],
        },
      ),
    )
    renderBoundPane()
    const composer = await screen.findByLabelText('给 AI 发送消息')

    await user.click(composer)
    await user.keyboard('/tree{Enter}')
    expect(await screen.findByText(/当前 Thread 正在运行/)).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: '历史分支' })).not.toBeInTheDocument()
    expect(harnessService.updateThreadHead).not.toHaveBeenCalled()
  })


  it('adopts the selected Agent name + activeTools while freezing model/environment/yolo', async () => {
    const user = userEvent.setup()
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(
      snapshot(
        thread({
          branchSettings: branchSettings({
            agentName: 'assistant',
            environment: null,
          }),
        }),
      ),
    )
    renderBoundPane()
    const composer = await screen.findByLabelText('给 AI 发送消息')

    await user.click(composer)
    await user.keyboard('/agent{Enter}')
    await user.click(await screen.findByRole('option', { name: /coder/ }))

    // Model/Variant 常驻于 Composer；Agent 只通过轻量选择面板编辑，不再放进 Footer。
    expect(screen.getByRole('button', { name: 'Model 与 Variant' })).toHaveTextContent(
      'minimax/MiniMax · default',
    )
    expect(screen.queryByText(/agent:coder/)).not.toBeInTheDocument()

    await user.type(composer, 'run')
    await user.click(screen.getByRole('button', { name: '发送消息' }))
    await waitFor(() => expect(harnessService.enqueueCommands).toHaveBeenCalledTimes(1))
    const batchArg = vi.mocked(harnessService.enqueueCommands).mock.calls[0]![1]
    const setAgent = batchArg.commands.find((command) => command.type === 'SET_AGENT')!
    expect(setAgent.agentName).toBe('coder')
    const setTools = batchArg.commands.find((command) => command.type === 'SET_ACTIVE_TOOLS')!
    expect(setTools.activeTools).toEqual(['web-search'])
    // Freeze 规则：仅 agent 变更产生 SET_AGENT + SET_ACTIVE_TOOLS 两个 SET 命令，
    // 不重新发送 model/environment/yolo；SET 顺序固定为 SET_AGENT -> SET_ACTIVE_TOOLS -> USER_MESSAGE。
    expect(batchArg.commands.map((command) => command.type)).toEqual([
      'SET_AGENT',
      'SET_ACTIVE_TOOLS',
      'USER_MESSAGE',
    ])
  })

  it('initializes Composer controls from queued settings without sending a reversal diff', async () => {
    const user = userEvent.setup()
    const alternateModel = {
      ...modelEntry(),
      providerName: 'local',
      name: 'Alternate',
      config: {
        ...modelEntry().config,
        defaultVariant: 'review',
        variants: [{ id: 'default' }, { id: 'review' }],
      },
    }
    vi.mocked(agentService.listModels).mockResolvedValue(page([modelEntry(), alternateModel]))
    const queuedSetting = (
      sequence: string,
      type: HarnessThreadCommandDTO['type'],
      payload: Record<string, unknown>,
    ): HarnessThreadCommandDTO => ({
      threadId: 't1',
      sequence,
      type,
      state: 'QUEUED',
      clientCommandId: `queued-${sequence}`,
      requestHash: '0123456789abcdef'.repeat(4),
      payloadJson: JSON.stringify(payload),
      consumedTurnStartEntryId: null,
      cancelledAt: null,
      createTime: null,
    })
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(
      snapshot(thread({}), {
        queuedCommands: [
          queuedSetting('1', 'SET_MODEL', {
            model: { providerName: 'local', modelName: 'Alternate', variant: 'review' },
          }),
          queuedSetting('2', 'SET_YOLO', { yoloEnabled: true }),
        ],
      }),
    )
    renderBoundPane()
    const composer = await screen.findByLabelText('给 AI 发送消息')

    expect(await screen.findByRole('button', { name: 'Model 与 Variant' })).toHaveTextContent(
      'local/Alternate · review',
    )
    expect(screen.getByRole('button', { name: '权限模式' })).toHaveTextContent('YOLO')

    await user.type(composer, 'continue queued settings')
    await user.click(screen.getByRole('button', { name: '发送消息' }))
    await waitFor(() => expect(harnessService.enqueueCommands).toHaveBeenCalledTimes(1))
    expect(
      vi.mocked(harnessService.enqueueCommands).mock.calls[0]![1].commands.map(
        (command) => command.type,
      ),
    ).toEqual(['USER_MESSAGE'])
  })

  it('blocks /thread while queued commands are pending and never opens the picker', async () => {
    const user = userEvent.setup()
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(
      snapshot(thread({}), {
        queuedCommands: [
          {
            threadId: 't1',
            sequence: '1',
            type: 'USER_MESSAGE',
            state: 'QUEUED',
            clientCommandId: 'cid-pending',
            requestHash: '0123456789abcdef'.repeat(4),
            payloadJson: '{}',
            consumedTurnStartEntryId: null,
            cancelledAt: null,
            createTime: null,
          },
        ],
      }),
    )
    const { onThreadChange } = renderBoundPane()
    const composer = await screen.findByLabelText('给 AI 发送消息')

    await user.click(composer)
    await user.keyboard('/thread{Enter}')
    expect(await screen.findByText(/当前 Thread 正在运行/)).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: /选择 Thread/ })).not.toBeInTheDocument()
    expect(chatService.listChatThreads).not.toHaveBeenCalled()
    expect(onThreadChange).not.toHaveBeenCalled()
  })

  it('blocks /new while queued commands are pending', async () => {
    const user = userEvent.setup()
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(
      snapshot(thread({}), {
        queuedCommands: [
          {
            threadId: 't1',
            sequence: '1',
            type: 'USER_MESSAGE',
            state: 'QUEUED',
            clientCommandId: 'cid-pending',
            requestHash: '0123456789abcdef'.repeat(4),
            payloadJson: '{}',
            consumedTurnStartEntryId: null,
            cancelledAt: null,
            createTime: null,
          },
        ],
      }),
    )
    const { onThreadChange } = renderBoundPane()
    const composer = await screen.findByLabelText('给 AI 发送消息')

    await user.click(composer)
    await user.keyboard('/new{Enter}')
    expect(await screen.findByText(/当前 Thread 正在运行/)).toBeInTheDocument()
    expect(onThreadChange).not.toHaveBeenCalled()
  })

  it('asks for confirmation before /new when the draft is dirty', async () => {
    const user = userEvent.setup()
    const { onThreadChange } = renderBoundPane()
    const composer = await screen.findByLabelText('给 AI 发送消息')

    await user.click(composer)
    await user.keyboard('/agent{Enter}')
    await user.click(await screen.findByRole('option', { name: /coder/ }))

    await user.click(screen.getByLabelText('给 AI 发送消息'))
    await user.keyboard('/new{Enter}')
    expect(
      await screen.findByRole('alertdialog', { name: '丢弃未发送的修改？' }),
    ).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '取消' }))
    expect(onThreadChange).not.toHaveBeenCalled()

    // 确认后会丢弃 draft 并打开空面板。
    await user.click(composer)
    await user.keyboard('/new{Enter}')
    await user.click(await screen.findByRole('button', { name: '丢弃修改' }))
    expect(onThreadChange).toHaveBeenCalledWith(null)
    expect(harnessService.enqueueCommands).not.toHaveBeenCalled()
  })

  it('starts a new pane without confirmation when /new is the only composer content', async () => {
    const user = userEvent.setup()
    const { onThreadChange } = renderBoundPane()
    const composer = await screen.findByLabelText('给 AI 发送消息')

    await user.click(composer)
    await user.keyboard('/new{Enter}')

    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(onThreadChange).toHaveBeenCalledWith(null)
    expect(harnessService.enqueueCommands).not.toHaveBeenCalled()
  })

  it('selecting the currently bound Thread from /thread needs no confirmation', async () => {
    const user = userEvent.setup()
    const { onThreadChange } = renderBoundPane()
    const composer = await screen.findByLabelText('给 AI 发送消息')

    await user.click(composer)
    await user.keyboard('/agent{Enter}')
    await user.click(await screen.findByRole('option', { name: /coder/ }))

    await user.click(screen.getByLabelText('给 AI 发送消息'))
    await user.keyboard('/thread{Enter}')
    await user.click(await screen.findByRole('option', { name: /t1/ }))

    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(onThreadChange).not.toHaveBeenCalled()
    expect(harnessService.updateThreadHead).not.toHaveBeenCalled()
  })


  it('treats non-empty composer text as pane-dirty for /tree relocation', async () => {
    const user = userEvent.setup()
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(
      snapshot(thread({ revision: '0' }), { entries: sessionEntries() }),
    )
    renderBoundPane()
    const composer = await screen.findByLabelText('给 AI 发送消息')

    // 非空草稿通过 + 命令表打开历史操作面板；面板与 Composer 互斥展示，
    // 但隐藏的 Composer 实例继续持有原草稿。
    await user.click(composer)
    await user.type(composer, 'unsent text')
    await user.click(screen.getByRole('button', { name: '打开命令表' }))
    await user.click(await screen.findByRole('option', { name: /^tree/ }))
    expect(await screen.findByRole('region', { name: '历史分支' })).toBeInTheDocument()
    expect(composer.closest('.thread-composer')).toHaveAttribute('hidden')
    await user.click(await screen.findByRole('button', { name: /用户 · s1 prompt/ }))
    await user.click(screen.getByRole('button', { name: '从这里继续当前 Thread' }))

    const discardDialog = await screen.findByRole(
      'alertdialog',
      { name: '丢弃未发送的修改？' },
    )
    expect(discardDialog).toHaveTextContent(
      '切换到所选历史位置后会丢弃输入框中未发送的消息，是否继续？',
    )
    await user.click(within(discardDialog).getByRole('button', { name: '取消' }))
    expect(harnessService.updateThreadHead).not.toHaveBeenCalled()

    await user.click(screen.getByRole('button', { name: '从这里继续当前 Thread' }))
    await user.click(await screen.findByRole('button', { name: '丢弃修改' }))
    await waitFor(() => expect(harnessService.updateThreadHead).toHaveBeenCalled())
  })

  it('blocks /thread while an approval decision is in flight (panePending)', async () => {
    const user = userEvent.setup()
    vi.mocked(harnessService.decideApproval).mockReturnValue(new Promise(() => undefined))
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(
      snapshot(thread({}), {
        entries: [toolCallEntry('40', 's1-root', 'call-1', 'bash')],
        toolInvocations: [toolInvocation()],
      }),
    )
    const { onThreadChange } = renderBoundPane()
    const composer = await screen.findByLabelText('给 AI 发送消息')

    await user.click(await screen.findByRole('button', { name: '允许' }))
    await waitFor(() =>
      expect(screen.getByRole('button', { name: '允许' })).toBeDisabled(),
    )

    await user.click(composer)
    await user.keyboard('/thread{Enter}')
    expect(await screen.findByText(/当前 Thread 正在运行/)).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: /选择 Thread/ })).not.toBeInTheDocument()
    expect(onThreadChange).not.toHaveBeenCalled()
  })

  it('blocks /tree when the Thread status is not IDLE/CONTINUATION_DUE', async () => {
    const user = userEvent.setup()
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(
      snapshot(thread({ status: 'MODEL_STREAMING' }), { entries: sessionEntries() }),
    )
    renderBoundPane()
    const composer = await screen.findByLabelText('给 AI 发送消息')

    await user.click(composer)
    await user.keyboard('/tree{Enter}')
    expect(await screen.findByText(/当前 Thread 正在运行/)).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: '历史分支' })).not.toBeInTheDocument()
    expect(harnessService.updateThreadHead).not.toHaveBeenCalled()
  })

  it('restores the branch target draft into the composer after a successful /tree rebind', async () => {
    const user = userEvent.setup()
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(
      snapshot(thread({ revision: '3' }), { entries: sessionEntries() }),
    )
    renderBoundPane()
    const composer = await screen.findByLabelText('给 AI 发送消息')

    await user.click(composer)
    await user.keyboard('/tree{Enter}')
    expect(await screen.findByRole('region', { name: '历史分支' })).toBeInTheDocument()

    // USER entry 会将 head 回退到其父节点，并恢复其可编辑文本。
    await user.click(await screen.findByRole('button', { name: /用户 · s1 prompt/ }))
    await user.click(screen.getByRole('button', { name: '从这里继续当前 Thread' }))

    await waitFor(() =>
      expect(harnessService.updateThreadHead).toHaveBeenCalledWith('t1', {
        targetEntryId: 's1-root',
        expectedRevision: '3',
      }),
    )
    await waitFor(() => expect(composer).toHaveTextContent('s1 prompt'))
    expect(screen.queryByRole('region', { name: '历史分支' })).not.toBeInTheDocument()
  })


  it('disables approval buttons pane-wide while the approval request is in flight and re-enables after', async () => {
    const user = userEvent.setup()
    let resolveApproval: (value: unknown) => void = () => undefined
    vi.mocked(harnessService.decideApproval).mockReturnValue(
      new Promise((resolve) => {
        resolveApproval = resolve
      }),
    )
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(
      snapshot(thread({}), {
        entries: [toolCallEntry('40', 's1-root', 'call-1', 'bash')],
        toolInvocations: [toolInvocation()],
      }),
    )
    renderBoundPane()
    const allowButton = await screen.findByRole('button', { name: '允许' })
    const denyButton = screen.getByRole('button', { name: '拒绝' })
    expect(allowButton).toBeEnabled()

    await user.click(allowButton)
    // 全局 approvalPending 会在整条链路中传递：HTTP 请求 pending 期间
    // 两个按钮都会被禁用。
    await waitFor(() => expect(allowButton).toBeDisabled())
    expect(denyButton).toBeDisabled()

    await act(async () => {
      resolveApproval({})
    })
    // 决策成功后会重新拉取 snapshot 并重渲染该 bar：按钮会重新启用
    //（持久化 snapshot 仍显示 undecided，因此 bar 仍可交互）。
    await waitFor(() =>
      expect(screen.getByRole('button', { name: '允许' })).toBeEnabled(),
    )
    expect(screen.getByRole('button', { name: '拒绝' })).toBeEnabled()
  })

  it('never sends a second same-CAS batch while the first request is still in flight', async () => {
    const user = userEvent.setup()
    let resolveSend: (value: unknown) => void = () => undefined
    vi.mocked(harnessService.enqueueCommands).mockReturnValue(
      new Promise((resolve) => {
        resolveSend = resolve
      }),
    )
    renderBoundPane()
    const composer = await screen.findByLabelText('给 AI 发送消息')

    await user.click(composer)
    await user.type(composer, 'only once')
    await user.click(screen.getByRole('button', { name: '发送消息' }))

    // 在 pending 期间，composer 和发送按钮被禁用：双击和按 Enter
    // 都不会基于同一组 CAS cursor 再生成一个 batch。
    await waitFor(() =>
      expect(screen.getByRole('button', { name: '发送消息' })).toBeDisabled(),
    )
    expect(screen.getByLabelText('给 AI 发送消息')).toHaveAttribute('aria-disabled', 'true')
    await user.click(screen.getByRole('button', { name: '发送消息' }))
    await user.keyboard('{Enter}')
    expect(harnessService.enqueueCommands).toHaveBeenCalledTimes(1)

    await act(async () => {
      resolveSend([])
    })
    await waitFor(() =>
      expect(harnessService.enqueueCommands).toHaveBeenCalledTimes(1),
    )
  })

  it('recovers a queued message when the running Thread advances past the rendered cursor', async () => {
    const user = userEvent.setup()
    const initial = thread({
      headEntryId: 'e-assistant',
      nextCommandSequence: '1',
      revision: '0',
    })
    const advanced = thread({
      headEntryId: 'e-turn-start',
      nextCommandSequence: '2',
      revision: '2',
      status: 'MODEL_STREAMING',
      processing: true,
    })
    vi.mocked(harnessService.getThreadSnapshot)
      .mockResolvedValueOnce(snapshot(initial))
      .mockResolvedValue(
        snapshot(advanced, {
          entries: [
            entry('e-assistant', null, 'ASSISTANT', 'previous answer'),
            {
              entryId: 'e-turn-start',
              sessionId: 's1',
              parentEntryId: 'e-assistant',
              entryType: 'TURN_START',
              payloadJson: '{}',
              createTime: null,
            },
          ],
        }),
      )
    vi.mocked(harnessService.enqueueCommands)
      .mockRejectedValueOnce(
        new ApiError(
          'The request conflicts with the current resource state.',
          409,
          'CONFLICT',
          { reason: 'STALE_COMMAND_CURSOR' },
        ),
      )
      .mockResolvedValueOnce([] as HarnessThreadCommandDTO[])
    renderBoundPane()
    const composer = await screen.findByLabelText('给 AI 发送消息')

    await user.click(composer)
    await user.type(composer, 'queued while the model advances')
    await user.click(screen.getByRole('button', { name: '发送消息' }))

    await waitFor(() => expect(harnessService.enqueueCommands).toHaveBeenCalledTimes(2))
    const calls = vi.mocked(harnessService.enqueueCommands).mock.calls
    expect(calls[0]?.[1]).toMatchObject({
      expectedHeadEntryId: 'e-assistant',
      expectedNextCommandSequence: '1',
    })
    expect(calls[1]?.[1]).toMatchObject({
      expectedHeadEntryId: 'e-turn-start',
      expectedNextCommandSequence: '2',
    })
    expect(calls[1]?.[1].commands[0]?.clientCommandId).toBe(
      calls[0]?.[1].commands[0]?.clientCommandId,
    )
    expect(screen.queryByText(/Thread 状态已变化/)).not.toBeInTheDocument()
    await waitFor(() => expect(composer).toBeEmptyDOMElement())
  })

  it('surfaces a 409 from send as the threadStateChanged message without clearing the draft', async () => {
    const user = userEvent.setup()
    vi.mocked(harnessService.enqueueCommands).mockRejectedValueOnce(
      new ApiError('expected revision mismatch', 409),
    )
    renderBoundPane()
    const composer = await screen.findByLabelText('给 AI 发送消息')

    await user.click(composer)
    await user.type(composer, 'will collide')
    await user.click(screen.getByRole('button', { name: '发送消息' }))

    await waitFor(() =>
      expect(screen.getByText(/Thread 状态已变化/)).toBeInTheDocument(),
    )
    expect(harnessService.updateThreadHead).not.toHaveBeenCalled()
    // draft 已被恢复，用户无需重新输入即可重试。
    await waitFor(() => expect(composer).toHaveTextContent('will collide'))
  })

  it('keeps /events enabled as a toggle in both main views', async () => {
    const user = userEvent.setup()
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(
      snapshot(thread({}), { entries: sessionEntries() }),
    )
    renderBoundPane()
    const composer = await screen.findByLabelText('给 AI 发送消息')

    await user.click(composer)
    await user.keyboard('/')
    await waitFor(() => {
      expect(screen.getByRole('option', { name: /^events/ })).toBeInTheDocument()
    })
    expect(screen.getByRole('option', { name: /^events/ })).toHaveAttribute('aria-disabled', 'false')
    expect(screen.queryByRole('option', { name: /^conversation/ })).not.toBeInTheDocument()
    await user.keyboard('{Escape}')

    await user.keyboard('/events{Enter}')
    await screen.findByRole('listbox', { name: '事件' })
    await user.click(composer)
    await user.keyboard('/')
    await waitFor(() => {
      expect(screen.getByRole('option', { name: /^events/ })).toBeInTheDocument()
    })
    expect(screen.getByRole('option', { name: /^events/ })).toHaveAttribute('aria-disabled', 'false')
    expect(screen.queryByRole('option', { name: /^conversation/ })).not.toBeInTheDocument()
  })

  it('closes the event detail when switching back to conversation', async () => {
    const user = userEvent.setup()
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(
      snapshot(thread({}), { entries: sessionEntries() }),
    )
    renderBoundPane()
    const composer = await screen.findByLabelText('给 AI 发送消息')

    await user.click(composer)
    await user.keyboard('/events{Enter}')
    const list = await screen.findByRole('listbox', { name: '事件' })
    await user.click(within(list).getByRole('option', { name: /s1 prompt/ }))
    await screen.findByLabelText('事件详情', { exact: true })

    await user.click(composer)
    await user.keyboard('/events{Enter}')
    await waitFor(() => expect(document.querySelector('.thread-dialogue')).not.toBeNull())
    expect(screen.queryByLabelText('事件详情', { exact: true })).not.toBeInTheDocument()
  })

  it('refreshes the selected detail by id and clears it when the id disappears', async () => {
    const user = userEvent.setup()
    let current = snapshot(thread({}), { entries: sessionEntries() })
    vi.mocked(harnessService.getThreadSnapshot).mockImplementation(async () => current)
    const { queryClient } = renderBoundPane()
    const composer = await screen.findByLabelText('给 AI 发送消息')

    await user.click(composer)
    await user.keyboard('/events{Enter}')
    const list = await screen.findByRole('listbox', { name: '事件' })
    await user.click(within(list).getByRole('option', { name: /s1 prompt/ }))
    await screen.findByLabelText('事件详情', { exact: true })
    const payloadBefore = document.querySelector<HTMLElement>('.thread-event-detail-payload')
    expect(payloadBefore?.textContent).toContain('s1 prompt')

    // 新 snapshot：同 id 的 USER 条目内容更新（events 数组引用变化、id 仍在）。
    current = snapshot(thread({}), {
      entries: [
        sessionEntries()[0]!,
        { ...sessionEntries()[1]!, payloadJson: JSON.stringify({
          message: { role: 'USER', contents: [{ type: 'text', text: 's1 prompt v2' }] },
        }) },
        sessionEntries()[2]!,
      ],
    })
    await act(async () => {
      queryClient.invalidateQueries({ queryKey: ['threads', 'snapshot', 't1'] })
    })
    await waitFor(() => {
      const payload = document.querySelector<HTMLElement>('.thread-event-detail-payload')
      expect(payload?.textContent).toContain('s1 prompt v2')
    })

    // 新 snapshot：选中 id 消失（USER 条目被移除）→ detail 清空。
    current = snapshot(thread({}), {
      entries: [sessionEntries()[0]!, sessionEntries()[2]!],
    })
    await act(async () => {
      queryClient.invalidateQueries({ queryKey: ['threads', 'snapshot', 't1'] })
    })
    await waitFor(() =>
      expect(screen.queryByLabelText('事件详情', { exact: true })).not.toBeInTheDocument(),
    )
  })

  it('restores independent scroll positions per main view and clears them on Thread rebind', async () => {
    const user = userEvent.setup()
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(
      snapshot(thread({}), { entries: sessionEntries() }),
    )
    // 真实 DOM 属性：jsdom 不计算布局，用 getter spy 提供可滚动的容器尺寸。
    const scrollHeightSpy = vi.spyOn(HTMLElement.prototype, 'scrollHeight', 'get')
    const clientHeightSpy = vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get')
    scrollHeightSpy.mockReturnValue(600)
    clientHeightSpy.mockReturnValue(200)
    try {
      const view = renderBoundPane()
      const composer = await screen.findByLabelText('给 AI 发送消息')
      const dialogue = () => document.querySelector<HTMLElement>('.thread-dialogue')!
      const eventsList = () => document.querySelector<HTMLElement>('.thread-events')!

      // conversation 首次进入贴底（600）；用户上滑到 100。
      await waitFor(() => expect(dialogue().scrollTop).toBe(600))
      dialogue().scrollTop = 100

      // 切到 events（首次进入贴底 600）；用户上滑到 120。
      await user.click(composer)
      await user.keyboard('/events{Enter}')
      await screen.findByRole('listbox', { name: '事件' })
      await waitFor(() => expect(eventsList().scrollTop).toBe(600))
      eventsList().scrollTop = 120

      // 切回 conversation：恢复 100（不是贴底）。
      await user.click(composer)
      await user.keyboard('/events{Enter}')
      await waitFor(() => expect(dialogue().scrollTop).toBe(100))

      // 再进 events：恢复 120（不是贴底）。
      await user.click(composer)
      await user.keyboard('/events{Enter}')
      await screen.findByRole('listbox', { name: '事件' })
      await waitFor(() => expect(eventsList().scrollTop).toBe(120))

      // Thread 重绑：两个位置清零；再进 events 恢复为首次进入贴底。
      view.rerender('t2')
      await waitFor(() => expect(dialogue().scrollTop).toBe(600))
      await user.click(composer)
      await user.keyboard('/events{Enter}')
      await screen.findByRole('listbox', { name: '事件' })
      await waitFor(() => expect(eventsList().scrollTop).toBe(600))
    } finally {
      scrollHeightSpy.mockRestore()
      clientHeightSpy.mockRestore()
    }
  })

  it('keeps an independent conversation stick lifecycle from the events view and follows the 210px threshold', async () => {
    const user = userEvent.setup()
    let current = snapshot(thread({}), { entries: sessionEntries() })
    vi.mocked(harnessService.getThreadSnapshot).mockImplementation(async () => current)
    // 真实 DOM 属性：jsdom 不计算布局，用 getter spy 提供可滚动的容器尺寸。
    const scrollHeightSpy = vi.spyOn(HTMLElement.prototype, 'scrollHeight', 'get')
    const clientHeightSpy = vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get')
    scrollHeightSpy.mockReturnValue(600)
    clientHeightSpy.mockReturnValue(200)
    try {
      const { queryClient } = renderBoundPane()
      const composer = await screen.findByLabelText('给 AI 发送消息')
      const dialogue = () => document.querySelector<HTMLElement>('.thread-dialogue')!
      const eventsList = () => document.querySelector<HTMLElement>('.thread-events')!

      // conversation 首次进入贴底；用户上滑到 450（距底部 -50px，<=210 阈值，stick 仍为 true）。
      await waitFor(() => expect(dialogue().scrollTop).toBe(600))
      dialogue().scrollTop = 450
      fireEvent.scroll(dialogue())

      // 切到 events：首次进入贴底（conversation 的 stick 状态不泄漏到 events）。
      await user.click(composer)
      await user.keyboard('/events{Enter}')
      await screen.findByRole('listbox', { name: '事件' })
      await waitFor(() => expect(eventsList().scrollTop).toBe(600))
      // events 用户上滑到 120（距底部 280px，>210 阈值，events stick=false）。
      eventsList().scrollTop = 120
      fireEvent.scroll(eventsList())

      // 切回 conversation：恢复 450（阈值内 → stick 跟随自己的恢复位置，而不是 events 的 stick=false）。
      await user.click(composer)
      await user.keyboard('/events{Enter}')
      await waitFor(() => expect(dialogue().scrollTop).toBe(450))

      // 内容增长：conversation 仍贴底 —— events 的 stick=false 绝不泄漏到 conversation。
      current = snapshot(thread({}), {
        entries: [
          ...sessionEntries(),
          entry('s1-extra', 's1-assistant', 'ASSISTANT', 's1 reply 2'),
        ],
      })
      await act(async () => {
        queryClient.invalidateQueries({ queryKey: ['threads', 'snapshot', 't1'] })
      })
      await waitFor(() => expect(dialogue().scrollTop).toBe(600))

      // 再进 events：恢复 120（不是贴底；events 自己的 stick=false 跟随自己的位置）。
      await user.click(composer)
      await user.keyboard('/events{Enter}')
      await screen.findByRole('listbox', { name: '事件' })
      await waitFor(() => expect(eventsList().scrollTop).toBe(120))
    } finally {
      scrollHeightSpy.mockRestore()
      clientHeightSpy.mockRestore()
    }
  })

  it('clears the events selection when switching back to conversation', async () => {
    const user = userEvent.setup()
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(
      snapshot(thread({}), { entries: sessionEntries() }),
    )
    renderBoundPane()
    const composer = await screen.findByLabelText('给 AI 发送消息')

    await user.click(composer)
    await user.keyboard('/events{Enter}')
    const list = await screen.findByRole('listbox', { name: '事件' })
    await user.click(within(list).getByRole('option', { name: /s1 prompt/ }))
    await waitFor(() =>
      expect(list.getAttribute('aria-activedescendant')).toBe('thread-event-entry:s1-user'),
    )
    await screen.findByLabelText('事件详情', { exact: true })

    await user.click(composer)
    await user.keyboard('/events{Enter}')
    await waitFor(() => expect(document.querySelector('.thread-dialogue')).not.toBeNull())
    await user.click(composer)
    await user.keyboard('/events{Enter}')
    const list2 = await screen.findByRole('listbox', { name: '事件' })
    expect(list2.getAttribute('aria-activedescendant')).toBeNull()
    expect(screen.queryByLabelText('事件详情', { exact: true })).not.toBeInTheDocument()
  })

  it('resets the events active row and detail when the pane rebinds to another Thread', async () => {
    const user = userEvent.setup()
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(
      snapshot(thread({}), { entries: sessionEntries() }),
    )
    const view = renderBoundPane()
    const composer = await screen.findByLabelText('给 AI 发送消息')

    await user.click(composer)
    await user.keyboard('/events{Enter}')
    const list = await screen.findByRole('listbox', { name: '事件' })
    await user.click(within(list).getByRole('option', { name: /s1 prompt/ }))
    await waitFor(() =>
      expect(list.getAttribute('aria-activedescendant')).toBe('thread-event-entry:s1-user'),
    )
    await screen.findByLabelText('事件详情', { exact: true })

    // threadId 重绑：回到 conversation 且选中清空。
    view.rerender('t2')
    await waitFor(() => expect(document.querySelector('.thread-dialogue')).not.toBeNull())
    expect(screen.queryByLabelText('事件详情', { exact: true })).not.toBeInTheDocument()

    await user.click(composer)
    await user.keyboard('/events{Enter}')
    const list2 = await screen.findByRole('listbox', { name: '事件' })
    expect(list2.getAttribute('aria-activedescendant')).toBeNull()
  })

  it('places the event detail first in the widget zone, before the task status widget', async () => {
    const user = userEvent.setup()
    const heartbeat = JSON.stringify({
      kind: 'task.status',
      threadId: '101',
      subagentType: 'explorer',
      state: 'running_tool',
      depth: 0,
      turns: 3,
      toolCalls: 5,
      lastActivity: 'running read',
      approvals: [],
      descendants: [],
    })
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(
      snapshot(thread({}), {
        entries: [...sessionEntries(), toolCallEntry('e-task', 's1-assistant', 'call-task', 'task')],
        toolInvocations: [
          toolInvocation({
            id: 'inv-task',
            assistantEntryId: 'e-task',
            toolCallId: 'call-task',
            toolName: 'task',
            rendererKey: 'task',
            resultJson: JSON.stringify({ contents: [{ type: 'text', text: heartbeat }] }),
          }),
        ],
      }),
    )
    renderBoundPane()
    const composer = await screen.findByLabelText('给 AI 发送消息')

    // 子任务心跳（task tool overlay）渲染 TaskStatus widget。
    await waitFor(() =>
      expect(document.querySelector('.task-status-widget')).not.toBeNull(),
    )

    // events 视图选中某条记录 → detail widget 出现。
    await user.click(composer)
    await user.keyboard('/events{Enter}')
    const list = await screen.findByRole('listbox', { name: '事件' })
    await user.click(within(list).getByRole('option', { name: /s1 prompt/ }))
    const detail = await screen.findByLabelText('事件详情', { exact: true })

    // 冻结契约：detail 是 widget zone 第一项，位于 TaskStatus 之前。
    const widgetZone = document.querySelector<HTMLElement>('.thread-widget-zone')!
    const detailInZone = widgetZone.querySelector('.thread-event-detail')!
    const statusInZone = widgetZone.querySelector('.task-status-widget')!
    expect(detailInZone).toBe(detail)
    expect(detailInZone.compareDocumentPosition(statusInZone) & Node.DOCUMENT_POSITION_FOLLOWING)
      .not.toBe(0)
  })

  it('permanently mounts the task status widget and removes the footer toggle', async () => {
    const view = renderBoundPane()
    await screen.findByLabelText('给 AI 发送消息')

    // 无 task 消息：TaskStatusWidget 常驻挂载（自身渲染为空），widget zone 仍存在于
    // DOM；Footer 不提供 Task/Notification toggle。
    const widgetZone = () => document.querySelector<HTMLElement>('.thread-widget-zone')
    expect(widgetZone()).not.toBeNull()
    expect(document.querySelector('.task-status-widget')).toBeNull()
    expect(screen.queryByRole('button', { name: 'task-status:on' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'task-status:off' })).not.toBeInTheDocument()
    expect(screen.queryByText(/notify:/)).not.toBeInTheDocument()

    // 有 task 消息：同一个常驻 widget zone 中出现任务状态，无需任何开关。
    const heartbeat = JSON.stringify({
      kind: 'task.status',
      threadId: '101',
      subagentType: 'explorer',
      state: 'running_tool',
      depth: 0,
      turns: 3,
      toolCalls: 5,
      lastActivity: 'running read',
      approvals: [],
      descendants: [],
    })
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(
      snapshot(thread({}), {
        entries: [...sessionEntries(), toolCallEntry('e-task', 's1-assistant', 'call-task', 'task')],
        toolInvocations: [
          toolInvocation({
            id: 'inv-task',
            assistantEntryId: 'e-task',
            toolCallId: 'call-task',
            toolName: 'task',
            rendererKey: 'task',
            resultJson: JSON.stringify({ contents: [{ type: 'text', text: heartbeat }] }),
          }),
        ],
      }),
    )
    view.rerender('t2')
    await waitFor(() =>
      expect(widgetZone()?.querySelector('.task-status-widget')).not.toBeNull(),
    )
    expect(screen.queryByRole('button', { name: 'task-status:on' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'task-status:off' })).not.toBeInTheDocument()
  })

  it('derives the notification permission live when settings change from an external consumer', async () => {
    const user = userEvent.setup()
    const notificationSpy = vi.fn()
    let permission: NotificationPermission = 'default'
    class FakeNotification {
      static get permission(): NotificationPermission {
        return permission
      }
      static requestPermission = vi.fn(async () => 'granted' as const)
      constructor(title: string, options?: NotificationOptions) {
        notificationSpy(title, options)
      }
    }
    Object.defineProperty(window, 'Notification', {
      configurable: true,
      value: FakeNotification,
    })
    // 快照包含一条等待审批的父 Thread 工具调用：通知 hook 启用后会为它推送
    // permission 通知（new Notification 被 spy 捕获），用于精确验证 hook 的
    // enabled 未被 mount 时的陈旧 permission 门控。
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(
      snapshot(thread({}), {
        entries: [
          ...sessionEntries(),
          toolCallEntry('e-bash', 's1-assistant', 'call-bash', 'bash'),
        ],
        toolInvocations: [
          toolInvocation({
            id: 'inv-bash',
            assistantEntryId: 'e-bash',
            toolCallId: 'call-bash',
            toolName: 'bash',
          }),
        ],
      }),
    )
    function SettingsPageProbe() {
      const { notificationsEnabled, setNotificationsEnabled } = useApplicationSettings()
      return (
        <button type="button" onClick={() => setNotificationsEnabled(true)}>
          {notificationsEnabled ? 'on' : 'off'}
        </button>
      )
    }
    try {
      renderBoundPane({ children: <SettingsPageProbe /> })
      await screen.findByLabelText('给 AI 发送消息')

      // mount 时浏览器 permission=default 且全局 off：通知 hook 禁用。
      expect(screen.getByRole('button', { name: /^(on|off)$/ })).toHaveTextContent('off')
      expect(screen.queryByText(/notify:/)).not.toBeInTheDocument()
      expect(notificationSpy).not.toHaveBeenCalled()

      // 模拟 SettingsPage：先获得浏览器权限，再通过全局设置启用通知。
      permission = 'granted'
      await user.click(screen.getByRole('button', { name: /^(on|off)$/ }))

      // 通知 hook 基于当前浏览器 permission 生效，而非 mount 时快照。
      await waitFor(() => expect(notificationSpy).toHaveBeenCalled())
      expect(notificationSpy).toHaveBeenCalledWith(
        '等待审批',
        expect.objectContaining({ body: expect.stringContaining('bash') }),
      )
    } finally {
      delete (window as unknown as { Notification?: unknown }).Notification
      localStorage.removeItem('kkstudio.application-settings.v1')
    }
  })

  it('freezes the widget zone height contract at min(36vh, 320px)', () => {
    const css = readFileSync(resolve(process.cwd(), 'src', 'styles.css'), 'utf8')
    const rule = css.match(/\.thread-widget-zone\s*\{[^}]*\}/)
    expect(rule).not.toBeNull()
    expect(rule![0]).toContain('max-height: min(36vh, 320px)')
  })

  it('isolates events view state across multiple panes', async () => {
    const user = userEvent.setup()
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(
      snapshot(thread({}), { entries: sessionEntries() }),
    )
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    const pane = (paneId: string) => (
      <ChatWorkspacePane
        chat={{
          id: `chat-${paneId}`,
          title: 'C',
          agentName: 'assistant',
          yoloEnabled: false,
          version: '1',
          createTime: null,
          updateTime: null,
        }}
        agents={agents}
        environments={[]}
        pane={{ id: paneId, threadId: 't1' }}
        focused
        threadSort="recent"
        onFocus={() => undefined}
        onThreadChange={vi.fn()}
        onThreadSortChange={vi.fn()}
        onAgentChange={vi.fn(async () => undefined)}
        onYoloChange={vi.fn(async () => undefined)}
      />
    )
    render(
      <ApplicationSettingsProvider>
        <QueryClientProvider client={queryClient}>
          <div data-testid="pane-A">{pane('pane-A')}</div>
          <div data-testid="pane-B">{pane('pane-B')}</div>
        </QueryClientProvider>
      </ApplicationSettingsProvider>,
    )
    const paneA = () => screen.getByTestId('pane-A')
    const paneB = () => screen.getByTestId('pane-B')
    const composerA = await within(paneA()).findByLabelText('给 AI 发送消息')
    const composerB = await within(paneB()).findByLabelText('给 AI 发送消息')

    // Pane A 切到 events；Pane B 保持 conversation（无事件 listbox）。
    await user.click(composerA)
    await user.keyboard('/events{Enter}')
    const listA = await within(paneA()).findByRole('listbox', { name: '事件' })
    expect(within(paneB()).queryByRole('listbox', { name: '事件' })).not.toBeInTheDocument()

    // Pane A 选择详情；Pane B 无 detail。
    await user.click(within(listA).getByRole('option', { name: /s1 prompt/ }))
    await within(paneA()).findByLabelText('事件详情', { exact: true })
    expect(within(paneB()).queryByLabelText('事件详情', { exact: true })).not.toBeInTheDocument()

    // Pane B 也切到 events：两份 listbox 各自持有独立 active。
    await user.click(composerB)
    await user.keyboard('/events{Enter}')
    const listB = await within(paneB()).findByRole('listbox', { name: '事件' })
    expect(within(paneA()).getByRole('listbox', { name: '事件' })).toBeInTheDocument()
    expect(listA.getAttribute('aria-activedescendant')).toBe('thread-event-entry:s1-user')
    expect(listB.getAttribute('aria-activedescendant')).toBeNull()

    // Pane A 切回 conversation：Pane B 的 events 视图不受影响。
    await user.click(composerA)
    await user.keyboard('/events{Enter}')
    await waitFor(() =>
      expect(within(paneA()).queryByRole('listbox', { name: '事件' })).not.toBeInTheDocument(),
    )
    expect(within(paneB()).getByRole('listbox', { name: '事件' })).toBeInTheDocument()
  })
})
