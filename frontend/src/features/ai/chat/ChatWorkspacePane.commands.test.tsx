import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ChatWorkspacePane } from '@/features/ai/chat/ChatWorkspacePane'
import { agentService } from '@/shared/api/agent-service'
import { ApiError } from '@/shared/api/client'
import { chatService } from '@/shared/api/chat-service'
import { harnessService } from '@/shared/api/harness-service'
import type {
  HarnessBranchSettingsDTO,
  HarnessModelSelectionDTO,
  HarnessSessionEntryDTO,
  HarnessThreadCommandDTO,
  HarnessThreadDTO,
  HarnessThreadSnapshotDTO,
} from '@/shared/api/contracts/ai-runtime'

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
    createThreadRealtimeStream: vi.fn(),
  },
}))
vi.mock('@/shared/api/environment-service', () => ({
  environmentService: {
    listEnvironments: vi.fn().mockResolvedValue([]),
  },
}))

class FakeEventSource {
  close = vi.fn()
  addEventListener = vi.fn()
  removeEventListener = vi.fn()
}

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
    environmentName: null,
    agentName: 'assistant',
    model: modelSelection(),
    thinkingLevel: 'off',
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
          { type: 'tool_call', toolCallId, toolName, argumentsJson: '{"command":"ls"}' },
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
    toolType: 'shell',
    environmentName: null,
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
}) {
  const onThreadChange = overrides?.onThreadChange ?? vi.fn()
  const onThreadSortChange = overrides?.onThreadSortChange ?? vi.fn()
  const onAgentChange = overrides?.onAgentChange ?? vi.fn(async () => undefined)
  const onYoloChange = overrides?.onYoloChange ?? vi.fn(async () => undefined)
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
        pane={{ id: 'pane-1', threadId: 't1' }}
        focused
        threadSort="recent"
        onFocus={() => undefined}
        onThreadChange={onThreadChange}
        onThreadSortChange={onThreadSortChange}
        onAgentChange={onAgentChange}
        onYoloChange={onYoloChange}
      />
    </QueryClientProvider>,
  )
  return {
    queryClient,
    onThreadChange,
    onThreadSortChange,
    onAgentChange,
    onYoloChange,
  }
}

describe('ChatWorkspacePane commands', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(agentService.listAgents).mockResolvedValue(page(agents))
    vi.mocked(agentService.listModels).mockResolvedValue(page([modelEntry()]))
    vi.mocked(agentService.listProviders).mockResolvedValue(page([]))
    vi.mocked(harnessService.createThreadRealtimeStream).mockReturnValue(
      new FakeEventSource() as unknown as EventSource,
    )
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(snapshot(thread({})))
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
    await user.click(screen.getByRole('button', { name: /coder/ }))
    await waitFor(() => expect(onAgentChange).not.toHaveBeenCalled())

    await user.click(screen.getByLabelText('给 AI 发送消息'))
    await user.keyboard('/environment{Enter}')
    expect(await screen.findByLabelText('选择 Environment')).toBeInTheDocument()
    await user.click(within(screen.getByLabelText('选择 Environment')).getByRole('button', { name: /^remote/ }))
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
    renderBoundPane()
    const composer = await screen.findByLabelText('给 AI 发送消息')

    await user.click(composer)
    await user.keyboard('/environment{Enter}')
    expect(await screen.findByLabelText('选择 Environment')).toBeInTheDocument()
    // 仅显示 READY 的 environment；CONNECTING 状态的会按 status 被过滤掉。
    const envModal = screen.getByLabelText('选择 Environment')
    expect(within(envModal).getByRole('button', { name: /^remote/ })).toBeInTheDocument()
    expect(within(envModal).getByRole('button', { name: /^local/ })).toBeInTheDocument()
    expect(within(envModal).queryByRole('button', { name: /connecting/ })).not.toBeInTheDocument()
    await user.click(within(envModal).getByRole('button', { name: /^remote/ }))

    // 绑定面板仅更新 draft；发送之前 footer 仍反映 thread snapshot，
    // 暂时不会发起 harness 调用。
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
            environmentName: null,
          }),
        }),
      ),
    )
    renderBoundPane()
    const composer = await screen.findByLabelText('给 AI 发送消息')

    // 在 draft 中编辑 agent（SET_AGENT）+ environment（SET_ENVIRONMENT）。
    await user.click(composer)
    await user.keyboard('/agent{Enter}')
    await user.click(await screen.findByRole('button', { name: /coder/ }))
    await user.click(screen.getByLabelText('给 AI 发送消息'))
    await user.keyboard('/environment{Enter}')
    await user.click(within(await screen.findByLabelText('选择 Environment')).getByRole('button', { name: /^remote/ }))

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
    expect(message.content).toBe('hello world')
    // 严格 wire：USER_MESSAGE 绝不携带 role。
    expect(message).not.toHaveProperty('role')
    expect(message).toHaveProperty('clientCommandId')
    const setAgent = batchArg.commands.find((command) => command.type === 'SET_AGENT')!
    expect(setAgent.agentName).toBe('coder')
    const setEnv = batchArg.commands.find((command) => command.type === 'SET_ENVIRONMENT')!
    expect(setEnv.environmentName).toBe('remote')
    expect(harnessService.updateThreadHead).not.toHaveBeenCalled()
  })

  it('shows /thread as a chat-scoped picker and only rebinds the pane', async () => {
    const user = userEvent.setup()
    const { onThreadChange } = renderBoundPane()
    const composer = await screen.findByLabelText('给 AI 发送消息')

    await user.click(composer)
    await user.keyboard('/thread{Enter}')
    expect(await screen.findByText('选择 Thread')).toBeInTheDocument()
    await waitFor(() => expect(chatService.listChatThreads).toHaveBeenCalledWith('chat-1'))

    await user.click(await screen.findByRole('button', { name: /t2/ }))

    expect(onThreadChange).toHaveBeenCalledWith('t2')
    expect(chatService.associateThread).not.toHaveBeenCalled()
    expect(harnessService.updateThreadHead).not.toHaveBeenCalled()
    expect(harnessService.enqueueCommands).not.toHaveBeenCalled()
  })

  it('treats /session as a disabled global command with no-op behavior', async () => {
    const user = userEvent.setup()
    renderBoundPane()
    const composer = await screen.findByLabelText('给 AI 发送消息')

    await user.click(composer)
    // 输入 /session 会打开 slash palette，展示被禁用的 session command
    //（以及描述中也包含 "Session" 的 /new command）。
    await user.keyboard('/session')
    const options = await screen.findAllByRole('option')
    const sessionOption = options.find((option) => {
      const text = option.textContent ?? ''
      return text.startsWith('session') && option.getAttribute('aria-disabled') === 'true'
    })
    expect(sessionOption).toBeDefined()
    // 在禁用的 command 上按 Enter 是 no-op：/session 不会打开 Thread picker
    //（只有 /thread 会触发）。
    await user.keyboard('{Enter}')
    expect(screen.queryByRole('dialog', { name: /选择 Thread/ })).not.toBeInTheDocument()
    expect(chatService.listChatThreads).not.toHaveBeenCalled()
    expect(harnessService.updateThreadHead).not.toHaveBeenCalled()
    expect(harnessService.enqueueCommands).not.toHaveBeenCalled()
  })

  it('rebinds via /tree PUT /head with the snapshot revision as the CAS cursor', async () => {
    const user = userEvent.setup()
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(
      snapshot(thread({ revision: '3' }), { entries: sessionEntries() }),
    )
    renderBoundPane()
    const composer = await screen.findByLabelText('给 AI 发送消息')

    await user.click(composer)
    await user.keyboard('/tree{Enter}')
    expect(await screen.findByRole('dialog', { name: '历史分支' })).toBeInTheDocument()

    await user.click(await screen.findByRole('button', { name: /用户 · s1 prompt/ }))
    await user.click(screen.getByRole('button', { name: '从这里继续当前 Thread' }))

    await waitFor(() =>
      expect(harnessService.updateThreadHead).toHaveBeenCalledWith('t1', {
        targetEntryId: 's1-root',
        expectedRevision: '3',
      }),
    )
  })

  it('requires window.confirm when the draft is dirty and aborts relocation when cancelled', async () => {
    const user = userEvent.setup()
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(false)
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(
      snapshot(thread({ revision: '0' }), { entries: sessionEntries() }),
    )
    renderBoundPane()
    const composer = await screen.findByLabelText('给 AI 发送消息')

    // 通过编辑 agent 让 draft 变脏。
    await user.click(composer)
    await user.keyboard('/agent{Enter}')
    await user.click(await screen.findByRole('button', { name: /coder/ }))

    await user.click(screen.getByLabelText('给 AI 发送消息'))
    await user.keyboard('/tree{Enter}')
    await user.click(await screen.findByRole('button', { name: /用户 · s1 prompt/ }))
    await user.click(screen.getByRole('button', { name: '从这里继续当前 Thread' }))

    expect(confirmSpy).toHaveBeenCalled()
    expect(harnessService.updateThreadHead).not.toHaveBeenCalled()

    confirmSpy.mockReturnValue(true)
    await user.click(screen.getByRole('button', { name: '从这里继续当前 Thread' }))
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
              commandId: 'c-pending',
              threadId: 't1',
              sequence: '1',
              type: 'USER_MESSAGE',
              state: 'QUEUED',
              clientCommandId: 'cid-pending',
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
    expect(screen.queryByRole('dialog', { name: '历史分支' })).not.toBeInTheDocument()
    expect(harnessService.updateThreadHead).not.toHaveBeenCalled()
  })


  it('adopts the selected Agent name + activeTools while freezing model/thinking/environment/yolo', async () => {
    const user = userEvent.setup()
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(
      snapshot(
        thread({
          branchSettings: branchSettings({
            agentName: 'assistant',
            environmentName: null,
            thinkingLevel: 'high',
          }),
        }),
      ),
    )
    renderBoundPane()
    const composer = await screen.findByLabelText('给 AI 发送消息')

    await user.click(composer)
    await user.keyboard('/agent{Enter}')
    await user.click(await screen.findByRole('button', { name: /coder/ }))

    // Footer 标签立即跟随面板 draft（不需要 service 往返）。
    expect(await screen.findByRole('button', { name: /agent:coder/ })).toBeInTheDocument()
    expect(screen.getByText(/minimax\/MiniMax · default/)).toBeInTheDocument()

    await user.type(composer, 'run')
    await user.click(screen.getByRole('button', { name: '发送消息' }))
    await waitFor(() => expect(harnessService.enqueueCommands).toHaveBeenCalledTimes(1))
    const batchArg = vi.mocked(harnessService.enqueueCommands).mock.calls[0]![1]
    const setAgent = batchArg.commands.find((command) => command.type === 'SET_AGENT')!
    expect(setAgent.agentName).toBe('coder')
    const setTools = batchArg.commands.find((command) => command.type === 'SET_ACTIVE_TOOLS')!
    expect(setTools.activeTools).toEqual(['web-search'])
    // Freeze 规则：仅 agent 变更不会重新发送 model/thinking/environment/yolo。
    expect(batchArg.commands.find((command) => command.type === 'SET_MODEL')).toBeUndefined()
    expect(batchArg.commands.find((command) => command.type === 'SET_THINKING_LEVEL')).toBeUndefined()
    expect(batchArg.commands.find((command) => command.type === 'SET_ENVIRONMENT')).toBeUndefined()
    expect(batchArg.commands.find((command) => command.type === 'SET_YOLO')).toBeUndefined()
  })

  it('blocks /thread while queued commands are pending and never opens the picker', async () => {
    const user = userEvent.setup()
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(
      snapshot(thread({}), {
        queuedCommands: [
          {
            commandId: 'c-pending',
            threadId: 't1',
            sequence: '1',
            type: 'USER_MESSAGE',
            state: 'QUEUED',
            clientCommandId: 'cid-pending',
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
    expect(screen.queryByRole('dialog', { name: /选择 Thread/ })).not.toBeInTheDocument()
    expect(chatService.listChatThreads).not.toHaveBeenCalled()
    expect(onThreadChange).not.toHaveBeenCalled()
  })

  it('blocks /new while queued commands are pending', async () => {
    const user = userEvent.setup()
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(
      snapshot(thread({}), {
        queuedCommands: [
          {
            commandId: 'c-pending',
            threadId: 't1',
            sequence: '1',
            type: 'USER_MESSAGE',
            state: 'QUEUED',
            clientCommandId: 'cid-pending',
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
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(false)
    const { onThreadChange } = renderBoundPane()
    const composer = await screen.findByLabelText('给 AI 发送消息')

    await user.click(composer)
    await user.keyboard('/agent{Enter}')
    await user.click(await screen.findByRole('button', { name: /coder/ }))

    await user.click(screen.getByLabelText('给 AI 发送消息'))
    await user.keyboard('/new{Enter}')
    expect(confirmSpy).toHaveBeenCalled()
    expect(onThreadChange).not.toHaveBeenCalled()

    // 确认后会丢弃 draft 并打开空面板。
    confirmSpy.mockReturnValue(true)
    await user.keyboard('/new{Enter}')
    expect(onThreadChange).toHaveBeenCalledWith(null)
    expect(harnessService.enqueueCommands).not.toHaveBeenCalled()
  })

  it('selecting the currently bound Thread from /thread needs no confirmation', async () => {
    const user = userEvent.setup()
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(false)
    const { onThreadChange } = renderBoundPane()
    const composer = await screen.findByLabelText('给 AI 发送消息')

    await user.click(composer)
    await user.keyboard('/agent{Enter}')
    await user.click(await screen.findByRole('button', { name: /coder/ }))

    await user.click(screen.getByLabelText('给 AI 发送消息'))
    await user.keyboard('/thread{Enter}')
    await user.click(await screen.findByRole('button', { name: /t1/ }))

    expect(confirmSpy).not.toHaveBeenCalled()
    expect(onThreadChange).not.toHaveBeenCalled()
    expect(harnessService.updateThreadHead).not.toHaveBeenCalled()
  })


  it('treats non-empty composer text as pane-dirty for /tree relocation', async () => {
    const user = userEvent.setup()
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(false)
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(
      snapshot(thread({ revision: '0' }), { entries: sessionEntries() }),
    )
    renderBoundPane()
    const composer = await screen.findByLabelText('给 AI 发送消息')

    // 先打开历史面板，再在 composer 中输入：历史面板是普通面板（不是 modal），
    // 所以未发送的 composer 文本可以与 relocation 并存。
    await user.click(composer)
    await user.keyboard('/tree{Enter}')
    expect(await screen.findByRole('dialog', { name: '历史分支' })).toBeInTheDocument()
    await user.click(composer)
    await user.type(composer, 'unsent text')
    await user.click(await screen.findByRole('button', { name: /用户 · s1 prompt/ }))
    await user.click(screen.getByRole('button', { name: '从这里继续当前 Thread' }))

    expect(confirmSpy).toHaveBeenCalled()
    expect(harnessService.updateThreadHead).not.toHaveBeenCalled()

    confirmSpy.mockReturnValue(true)
    await user.click(screen.getByRole('button', { name: '从这里继续当前 Thread' }))
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
    expect(screen.queryByRole('dialog', { name: /选择 Thread/ })).not.toBeInTheDocument()
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
    expect(screen.queryByRole('dialog', { name: '历史分支' })).not.toBeInTheDocument()
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
    expect(await screen.findByRole('dialog', { name: '历史分支' })).toBeInTheDocument()

    // USER entry 会将 head 回退到其父节点，并恢复其可编辑文本。
    await user.click(await screen.findByRole('button', { name: /用户 · s1 prompt/ }))
    await user.click(screen.getByRole('button', { name: '从这里继续当前 Thread' }))

    await waitFor(() =>
      expect(harnessService.updateThreadHead).toHaveBeenCalledWith('t1', {
        targetEntryId: 's1-root',
        expectedRevision: '3',
      }),
    )
    expect(await screen.findByDisplayValue('s1 prompt')).toBeInTheDocument()
    expect(screen.queryByRole('dialog', { name: '历史分支' })).not.toBeInTheDocument()
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
    expect(screen.getByLabelText('给 AI 发送消息')).toBeDisabled()
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
    expect(await screen.findByDisplayValue('will collide')).toBeInTheDocument()
  })
})
