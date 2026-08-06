import { useState } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ChatWorkspacePane } from '@/features/ai/chat/ChatWorkspacePane'
import { BLANK_PANE_COMMANDS } from '@/features/ai/chat/chat-workspace-pane/commands'
import { agentService } from '@/shared/api/agent-service'
import { ApiError } from '@/shared/api/client'
import { chatService } from '@/shared/api/chat-service'
import { harnessService } from '@/shared/api/harness-service'
import type {
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
      environmentId: null,
      agentName: 'assistant',
      model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'default' },
      thinkingLevel: 'off',
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
  agentName?: string | null
  yoloEnabled?: boolean
  initialThreadId?: string | null
  title?: string | null
  agents?: Array<typeof assistantAgent>
}) {
  const onThreadChange = overrides?.onThreadChange ?? vi.fn()
  const onAgentChange = overrides?.onAgentChange ?? vi.fn(async () => undefined)
  const onYoloChange = overrides?.onYoloChange ?? vi.fn(async () => undefined)
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  // Stateful wrapper: pane state advances on onThreadChange so the pane can transition
  // blank → bound in tests (mirrors the ChatWorkspacePage saveChatPaneState behavior).
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
          yoloEnabled: overrides?.yoloEnabled ?? false,
          version: '1',
          createTime: null,
          updateTime: null,
        }}
        agents={overrides?.agents ?? [assistantAgent]}
        environments={[
          { id: 'env-local', name: 'local', status: 'READY', lastSeen: null, tools: [], skills: [] },
          {
            id: 'env-connecting',
            name: 'connecting',
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
      />
    )
  }
  render(
    <QueryClientProvider client={queryClient}>
      <Harness />
    </QueryClientProvider>,
  )
  return { onThreadChange, onAgentChange, onYoloChange }
}

describe('BlankComposerPane /thread and agent error handling', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(agentService.listAgents).mockResolvedValue(page([assistantAgent]))
    vi.mocked(agentService.listModels).mockResolvedValue(page([modelEntry()]))
    vi.mocked(agentService.listProviders).mockResolvedValue(page([]))
    vi.mocked(harnessService.createThreadRealtimeStream).mockReturnValue(
      new FakeEventSource() as unknown as EventSource,
    )
    vi.mocked(chatService.listChatThreads).mockResolvedValue([
      thread({ threadId: 't-idle' }),
      thread({ threadId: 't-running', status: 'RUNNING', processing: true }),
    ])
    vi.mocked(harnessService.enqueueCommands).mockResolvedValue([] as HarnessThreadCommandDTO[])
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('exposes full command table on blank panes with unsupported entries disabled', () => {
    expect(BLANK_PANE_COMMANDS.map((command) => command.id)).toEqual([
      'session',
      'thread',
      'agent',
      'environment',
      'yolo',
      'tree',
      'stop',
      'new',
    ])
    // /thread only switches the pane, so it works before any Thread exists; /session and /tree
    // rebind the *current* Thread and therefore stay disabled on a blank pane.
    expect(BLANK_PANE_COMMANDS.filter((command) => !command.disabled).map((command) => command.id)).toEqual([
      'thread',
      'agent',
      'environment',
      'yolo',
    ])
    expect(BLANK_PANE_COMMANDS.find((command) => command.id === 'session')?.disabled).toBe(true)
    expect(BLANK_PANE_COMMANDS.find((command) => command.id === 'tree')?.disabled).toBe(true)
    expect(BLANK_PANE_COMMANDS.find((command) => command.id === 'new')?.disabled).toBe(true)
  })

  it('keeps Environment selection as a local blank-pane draft', async () => {
    const user = userEvent.setup()
    renderBlankPane()
    const composer = await screen.findByLabelText('给 AI 发送消息')

    await user.click(composer)
    await user.keyboard('/environment{Enter}')
    const envModal = await screen.findByLabelText('选择 Environment')
    expect(within(envModal).getByRole('button', { name: /^local/ })).toBeInTheDocument()
    expect(within(envModal).queryByRole('button', { name: /connecting/ })).not.toBeInTheDocument()
    await user.click(within(envModal).getByRole('button', { name: /^local/ }))

    // The blank pane footer reflects the draft immediately.
    expect(await screen.findByRole('button', { name: /\u73af\u5883[\uff1a:]\s*local/ })).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: /\u73af\u5883[\uff1a:]\s*local/ }))
    const envModalAgain = await screen.findByLabelText('选择 Environment')
    await user.click(within(envModalAgain).getByRole('button', { name: /\uff08\u65e0\uff09/ }))
    expect(await screen.findByRole('button', { name: /\u73af\u5883[\uff1a:]\uff08\u65e0\uff09/ })).toBeInTheDocument()
  })

  it('materializes the blank pane frozen draft from the catalog (footer shows agent/model)', async () => {
    renderBlankPane()

    // Footer should reflect the catalog-derived frozen draft: agent=assistant, model from
    // minimax/MiniMax, default variant. Catalog query resolves immediately.
    expect(await screen.findByRole('button', { name: /agent:assistant/ })).toBeInTheDocument()
    expect(screen.getByText(/minimax\/MiniMax · default/)).toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: /\u73af\u5883[\uff1a:]\uff08\u65e0\uff09/ }),
    ).toBeInTheDocument()
    expect(agentService.listModels).toHaveBeenCalled()
    // No draft edits triggered any service call.
    expect(chatService.createChatThread).not.toHaveBeenCalled()
    expect(harnessService.enqueueCommands).not.toHaveBeenCalled()
  })

  it('performs atomic createChatThread + enqueueCommands first send', async () => {
    const user = userEvent.setup()
    const createdThread = thread({ threadId: 't-created', environmentId: null })
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
          environmentId: null,
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
    expect(batchArg.commands[0]?.content).toBe('first message')
    // Strict wire: USER_MESSAGE never carries role.
    expect(batchArg.commands[0]).not.toHaveProperty('role')
    expect(batchArg.commands[0]?.clientCommandId).toBeTruthy()
    // First send has NO environment in the command batch: environment was baked into the
    // Thread creation as branchSettings.environmentId.
    expect(batchArg.commands[0]).not.toHaveProperty('environmentId')

    // The blank-pane body disappears once the pane is bound to the created Thread.
    await waitFor(() =>
      expect(screen.queryByRole('heading', { name: /新对话/ })).not.toBeInTheDocument(),
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
        // First send fails → the bound pane should retry the same USER_MESSAGE.
        throw new ApiError('temporary failure', 503)
      }
      return [] as HarnessThreadCommandDTO[]
    })
    // Snapshot for the recovered bound pane (must match the created Thread's revision/head).
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(createdSnapshot)

    const onThreadChange = vi.fn()
    renderBlankPane({ onThreadChange })
    const composer = await screen.findByLabelText('给 AI 发送消息')

    await user.click(composer)
    await user.type(composer, 'retry me')
    await user.click(screen.getByRole('button', { name: '发送消息' }))

    // First send: create + enqueue (fails). Draft is restored so the user can retry.
    await waitFor(() => expect(chatService.createChatThread).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(harnessService.enqueueCommands).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(onThreadChange).toHaveBeenCalledWith('t-replay'))
    expect(await screen.findByDisplayValue('retry me')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '发送消息' }))
    await waitFor(() => expect(harnessService.enqueueCommands).toHaveBeenCalledTimes(2))
    expect(chatService.createChatThread).toHaveBeenCalledTimes(1)

    const calls = vi.mocked(harnessService.enqueueCommands).mock.calls
    const firstBatch = calls[0]?.[1]
    const secondBatch = calls[1]?.[1]
    expect(secondBatch).toBeDefined()
    // Stable retry identity: the retry reuses the EXACT first-send batch byte-for-byte
    // (same command IDs/payload/order and original expected cursors). The created Thread is
    // the effective base, so the semantic identity matches and the message command id is
    // preserved across the blank->bound recovery.
    expect(secondBatch).toEqual(firstBatch)
  })

  it('binds on a known 409 without replaying the stale batch and rebuilds fresh cursors', async () => {
    const user = userEvent.setup()
    const createdThread = thread({ threadId: 't-conflict' })
    vi.mocked(chatService.createChatThread).mockResolvedValue(snapshotOf(createdThread))
    // Known 409: the server explicitly rejected the stale batch (cursors moved).
    vi.mocked(harnessService.enqueueCommands).mockRejectedValue(
      new ApiError('stale cursors', 409),
    )
    // The recovered bound pane refetches and sees the ADVANCED snapshot (the server moved on).
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

    // First send: create + enqueue (409). The pane still binds the created Thread and
    // restores the composer text, so the user can retry.
    await waitFor(() => expect(chatService.createChatThread).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(harnessService.enqueueCommands).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(onThreadChange).toHaveBeenCalledWith('t-conflict'))
    expect(await screen.findByDisplayValue('fresh retry')).toBeInTheDocument()

    // Retry while the restored draft is the same text: NO exact replay is armed for a known
    // 409 — the next submit must rebuild against the refreshed snapshot with fresh cursors
    // and a fresh command id.
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

    const items = await screen.findAllByRole('button', { name: /^t-/ })
    // recent mode keeps the newer IDLE Thread first.
    expect(items[0]).toHaveTextContent('t-idle')

    await user.click(screen.getByRole('button', { name: /t-running/ }))
    expect(onThreadChange).toHaveBeenCalledWith('t-running')
    // No association API on a chat-scoped picker.
    expect(chatService.associateThread).not.toHaveBeenCalled()
    expect(events).toEqual(['bind'])
    // Selecting a Thread never runs the first-send path.
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
    // Session rejects blank titles; Chat title is nullable and null stays null (no ?? '').
    expect(payload.title).toBeNull()
    expect(payload).toEqual(
      expect.objectContaining({
        title: null,
        yoloEnabled: false,
        branchSettings: expect.objectContaining({
          agentName: 'assistant',
          environmentId: null,
          model: expect.objectContaining({
            providerName: 'minimax',
            modelName: 'MiniMax',
            variant: 'default',
          }),
          thinkingLevel: 'off',
          activeTools: [],
        }),
      }),
    )
  })

  it('keeps chat.yoloEnabled when a stale Chat agent triggers full materialization', async () => {
    const user = userEvent.setup()
    const createdThread = thread({ threadId: 't-materialized-yolo' })
    vi.mocked(chatService.createChatThread).mockResolvedValue(snapshotOf(createdThread))
    const tooledAgent = {
      ...assistantAgent,
      config: { tools: ['web-search'], skills: [] },
    }
    // Stale Chat agent (no catalog match) + the Chat default yolo=true: full materialization
    // must preserve the user's yolo preference instead of silently falling back to false.
    renderBlankPane({ agentName: 'ghost', yoloEnabled: true, agents: [tooledAgent] })
    const composer = await screen.findByLabelText('给 AI 发送消息')

    await user.click(composer)
    await user.type(composer, 'hi')
    await user.click(screen.getByRole('button', { name: '发送消息' }))
    await user.click(await screen.findByRole('button', { name: /assistant/ }))

    await waitFor(() => expect(chatService.createChatThread).toHaveBeenCalledTimes(1))
    const payload = vi.mocked(chatService.createChatThread).mock.calls[0]![1]
    expect(payload.yoloEnabled).toBe(true)
    expect(payload.branchSettings.agentName).toBe('assistant')
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

    // The Chat agent cannot be resolved from the catalog: the draft stays unfrozen and the
    // composer surfaces the explicit agent-missing state.
    expect(await screen.findByText(/Agent 已删除\/缺失/)).toBeInTheDocument()

    // Submitting opens the agent picker INSTEAD of creating a Thread with an empty
    // provider/model/variant that the strict mapper would reject.
    await user.click(composer)
    await user.type(composer, 'hi')
    await user.click(screen.getByRole('button', { name: '发送消息' }))
    expect(await screen.findByText('选择 Agent')).toBeInTheDocument()
    expect(chatService.createChatThread).not.toHaveBeenCalled()

    // Selecting the Agent fully materializes the draft (name + activeTools + model) and the
    // pending message is sent immediately.
    await user.click(screen.getByRole('button', { name: /assistant/ }))
    await waitFor(() => expect(chatService.createChatThread).toHaveBeenCalledTimes(1))
    const payload = vi.mocked(chatService.createChatThread).mock.calls[0]![1]
    expect(payload.branchSettings).toEqual({
      environmentId: null,
      agentName: 'assistant',
      model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'default' },
      thinkingLevel: 'off',
      activeTools: ['web-search'],
    })
    expect(payload.yoloEnabled).toBe(false)
    const batchArg = vi.mocked(harnessService.enqueueCommands).mock.calls[0]![1]
    expect(batchArg.commands).toHaveLength(1)
    expect(batchArg.commands[0]?.content).toBe('hi')
    expect(batchArg.commands[0]).not.toHaveProperty('role')
  })


  it('establishes the pane-local baseline on first picker materialization so later edits are dirty', async () => {
    const user = userEvent.setup()
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(false)
    vi.mocked(chatService.listChatThreads).mockResolvedValue([
      thread({ threadId: 't-idle' }),
      thread({ threadId: 't-other' }),
    ])
    const tooledAgent = {
      ...assistantAgent,
      config: { tools: ['web-search'], skills: [] },
    }
    const onThreadChange = vi.fn()
    // Stale Chat agent ('ghost' not in the catalog): the draft cannot be materialized from the
    // Chat default, so the first materialization happens through the picker.
    renderBlankPane({ agentName: 'ghost', agents: [tooledAgent], onThreadChange })
    const composer = await screen.findByLabelText('给 AI 发送消息')

    // First picker materialization: selecting the Agent must establish the immutable
    // pane-local baseline (previously initialFrozenDraft stayed null forever, so later edits
    // were never judged dirty).
    await user.click(composer)
    await user.keyboard('/agent{Enter}')
    await user.click(await screen.findByRole('button', { name: /assistant/ }))

    // A later pane-local edit (environment) must count as dirty relative to that baseline.
    await user.click(screen.getByLabelText('给 AI 发送消息'))
    await user.keyboard('/environment{Enter}')
    const envModal = await screen.findByLabelText('选择 Environment')
    await user.click(within(envModal).getByRole('button', { name: /^local/ }))

    await user.click(screen.getByLabelText('给 AI 发送消息'))
    await user.keyboard('/thread{Enter}')
    const picker = await screen.findByLabelText('选择 Thread')
    const items = await within(picker).findAllByRole('button', { name: /^t-/ })
    const otherItem = items.find((item) => item.textContent?.includes('t-other'))
    expect(otherItem).toBeDefined()
    await user.click(otherItem!)

    expect(confirmSpy).toHaveBeenCalled()
    expect(onThreadChange).not.toHaveBeenCalled()
  })

  it('confirms discarding a modified pane-local frozen draft before switching Threads from /thread', async () => {
    const user = userEvent.setup()
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(false)
    vi.mocked(chatService.listChatThreads).mockResolvedValue([
      thread({ threadId: 't-idle' }),
      thread({ threadId: 't-other' }),
    ])
    const onThreadChange = vi.fn()
    renderBlankPane({ onThreadChange })
    const composer = await screen.findByLabelText('给 AI 发送消息')

    // Pane-local edit of the frozen branch draft (environment selection) makes the pane
    // dirty relative to its initial materialized value.
    await user.click(composer)
    await user.keyboard('/environment{Enter}')
    expect(await screen.findByLabelText('选择 Environment')).toBeInTheDocument()
    await user.click(within(screen.getByLabelText('选择 Environment')).getByRole('button', { name: /^local/ }))

    await user.click(screen.getByLabelText('给 AI 发送消息'))
    await user.keyboard('/thread{Enter}')
    const picker = await screen.findByLabelText('选择 Thread')
    const items = await within(picker).findAllByRole('button', { name: /^t-/ })
    const otherItem = items.find((item) => item.textContent?.includes('t-other'))
    expect(otherItem).toBeDefined()
    await user.click(otherItem!)

    expect(confirmSpy).toHaveBeenCalled()
    expect(onThreadChange).not.toHaveBeenCalled()

    confirmSpy.mockReturnValue(true)
    // Re-open the picker (the first rejection closed it) and select again.
    await user.click(screen.getByLabelText('给 AI 发送消息'))
    await user.keyboard('/thread{Enter}')
    const pickerAgain = await screen.findByLabelText('选择 Thread')
    const itemsAgain = await within(pickerAgain).findAllByRole('button', { name: /^t-/ })
    const otherAgain = itemsAgain.find((item) => item.textContent?.includes('t-other'))
    expect(otherAgain).toBeDefined()
    await user.click(otherAgain!)
    await waitFor(() => expect(onThreadChange).toHaveBeenCalledWith('t-other'))
    expect(chatService.createChatThread).not.toHaveBeenCalled()
  })

  it('keeps an unmodified blank pane draft confirmation-free for /thread', async () => {
    const user = userEvent.setup()
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(false)
    vi.mocked(chatService.listChatThreads).mockResolvedValue([
      thread({ threadId: 't-idle' }),
      thread({ threadId: 't-other' }),
    ])
    const onThreadChange = vi.fn()
    renderBlankPane({ onThreadChange })
    const composer = await screen.findByLabelText('给 AI 发送消息')

    // The pane-local draft equals its initial materialized value: no confirmation needed.
    await user.click(composer)
    await user.keyboard('/thread{Enter}')
    expect(await screen.findByText('选择 Thread')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /t-other/ }))

    expect(confirmSpy).not.toHaveBeenCalled()
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
    // Make createChatThread reject too so the blank pane stays mounted and the action
    // error becomes visible in the blank-pane footer.
    vi.mocked(chatService.createChatThread).mockRejectedValue(
      new Error('agent update failed upstream'),
    )
    renderBlankPane({ agentName: null, onAgentChange })

    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.type(composer, 'hello need agent')
    await user.click(screen.getByRole('button', { name: '发送消息' }))
    expect(await screen.findByText('选择 Agent')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /assistant/ }))

    await waitFor(() => expect(onAgentChange).toHaveBeenCalledWith('assistant'))
    // Allow microtasks to drain so any unhandled rejection would be observed.
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
    // The pane stays blank so the user can retry after fixing the Agent.
    expect(screen.getByRole('heading', { name: /新对话/ })).toBeInTheDocument()
    expect(await screen.findByDisplayValue('first message')).toBeInTheDocument()
  })
})
