import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useBoundBranchPanel } from '@/features/ai/runtime/useBoundBranchPanel'
import {
  buildBoundThreadTranscript,
} from '@/features/ai/runtime/useBoundThreadPanelViews'
import { createTextPart } from '@/features/ai/composer/composer-parts'
import type {
  DialogueMessage,
  ToolDialogueMessage,
} from '@/features/ai/runtime/thread-timeline-types'
import { agentService } from '@/shared/api/agent-service'
import { harnessService } from '@/shared/api/harness-service'
import type {
  HarnessBranchSettingsDTO,
  HarnessModelSelectionDTO,
  HarnessSessionEntryDTO,
  HarnessThreadCommandDTO,
  HarnessThreadDTO,
  HarnessThreadSnapshotDTO,
} from '@/shared/api/contracts/ai-runtime'

const THREAD_ID = '11111111-2222-4333-8444-555555555555'
const THREAD_ID_2 = 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee'

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
vi.mock('@/shared/api/harness-service', () => ({
  harnessService: {
    getThreadSnapshot: vi.fn(),
    getSystemPromptPreview: vi.fn(),
    enqueueCommands: vi.fn(),
    updateThreadHead: vi.fn(),
    setThreadYolo: vi.fn(),
    stopThread: vi.fn(),
    decideApproval: vi.fn(),
  },
}))

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

function threadFixture(
  threadId: string,
  overrides: Partial<HarnessThreadDTO> = {},
): HarnessThreadDTO {
  return {
    threadId,
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

function snapshotOf(
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
    threadId: THREAD_ID,
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
    config: { tools: ['web-search'], skills: ['skill-a'], subagents: [] },
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

function clientWrapper(client: QueryClient) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>
  }
}

function createClient() {
  return new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
}

async function send(result: { current: ReturnType<typeof useBoundBranchPanel> }) {
  await act(async () => {
    await result.current.controller.submitMessage([createTextPart('hello world')])
  })
}

describe('useBoundBranchPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(agentService.listAgents).mockResolvedValue({
      pageNumber: 1,
      pageSize: 50,
      totalCount: agents.length,
      results: agents,
    })
    vi.mocked(agentService.listModels).mockResolvedValue({
      pageNumber: 1,
      pageSize: 50,
      totalCount: 1,
      results: [modelEntry],
    })
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(
      snapshotOf(threadFixture(THREAD_ID)),
    )
    vi.mocked(harnessService.enqueueCommands).mockResolvedValue([] as HarnessThreadCommandDTO[])
    // 直接控制面默认回显请求值（同值 no-op 由服务端保证，这里仅回显）。
    vi.mocked(harnessService.setThreadYolo).mockImplementation((threadId, data) =>
      Promise.resolve(threadFixture(threadId, { yoloEnabled: data.yoloEnabled })),
    )
  })

  it('initializes the draft from the snapshot with queued SET_* projected, and submits without a reversal diff', async () => {
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(
      snapshotOf(threadFixture(THREAD_ID), {
        queuedCommands: [queuedSettingCommand('1', 'SET_AGENT', { agentName: 'coder' })],
      }),
    )
    const { result } = renderHook(() => useBoundBranchPanel({ threadId: THREAD_ID }), {
      wrapper: clientWrapper(createClient()),
    })

    await waitFor(() => expect(result.current.controller.thread?.threadId).toBe(THREAD_ID))
    // base 跟随快照事实；draft 投影 queued SET_AGENT（绝不重复携带在途 settings）。
    expect(result.current.branchState?.base.agentName).toBe('assistant')
    expect(result.current.draft?.agentName).toBe('coder')
    expect(result.current.effectiveBase?.agentName).toBe('coder')
    expect(result.current.dirty).toBe(false)

    await send(result)

    expect(harnessService.enqueueCommands).toHaveBeenCalledTimes(1)
    const [threadIdArg, batch] = vi.mocked(harnessService.enqueueCommands).mock.calls[0]!
    expect(threadIdArg).toBe(THREAD_ID)
    expect(batch.expectedHeadEntryId).toBe('e-assistant')
    expect(batch.expectedNextCommandSequence).toBe('1')
    // 投影后 effectiveBase === draft：最小 diff 只含 USER_MESSAGE，无 SET_AGENT reversal。
    expect(batch.commands.map((command) => command.type)).toEqual(['USER_MESSAGE'])
    expect(batch.commands[0]).toMatchObject({
      contents: [{ type: 'TEXT', text: 'hello world' }],
    })
  })

  it('applies draft-local selections with freeze rules and emits the fixed minimal diff order in one batch', async () => {
    const { result } = renderHook(() => useBoundBranchPanel({ threadId: THREAD_ID }), {
      wrapper: clientWrapper(createClient()),
    })

    await waitFor(() => expect(result.current.branchState).not.toBeNull())
    expect(result.current.dirty).toBe(false)

    act(() => {
      // freeze 规则：采用新 agent 的 name + activeTools（tools + skills 内部工具），
      // 冻结的 model/environment/yolo 保持快照值。
      expect(result.current.selectAgent('coder')).toBe(true)
      expect(result.current.selectAgent('missing')).toBe(false)
      result.current.selectEnvironment({ name: 'local', workspacePath: '.' })
      result.current.setYoloEnabled(true)
      result.current.selectModel({ providerName: 'openai', modelName: 'GPT-5', variant: 'v2' })
    })

    expect(result.current.draft).toEqual({
      environment: { name: 'local', workspacePath: '.' },
      agentName: 'coder',
      model: { providerName: 'openai', modelName: 'GPT-5', variant: 'v2' },
      activeTools: ['web-search', 'load_skill'],
      yoloEnabled: true,
    })
    // effectiveBase 仍是干净快照；draft 已变脏。
    expect(result.current.effectiveBase?.agentName).toBe('assistant')
    expect(result.current.dirty).toBe(true)

    await send(result)

    const [, batch] = vi.mocked(harnessService.enqueueCommands).mock.calls[0]!
    // buildBranchDiffCommands 的固定顺序：ENV/AGENT/MODEL/TOOLS + USER_MESSAGE；yolo 走直接控制面。
    expect(batch.commands.map((command) => command.type)).toEqual([
      'SET_ENVIRONMENT',
      'SET_AGENT',
      'SET_MODEL',
      'SET_ACTIVE_TOOLS',
      'USER_MESSAGE',
    ])
    const setAgent = batch.commands[1]!
    expect(setAgent).toMatchObject({ agentName: 'coder' })
    const setTools = batch.commands[3]!
    expect(setTools).toMatchObject({ activeTools: ['web-search', 'load_skill'] })
    // 直接控制面调用基于 snapshot revision 的精确 CAS，绝不生成 SET_YOLO command。
    expect(harnessService.setThreadYolo).toHaveBeenCalledWith(THREAD_ID, {
      expectedRevision: '0',
      yoloEnabled: true,
    })
  })

  it('optimistically updates yolo via the direct API, aligning base+draft on success without touching other unsent settings', async () => {
    const { result } = renderHook(() => useBoundBranchPanel({ threadId: THREAD_ID }), {
      wrapper: clientWrapper(createClient()),
    })

    await waitFor(() => expect(result.current.branchState).not.toBeNull())
    act(() => {
      result.current.selectAgent('coder')
      result.current.setYoloEnabled(true)
    })
    // 乐观：draft 立即变脏（yolo 目标值 + 未发送 agent），base 保持快照。
    expect(result.current.draft?.yoloEnabled).toBe(true)
    expect(result.current.draft?.agentName).toBe('coder')
    expect(result.current.branchState?.base.yoloEnabled).toBe(false)

    await act(async () => {
      await Promise.resolve()
    })
    // 成功：base + draft 的 yolo 对齐服务器权威值；未发送的 agent 编辑原样保留。
    expect(result.current.branchState?.base.yoloEnabled).toBe(true)
    expect(result.current.draft?.yoloEnabled).toBe(true)
    expect(result.current.draft?.agentName).toBe('coder')
    expect(result.current.yoloError).toBeNull()

    await send(result)
    const [, batch] = vi.mocked(harnessService.enqueueCommands).mock.calls[0]!
    // 未发送的 agent 编辑（name + activeTools）仍与消息一起提交；yolo 已对齐，绝不重复发送。
    expect(batch.commands.map((command) => command.type)).toEqual([
      'SET_AGENT',
      'SET_ACTIVE_TOOLS',
      'USER_MESSAGE',
    ])
  })

  it('reverts the optimistic yolo edit and surfaces yoloError when the direct API fails', async () => {
    vi.mocked(harnessService.setThreadYolo).mockRejectedValueOnce(
      new Error('STALE_REVISION: revision 2 does not match expected 0'),
    )
    const { result } = renderHook(() => useBoundBranchPanel({ threadId: THREAD_ID }), {
      wrapper: clientWrapper(createClient()),
    })

    await waitFor(() => expect(result.current.branchState).not.toBeNull())
    act(() => {
      result.current.setYoloEnabled(true)
    })
    expect(result.current.draft?.yoloEnabled).toBe(true)

    await act(async () => {
      await Promise.resolve()
    })
    // 失败：draft 回滚到 base 值，错误暴露给面板；base 零触碰。
    expect(result.current.draft?.yoloEnabled).toBe(false)
    expect(result.current.branchState?.base.yoloEnabled).toBe(false)
    expect(result.current.yoloError).toContain('STALE_REVISION')

    act(() => {
      result.current.dismissYoloError()
    })
    expect(result.current.yoloError).toBeNull()
  })

  it('uses the authoritative revision returned by /yolo for the next CAS (sequential second toggle)', async () => {
    vi.mocked(harnessService.setThreadYolo).mockImplementation((threadId, data) =>
      Promise.resolve(
        threadFixture(threadId, {
          yoloEnabled: data.yoloEnabled,
          revision: String(Number(data.expectedRevision) + 1),
        }),
      ),
    )
    const { result } = renderHook(() => useBoundBranchPanel({ threadId: THREAD_ID }), {
      wrapper: clientWrapper(createClient()),
    })

    await waitFor(() => expect(result.current.branchState).not.toBeNull())
    act(() => {
      result.current.setYoloEnabled(true)
    })
    await act(async () => {
      await Promise.resolve()
    })
    // 第一次写基于 snapshot revision 0；成功后采纳 /yolo 返回的权威 revision 1。
    expect(vi.mocked(harnessService.setThreadYolo).mock.calls[0]![1]).toEqual({
      expectedRevision: '0',
      yoloEnabled: true,
    })
    expect(result.current.branchState?.base.yoloEnabled).toBe(true)

    act(() => {
      result.current.setYoloEnabled(false)
    })
    await act(async () => {
      await Promise.resolve()
    })
    // 第二次写必须使用权威 revision 1，而不是滞后的 controller snapshot revision 0。
    expect(vi.mocked(harnessService.setThreadYolo).mock.calls[1]![1]).toEqual({
      expectedRevision: '1',
      yoloEnabled: false,
    })
    expect(result.current.draft?.yoloEnabled).toBe(false)
    expect(result.current.branchState?.base.yoloEnabled).toBe(false)
  })

  it('serializes rapid yolo toggles: the next request starts only after the prior resolves and uses its revision (latest wins)', async () => {
    let releaseFirst: (() => void) | null = null
    const calls: Array<{ expectedRevision: string; yoloEnabled: boolean }> = []
    vi.mocked(harnessService.setThreadYolo).mockImplementation((threadId, data) => {
      calls.push({ expectedRevision: data.expectedRevision, yoloEnabled: data.yoloEnabled })
      if (calls.length === 1) {
        return new Promise<HarnessThreadDTO>((resolve) => {
          releaseFirst = () =>
            resolve(threadFixture(threadId, { yoloEnabled: true, revision: '1' }))
        })
      }
      return Promise.resolve(threadFixture(threadId, { yoloEnabled: false, revision: '2' }))
    })
    const { result } = renderHook(() => useBoundBranchPanel({ threadId: THREAD_ID }), {
      wrapper: clientWrapper(createClient()),
    })

    await waitFor(() => expect(result.current.branchState).not.toBeNull())
    act(() => {
      result.current.setYoloEnabled(true)
      result.current.setYoloEnabled(false)
    })
    // 快速连点串行：第一个（true）请求返回前只有它在网；false 仅记录为最新意图。
    expect(calls).toEqual([{ expectedRevision: '0', yoloEnabled: true }])

    await act(async () => {
      releaseFirst?.()
    })
    // true 返回（权威 revision 1）后，最新意图 false 才被发送，且携带 revision 1。
    await waitFor(() => expect(calls.length).toBe(2))
    expect(calls[1]).toEqual({ expectedRevision: '1', yoloEnabled: false })
    await act(async () => {
      await Promise.resolve()
    })
    // latest wins：最终 draft 与 base 均为 false。
    expect(result.current.draft?.yoloEnabled).toBe(false)
    expect(result.current.branchState?.base.yoloEnabled).toBe(false)
  })

  it('fences late yolo responses after rebind: old-thread failure neither surfaces yoloError nor mutates the new draft', async () => {
    let rejectOld: ((error: Error) => void) | null = null
    vi.mocked(harnessService.setThreadYolo).mockImplementation((threadId, data) => {
      if (threadId === THREAD_ID) {
        return new Promise<HarnessThreadDTO>((_, reject) => {
          rejectOld = reject
        })
      }
      return Promise.resolve(threadFixture(threadId, { yoloEnabled: data.yoloEnabled }))
    })
    vi.mocked(harnessService.getThreadSnapshot).mockImplementation((threadId) =>
      Promise.resolve(
        threadId === THREAD_ID_2
          ? snapshotOf(
              threadFixture(THREAD_ID_2, {
                yoloEnabled: true,
                branchSettings: branchSettings({ agentName: 'assistant2' }),
              }),
            )
          : snapshotOf(threadFixture(THREAD_ID)),
      ),
    )
    const client = createClient()
    const { result, rerender } = renderHook(
      ({ threadId }: { threadId: string }) => useBoundBranchPanel({ threadId }),
      { wrapper: clientWrapper(client), initialProps: { threadId: THREAD_ID } },
    )

    await waitFor(() => expect(result.current.branchState).not.toBeNull())
    act(() => {
      result.current.setYoloEnabled(true)
    })
    expect(result.current.draft?.yoloEnabled).toBe(true)

    rerender({ threadId: THREAD_ID_2 })
    await waitFor(() => expect(result.current.draft?.agentName).toBe('assistant2'))
    // 重绑后新面板干净，无陈旧错误。
    expect(result.current.yoloError).toBeNull()

    // 旧 Thread 的请求在重绑之后才被拒绝：generation 已失效。
    await act(async () => {
      rejectOld?.(new Error('STALE_REVISION: old thread gone'))
    })
    await act(async () => {
      await Promise.resolve()
    })
    // 迟到失败不污染新面板：无 yoloError，draft/base 保持新 Thread 权威值。
    expect(result.current.yoloError).toBeNull()
    expect(result.current.draft?.yoloEnabled).toBe(true)
    expect(result.current.draft?.agentName).toBe('assistant2')
  })

  it('resets the pane-local draft on thread rebind and re-initializes from the new snapshot', async () => {
    vi.mocked(harnessService.getThreadSnapshot).mockImplementation((threadId) =>
      Promise.resolve(
        threadId === THREAD_ID_2
          ? snapshotOf(
              threadFixture(THREAD_ID_2, {
                yoloEnabled: true,
                branchSettings: branchSettings({
                  agentName: 'assistant2',
                  model: modelSelection({ providerName: 'anthropic', modelName: 'Claude', variant: 'v1' }),
                  activeTools: ['web-search'],
                }),
              }),
            )
          : snapshotOf(threadFixture(THREAD_ID)),
      ),
    )
    const client = createClient()
    const { result, rerender } = renderHook(
      ({ threadId }: { threadId: string }) => useBoundBranchPanel({ threadId }),
      { wrapper: clientWrapper(client), initialProps: { threadId: THREAD_ID } },
    )

    await waitFor(() => expect(result.current.branchState).not.toBeNull())
    act(() => {
      result.current.selectAgent('coder')
      result.current.setYoloEnabled(true)
    })
    expect(result.current.dirty).toBe(true)

    rerender({ threadId: THREAD_ID_2 })
    await waitFor(() =>
      expect(result.current.branchState?.base.agentName).toBe('assistant2'),
    )
    // 旧 Thread 的 draft 编辑绝不泄漏：draft 完全来自新 snapshot，且恢复干净。
    expect(result.current.draft).toEqual({
      environment: null,
      agentName: 'assistant2',
      model: { providerName: 'anthropic', modelName: 'Claude', variant: 'v1' },
      activeTools: ['web-search'],
      yoloEnabled: true,
    })
    expect(result.current.dirty).toBe(false)
  })

  it('fails closed during the rebind gap: old draft is not exposed and cannot be submitted until the new snapshot arrives', async () => {
    let resolveNewSnapshot: ((snapshot: HarnessThreadSnapshotDTO) => void) | null = null
    vi.mocked(harnessService.getThreadSnapshot).mockImplementation((threadId) => {
      if (threadId === THREAD_ID) {
        return Promise.resolve(snapshotOf(threadFixture(THREAD_ID)))
      }
      // 新 Thread 的 snapshot deferred：模拟重绑后 snapshot 未到达的窗口。
      return new Promise((resolve) => {
        resolveNewSnapshot = resolve
      })
    })
    const client = createClient()
    const { result, rerender } = renderHook(
      ({ threadId }: { threadId: string }) => useBoundBranchPanel({ threadId }),
      { wrapper: clientWrapper(client), initialProps: { threadId: THREAD_ID } },
    )

    await waitFor(() => expect(result.current.branchState).not.toBeNull())
    act(() => {
      result.current.selectAgent('coder')
      result.current.setYoloEnabled(true)
    })
    expect(result.current.draft?.agentName).toBe('coder')
    expect(result.current.dirty).toBe(true)

    rerender({ threadId: THREAD_ID_2 })

    // render-time fail-closed：新 snapshot 未到前，旧 state 不暴露、composer 门控全 null。
    expect(result.current.branchState).toBeNull()
    expect(result.current.draft).toBeUndefined()
    expect(result.current.effectiveBase).toBeNull()
    expect(result.current.dirty).toBe(false)
    // 即使是旧 Thread 的 snapshot 也绝不会被当作新 Thread 的状态初始化。
    expect(result.current.controller.thread).toBeUndefined()

    // submit 绝不把旧 draft 发到新 Thread。
    await act(async () => {
      await result.current.controller.submitMessage([createTextPart('hello world')])
    })
    expect(harnessService.enqueueCommands).not.toHaveBeenCalled()

    // 新 snapshot 到达后，从新 Thread 重新初始化，旧编辑不泄漏。
    await act(async () => {
      resolveNewSnapshot?.(
        snapshotOf(
          threadFixture(THREAD_ID_2, {
            yoloEnabled: true,
            branchSettings: branchSettings({
              agentName: 'assistant2',
              model: modelSelection({ providerName: 'anthropic', modelName: 'Claude', variant: 'v1' }),
              activeTools: ['web-search'],
            }),
          }),
        ),
      )
    })
    await waitFor(() => expect(result.current.draft?.agentName).toBe('assistant2'))
    expect(result.current.draft).toEqual({
      environment: null,
      agentName: 'assistant2',
      model: { providerName: 'anthropic', modelName: 'Claude', variant: 'v1' },
      activeTools: ['web-search'],
      yoloEnabled: true,
    })
    expect(result.current.dirty).toBe(false)
  })
})

describe('buildBoundThreadTranscript', () => {
  const controller = {
    timeline: {
      messages: [] as DialogueMessage[],
      queuedMessages: [],
      hasPendingInputs: false,
    },
    bodyRef: { current: null },
    entries: [{ entryId: 'e1' }] as HarnessSessionEntryDTO[],
    queuedCommands: [{ sequence: '1' }] as HarnessThreadCommandDTO[],
    messagesLoading: false,
    messagesError: null,
    approvalPending: false,
    decideApproval: vi.fn(async () => undefined),
  }

  it('forwards approval decisions to the controller and marks DENY via the scene callback', () => {
    const onDenyApproval = vi.fn()
    const transcript = buildBoundThreadTranscript({
      controller,
      threadId: THREAD_ID,
      initialConversationScrollTop: 42,
      onDenyApproval,
    })
    const message = {
      invocationId: 'inv-1',
    } as ToolDialogueMessage

    transcript.onDecideApproval?.(message, 'DENY')
    expect(controller.decideApproval).toHaveBeenCalledWith('inv-1', 'DENY')
    expect(onDenyApproval).toHaveBeenCalledTimes(1)

    transcript.onDecideApproval?.(message, 'ALLOW')
    expect(controller.decideApproval).toHaveBeenCalledWith('inv-1', 'ALLOW')
    expect(onDenyApproval).toHaveBeenCalledTimes(1)

    // 无 invocationId 的消息不触发任何副作用。
    transcript.onDecideApproval?.({} as ToolDialogueMessage, 'DENY')
    expect(controller.decideApproval).toHaveBeenCalledTimes(2)
    expect(onDenyApproval).toHaveBeenCalledTimes(1)
  })

  it('projects the scene-neutral transcript facts (scroll/resetKey/eventCount)', () => {
    const transcript = buildBoundThreadTranscript({
      controller,
      threadId: THREAD_ID,
      initialConversationScrollTop: null,
    })
    expect(transcript.resetKey).toBe(THREAD_ID)
    expect(transcript.initialScrollTop).toBeNull()
    expect(transcript.eventCount).toBe(2)
    expect(transcript.bodyRef).toBe(controller.bodyRef)
    expect(transcript.onDecideApproval).toBeDefined()
  })
})
