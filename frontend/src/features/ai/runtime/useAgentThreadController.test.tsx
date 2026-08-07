import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useAgentThreadController, retireStaleStopPending } from '@/features/ai/runtime/useAgentThreadController'
import {
  buildMessageBatchPlan,
  type CommandBatchPlan,
} from '@/features/ai/chat/command-batch-plan'
import { branchDraftFromThread, type BranchDraft } from '@/features/ai/chat/branch-draft'
import { agentService } from '@/shared/api/agent-service'
import { ApiError } from '@/shared/api/client'
import { harnessService } from '@/shared/api/harness-service'
import type {
  HarnessBranchSettingsDTO,
  HarnessModelSelectionDTO,
  HarnessThreadCommandDTO,
  HarnessThreadDTO,
  HarnessThreadSnapshotDTO,
  HarnessThreadStopResultDTO,
  ToolInvocationDTO,
} from '@/shared/api/contracts/ai-runtime'

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
    enqueueCommands: vi.fn(),
    updateThreadHead: vi.fn(),
    stopThread: vi.fn(),
    decideApproval: vi.fn(),
    createThreadRealtimeStream: vi.fn(),
  },
}))

class FakeEventSource {
  private readonly listeners = new Map<string, EventListener[]>()

  addEventListener(type: string, listener: EventListener): void {
    const existing = this.listeners.get(type) ?? []
    existing.push(listener)
    this.listeners.set(type, existing)
  }

  removeEventListener(type: string, listener: EventListener): void {
    const existing = this.listeners.get(type) ?? []
    this.listeners.set(
      type,
      existing.filter((value) => value !== listener),
    )
  }

  emit(type: string): void {
    const listeners = this.listeners.get(type)
    if (listeners) {
      for (const listener of listeners) {
        listener({} as Event)
      }
    }
  }

  close(): void {
    this.listeners.clear()
  }
}

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
    environmentName: null,
    agentName: 'assistant',
    model: modelSelection(),
    thinkingLevel: 'off',
    activeTools: [],
    ...overrides,
  }
}

function threadFixture(overrides: Partial<HarnessThreadDTO> = {}): HarnessThreadDTO {
  return {
    threadId: 't1',
    sessionId: 's1',
    headEntryId: 'h1',
    yoloEnabled: false,
    nextCommandSequence: '1',
    revision: '0',
    status: 'IDLE',
    processing: false,
    branchSettings: branchSettings(),
    createTime: null,
    updateTime: null,
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
    ...extras,
  }
}

const assistantAgentEntry = {
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

let realtimeSource: FakeEventSource

function wrapper({ children }: { children: ReactNode }) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>
}

function buildBatchFor(
  currentThread: HarnessThreadDTO,
  base: BranchDraft,
  draft: BranchDraft,
): (content: string) => CommandBatchPlan | null {
  return (content) =>
    buildMessageBatchPlan({ thread: currentThread, effectiveBase: base, draft, content })
}

describe('useAgentThreadController', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    realtimeSource = new FakeEventSource()
    vi.mocked(agentService.listAgents).mockResolvedValue({
      pageNumber: 1,
      pageSize: 50,
      totalCount: 1,
      results: [assistantAgentEntry],
    })
    vi.mocked(agentService.listModels).mockResolvedValue({
      pageNumber: 1,
      pageSize: 50,
      totalCount: 1,
      results: [
        {
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
        },
      ],
    })
    vi.mocked(harnessService.createThreadRealtimeStream).mockImplementation(
      () => realtimeSource as unknown as EventSource,
    )
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(snapshotOf(threadFixture()))
    vi.mocked(harnessService.enqueueCommands).mockResolvedValue(
      [] as HarnessThreadCommandDTO[],
    )
    vi.mocked(harnessService.stopThread).mockResolvedValue({
      status: 'IDLE',
      thread: threadFixture(),
      stoppedTurnEndEntryId: null,
      cancelledCommandCount: 0,
    } as HarnessThreadStopResultDTO)
    vi.mocked(harnessService.decideApproval).mockImplementation(
      async (_threadId, invocationId) =>
        ({
          id: invocationId,
          modelInvocationId: 'm1',
          assistantEntryId: 'a1',
          ordinal: 0,
          status: 'APPROVED',
          attempt: 1,
          toolCallId: 'call-1',
          toolName: 'demo',
          toolVersion: '1',
          toolType: 'PLATFORM',
          environmentName: null,
          argumentsJson: '{}',
          approvalJson: '{}',
          resultJson: null,
          errorJson: null,
          resultEntryId: null,
          createTime: null,
          updateTime: null,
        }) satisfies ToolInvocationDTO,
    )
  })

  it('submits a USER_MESSAGE plus a SET_* diff batch via the snapshot CAS cursors', async () => {
    const currentThread = threadFixture({
      branchSettings: branchSettings({ agentName: 'assistant' }),
    })
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(snapshotOf(currentThread))

    const base = branchDraftFromThread(currentThread)
    const draft: BranchDraft = {
      ...base,
      agentName: 'coder',
      yoloEnabled: true,
    }
    const { result } = renderHook(
      () =>
        useAgentThreadController(
          currentThread.threadId,
          '',
          undefined,
          buildBatchFor(currentThread, base, draft),
          new Map(),
        ),
      { wrapper },
    )
    await waitFor(() => expect(result.current.disabled).toBe(false))

    act(() => result.current.setDraft('hello world'))
    await act(async () => {
      await result.current.submitMessage()
    })

    await waitFor(() => expect(harnessService.enqueueCommands).toHaveBeenCalledTimes(1))
    const [threadIdArg, batchArg] = vi.mocked(harnessService.enqueueCommands).mock.calls[0]!
    expect(threadIdArg).toBe('t1')
    expect(batchArg.expectedHeadEntryId).toBe('h1')
    expect(batchArg.expectedNextCommandSequence).toBe('1')
    const types = batchArg.commands.map((command) => command.type)
    expect(types).toContain('SET_AGENT')
    expect(types).toContain('SET_YOLO')
    expect(types[types.length - 1]).toBe('USER_MESSAGE')
    const userMessage = batchArg.commands[batchArg.commands.length - 1]!
    expect(userMessage.content).toBe('hello world')
    // 严格的协议载荷：USER_MESSAGE 永远不会携带 role 字段。
    expect(userMessage).not.toHaveProperty('role')
    expect(result.current.draft).toBe('')
  })

  it('reports a 409 from send as threadStateChanged and invalidates the snapshot', async () => {
    vi.mocked(harnessService.enqueueCommands).mockRejectedValueOnce(
      new ApiError('expected revision mismatch', 409),
    )
    const currentThread = threadFixture()
    const base = branchDraftFromThread(currentThread)
    const draft = base
    const snapshotCallsBefore = vi.mocked(harnessService.getThreadSnapshot).mock.calls.length

    const { result } = renderHook(
      () =>
        useAgentThreadController(
          currentThread.threadId,
          '',
          undefined,
          buildBatchFor(currentThread, base, draft),
          new Map(),
        ),
      { wrapper },
    )
    await waitFor(() => expect(result.current.disabled).toBe(false))

    act(() => result.current.setDraft('stale message'))
    await act(async () => {
      await result.current.submitMessage()
    })

    await waitFor(() =>
      expect(result.current.actionError).toContain('Thread \u72b6\u6001\u5df2\u53d8\u5316'),
    )
    expect(result.current.actionError).toContain('expected revision mismatch')
    // 失效 snapshot 查询，以重新拉取当前 revision。
    await waitFor(() =>
      expect(harnessService.getThreadSnapshot.mock.calls.length).toBeGreaterThan(
        snapshotCallsBefore,
      ),
    )
    // 失败的 draft 会被恢复，用户无需重新输入即可重试。
    expect(result.current.draft).toBe('stale message')
  })

  it('reuses the same batch object when a retry carries identical content and draft', async () => {
    const currentThread = threadFixture()
    const base = branchDraftFromThread(currentThread)
    const draft = base
    vi.mocked(harnessService.enqueueCommands)
      .mockRejectedValueOnce(new Error('queue full'))
      .mockResolvedValueOnce([] as HarnessThreadCommandDTO[])

    const { result } = renderHook(
      () =>
        useAgentThreadController(
          currentThread.threadId,
          '',
          undefined,
          buildBatchFor(currentThread, base, draft),
          new Map(),
        ),
      { wrapper },
    )
    await waitFor(() => expect(result.current.disabled).toBe(false))

    act(() => result.current.setDraft('retry me'))
    await act(async () => {
      await result.current.submitMessage()
    })
    await waitFor(() => expect(harnessService.enqueueCommands).toHaveBeenCalledTimes(1))
    const firstBatch = vi.mocked(harnessService.enqueueCommands).mock.calls[0]?.[1]
    expect(firstBatch).toBeDefined()
    expect(result.current.draft).toBe('retry me')

    await act(async () => {
      await result.current.submitMessage()
    })
    await waitFor(() => expect(harnessService.enqueueCommands).toHaveBeenCalledTimes(2))
    const secondBatch = vi.mocked(harnessService.enqueueCommands).mock.calls[1]?.[1]
    // 身份匹配：完全复用上一次提交的 batch 对象（逐字节相同）。
    expect(secondBatch).toBe(firstBatch)
  })

  it('replays the exact batch even when the queued SET_* projection mutates the effective base', async () => {
    // 场景：base=A，draft=B 因不确定的网络错误失败；下一次 snapshot 中
    // 排队的 SET_* 命令将 base 投影为 B。期间用户并未改动，因此重试必须
    // 复用原始 batch（保持相同 command id），而不是新建一个仅含 USER_MESSAGE 的 batch。
    const currentThread = threadFixture()
    const baseA = branchDraftFromThread(currentThread)
    const draftB = { ...baseA, agentName: 'coder' }
    let projectionApplied = false
    const buildBatch = (content: string) =>
      buildMessageBatchPlan({
        // 投影之后 effectiveBase 等于 draft：粗略的 {content,base,draft}
        // 身份判定无法匹配；不可变意图层面的身份仍需命中。
        thread: currentThread,
        effectiveBase: projectionApplied ? draftB : baseA,
        draft: draftB,
        content,
      })
    vi.mocked(harnessService.enqueueCommands)
      .mockRejectedValueOnce(new Error('queue full'))
      .mockImplementationOnce(async () => {
        projectionApplied = true
        return [] as HarnessThreadCommandDTO[]
      })

    const { result } = renderHook(
      () =>
        useAgentThreadController(
          currentThread.threadId,
          '',
          undefined,
          buildBatch,
          new Map(),
        ),
      { wrapper },
    )
    await waitFor(() => expect(result.current.disabled).toBe(false))

    act(() => result.current.setDraft('retry me'))
    await act(async () => {
      await result.current.submitMessage()
    })
    await waitFor(() => expect(harnessService.enqueueCommands).toHaveBeenCalledTimes(1))
    const firstBatch = vi.mocked(harnessService.enqueueCommands).mock.calls[0]?.[1]
    expect(firstBatch?.commands).toHaveLength(2) // SET_AGENT + USER_MESSAGE

    await act(async () => {
      await result.current.submitMessage()
    })
    await waitFor(() => expect(harnessService.enqueueCommands).toHaveBeenCalledTimes(2))
    const secondBatch = vi.mocked(harnessService.enqueueCommands).mock.calls[1]?.[1]
    // 精确回放：必须复用同一个 batch 对象（SET_AGENT + USER_MESSAGE），不能退化为只含消息的 batch。
    expect(secondBatch).toBe(firstBatch)
    expect(secondBatch?.commands).toHaveLength(2)
  })

  it('mints a new batch when the draft diverges from the failed replay', async () => {
    const currentThread = threadFixture()
    const base = branchDraftFromThread(currentThread)
    const draft = base
    vi.mocked(harnessService.enqueueCommands)
      .mockRejectedValueOnce(new Error('queue full'))
      .mockResolvedValue([] as HarnessThreadCommandDTO[])

    const { result } = renderHook(
      () =>
        useAgentThreadController(
          currentThread.threadId,
          '',
          undefined,
          buildBatchFor(currentThread, base, draft),
          new Map(),
        ),
      { wrapper },
    )
    await waitFor(() => expect(result.current.disabled).toBe(false))

    act(() => result.current.setDraft('retry me'))
    await act(async () => {
      await result.current.submitMessage()
    })
    const firstId = vi.mocked(harnessService.enqueueCommands).mock.calls[0]?.[1]
      .commands[0]?.clientCommandId

    // 将 composer 编辑成不同内容时，会重置回放身份。
    act(() => result.current.setDraft('changed content'))
    await act(async () => {
      await result.current.submitMessage()
    })
    const secondId = vi.mocked(harnessService.enqueueCommands).mock.calls[1]?.[1]
      .commands[0]?.clientCommandId
    expect(secondId).toBeTruthy()
    expect(secondId).not.toBe(firstId)
  })

  it('surfaces non-conflict send failures with the draft restored', async () => {
    vi.mocked(harnessService.enqueueCommands).mockRejectedValueOnce(new Error('queue full'))
    const currentThread = threadFixture()
    const base = branchDraftFromThread(currentThread)
    const draft = base

    const { result } = renderHook(
      () =>
        useAgentThreadController(
          currentThread.threadId,
          '',
          undefined,
          buildBatchFor(currentThread, base, draft),
          new Map(),
        ),
      { wrapper },
    )
    await waitFor(() => expect(result.current.disabled).toBe(false))

    act(() => result.current.setDraft('retry me'))
    await act(async () => {
      await result.current.submitMessage()
    })
    await waitFor(() => expect(result.current.actionError).toContain('queue full'))
    expect(result.current.draft).toBe('retry me')
  })

  it('clears the draft on a successful send', async () => {
    const currentThread = threadFixture()
    const base = branchDraftFromThread(currentThread)
    const draft = base

    const { result } = renderHook(
      () =>
        useAgentThreadController(
          currentThread.threadId,
          '',
          undefined,
          buildBatchFor(currentThread, base, draft),
          new Map(),
        ),
      { wrapper },
    )
    await waitFor(() => expect(result.current.disabled).toBe(false))

    act(() => result.current.setDraft('hello world'))
    await act(async () => {
      await result.current.submitMessage()
    })
    await waitFor(() => expect(harnessService.enqueueCommands).toHaveBeenCalledTimes(1))
    expect(result.current.draft).toBe('')
  })

  it('stops the Thread with a stable stopRequestId across a retry after failure', async () => {
    vi.mocked(harnessService.stopThread)
      .mockRejectedValueOnce(new Error('network unavailable'))
      .mockResolvedValueOnce({
        status: 'IDLE',
        thread: threadFixture(),
        stoppedTurnEndEntryId: null,
        cancelledCommandCount: 0,
      } as HarnessThreadStopResultDTO)

    const { result } = renderHook(() => useAgentThreadController('t1'), { wrapper })
    await waitFor(() => expect(result.current.disabled).toBe(false))

    await act(async () => {
      await result.current.stopThread()
    })
    expect(result.current.actionError).toContain('network unavailable')
    expect(harnessService.stopThread).toHaveBeenCalledTimes(1)
    const firstStopArg = vi.mocked(harnessService.stopThread).mock.calls[0]?.[1]
    expect(firstStopArg).toBeDefined()
    expect(firstStopArg?.expectedRevision).toBe('0')

    await act(async () => {
      await result.current.stopThread()
    })
    expect(harnessService.stopThread).toHaveBeenCalledTimes(2)
    const secondStopArg = vi.mocked(harnessService.stopThread).mock.calls[1]?.[1]
    // 稳定的幂等键在瞬态失败后仍然保留。
    expect(secondStopArg?.stopRequestId).toBe(firstStopArg?.stopRequestId)
    expect(secondStopArg?.expectedRevision).toBe('0')
  })

  it('retires an ambiguous stop when the snapshot proves the old Turn ended and mints a fresh id', async () => {
    // 第一次 Stop 实际上已经在服务端生效，只是响应丢失了。
    vi.mocked(harnessService.stopThread).mockRejectedValue(new Error('response lost'))
    const turn1 = threadFixture()
    const turn2 = threadFixture({ headEntryId: 'e-turn1-end', revision: '2' })
    // 挂载时的拉取读取的是 Turn 1；由失败 Stop 触发的失效拉取会读取
    // 已前进的 Turn 2 snapshot（说明那次含糊的 Stop 实际上已经在服务端落地）。
    vi.mocked(harnessService.getThreadSnapshot)
      .mockResolvedValueOnce(snapshotOf(turn1))
      .mockResolvedValue(snapshotOf(turn2))
    const { result } = renderHook(() => useAgentThreadController('t1'), { wrapper })
    await waitFor(() => expect(result.current.disabled).toBe(false))

    await act(async () => {
      await result.current.stopThread()
    })
    expect(result.current.stopReplayPending).toBe(true)
    expect(harnessService.stopThread).toHaveBeenCalledTimes(1)
    const firstStopArg = vi.mocked(harnessService.stopThread).mock.calls[0]?.[1]
    expect(firstStopArg?.expectedRevision).toBe('0')

    // 权威 snapshot 推进到 Turn 2（head + revision 均已变化）：含糊的
    // 操作自动失效；realtime revision 信号触发一次 refetch。
    act(() => realtimeSource.emit('revision'))
    await waitFor(() => expect(result.current.thread?.revision).toBe('2'))
    await waitFor(() => expect(result.current.stopReplayPending).toBe(false))

    // 在新 Turn 上发起的 Stop 必须使用全新的 id + 当前 revision，绝不能把
    // 旧 id 拼接到更新的 revision 上。
    vi.mocked(harnessService.stopThread).mockResolvedValue({
      status: 'IDLE',
      thread: threadFixture({ headEntryId: 'e-turn1-end', revision: '2' }),
      stoppedTurnEndEntryId: null,
      cancelledCommandCount: 0,
    } as HarnessThreadStopResultDTO)
    await act(async () => {
      await result.current.stopThread()
    })
    const secondStopArg = vi.mocked(harnessService.stopThread).mock.calls[1]?.[1]
    expect(secondStopArg?.stopRequestId).not.toBe(firstStopArg?.stopRequestId)
    expect(secondStopArg?.expectedRevision).toBe('2')
  })

  it('keeps the exact stop body for the retry while the snapshot basis is unchanged', async () => {
    vi.mocked(harnessService.stopThread)
      .mockRejectedValueOnce(new Error('network unavailable'))
      .mockResolvedValueOnce({
        status: 'IDLE',
        thread: threadFixture(),
        stoppedTurnEndEntryId: null,
        cancelledCommandCount: 0,
      } as HarnessThreadStopResultDTO)
    const { result } = renderHook(() => useAgentThreadController('t1'), { wrapper })
    await waitFor(() => expect(result.current.disabled).toBe(false))

    await act(async () => {
      await result.current.stopThread()
    })
    const firstStopArg = vi.mocked(harnessService.stopThread).mock.calls[0]?.[1]
    expect(result.current.stopReplayPending).toBe(true)

    // snapshot refetch 返回完全相同的 basis（head/revision 未变）：重试必须
    // 发送与原始完全一致的请求体，而不是基于更新后的 snapshot 重新推导请求体。
    await act(async () => {
      await result.current.stopThread()
    })
    const secondStopArg = vi.mocked(harnessService.stopThread).mock.calls[1]?.[1]
    expect(secondStopArg?.stopRequestId).toBe(firstStopArg?.stopRequestId)
    expect(secondStopArg?.expectedRevision).toBe(firstStopArg?.expectedRevision)
    expect(result.current.stopReplayPending).toBe(false)
  })

  it('clears the ambiguous stop after a known 409 and mints a new operation on the next stop', async () => {
    vi.mocked(harnessService.stopThread)
      .mockRejectedValueOnce(new ApiError('stale revision', 409))
      .mockResolvedValueOnce({
        status: 'IDLE',
        thread: threadFixture(),
        stoppedTurnEndEntryId: null,
        cancelledCommandCount: 0,
      } as HarnessThreadStopResultDTO)
    const { result } = renderHook(() => useAgentThreadController('t1'), { wrapper })
    await waitFor(() => expect(result.current.disabled).toBe(false))

    await act(async () => {
      await result.current.stopThread()
    })
    expect(result.current.stopReplayPending).toBe(false)

    await act(async () => {
      await result.current.stopThread()
    })
    const firstStopArg = vi.mocked(harnessService.stopThread).mock.calls[0]?.[1]
    const secondStopArg = vi.mocked(harnessService.stopThread).mock.calls[1]?.[1]
    expect(secondStopArg?.stopRequestId).not.toBe(firstStopArg?.stopRequestId)
    expect(secondStopArg?.expectedRevision).toBe('0')
  })

  it('retireStaleStopPending retires exactly when the authoritative basis moved', () => {
    const pending = {
      stopRequestId: 's-1',
      expectedRevision: '0',
      basisHeadEntryId: 'h1',
      basisRevision: '0',
    }
    // basis 匹配时：精确重试继续生效。
    expect(
      retireStaleStopPending(pending, { headEntryId: 'h1', revision: '0' }),
    ).toBe(pending)
    // head 已移动（旧 Turn 已结束）：操作被失效。
    expect(
      retireStaleStopPending(pending, { headEntryId: 'h2', revision: '0' }),
    ).toBeNull()
    // revision 已移动（Thread 已前进）：操作被失效。
    expect(
      retireStaleStopPending(pending, { headEntryId: 'h1', revision: '1' }),
    ).toBeNull()
    // 无 pending 操作或无已加载的 Thread：no-op。
    expect(retireStaleStopPending(null, { headEntryId: 'h1', revision: '0' })).toBeNull()
    expect(retireStaleStopPending(pending, null)).toBe(pending)
  })

  it('re-mints immediately when stopThread runs after the snapshot basis moved (synchronous fence)', async () => {
    // 第一次 Stop 实际上已经在服务端生效，但响应丢失：含混的操作携带
    // Turn-1 basis 保持 pending。
    vi.mocked(harnessService.stopThread).mockRejectedValue(new Error('response lost'))
    const turn1 = threadFixture()
    const turn2 = threadFixture({ headEntryId: 'e-turn1-end', revision: '2' })
    vi.mocked(harnessService.getThreadSnapshot)
      .mockResolvedValueOnce(snapshotOf(turn1))
      .mockResolvedValue(snapshotOf(turn2))
    const { result } = renderHook(() => useAgentThreadController('t1'), { wrapper })
    await waitFor(() => expect(result.current.disabled).toBe(false))

    await act(async () => {
      await result.current.stopThread()
    })
    expect(result.current.stopReplayPending).toBe(true)
    const firstStopArg = vi.mocked(harnessService.stopThread).mock.calls[0]?.[1]
    expect(firstStopArg?.expectedRevision).toBe('0')

    // Query snapshot 推进到 Turn 2（head + revision 均已变化）并完成渲染。stopThread
    // 不得依赖被动清理 effect 的 flush：它自身的同步栅栏会失效陈旧 basis，
    // 并基于当前 revision 派生一个新的 id。（在 RTL 下 effect 会随 commit 一同 flush，
    // 因此上述栅栏契约由前面的 retireStaleStopPending 单元测试固化。）
    act(() => realtimeSource.emit('revision'))
    await waitFor(() => expect(result.current.thread?.revision).toBe('2'))
    vi.mocked(harnessService.stopThread).mockResolvedValue({
      status: 'IDLE',
      thread: turn2,
      stoppedTurnEndEntryId: null,
      cancelledCommandCount: 0,
    } as HarnessThreadStopResultDTO)
    await act(async () => {
      await result.current.stopThread()
    })
    const secondStopArg = vi.mocked(harnessService.stopThread).mock.calls[1]?.[1]
    expect(secondStopArg?.stopRequestId).not.toBe(firstStopArg?.stopRequestId)
    expect(secondStopArg?.expectedRevision).toBe('2')
    expect(result.current.stopReplayPending).toBe(false)
  })

  it('mints a fresh stopRequestId after a successful stop', async () => {
    const { result } = renderHook(() => useAgentThreadController('t1'), { wrapper })
    await waitFor(() => expect(result.current.disabled).toBe(false))

    await act(async () => {
      await result.current.stopThread()
    })
    await act(async () => {
      await result.current.stopThread()
    })
    expect(harnessService.stopThread).toHaveBeenCalledTimes(2)
    const firstId = vi.mocked(harnessService.stopThread).mock.calls[0]?.[1]?.stopRequestId
    const secondId = vi.mocked(harnessService.stopThread).mock.calls[1]?.[1]?.stopRequestId
    expect(firstId).toBeTruthy()
    expect(secondId).toBeTruthy()
    expect(secondId).not.toBe(firstId)
  })

  it('decides approval with a stable decisionId per invocation across a retry', async () => {
    vi.mocked(harnessService.decideApproval)
      .mockRejectedValueOnce(new Error('network unavailable'))
      .mockResolvedValueOnce({
        id: 'tool-1',
        modelInvocationId: 'm1',
        assistantEntryId: 'a1',
        ordinal: 0,
        status: 'APPROVED',
        attempt: 1,
        toolCallId: 'call-1',
        toolName: 'demo',
        toolVersion: '1',
        toolType: 'PLATFORM',
        environmentName: null,
        argumentsJson: '{}',
        approvalJson: '{}',
        resultJson: null,
        errorJson: null,
        resultEntryId: null,
        createTime: null,
        updateTime: null,
      } as ToolInvocationDTO)

    const { result } = renderHook(() => useAgentThreadController('t1'), { wrapper })
    await waitFor(() => expect(result.current.disabled).toBe(false))

    await act(async () => {
      await result.current.decideApproval('tool-1', 'ALLOW')
    })
    expect(harnessService.decideApproval).toHaveBeenCalledTimes(1)
    const firstCall = vi.mocked(harnessService.decideApproval).mock.calls[0]
    expect(firstCall?.[0]).toBe('t1')
    expect(firstCall?.[1]).toBe('tool-1')
    expect(firstCall?.[2]).toMatchObject({
      decision: 'ALLOW',
      actor: 'web',
      reason: null,
    })
    const firstDecisionId = firstCall?.[2]?.decisionId
    expect(firstDecisionId).toBeTruthy()

    await act(async () => {
      await result.current.decideApproval('tool-1', 'ALLOW')
    })
    expect(harnessService.decideApproval).toHaveBeenCalledTimes(2)
    const secondDecisionId = vi.mocked(harnessService.decideApproval).mock.calls[1]?.[2]
      ?.decisionId
    // 同一 invocation 跨重试 → 同一幂等键。
    expect(secondDecisionId).toBe(firstDecisionId)
  })

  it('mints a fresh decisionId for a different invocation', async () => {
    const { result } = renderHook(() => useAgentThreadController('t1'), { wrapper })
    await waitFor(() => expect(result.current.disabled).toBe(false))

    await act(async () => {
      await result.current.decideApproval('tool-A', 'ALLOW')
    })
    await act(async () => {
      await result.current.decideApproval('tool-B', 'DENY')
    })
    const aId = vi.mocked(harnessService.decideApproval).mock.calls[0]?.[2]?.decisionId
    const bId = vi.mocked(harnessService.decideApproval).mock.calls[1]?.[2]?.decisionId
    expect(aId).toBeTruthy()
    expect(bId).toBeTruthy()
    expect(bId).not.toBe(aId)
    expect(vi.mocked(harnessService.decideApproval).mock.calls[1]?.[2]).toMatchObject({
      decision: 'DENY',
      actor: 'web',
      reason: null,
    })
  })

  it('invalidates the snapshot after a successful stop', async () => {
    const { result } = renderHook(() => useAgentThreadController('t1'), { wrapper })
    await waitFor(() => expect(result.current.disabled).toBe(false))

    const initialCalls = vi.mocked(harnessService.getThreadSnapshot).mock.calls.length
    await act(async () => {
      await result.current.stopThread()
    })
    await waitFor(() =>
      expect(harnessService.getThreadSnapshot.mock.calls.length).toBeGreaterThan(initialCalls),
    )
  })

  it('exposes runtimeLabels from the snapshot branch settings and environment map', async () => {
    // name -> ready（统一可用性标记）；display name 已不存在。
    const environments = new Map<string, boolean>([['env-local', true]])
    const currentThread = threadFixture({
      branchSettings: branchSettings({ environmentName: 'env-local' }),
    })
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(snapshotOf(currentThread))

    const { result } = renderHook(
      () => useAgentThreadController(currentThread.threadId, '', undefined, null, environments),
      { wrapper },
    )
    await waitFor(() => expect(result.current.disabled).toBe(false))
    expect(result.current.runtimeLabels.agentName).toBe('assistant')
    expect(result.current.runtimeLabels.environmentName).toBe('env-local')
    expect(result.current.runtimeLabels.environmentReady).toBe(true)
    expect(result.current.runtimeLabels.modelName).toBe('minimax/MiniMax')
    expect(result.current.thread?.headEntryId).toBe('h1')
    expect(result.current.thread?.nextCommandSequence).toBe('1')
  })
  it('clears the exact replay on 409 so the retry mints fresh command ids and cursors', async () => {
    const currentThread = threadFixture({
      revision: '1',
      nextCommandSequence: '3',
      headEntryId: 'h1',
    })
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(snapshotOf(currentThread))
    vi.mocked(harnessService.enqueueCommands).mockRejectedValueOnce(
      new ApiError('conflict', 409, 'CONFLICT'),
    )
    vi.mocked(harnessService.enqueueCommands).mockResolvedValueOnce([])
    const base = branchDraftFromThread(currentThread)

    const { result } = renderHook(
      () =>
        useAgentThreadController(
          currentThread.threadId,
          '',
          undefined,
          buildBatchFor(currentThread, base, base),
          new Map(),
        ),
      { wrapper },
    )
    await waitFor(() => expect(result.current.disabled).toBe(false))

    act(() => result.current.setDraft('retry me'))
    await act(async () => {
      await result.current.submitMessage()
      await result.current.submitMessage()
    })

    const calls = vi.mocked(harnessService.enqueueCommands).mock.calls
    expect(calls).toHaveLength(2)
    const first = calls[0]?.[1] as {
      expectedHeadEntryId: string
      expectedNextCommandSequence: string
      commands: Array<{ clientCommandId: string }>
    }
    const second = calls[1]?.[1] as {
      expectedHeadEntryId: string
      expectedNextCommandSequence: string
      commands: Array<{ clientCommandId: string }>
    }
    // 409 = 该 batch 未被接受：重试使用刷新后的 head/nextSequence
    // 以及全新的 command id，而不是回放陈旧的 batch。
    expect(second.expectedHeadEntryId).toBe('h1')
    expect(second.expectedNextCommandSequence).toBe('3')
    expect(second.commands[0]?.clientCommandId).not.toBe(first.commands[0]?.clientCommandId)
    // 网络/不确定失败会保留精确 batch；409 不能这样做。
    void first
    void second
  })

  it('mints a new decision id when the user switches ALLOW -> DENY for the same invocation', async () => {
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(snapshotOf(threadFixture()))
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    const { result } = renderHook(() => useAgentThreadController('t1'), {
      wrapper: ({ children }: { children: ReactNode }) => (
        <QueryClientProvider client={client}>{children}</QueryClientProvider>
      ),
    })
    await waitFor(() => expect(result.current.disabled).toBe(false))

    // 第一次 ALLOW 失败：重试复用同一个 decision id。
    vi.mocked(harnessService.decideApproval).mockRejectedValueOnce(new Error('network'))
    await act(async () => {
      await result.current.decideApproval('inv-1', 'ALLOW')
      await result.current.decideApproval('inv-1', 'ALLOW')
    })
    const allowCalls = vi.mocked(harnessService.decideApproval).mock.calls
    expect(allowCalls[0]?.[2].decisionId).toBe(allowCalls[1]?.[2].decisionId)

    vi.mocked(harnessService.decideApproval).mockClear()
    // 决策成功后会同时刷新 snapshot 与 Chat-scoped 列表。
    const invalidateSpy = vi.spyOn(client, 'invalidateQueries')
    await act(async () => {
      await result.current.decideApproval('inv-1', 'DENY')
    })
    expect(invalidateSpy).toHaveBeenCalledWith(
      expect.objectContaining({ queryKey: ['chats'] }),
    )
    invalidateSpy.mockRestore()
    const denyCall = vi.mocked(harnessService.decideApproval).mock.calls[0]?.[2]
    expect(denyCall?.decisionId).toBeTruthy()
    expect(denyCall?.decisionId).not.toBe(allowCalls[0]?.[2].decisionId)
    expect(denyCall?.decision).toBe('DENY')
  })

})
