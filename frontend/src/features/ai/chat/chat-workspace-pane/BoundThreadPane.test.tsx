import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { BoundThreadPane } from '@/features/ai/chat/chat-workspace-pane/BoundThreadPane'
import { BrowserPreferencesProvider } from '@/features/settings/browser-preferences'
import { agentService } from '@/shared/api/agent-service'
import { chatService } from '@/shared/api/chat-service'
import { environmentService } from '@/shared/api/environment-service'
import { harnessService } from '@/shared/api/harness-service'
import type {
  HarnessBranchSettingsDTO,
  HarnessModelSelectionDTO,
  HarnessThreadCommandDTO,
  HarnessThreadDTO,
  HarnessThreadSnapshotDTO,
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
    listChatThreads: vi.fn(),
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
    listDirectories: vi.fn(),
  },
}))

const page = <T,>(results: T[]) => ({
  pageNumber: 1,
  pageSize: 50,
  totalCount: results.length,
  results,
})

function modelSelection(
  overrides: Partial<HarnessModelSelectionDTO> = {},
): HarnessModelSelectionDTO {
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

function queuedSettingCommand(
  sequence: string,
  type: string,
  payload: Record<string, unknown>,
): HarnessThreadCommandDTO {
  return {
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
  }
}

const agents = [
  {
    name: 'assistant',
    description: null,
    systemPrompt: null,
    model: 'minimax/MiniMax',
    variant: 'default',
    config: { tools: [], skills: [], subagents: [] },
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
    config: { tools: ['web-search'], skills: [], subagents: [] },
    version: '0',
    createTime: null,
    updateTime: null,
  },
]

const modelEntry = {
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

function renderBoundPane(threadId = 't1') {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  const onThreadChange = vi.fn()
  const element = (boundThreadId: string) => (
    <BrowserPreferencesProvider>
      <QueryClientProvider client={queryClient}>
        <BoundThreadPane
          chatId="chat-1"
          environments={[
            { name: 'local', ready: true, status: 'READY', lastSeen: null, tools: [], skills: [] },
            { name: 'remote', ready: true, status: 'READY', lastSeen: null, tools: [], skills: [] },
          ]}
          paneId="pane-1"
          threadId={boundThreadId}
          focused
          threadSort="recent"
          onFocus={() => undefined}
          onThreadChange={onThreadChange}
          onThreadSortChange={() => undefined}
        />
      </QueryClientProvider>
    </BrowserPreferencesProvider>
  )
  const view = render(element(threadId))
  return {
    onThreadChange,
    rerender: (nextThreadId: string) => {
      view.rerender(element(nextThreadId))
    },
  }
}

describe('BoundThreadPane scene', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(agentService.listAgents).mockResolvedValue(page(agents))
    vi.mocked(agentService.listModels).mockResolvedValue(page([modelEntry]))
    vi.mocked(agentService.listProviders).mockResolvedValue(page([]))
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(snapshot(thread({})))
    vi.mocked(harnessService.listThreadEntries).mockResolvedValue([])
    vi.mocked(harnessService.getSystemPromptPreview).mockResolvedValue({ text: '' })
    vi.mocked(harnessService.enqueueCommands).mockResolvedValue([] as HarnessThreadCommandDTO[])
    vi.mocked(chatService.listChatThreads).mockResolvedValue([])
    vi.mocked(environmentService.listDirectories).mockResolvedValue({
      path: '.',
      displayPath: '.',
      parentPath: '.',
      truncated: false,
      gitBranch: null,
      entries: [],
    })
  })

  it('sends only USER_MESSAGE while queued SET_* are projected (no reversal diff)', async () => {
    const user = userEvent.setup()
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(
      snapshot(thread({}), {
        queuedCommands: [queuedSettingCommand('1', 'SET_AGENT', { agentName: 'coder' })],
      }),
    )
    renderBoundPane()
    const composer = await screen.findByLabelText('给 AI 发送消息')

    await user.click(composer)
    await user.type(composer, 'hello world')
    await user.click(screen.getByRole('button', { name: '发送消息' }))

    await waitFor(() => expect(harnessService.enqueueCommands).toHaveBeenCalledTimes(1))
    const [threadIdArg, batch] = vi.mocked(harnessService.enqueueCommands).mock.calls[0]!
    expect(threadIdArg).toBe('t1')
    expect(batch.expectedHeadEntryId).toBe('e-assistant')
    expect(batch.expectedNextCommandSequence).toBe('1')
    // queued SET_AGENT 已投影进 effectiveBase，draft 与之相等：绝不回发 reversal。
    expect(batch.commands.map((command) => command.type)).toEqual(['USER_MESSAGE'])
    expect(batch.commands[0]).toMatchObject({
      contents: [{ type: 'TEXT', text: 'hello world' }],
    })
  })

  it('wires the agent picker into the shared draft editor and sends a minimal SET_* diff batch', async () => {
    const user = userEvent.setup()
    renderBoundPane()
    const composer = await screen.findByLabelText('给 AI 发送消息')

    await user.click(composer)
    await user.keyboard('/agent{Enter}')
    await user.click(await screen.findByRole('option', { name: /coder/ }))

    await user.click(screen.getByLabelText('给 AI 发送消息'))
    await user.type(composer, 'hello world')
    await user.click(screen.getByRole('button', { name: '发送消息' }))

    await waitFor(() => expect(harnessService.enqueueCommands).toHaveBeenCalledTimes(1))
    const [, batch] = vi.mocked(harnessService.enqueueCommands).mock.calls[0]!
    // 面板本地编辑：仅 agent + activeTools 变脏，按固定顺序生成最小 diff。
    expect(batch.commands.map((command) => command.type)).toEqual([
      'SET_AGENT',
      'SET_ACTIVE_TOOLS',
      'USER_MESSAGE',
    ])
    const setAgent = batch.commands.find((command) => command.type === 'SET_AGENT')!
    expect(setAgent).toMatchObject({ agentName: 'coder' })
    const setTools = batch.commands.find((command) => command.type === 'SET_ACTIVE_TOOLS')!
    expect(setTools).toMatchObject({ activeTools: ['web-search'] })
    const message = batch.commands[batch.commands.length - 1]!
    expect(message).toMatchObject({ contents: [{ type: 'TEXT', text: 'hello world' }] })
    expect(harnessService.updateThreadHead).not.toHaveBeenCalled()

    // 选中后 Agent picker 关闭，回到 conversation 主视图（共享 view 状态未被 draft 编辑破坏）。
    expect(screen.queryByLabelText('选择 Agent')).not.toBeInTheDocument()
  })

  it('disables sending after rebinding until the new snapshot arrives (no old draft leak)', async () => {
    const user = userEvent.setup()
    let resolveNewSnapshot: ((snapshot: HarnessThreadSnapshotDTO) => void) | null = null
    vi.mocked(harnessService.getThreadSnapshot).mockImplementation((threadId) =>
      threadId === 't1'
        ? Promise.resolve(snapshot(thread({})))
        : new Promise((resolve) => {
            resolveNewSnapshot = resolve
          }),
    )
    const { rerender } = renderBoundPane()
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.click(composer)
    await user.type(composer, 'hello')
    const sendButton = () => screen.getByRole('button', { name: '发送消息' })
    expect(sendButton()).toBeEnabled()

    // 重绑到 snapshot 未到达的新 Thread：composer 立即 fail-closed（effectiveBase null）。
    rerender('t2')
    await waitFor(() =>
      expect(screen.getByLabelText('给 AI 发送消息')).toHaveAttribute('aria-disabled', 'true'),
    )
    expect(sendButton()).toBeDisabled()
    await user.click(sendButton())
    expect(harnessService.enqueueCommands).not.toHaveBeenCalled()
    expect(harnessService.updateThreadHead).not.toHaveBeenCalled()

    // 新 snapshot 到达后 composer 恢复可用；旧 draft 不残留，需重新输入才能发送。
    await act(async () => {
      resolveNewSnapshot?.(snapshot(thread({ threadId: 't2', headEntryId: 'e-t2' })))
    })
    await waitFor(() =>
      expect(screen.getByLabelText('给 AI 发送消息')).toHaveAttribute('aria-disabled', 'false'),
    )
    await user.click(screen.getByLabelText('给 AI 发送消息'))
    await user.type(screen.getByLabelText('给 AI 发送消息'), 'for t2')
    await user.click(sendButton())

    await waitFor(() => expect(harnessService.enqueueCommands).toHaveBeenCalledTimes(1))
    const [threadIdArg, batch] = vi.mocked(harnessService.enqueueCommands).mock.calls[0]!
    expect(threadIdArg).toBe('t2')
    expect(batch.expectedHeadEntryId).toBe('e-t2')
    expect(batch.commands.map((command) => command.type)).toEqual(['USER_MESSAGE'])
  })
})
