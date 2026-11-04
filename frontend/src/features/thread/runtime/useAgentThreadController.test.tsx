import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  useAgentThreadController,
  retireStaleStopPending,
} from '@/features/thread/runtime/useAgentThreadController'
import { useThreadProjection, type ThreadProjection } from '@/features/thread/runtime/useThreadProjection'
import { isCursorFreeThreadBatch } from '@/features/thread/runtime/agent-pane/agent-pane-pipeline'
import {
  buildMessageBatchPlan,
  type CommandBatchPlan,
} from '@/features/thread/command-batch-plan'
import { branchDraftFromThread, type BranchDraft } from '@/features/thread/branch-draft'
import {
  createAttachmentPart,
  createTextPart,
  partsToMessageContents,
  partsToText,
  type ComposerPart,
} from '@/features/thread/composer/composer-parts'
import {
  applyStopReceipt,
  loadThreadDraft,
  restoreThreadDraftParts,
  restoreThreadGoalText,
  saveThreadDraftParts,
  saveThreadGoalText,
  type DraftSaveOutcome,
  type StopReceiptMergeResult,
  type ThreadDraftRecord,
} from '@/features/thread/runtime/thread-draft-store'
import { loadPendingStop, pendingStopStorageKey } from '@/features/thread/runtime/pending-stop-sidecar'
import {
  boundPendingStorageKey,
  loadBoundPendingMessage,
} from '@/features/thread/runtime/pending-request-store'
import { translate as t } from '@/shared/i18n'
import { queryKeys } from '@/shared/lib/query-keys'

function useTestController(
  threadId: string,
  initialParts: ComposerPart[] = [],
  buildBatch: ((parts: ComposerPart[]) => CommandBatchPlan | null) | null = null,
  buildGoalBatch: ((goalText: string | null) => CommandBatchPlan | null) | null = null,
  injectedProjection?: ThreadProjection,
) {
  const ownProjection = useThreadProjection(threadId)
  const projection = injectedProjection ?? ownProjection
  return useAgentThreadController(projection, initialParts, buildBatch, buildGoalBatch)
}
import { ApplicationEventProvider } from '@/shared/app-events'
import { FakeWebSocketHarness } from '@/shared/app-events/__tests__/fake-websocket'
import { agentService } from '@/shared/api/agent-service'
import { ApiError } from '@/shared/api/client'
import { harnessService } from '@/shared/api/harness-service'
import { createBuiltinExecution } from '@/shared/api/contracts/ai-runtime'
import type {
  AgentDefinitionDTO,
  HarnessAcceptedCommandsDTO,
  HarnessBranchSettingsDTO,
  HarnessCancelledInputDTO,
  HarnessSessionEntryDTO,
  HarnessStoppedThreadReceiptDTO,
  HarnessThreadCompactResultDTO,
  HarnessThreadDTO,
  HarnessThreadSnapshotDTO,
  HarnessThreadStopResultDTO,
  ToolInvocationDTO,
} from '@/shared/api/generated/contracts'

/** 控制器 Thread id：资源订阅经严格 codec，必须是 canonical UUID。 */
const THREAD_ID = '11111111-2222-4333-8444-555555555555'
const THREAD_ID_2 = 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee'

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
    acceptThreadCommandBatch: vi.fn(),
    compactThread: vi.fn(),
    stopThread: vi.fn(),
    decideApproval: vi.fn(),
  },
}))
/**
 * 存储边界 mock 只用于确定性地控制 IndexedDB 事务时机与失败：默认透传真实实现，
 * 且每个用例开始前都会被重置回真实实现（见 resetDraftStoreToReal），因此它不会
 * 改变其余控制器用例观察到的草稿持久化行为。
 */
vi.mock('@/features/thread/runtime/thread-draft-store', async (importOriginal) => {
  const actual =
    await importOriginal<typeof import('@/features/thread/runtime/thread-draft-store')>()
  return {
    ...actual,
    applyStopReceipt: vi.fn(actual.applyStopReceipt),
    loadThreadDraft: vi.fn(actual.loadThreadDraft),
    restoreThreadDraftParts: vi.fn(actual.restoreThreadDraftParts),
    restoreThreadGoalText: vi.fn(actual.restoreThreadGoalText),
    saveThreadDraftParts: vi.fn(actual.saveThreadDraftParts),
    saveThreadGoalText: vi.fn(actual.saveThreadGoalText),
  }
})

const applyStopReceiptMock = vi.mocked(applyStopReceipt)
const loadThreadDraftMock = vi.mocked(loadThreadDraft)
const restoreThreadDraftPartsMock = vi.mocked(restoreThreadDraftParts)
const restoreThreadGoalTextMock = vi.mocked(restoreThreadGoalText)
const saveThreadDraftPartsMock = vi.mocked(saveThreadDraftParts)
const saveThreadGoalTextMock = vi.mocked(saveThreadGoalText)

function branchSettings(
  overrides: Partial<HarnessBranchSettingsDTO> = {},
): HarnessBranchSettingsDTO {
  return {
    agentName: 'assistant',
    execution: createBuiltinExecution('MiniMax', 'default'),
    environmentName: null,
    goal: null,
    ...overrides,
  }
}

function threadFixture(
  overrides: Partial<HarnessThreadDTO> & { yoloEnabled?: boolean } = {},
): HarnessThreadDTO {
  const { yoloEnabled = false, ...rest } = overrides
  return {
    /** Thread 名称（服务端权威必填非空）。 */
    name: 'thread-name',
    threadId: THREAD_ID,
    sessionId: 's1',
    headEntryId: 'h1',
    parentThreadId: null,
    rootThreadId: THREAD_ID, yoloEnabled,
    nextCommandSequence: '1',
    version: '0',
    status: 'IDLE',
    executionControl: 'RUNNABLE',
    branchSettings: branchSettings(),
    createTime: 0,
    updateTime: 0,
    ...rest,
  }
}

function snapshotOf(
  currentThread: HarnessThreadDTO,
  extras: Partial<HarnessThreadSnapshotDTO> = {},
): HarnessThreadSnapshotDTO {
  return {
    version: currentThread.version,
    thread: currentThread,
    baseEntryId: null,
    entries: [],
    queuedCommands: [],
    executionStatus: 'IDLE',
    activeAssistantOutput: null,
    toolInvocations: [],
    generationFailures: [],
    stopReceipts: [],
    unconfirmedExecutions: [],
    ...extras,
  }
}

function acceptedResponse(currentThread = threadFixture()): HarnessAcceptedCommandsDTO {
  return {
    session: { sessionId: currentThread.sessionId, name: 'Session', createdAt: 0 },
    rootEntry: {
      entryId: 'root-1',
      sessionId: currentThread.sessionId,
      parentEntryId: null,
      entryType: 'ROOT',
      payloadJson: '{}',
      createTime: 0,
      usageCost: null,
    },
    thread: currentThread,
    acceptedCommands: [],
    replayed: false,
  }
}

function toolInvocationFixture(overrides: Partial<ToolInvocationDTO> = {}): ToolInvocationDTO {
  return {
    id: 'tool-1',
    threadId: 'thread-1',
    turnStartEntryId: 'turn-1',
    assistantEntryId: 'a1',
    callIndex: 0,
    status: 'APPROVED',
    attempt: 1,
    toolCallId: 'call-1',
    toolName: 'demo',
    rendererKey: 'demo',
    environmentId: null,
    requiredEnvironmentId: null,
    waitingForEnvironment: false,
    requiredEnvironmentName: null,
    environmentWaitFreshnessAt: null,
    argumentsJson: '{}',
    approvalJson: '{}',
    resultJson: null,
    errorJson: null,
    createTime: 0,
    updateTime: 0,
    ...overrides,
  }
}

const assistantAgentEntry: AgentDefinitionDTO = {
  name: 'assistant',
  description: null,
  systemPrompt: null,
  type: 'USER',
  execution: { driver: 'builtin', settings: { modelName: 'minimax/MiniMax', variant: 'default' } },
  config: { tools: [], skills: [], subagents: [] },
  version: '0',
  createTime: 0,
  updateTime: 0,
}

let realtimeSockets: FakeWebSocketHarness

function wrapper({ children }: { children: ReactNode }) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return (
    <QueryClientProvider client={client}>
      <ApplicationEventProvider url="ws://test/api/events" socketFactory={realtimeSockets.factory}>
        {children}
      </ApplicationEventProvider>
    </QueryClientProvider>
  )
}

function clientWrapper(client: QueryClient) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={client}>
        <ApplicationEventProvider url="ws://test/api/events" socketFactory={realtimeSockets.factory}>
          {children}
        </ApplicationEventProvider>
      </QueryClientProvider>
    )
  }
}

function buildBatchFor(
  currentThread: HarnessThreadDTO,
  base: BranchDraft,
  draft: BranchDraft,
): (parts: ComposerPart[]) => CommandBatchPlan | null {
  return (parts) =>
    buildMessageBatchPlan({ thread: currentThread, effectiveBase: base, draft, parts })
}

/** 可控 promise：用于确定性地让回执合并晚于重绑或用户编辑完成。 */
function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

function cancelledUserMessage(sequence: number, text: string): HarnessCancelledInputDTO {
  return {
    sequence: String(sequence),
    idempotencyKey: `idempotency-${sequence}`,
    type: 'USER_MESSAGE',
    payloadJson: JSON.stringify({
      message: { role: 'USER', contents: [{ type: 'text', text }] },
    }),
  }
}

function stopReceipt(
  forThreadId: string,
  stopRequestId: string,
  cancelledInputs: HarnessCancelledInputDTO[],
): HarnessStoppedThreadReceiptDTO {
  return {
    threadId: forThreadId,
    stopRequestId,
    stoppedTurnEndEntryId: 'turn-end-entry',
    cancelledCommandCount: cancelledInputs.length,
    cancelledInputs,
  }
}

function recordOf(
  forThreadId: string,
  parts: ThreadDraftRecord['parts'],
  appliedStopRequestIds: string[] = [],
): ThreadDraftRecord {
  return { threadId: forThreadId, parts, goalText: null, appliedStopRequestIds }
}

/** pending GOAL sidecar：Stop 成功后会走「未消费 Goal 文本写回」路径。 */
function goalPendingMessage() {
  return {
    threadId: THREAD_ID,
    request: {
      expectedHeadEntryId: 'h1',
      expectedNextCommandSequence: '1',
      commands: [{ type: 'GOAL', idempotencyKey: 'goal-1', text: 'pending goal' }],
    },
    targetDraft: {
      agentName: 'assistant',
      execution: createBuiltinExecution('MiniMax', 'default'),
      environmentName: null,
      yoloEnabled: false,
    },
    kind: 'GOAL',
    localDraft: [],
    goalText: 'pending goal',
    unknownOutcome: true,
  }
}

/** 经快照补偿通道投递一条回执（走真实已有的回执应用入口）。 */
async function deliverReceipt(
  client: QueryClient,
  receipt: HarnessStoppedThreadReceiptDTO,
  forThreadId: string,
): Promise<void> {
  vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(
    snapshotOf(threadFixture({ threadId: forThreadId }), { stopReceipts: [receipt] }),
  )
  await act(async () => {
    await client.invalidateQueries({ queryKey: queryKeys.threads.snapshot(forThreadId) })
  })
}

/** 需要显式触发快照重取的用例用同一个 client。 */
function testClient() {
  return new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
}

async function readDraftText(forThreadId: string): Promise<string> {
  return partsToText((await loadThreadDraft(forThreadId))?.parts ?? [])
}

/** 让存储边界 mock 回到真实实现；只有显式覆盖的用例才模拟事务延迟或失败。 */
async function resetDraftStoreToReal() {
  const actual =
    await vi.importActual<typeof import('@/features/thread/runtime/thread-draft-store')>(
      '@/features/thread/runtime/thread-draft-store',
    )
  applyStopReceiptMock.mockReset().mockImplementation(actual.applyStopReceipt)
  loadThreadDraftMock.mockReset().mockImplementation(actual.loadThreadDraft)
  restoreThreadDraftPartsMock.mockReset().mockImplementation(actual.restoreThreadDraftParts)
  restoreThreadGoalTextMock.mockReset().mockImplementation(actual.restoreThreadGoalText)
  saveThreadDraftPartsMock.mockReset().mockImplementation(actual.saveThreadDraftParts)
  saveThreadGoalTextMock.mockReset().mockImplementation(actual.saveThreadGoalText)
}

/**
 * 控制器测试基座：两个 describe 共用同一份 mock 默认值与真实存储实现，
 * 每个用例都从干净、未被上一个用例覆盖的状态开始。
 */
async function resetControllerTestEnvironment() {
  vi.clearAllMocks()
  localStorage.clear()
  await resetDraftStoreToReal()
  realtimeSockets = new FakeWebSocketHarness()
  vi.mocked(agentService.listAgents).mockResolvedValue({
    pageNumber: 1,
    pageSize: 50,
    totalCount: '1',
    results: [assistantAgentEntry],
  })
  vi.mocked(agentService.listModels).mockResolvedValue({
    pageNumber: 1,
    pageSize: 50,
    totalCount: '1',
    results: [
      {
        providerName: 'minimax',
        kind: 'MODEL',
        name: 'MiniMax',
        modelId: 'MiniMax',
        description: null,
        config: {
          limit: { context: 128000, output: 8192 },
          abilities: { tools: true, reasoning: false, inputModalities: ['TEXT'] },
          pricing: {
            base: { input: '0', output: '0', cacheRead: '0', cacheWrite: '0', cacheWriteByTtl: {}, reasoning: null },
            overrides: [],
            multipliers: [],
          },
          defaultVariant: 'default',
          variants: [{ id: 'default' }],
        },
        version: '0',
        createTime: 0,
        updateTime: 0,
      },
    ],
  })
  vi.mocked(harnessService.getThreadSnapshot).mockImplementation(async (threadId) =>
    snapshotOf(threadFixture({ threadId })))
  vi.mocked(harnessService.acceptThreadCommandBatch).mockResolvedValue(
    acceptedResponse(),
  )
  vi.mocked(harnessService.stopThread).mockResolvedValue({
    status: 'STOPPED',
    thread: threadFixture(),
    stoppedThreads: [],
  } as HarnessThreadStopResultDTO)
  vi.mocked(harnessService.decideApproval).mockImplementation(
    async (_threadId, invocationId) => toolInvocationFixture({ id: invocationId }),
  )
}

describe('useAgentThreadController', () => {
  beforeEach(resetControllerTestEnvironment)

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
        useTestController(
          currentThread.threadId,
          [],
          buildBatchFor(currentThread, base, draft),
        ),
      { wrapper },
    )
    await waitFor(() => expect(result.current.disabled).toBe(false))

    act(() => result.current.setDraft([createTextPart('hello world')]))
    await act(async () => {
      await result.current.submitMessage()
    })

    await waitFor(() => expect(harnessService.acceptThreadCommandBatch).toHaveBeenCalledTimes(1))
    const calls = vi.mocked(harnessService.acceptThreadCommandBatch).mock.calls
    expect(calls[0]?.[0]).toBe(THREAD_ID)
    const batchArg = calls[0]?.[1]
    expect(batchArg).toBeDefined()
    if (batchArg == null) {
      return
    }
    expect(batchArg.expectedHeadEntryId).toBe('h1')
    expect(batchArg.expectedNextCommandSequence).toBe('1')
    // 既有 Thread 的写入不携带 owner/target。
    expect(batchArg).not.toHaveProperty('owner')
    expect(batchArg).not.toHaveProperty('target')
    const types = batchArg.commands.map((command) => command.type)
    expect(types).toContain('SET_AGENT')
    // YOLO 是 Thread 直接控制面，绝不进入 message batch。
    expect(types).not.toContain('SET_YOLO')
    expect(types[types.length - 1]).toBe('USER_MESSAGE')
    const userMessage = batchArg.commands[batchArg.commands.length - 1]!
    expect(userMessage).toMatchObject({
      contents: [{ type: 'TEXT', text: 'hello world' }],
    })
    // 严格的协议载荷：USER_MESSAGE 永远不会携带 role 字段。
    expect(userMessage).not.toHaveProperty('role')
    expect(partsToText(result.current.draft)).toBe('')
  })

  it('reports a 409 from send as threadStateChanged and invalidates the snapshot', async () => {
    vi.mocked(harnessService.acceptThreadCommandBatch).mockRejectedValueOnce(
      new ApiError('expected version mismatch', 409),
    )
    const currentThread = threadFixture()
    const base = branchDraftFromThread(currentThread)
    const draft = base
    const snapshotCallsBefore = vi.mocked(harnessService.getThreadSnapshot).mock.calls.length

    const { result } = renderHook(
      () =>
        useTestController(
          currentThread.threadId,
          [],
          buildBatchFor(currentThread, base, draft),
        ),
      { wrapper },
    )
    await waitFor(() => expect(result.current.disabled).toBe(false))

    act(() => result.current.setDraft([createTextPart('stale message')]))
    await act(async () => {
      await result.current.submitMessage()
    })

    await waitFor(() =>
      expect(result.current.conflict?.reason).toBe('CONFLICT'),
    )
    expect(result.current.conflict?.detail).toContain('expected version mismatch')
    // 失效 snapshot 查询，以重新拉取当前 version。
    await waitFor(() =>
      expect(vi.mocked(harnessService.getThreadSnapshot).mock.calls.length).toBeGreaterThan(
        snapshotCallsBefore,
      ),
    )
    // 失败的 draft 会被恢复，用户无需重新输入即可重试。
    expect(partsToText(result.current.draft)).toBe('stale message')
  })

  it('sends a pure USER_MESSAGE without cursor and never auto-retries a stale response', async () => {
    const currentThread = threadFixture()
    vi.mocked(harnessService.acceptThreadCommandBatch).mockRejectedValueOnce(
      new ApiError('stale', 409, 'CONFLICT', { reason: 'STALE_COMMAND_CURSOR' }),
    )
    const base = branchDraftFromThread(currentThread)

    const { result } = renderHook(
      () => useTestController(currentThread.threadId, [], buildBatchFor(currentThread, base, base)),
      { wrapper },
    )
    await waitFor(() => expect(result.current.disabled).toBe(false))

    act(() => result.current.setDraft([createTextPart('cursorless')]))
    await act(async () => {
      await result.current.submitMessage()
    })

    expect(harnessService.acceptThreadCommandBatch).toHaveBeenCalledTimes(1)
    const request = vi.mocked(harnessService.acceptThreadCommandBatch).mock.calls[0]?.[1]
    expect(request).toEqual({ commands: [expect.objectContaining({ type: 'USER_MESSAGE' })] })
    expect(partsToText(result.current.draft)).toBe('cursorless')
  })

  it('treats persisted cursor-free request shapes as retryable after reload', () => {
    const currentThread = threadFixture()
    const base = branchDraftFromThread(currentThread)
    const plan = buildMessageBatchPlan({
      thread: currentThread,
      effectiveBase: base,
      draft: base,
      parts: [createTextPart('reload me')],
      createCommandId: () => 'reload-id',
    })
    expect(plan.request).toEqual({ commands: [expect.objectContaining({ idempotencyKey: 'reload-id' })] })
  })

  it('distinguishes cursor-free pure messages from cursor-bound settings batches', () => {
    const currentThread = threadFixture()
    const base = branchDraftFromThread(currentThread)
    const messagePlan = buildMessageBatchPlan({
      thread: currentThread,
      effectiveBase: base,
      draft: base,
      parts: [createTextPart('hello')],
      createCommandId: () => 'message-id',
    })
    expect(isCursorFreeThreadBatch(messagePlan.request.commands)).toBe(true)
    expect(messagePlan.request).not.toHaveProperty('expectedHeadEntryId')
    expect(messagePlan.request).not.toHaveProperty('expectedNextCommandSequence')

    const settingsPlan = buildMessageBatchPlan({
      thread: currentThread,
      effectiveBase: base,
      draft: { ...base, agentName: 'coder' },
      parts: [createTextPart('hello')],
      createCommandId: () => 'settings-id',
    })
    expect(isCursorFreeThreadBatch(settingsPlan.request.commands)).toBe(false)
    expect(settingsPlan.request).toMatchObject({
      expectedHeadEntryId: 'h1',
      expectedNextCommandSequence: '1',
    })
  })

  it('restores the local-id draft, not the resolved payload, after a send failure with attachments', async () => {
    vi.mocked(harnessService.acceptThreadCommandBatch).mockRejectedValueOnce(
      new ApiError('network down', 0),
    )
    const currentThread = threadFixture()
    const base = branchDraftFromThread(currentThread)
    const draft = base

    const { result } = renderHook(
      () =>
        useTestController(
          currentThread.threadId,
          [],
          buildBatchFor(currentThread, base, draft),
        ),
      { wrapper },
    )
    await waitFor(() => expect(result.current.disabled).toBe(false))

    const localPart = createAttachmentPart('local-1', 'a.png')
    act(() => result.current.setDraft([createTextPart('hello'), localPart]))
    await act(async () => {
      await result.current.submitMessage()
    })

    await waitFor(() => expect(result.current.actionError).toBeTruthy())
    // 恢复的是本地草稿（客户端 localId），而不是提交 payload 中的服务端句柄。
    expect(result.current.draft).toEqual([
      expect.objectContaining({ type: 'text', text: 'hello' }) as Record<string, string>,
      expect.objectContaining({ type: 'attachment', uploadId: 'local-1', filename: 'a.png' }) as Record<string, string>,
    ])
    // batch 中序列化的是有序 contents（含 ATTACHMENT uploadId）。
    const batch = vi.mocked(harnessService.acceptThreadCommandBatch).mock.calls[0]?.[1]
    const message = batch?.commands[batch.commands.length - 1] as {
      contents?: Array<Record<string, unknown>>
    }
    expect(message.contents).toEqual([
      { type: 'TEXT', text: 'hello' },
      { type: 'ATTACHMENT', uploadId: 'local-1' },
    ])
  })

  it('reuses the same batch object when a retry carries identical content and draft', async () => {
    const currentThread = threadFixture()
    const base = branchDraftFromThread(currentThread)
    const draft = base
    vi.mocked(harnessService.acceptThreadCommandBatch)
      .mockRejectedValueOnce(new Error('queue full'))
      .mockResolvedValueOnce(acceptedResponse(currentThread))

    const { result } = renderHook(
      () =>
        useTestController(
          currentThread.threadId,
          [],
          buildBatchFor(currentThread, base, draft),
        ),
      { wrapper },
    )
    await waitFor(() => expect(result.current.disabled).toBe(false))

    act(() => result.current.setDraft([createTextPart('retry me')]))
    await act(async () => {
      await result.current.submitMessage()
    })
    await waitFor(() => expect(harnessService.acceptThreadCommandBatch).toHaveBeenCalledTimes(1))
    const firstBatch = vi.mocked(harnessService.acceptThreadCommandBatch).mock.calls[0]?.[1]
    expect(firstBatch).toBeDefined()
    expect(partsToText(result.current.draft)).toBe('retry me')

    await act(async () => {
      await result.current.submitMessage()
    })
    await waitFor(() => expect(harnessService.acceptThreadCommandBatch).toHaveBeenCalledTimes(2))
    const secondBatch = vi.mocked(harnessService.acceptThreadCommandBatch).mock.calls[1]?.[1]
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
    const buildBatch = (parts: ComposerPart[]) =>
      buildMessageBatchPlan({
        // 投影之后 effectiveBase 等于 draft：粗略的 {content,base,draft}
        // 身份判定无法匹配；不可变意图层面的身份仍需命中。
        thread: currentThread,
        effectiveBase: projectionApplied ? draftB : baseA,
        draft: draftB,
        parts,
      })
    vi.mocked(harnessService.acceptThreadCommandBatch)
      .mockRejectedValueOnce(new Error('queue full'))
      .mockImplementationOnce(async () => {
        projectionApplied = true
        return acceptedResponse(currentThread)
      })

    const { result } = renderHook(
      () =>
        useTestController(
          currentThread.threadId,
          [],
          buildBatch,
        ),
      { wrapper },
    )
    await waitFor(() => expect(result.current.disabled).toBe(false))

    act(() => result.current.setDraft([createTextPart('retry me')]))
    await act(async () => {
      await result.current.submitMessage()
    })
    await waitFor(() => expect(harnessService.acceptThreadCommandBatch).toHaveBeenCalledTimes(1))
    const firstBatch = vi.mocked(harnessService.acceptThreadCommandBatch).mock.calls[0]?.[1]
    expect(firstBatch?.commands).toHaveLength(2) // SET_AGENT + USER_MESSAGE

    await act(async () => {
      await result.current.submitMessage()
    })
    await waitFor(() => expect(harnessService.acceptThreadCommandBatch).toHaveBeenCalledTimes(2))
    const secondBatch = vi.mocked(harnessService.acceptThreadCommandBatch).mock.calls[1]?.[1]
    // 精确回放：必须复用同一个 batch 对象（SET_AGENT + USER_MESSAGE），不能退化为只含消息的 batch。
    expect(secondBatch).toBe(firstBatch)
    expect(secondBatch?.commands).toHaveLength(2)
  })

  it('mints a new batch when the draft diverges from the failed replay', async () => {
    const currentThread = threadFixture()
    const base = branchDraftFromThread(currentThread)
    const draft = base
    vi.mocked(harnessService.acceptThreadCommandBatch)
      .mockRejectedValueOnce(new Error('queue full'))
      .mockResolvedValue(acceptedResponse(currentThread))

    const { result } = renderHook(
      () =>
        useTestController(
          currentThread.threadId,
          [],
          buildBatchFor(currentThread, base, draft),
        ),
      { wrapper },
    )
    await waitFor(() => expect(result.current.disabled).toBe(false))

    act(() => result.current.setDraft([createTextPart('retry me')]))
    await act(async () => {
      await result.current.submitMessage()
    })
    const firstId = vi.mocked(harnessService.acceptThreadCommandBatch).mock.calls[0]?.[1]
      .commands[0]?.idempotencyKey

    // unknown 状态下不允许直接通过修改草稿解锁原回放身份（勿用生成新 key 解锁）；
    // 用户必须先显式调用 abandonPendingMessage 放弃旧未决消息，然后再提交新内容铸造新 key。
    act(() => {
      result.current.abandonPendingMessage()
    })
    act(() => result.current.setDraft([createTextPart('changed content')]))
    await act(async () => {
      await result.current.submitMessage()
    })
    const secondId = vi.mocked(harnessService.acceptThreadCommandBatch).mock.calls[1]?.[1]
      .commands[0]?.idempotencyKey
    expect(secondId).toBeTruthy()
    expect(secondId).not.toBe(firstId)
  })

  it('surfaces non-conflict send failures with the draft restored', async () => {
    vi.mocked(harnessService.acceptThreadCommandBatch).mockRejectedValueOnce(new Error('queue full'))
    const currentThread = threadFixture()
    const base = branchDraftFromThread(currentThread)
    const draft = base

    const { result } = renderHook(
      () =>
        useTestController(
          currentThread.threadId,
          [],
          buildBatchFor(currentThread, base, draft),
        ),
      { wrapper },
    )
    await waitFor(() => expect(result.current.disabled).toBe(false))

    act(() => result.current.setDraft([createTextPart('retry me')]))
    await act(async () => {
      await result.current.submitMessage()
    })
    await waitFor(() => expect(result.current.actionError).toContain('queue full'))
    expect(partsToText(result.current.draft)).toBe('retry me')
  })

  it('clears the draft on a successful send', async () => {
    const currentThread = threadFixture()
    const base = branchDraftFromThread(currentThread)
    const draft = base

    const { result } = renderHook(
      () =>
        useTestController(
          currentThread.threadId,
          [],
          buildBatchFor(currentThread, base, draft),
        ),
      { wrapper },
    )
    await waitFor(() => expect(result.current.disabled).toBe(false))

    act(() => result.current.setDraft([createTextPart('hello world')]))
    await act(async () => {
      await result.current.submitMessage()
    })
    await waitFor(() => expect(harnessService.acceptThreadCommandBatch).toHaveBeenCalledTimes(1))
    expect(partsToText(result.current.draft)).toBe('')
  })

  it('stops the Thread with a stable stopRequestId across a retry after failure', async () => {
    vi.mocked(harnessService.stopThread)
      .mockRejectedValueOnce(new Error('network unavailable'))
      .mockResolvedValueOnce({
        status: 'STOPPED',
        thread: threadFixture(),
        stoppedThreads: [],
      } as HarnessThreadStopResultDTO)

    const { result } = renderHook(() => useTestController(THREAD_ID), { wrapper })
    await waitFor(() => expect(result.current.disabled).toBe(false))

    await act(async () => {
      await result.current.stopThread()
    })
    expect(result.current.actionError).toContain('network unavailable')
    expect(harnessService.stopThread).toHaveBeenCalledTimes(1)
    const firstStopArg = vi.mocked(harnessService.stopThread).mock.calls[0]?.[1]
    expect(firstStopArg).toBeDefined()
    expect(firstStopArg?.expectedVersion).toBe('0')

    await act(async () => {
      await result.current.stopThread()
    })
    expect(harnessService.stopThread).toHaveBeenCalledTimes(2)
    const secondStopArg = vi.mocked(harnessService.stopThread).mock.calls[1]?.[1]
    // 稳定的幂等键在瞬态失败后仍然保留。
    expect(secondStopArg?.stopRequestId).toBe(firstStopArg?.stopRequestId)
    expect(secondStopArg?.expectedVersion).toBe('0')
  })

  it('restores cancelled text and resources once after an ambiguous Stop retry', async () => {
    vi.mocked(harnessService.stopThread)
      .mockRejectedValueOnce(new Error('response lost'))
      .mockResolvedValueOnce({
        status: 'REPLAYED',
        thread: threadFixture(),
        stoppedThreads: [
          {
            threadId: THREAD_ID,
            stopRequestId: 'stop-replay',
            stoppedTurnEndEntryId: null,
            cancelledCommandCount: 2,
            cancelledInputs: [
              {
                sequence: '1',
                idempotencyKey: 'c1',
                type: 'USER_MESSAGE',
                payloadJson:
                  '{"message":{"role":"USER","contents":[{"type":"text","text":"cancelled"}]}}',
              },
              {
                sequence: '2',
                idempotencyKey: 'c2',
                type: 'USER_MESSAGE',
                payloadJson:
                  '{"message":{"role":"USER","contents":[{"type":"resource","blobId":"00000000-0000-0000-0000-000000000001","name":"a.txt","preview":"p"}]}}',
              },
            ],
          },
        ],
      } as HarnessThreadStopResultDTO)
    const { result } = renderHook(() => useTestController(THREAD_ID), { wrapper })
    await waitFor(() => expect(result.current.disabled).toBe(false))
    act(() => result.current.setDraft([createTextPart('current')]))

    await act(async () => {
      await result.current.stopThread()
    })
    expect(partsToText(result.current.draft)).toBe('current')

    await act(async () => {
      await result.current.stopThread()
    })
    expect(partsToMessageContents(result.current.draft)).toEqual([
      { type: 'TEXT', text: 'cancelled\n\n' },
      {
        type: 'RESOURCE',
        blobId: '00000000-0000-0000-0000-000000000001',
        name: 'a.txt',
        preview: 'p',
      },
      { type: 'TEXT', text: '\n\ncurrent' },
    ])
    expect(localStorage.getItem(pendingStopStorageKey(THREAD_ID))).toBeNull()
  })

  it('applies concurrent replay-equivalent Stop responses at most once', async () => {
    vi.mocked(harnessService.stopThread).mockResolvedValue({
      status: 'REPLAYED',
      thread: threadFixture(),
      stoppedThreads: [
        {
          threadId: THREAD_ID,
          stopRequestId: 'stop-concurrent',
          stoppedTurnEndEntryId: 'e-turn-end',
          cancelledCommandCount: 1,
          cancelledInputs: [
            {
              sequence: '1',
              idempotencyKey: 'c1',
              type: 'USER_MESSAGE',
              payloadJson:
                '{"message":{"role":"USER","contents":[{"type":"text","text":"cancelled"}]}}',
            },
          ],
        },
      ],
    } as HarnessThreadStopResultDTO)
    const { result } = renderHook(() => useTestController(THREAD_ID), { wrapper })
    await waitFor(() => expect(result.current.disabled).toBe(false))

    await act(async () => {
      await Promise.all([
        result.current.stopThread(),
        result.current.stopThread(),
      ])
    })

    expect(harnessService.stopThread).toHaveBeenCalledTimes(2)
    expect(vi.mocked(harnessService.stopThread).mock.calls[0]?.[1]?.stopRequestId).toBe(
      vi.mocked(harnessService.stopThread).mock.calls[1]?.[1]?.stopRequestId,
    )
    expect(partsToText(result.current.draft)).toBe('cancelled')
  })

  it('restores the persisted pending Stop identity after remount', async () => {
    vi.mocked(harnessService.stopThread).mockRejectedValueOnce(new Error('response lost'))
    const first = renderHook(() => useTestController(THREAD_ID), { wrapper })
    await waitFor(() => expect(first.result.current.disabled).toBe(false))

    await act(async () => {
      await first.result.current.stopThread()
    })
    const firstBody = vi.mocked(harnessService.stopThread).mock.calls[0]?.[1]
    expect(localStorage.getItem(pendingStopStorageKey(THREAD_ID))).not.toBeNull()
    first.unmount()

    vi.mocked(harnessService.stopThread).mockResolvedValue({
      status: 'REPLAYED',
      thread: threadFixture(),
      stoppedThreads: [],
    } as HarnessThreadStopResultDTO)
    const second = renderHook(() => useTestController(THREAD_ID), { wrapper })
    await waitFor(() => expect(second.result.current.disabled).toBe(false))
    await waitFor(() => expect(second.result.current.stopReplayPending).toBe(true))

    await act(async () => {
      await second.result.current.stopThread()
    })
    const replayBody = vi.mocked(harnessService.stopThread).mock.calls[1]?.[1]
    expect(replayBody).toEqual(firstBody)
    expect(localStorage.getItem(pendingStopStorageKey(THREAD_ID))).toBeNull()
  })

  it('retires an ambiguous stop when the snapshot proves the old Turn ended and mints a fresh id', async () => {
    // 第一次 Stop 实际上已经在服务端生效，只是响应丢失了。
    vi.mocked(harnessService.stopThread).mockRejectedValue(new Error('response lost'))
    const turn1 = threadFixture()
    const turn2 = threadFixture({ headEntryId: 'e-turn1-end', version: '2' })
    // 挂载时的拉取读取的是 Turn 1；由失败 Stop 触发的失效拉取会读取
    // 已前进的 Turn 2 snapshot（说明那次含糊的 Stop 实际上已经在服务端落地）。
    vi.mocked(harnessService.getThreadSnapshot)
      .mockResolvedValueOnce(snapshotOf(turn1))
      .mockResolvedValue(snapshotOf(turn2))
    const { result } = renderHook(() => useTestController(THREAD_ID), { wrapper })
    await waitFor(() => expect(result.current.disabled).toBe(false))

    await act(async () => {
      await result.current.stopThread()
    })
    expect(result.current.stopReplayPending).toBe(true)
    expect(harnessService.stopThread).toHaveBeenCalledTimes(1)
    const firstStopArg = vi.mocked(harnessService.stopThread).mock.calls[0]?.[1]
    expect(firstStopArg?.expectedVersion).toBe('0')

    // 权威 snapshot 推进到 Turn 2（head + version 均已变化）：含糊的
    // 操作自动失效；realtime version 信号触发一次 refetch。
    act(() => realtimeSockets.latest?.emitServer({
      type: 'event',
      resource: { kind: 'thread', id: THREAD_ID },
      name: 'version',
      data: { version: '2' },
      cursor: '2',
    }))
    await waitFor(() => expect(result.current.thread?.version).toBe('2'))
    await waitFor(() => expect(result.current.stopReplayPending).toBe(false))

    // 在新 Turn 上发起的 Stop 必须使用全新的 id + 当前 version，绝不能把
    // 旧 id 拼接到更新的 version 上。
    vi.mocked(harnessService.stopThread).mockResolvedValue({
      status: 'STOPPED',
      thread: threadFixture({ headEntryId: 'e-turn1-end', version: '2' }),
      stoppedThreads: [],
    } as HarnessThreadStopResultDTO)
    await act(async () => {
      await result.current.stopThread()
    })
    const secondStopArg = vi.mocked(harnessService.stopThread).mock.calls[1]?.[1]
    expect(secondStopArg?.stopRequestId).not.toBe(firstStopArg?.stopRequestId)
    expect(secondStopArg?.expectedVersion).toBe('2')
  })

  it('keeps the exact stop body for the retry while the snapshot basis is unchanged', async () => {
    vi.mocked(harnessService.stopThread)
      .mockRejectedValueOnce(new Error('network unavailable'))
      .mockResolvedValueOnce({
        status: 'STOPPED',
        thread: threadFixture(),
        stoppedThreads: [],
      } as HarnessThreadStopResultDTO)
    const { result } = renderHook(() => useTestController(THREAD_ID), { wrapper })
    await waitFor(() => expect(result.current.disabled).toBe(false))

    await act(async () => {
      await result.current.stopThread()
    })
    const firstStopArg = vi.mocked(harnessService.stopThread).mock.calls[0]?.[1]
    expect(result.current.stopReplayPending).toBe(true)

    // snapshot refetch 返回完全相同的 basis（head/version 未变）：重试必须
    // 发送与原始完全一致的请求体，而不是基于更新后的 snapshot 重新推导请求体。
    await act(async () => {
      await result.current.stopThread()
    })
    const secondStopArg = vi.mocked(harnessService.stopThread).mock.calls[1]?.[1]
    expect(secondStopArg?.stopRequestId).toBe(firstStopArg?.stopRequestId)
    expect(secondStopArg?.expectedVersion).toBe(firstStopArg?.expectedVersion)
    expect(result.current.stopReplayPending).toBe(false)
  })

  it('clears the ambiguous stop after a known 409 and mints a new operation on the next stop', async () => {
    vi.mocked(harnessService.stopThread)
      .mockRejectedValueOnce(new ApiError('stale version', 409))
      .mockResolvedValueOnce({
        status: 'STOPPED',
        thread: threadFixture(),
        stoppedThreads: [],
      } as HarnessThreadStopResultDTO)
    const { result } = renderHook(() => useTestController(THREAD_ID), { wrapper })
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
    expect(secondStopArg?.expectedVersion).toBe('0')
  })

  it('retireStaleStopPending retires exactly when the authoritative basis moved', () => {
    const pending = {
      stopRequestId: 's-1',
      expectedVersion: '0',
      requestHeadEntryId: 'h1',
      basisVersion: '0',
    }
    // basis 匹配时：精确重试继续生效。
    expect(
      retireStaleStopPending(pending, { headEntryId: 'h1', version: '0' }),
    ).toBe(pending)
    // head 已移动（旧 Turn 已结束）：操作被失效。
    expect(
      retireStaleStopPending(pending, { headEntryId: 'h2', version: '0' }),
    ).toBeNull()
    // version 已移动（Thread 已前进）：操作被失效。
    expect(
      retireStaleStopPending(pending, { headEntryId: 'h1', version: '1' }),
    ).toBeNull()
    // 无 pending 操作或无已加载的 Thread：no-op。
    expect(retireStaleStopPending(null, { headEntryId: 'h1', version: '0' })).toBeNull()
    expect(retireStaleStopPending(pending, null)).toBe(pending)
  })

  it('re-mints immediately when stopThread runs after the snapshot basis moved (synchronous fence)', async () => {
    // 第一次 Stop 实际上已经在服务端生效，但响应丢失：含混的操作携带
    // Turn-1 basis 保持 pending。
    vi.mocked(harnessService.stopThread).mockRejectedValue(new Error('response lost'))
    const turn1 = threadFixture()
    const turn2 = threadFixture({ headEntryId: 'e-turn1-end', version: '2' })
    vi.mocked(harnessService.getThreadSnapshot)
      .mockResolvedValueOnce(snapshotOf(turn1))
      .mockResolvedValue(snapshotOf(turn2))
    const { result } = renderHook(() => useTestController(THREAD_ID), { wrapper })
    await waitFor(() => expect(result.current.disabled).toBe(false))

    await act(async () => {
      await result.current.stopThread()
    })
    expect(result.current.stopReplayPending).toBe(true)
    const firstStopArg = vi.mocked(harnessService.stopThread).mock.calls[0]?.[1]
    expect(firstStopArg?.expectedVersion).toBe('0')

    // Query snapshot 推进到 Turn 2（head + version 均已变化）并完成渲染。stopThread
    // 不得依赖被动清理 effect 的 flush：它自身的同步栅栏会失效陈旧 basis，
    // 并基于当前 version 派生一个新的 id。（在 RTL 下 effect 会随 commit 一同 flush，
    // 因此上述栅栏契约由前面的 retireStaleStopPending 单元测试固化。）
    act(() => realtimeSockets.latest?.emitServer({
      type: 'event',
      resource: { kind: 'thread', id: THREAD_ID },
      name: 'version',
      data: { version: '2' },
      cursor: '2',
    }))
    await waitFor(() => expect(result.current.thread?.version).toBe('2'))
    vi.mocked(harnessService.stopThread).mockResolvedValue({
      status: 'STOPPED',
      thread: turn2,
      stoppedThreads: [],
    } as HarnessThreadStopResultDTO)
    await act(async () => {
      await result.current.stopThread()
    })
    const secondStopArg = vi.mocked(harnessService.stopThread).mock.calls[1]?.[1]
    expect(secondStopArg?.stopRequestId).not.toBe(firstStopArg?.stopRequestId)
    expect(secondStopArg?.expectedVersion).toBe('2')
    expect(result.current.stopReplayPending).toBe(false)
  })

  it('mints a fresh stopRequestId after a successful stop', async () => {
    const { result } = renderHook(() => useTestController(THREAD_ID), { wrapper })
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
      .mockResolvedValueOnce(toolInvocationFixture({ id: 'tool-1' }))

    const { result } = renderHook(() => useTestController(THREAD_ID), { wrapper })
    await waitFor(() => expect(result.current.disabled).toBe(false))

    await act(async () => {
      await result.current.decideApproval('tool-1', 'ALLOW')
    })
    expect(harnessService.decideApproval).toHaveBeenCalledTimes(1)
    const firstCall = vi.mocked(harnessService.decideApproval).mock.calls[0]
    expect(firstCall?.[0]).toBe(THREAD_ID)
    expect(firstCall?.[1]).toBe('tool-1')
    expect(firstCall?.[2]).toMatchObject({
      decision: 'ALLOW',
      reason: null,
    })
    expect(firstCall?.[2]).not.toHaveProperty('actor')
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
    const { result } = renderHook(() => useTestController(THREAD_ID), { wrapper })
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
      reason: null,
    })
    expect(vi.mocked(harnessService.decideApproval).mock.calls[1]?.[2]).not.toHaveProperty('actor')
  })

  it('relays child-thread approvals to the target thread with an isolated replay key', async () => {
    vi.mocked(harnessService.decideApproval)
      .mockRejectedValueOnce(new Error('network unavailable'))
      .mockResolvedValueOnce(
        toolInvocationFixture({ id: 'tool-child', toolName: 'bash', rendererKey: 'bash' }),
      )

    const { result } = renderHook(() => useTestController(THREAD_ID), { wrapper })
    await waitFor(() => expect(result.current.disabled).toBe(false))

    // 子 Thread 审批：mutation 必须打到 status.threadId，而不是父 Thread。
    await act(async () => {
      await result.current.decideApproval('tool-child', 'ALLOW', 't-child')
    })
    expect(vi.mocked(harnessService.decideApproval).mock.calls[0]?.[0]).toBe('t-child')
    expect(vi.mocked(harnessService.decideApproval).mock.calls[0]?.[1]).toBe('tool-child')

    // 同一子 Thread + invocation + decision 的重试复用同一幂等键。
    await act(async () => {
      await result.current.decideApproval('tool-child', 'ALLOW', 't-child')
    })
    const retryId = vi.mocked(harnessService.decideApproval).mock.calls[1]?.[2]?.decisionId
    expect(retryId).toBe(vi.mocked(harnessService.decideApproval).mock.calls[0]?.[2]?.decisionId)

    // 不同子 Thread 复用相同 invocationId 时，回放键必须隔离（铸造全新 id）。
    await act(async () => {
      await result.current.decideApproval('tool-child', 'ALLOW', 't-other')
    })
    const otherCall = vi.mocked(harnessService.decideApproval).mock.calls[2]
    expect(otherCall?.[0]).toBe('t-other')
    expect(otherCall?.[2]?.decisionId).not.toBe(retryId)
  })

  it('refreshes the target child thread snapshot on a 409 approval conflict', async () => {
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    vi.mocked(harnessService.decideApproval).mockRejectedValueOnce(
      new ApiError('stale child version', 409),
    )
    const { result } = renderHook(() => useTestController(THREAD_ID), {
      wrapper: clientWrapper(client),
    })
    await waitFor(() => expect(result.current.disabled).toBe(false))

    const invalidateSpy = vi.spyOn(client, 'invalidateQueries')
    await act(async () => {
      await result.current.decideApproval('tool-child', 'ALLOW', 't-child')
    })
    // 409 的刷新目标是子 Thread snapshot（而非父 Thread）。
    expect(invalidateSpy).toHaveBeenCalledWith(
      expect.objectContaining({ queryKey: ['threads', 'snapshot', 't-child'] }),
    )
    invalidateSpy.mockRestore()
  })

  it('invalidates the snapshot after a successful stop', async () => {
    const { result } = renderHook(() => useTestController(THREAD_ID), { wrapper })
    await waitFor(() => expect(result.current.disabled).toBe(false))

    const initialCalls = vi.mocked(harnessService.getThreadSnapshot).mock.calls.length
    await act(async () => {
      await result.current.stopThread()
    })
    await waitFor(() =>
      expect(vi.mocked(harnessService.getThreadSnapshot).mock.calls.length).toBeGreaterThan(initialCalls),
    )
  })

  it('exposes runtime facts from the snapshot branch settings', async () => {
    vi.mocked(agentService.listAgents).mockResolvedValue({
      pageNumber: 1,
      pageSize: 50,
      totalCount: '1',
      results: [
        {
          name: 'assistant',
          description: null,
          systemPrompt: null,
          type: 'USER',
          execution: { driver: 'builtin', settings: { modelName: 'minimax/MiniMax', variant: 'default' } },
          config: { tools: [], skills: [], subagents: [] },
          version: '1',
          createTime: 0,
          updateTime: 0,
        },
      ],
    })
    const currentThread = threadFixture({
      branchSettings: branchSettings({
        agentName: 'assistant',
        environmentName: 'dev-cluster',
      }),
    })
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(snapshotOf(currentThread))

    const { result } = renderHook(() => useTestController(currentThread.threadId), {
      wrapper,
    })
    await waitFor(() => expect(result.current.disabled).toBe(false))
    expect(result.current.runtimeLabels.environmentName).toBe('dev-cluster')
    expect(result.current.runtimeLabels.contextWindow).toBe(128000)
    expect(result.current.thread?.headEntryId).toBe('h1')
    expect(result.current.thread?.nextCommandSequence).toBe('1')
  })

  it('derives Branch Usage from the current snapshot entries and their read-time cost projection', async () => {
    const currentThread = threadFixture()
    const entry = (
      entryId: string,
      entryType: HarnessSessionEntryDTO['entryType'],
      payload: Record<string, unknown>,
    ): HarnessSessionEntryDTO => ({
      entryId,
      sessionId: 's1',
      parentEntryId: null,
      entryType,
      payloadJson: JSON.stringify(payload),
      createTime: 0,
      usageCost: null,
    })
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(
      snapshotOf(currentThread, {
        entries: [
          entry('turn-1', 'TURN_START', { reason: 'USER_MESSAGE' }),
          {
            ...entry('assistant-1', 'MESSAGE', {
              message: { role: 'ASSISTANT', contents: [{ type: 'text', text: 'done' }] },
              assistantMetadata: {
                usage: {
                  inputTokens: 1_200,
                  outputTokens: 80,
                  cacheReadTokens: 300,
                  cacheWriteTokens: 40,
                  reasoningTokens: 20,
                  providerTotalTokens: 1_640,
                },
              },
            }),
            // 费用只来自读取投影，不来自 payload 内的任何 cost 字段
            usageCost: { currency: 'USD', amount: '0.25' },
          },
          entry('end-1', 'TURN_END', { outcome: 'COMPLETED', continueModel: false }),
        ],
      }),
    )

    const { result } = renderHook(() => useTestController(currentThread.threadId), {
      wrapper,
    })
    await waitFor(() => expect(result.current.disabled).toBe(false))
    expect(result.current.branchUsage).toEqual({
      input: 1_200,
      output: 80,
      cacheRead: 300,
      cacheWrite: 40,
      reasoning: 20,
      providerTotal: 1_640,
      cost: { currency: 'USD', amount: '0.25' },
      decodeTokens: null,
      decodeDurationMillis: null,
      contextInputTokens: 1_540,
    })
  })

  it('clears the exact replay on 409 so the retry mints fresh command ids without cursors', async () => {
    vi.mocked(harnessService.acceptThreadCommandBatch).mockRejectedValueOnce(
      new ApiError('conflict', 409, 'CONFLICT', { reason: 'IDEMPOTENCY_KEY_REUSED' }),
    )
    const currentThread = threadFixture()
    vi.mocked(harnessService.acceptThreadCommandBatch).mockResolvedValueOnce(
      acceptedResponse(currentThread),
    )
    const base = branchDraftFromThread(currentThread)

    const { result } = renderHook(
      () => useTestController(currentThread.threadId, [], buildBatchFor(currentThread, base, base)),
      { wrapper },
    )
    await waitFor(() => expect(result.current.disabled).toBe(false))

    act(() => result.current.setDraft([createTextPart('retry me')]))
    await act(async () => {
      await result.current.submitMessage()
      await result.current.submitMessage()
    })

    const calls = vi.mocked(harnessService.acceptThreadCommandBatch).mock.calls
    expect(calls).toHaveLength(2)
    const first = calls[0]?.[1]
    const second = calls[1]?.[1]
    expect(second).not.toHaveProperty('expectedHeadEntryId')
    expect(second).not.toHaveProperty('expectedNextCommandSequence')
    expect(second?.commands[0]?.idempotencyKey).not.toBe(first?.commands[0]?.idempotencyKey)
  })

  it('mints a new decision id when the user switches ALLOW -> DENY for the same invocation', async () => {
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(snapshotOf(threadFixture()))
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    const { result } = renderHook(() => useTestController(THREAD_ID), {
      wrapper: clientWrapper(client),
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

  it('falls back to the generic requestFailed message for a non-Error, non-string send rejection', async () => {
    vi.mocked(harnessService.acceptThreadCommandBatch).mockRejectedValueOnce(null)
    const currentThread = threadFixture()
    const base = branchDraftFromThread(currentThread)
    const { result } = renderHook(
      () =>
        useTestController(
          currentThread.threadId,
          [],
          buildBatchFor(currentThread, base, base),
        ),
      { wrapper },
    )
    await waitFor(() => expect(result.current.disabled).toBe(false))
    act(() => result.current.setDraft([createTextPart('hello')]))
    await act(async () => {
      await result.current.submitMessage()
    })
    await waitFor(() => expect(result.current.actionError).toBeTruthy())
    // 非法 error payload（null）→ 通用失败文案（本文件 beforeEach 清空 localStorage，locale 为 en-US）。
    expect(result.current.actionError).toBe('Request failed')
    act(() => result.current.dismissActionError())
    expect(result.current.actionError).toBeNull()
  })

  it('surfaces a raw string rejection from stop without wrapping it', async () => {
    vi.mocked(harnessService.stopThread).mockRejectedValueOnce('plain string failure')
    const { result } = renderHook(() => useTestController(THREAD_ID), { wrapper })
    await waitFor(() => expect(result.current.disabled).toBe(false))
    await act(async () => {
      await result.current.stopThread()
    })
    expect(result.current.actionError).toBe('plain string failure')
  })

  it('no-ops stop and compact before the thread snapshot has loaded', async () => {
    vi.mocked(harnessService.getThreadSnapshot).mockReturnValue(
      new Promise<HarnessThreadSnapshotDTO>(() => undefined),
    )
    const { result } = renderHook(() => useTestController(THREAD_ID), { wrapper })
    await act(async () => {
      await result.current.stopThread()
    })
    expect(harnessService.stopThread).not.toHaveBeenCalled()
    await act(async () => {
      await result.current.compactThread()
    })
    expect(harnessService.compactThread).not.toHaveBeenCalled()
    expect(result.current.actionError).toBeNull()
  })

  it('reports threadNotLoaded without a batch builder and silently no-ops a null plan', async () => {
    const currentThread = threadFixture()
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(snapshotOf(currentThread))
    // 缺省 buildBatch（null）：submit 给出 threadNotLoaded 并阻止发送。
    const { result } = renderHook(() => useTestController(currentThread.threadId), {
      wrapper,
    })
    await waitFor(() => expect(result.current.disabled).toBe(false))
    act(() => result.current.setDraft([createTextPart('hello')]))
    await act(async () => {
      await result.current.submitMessage()
    })
    expect(result.current.actionError).toContain('Thread')
    expect(harnessService.acceptThreadCommandBatch).not.toHaveBeenCalled()

    // buildBatch 返回 null（不可构建的 payload）：静默 no-op，draft 原样保留。
    const { result: nullPlan } = renderHook(
      () =>
        useTestController(
          currentThread.threadId,
          [],
          () => null,
        ),
      { wrapper },
    )
    await waitFor(() => expect(nullPlan.current.disabled).toBe(false))
    act(() => nullPlan.current.setDraft([createTextPart('keep me')]))
    await act(async () => {
      await nullPlan.current.submitMessage()
    })
    expect(nullPlan.current.actionError).toBeNull()
    expect(partsToText(nullPlan.current.draft)).toBe('keep me')
    expect(harnessService.acceptThreadCommandBatch).not.toHaveBeenCalled()
  })

  it('never overwrites a newer composer draft when an earlier send fails', async () => {
    vi.mocked(harnessService.acceptThreadCommandBatch).mockRejectedValueOnce(new Error('queue full'))
    const currentThread = threadFixture()
    const base = branchDraftFromThread(currentThread)
    const { result } = renderHook(
      () =>
        useTestController(
          currentThread.threadId,
          [],
          buildBatchFor(currentThread, base, base),
        ),
      { wrapper },
    )
    await waitFor(() => expect(result.current.disabled).toBe(false))
    act(() => result.current.setDraft([createTextPart('first attempt')]))
    await act(async () => {
      const pendingSubmit = result.current.submitMessage()
      result.current.setDraft([createTextPart('newer draft')])
      await pendingSubmit
    })
    await waitFor(() => expect(result.current.actionError).toContain('queue full'))
    // 失败时 composer 已有更新内容：绝不覆盖为失败快照；同时 unknown 状态下锁定原身份，必须显式放弃
    expect(partsToText(result.current.draft)).toBe('newer draft')
    expect(result.current.replayPending).toBe(true)
    act(() => {
      result.current.abandonPendingMessage()
    })
    expect(result.current.replayPending).toBe(false)
  })

  it('blocks overlapping in-flight submission and prevents second submit from overwriting first pending', async () => {
    let releaseFirst!: (val: HarnessAcceptedCommandsDTO) => void
    vi.mocked(harnessService.acceptThreadCommandBatch)
      .mockImplementationOnce(
        () =>
          new Promise<HarnessAcceptedCommandsDTO>((resolve) => {
            releaseFirst = resolve
          }),
      )
      .mockResolvedValueOnce(acceptedResponse())
    const currentThread = threadFixture()
    const base = branchDraftFromThread(currentThread)
    const { result } = renderHook(
      () =>
        useTestController(
          currentThread.threadId,
          [],
          buildBatchFor(currentThread, base, base),
        ),
      { wrapper },
    )
    await waitFor(() => expect(result.current.disabled).toBe(false))
    act(() => result.current.setDraft([createTextPart('first')]))
    act(() => {
      // 启动第一个请求进入飞行状态
      void result.current.submitMessage()
    })
    expect(harnessService.acceptThreadCommandBatch).toHaveBeenCalledTimes(1)

    // 在第一个请求飞行中：试图发送第二个请求，被同步 ref 阻止，不能覆盖首请求
    act(() => result.current.setDraft([createTextPart('second')]))
    act(() => {
      void result.current.submitMessage()
    })
    expect(harnessService.acceptThreadCommandBatch).toHaveBeenCalledTimes(1)

    // 释放首个请求成功返回
    await act(async () => {
      releaseFirst(acceptedResponse(currentThread))
    })
    await waitFor(() => expect(result.current.pending).toBe(false))

    // 首请求完成后，新请求可以正常发送
    await act(async () => {
      await result.current.submitMessage()
    })
    expect(harnessService.acceptThreadCommandBatch).toHaveBeenCalledTimes(2)
  })

  it('keeps the exact cursor-free request in the unknown-outcome sidecar', async () => {
    vi.mocked(harnessService.acceptThreadCommandBatch).mockRejectedValueOnce(
      new TypeError('Network drop'),
    )
    const currentThread = threadFixture()
    const base = branchDraftFromThread(currentThread)
    const { result } = renderHook(
      () => useTestController(currentThread.threadId, [], buildBatchFor(currentThread, base, base)),
      { wrapper },
    )
    await waitFor(() => expect(result.current.disabled).toBe(false))

    act(() => result.current.setDraft([createTextPart('unknown request')]))
    await act(async () => {
      await result.current.submitMessage()
    })

    expect(result.current.pendingMessage?.unknownOutcome).toBe(true)
    expect(result.current.pendingMessage?.request).toEqual({
      commands: [expect.objectContaining({ type: 'USER_MESSAGE' })],
    })
  })

  it('persists edit drafts to the thread record and skips persistence for history sources', async () => {
    const currentThread = threadFixture()
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(snapshotOf(currentThread))
    const { result } = renderHook(() => useTestController(currentThread.threadId), {
      wrapper,
    })
    await waitFor(() => expect(result.current.disabled).toBe(false))
    act(() => result.current.setDraft([createTextPart('edit draft')]))
    // edit 来源写入该 Thread 的持久记录（IndexedDB 事务）。
    await waitFor(async () =>
      expect(partsToText((await loadThreadDraft(THREAD_ID))?.parts ?? [])).toBe('edit draft'),
    )
    // history 来源（上下键浏览）不写记录：持久值原样保留。
    act(() => result.current.setDraft([createTextPart('history draft')], 'history'))
    expect(partsToText(result.current.draft)).toBe('history draft')
    await waitFor(async () =>
      expect(partsToText((await loadThreadDraft(THREAD_ID))?.parts ?? [])).toBe('edit draft'),
    )
  })

  it('never adds owner/target or cursors to a pure bound request', () => {
    const currentThread = threadFixture()
    const base = branchDraftFromThread(currentThread)
    const messagePlan = buildMessageBatchPlan({
      thread: currentThread,
      effectiveBase: base,
      draft: base,
      parts: [createTextPart('hello')],
      createCommandId: () => 'message-id',
    })
    expect(messagePlan.request).not.toHaveProperty('owner')
    expect(messagePlan.request).not.toHaveProperty('target')
    expect(messagePlan.request).not.toHaveProperty('expectedHeadEntryId')
    expect(messagePlan.request).not.toHaveProperty('expectedNextCommandSequence')
  })

  it('runs manual compaction with the snapshot version and clears conflict on success', async () => {
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(
      snapshotOf(threadFixture()),
    )
    vi.mocked(harnessService.compactThread).mockResolvedValue({
      thread: threadFixture({ version: '1' }),
      turnStartEntryId: 't1',
      childThreadId: null,
    })
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    const invalidateSpy = vi.spyOn(client, 'invalidateQueries')
    const { result } = renderHook(() => useTestController(THREAD_ID), {
      wrapper: clientWrapper(client),
    })
    await waitFor(() => expect(result.current.disabled).toBe(false))
    await act(async () => {
      await result.current.compactThread()
    })
    expect(harnessService.compactThread).toHaveBeenCalledWith(THREAD_ID, {
      expectedVersion: '0',
    })
    expect(invalidateSpy).toHaveBeenCalledWith(
      expect.objectContaining({ queryKey: ['threads', 'snapshot', THREAD_ID] }),
    )
    expect(result.current.actionError).toBeNull()
    invalidateSpy.mockRestore()
  })

  it('reports compaction unavailability and surfaces 409/non-conflict failures distinctly', async () => {
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(
      snapshotOf(threadFixture()),
    )
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    const invalidateSpy = vi.spyOn(client, 'invalidateQueries')
    // 409：conflict 展示 + 目标 snapshot 失效；draft/错误通道不受影响。
    vi.mocked(harnessService.compactThread).mockRejectedValueOnce(
      new ApiError('stale compact version', 409, 'CONFLICT', { reason: 'STALE_VERSION' }),
    )
    const { result } = renderHook(() => useTestController(THREAD_ID), {
      wrapper: clientWrapper(client),
    })
    await waitFor(() => expect(result.current.disabled).toBe(false))
    await act(async () => {
      await result.current.compactThread()
    })
    expect(result.current.conflict?.reason).toBe('STALE_VERSION')
    expect(result.current.actionError).toBeNull()
    expect(invalidateSpy).toHaveBeenCalledWith(
      expect.objectContaining({ queryKey: ['threads', 'snapshot', THREAD_ID] }),
    )
    act(() => result.current.dismissConflict())
    expect(result.current.conflict).toBeNull()
    // 非 conflict 失败：actionError 通道。
    vi.mocked(harnessService.compactThread).mockRejectedValueOnce(new Error('compact boom'))
    await act(async () => {
      await result.current.compactThread()
    })
    expect(result.current.actionError).toContain('compact boom')
    invalidateSpy.mockRestore()
  })

  it('submits compaction with expectedVersion guarded by server authority', async () => {
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(
      snapshotOf(threadFixture({ version: '7' })),
    )
    vi.mocked(harnessService.compactThread).mockResolvedValueOnce({
      thread: threadFixture({ version: '8' }),
      turnStartEntryId: 'turn-8',
      childThreadId: null,
    })
    const { result } = renderHook(() => useTestController(THREAD_ID), { wrapper })
    await waitFor(() => expect(result.current.disabled).toBe(false))
    await act(async () => {
      await result.current.compactThread()
    })
    expect(harnessService.compactThread).toHaveBeenCalledWith(THREAD_ID, {
      expectedVersion: '7',
    })
  })

  it('routes runCommand to stop/compact and reports unknown commands', async () => {
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(
      snapshotOf(threadFixture()),
    )
    vi.mocked(harnessService.compactThread).mockResolvedValue({
      thread: threadFixture(),
      turnStartEntryId: 't1',
      childThreadId: null,
    })
    const { result } = renderHook(() => useTestController(THREAD_ID), { wrapper })
    await waitFor(() => expect(result.current.disabled).toBe(false))
    act(() => result.current.runCommand({ id: 'stop', label: 'stop', description: 'stop' }))
    await waitFor(() => expect(harnessService.stopThread).toHaveBeenCalledTimes(1))
    act(() => result.current.runCommand({ id: 'compact', label: 'compact', description: 'compact' }))
    await waitFor(() => expect(harnessService.compactThread).toHaveBeenCalledTimes(1))
    // 未接管的命令 id 必须给出明确错误，而不是静默。
    act(() => result.current.runCommand({ id: 'agent', label: 'agent', description: 'agent' }))
    expect(result.current.actionError).toContain('agent')
  })

  it('guards a late stop success after rebind: invalidates the old thread but never mutates the new panel', async () => {
    let releaseStop: ((result: HarnessThreadStopResultDTO) => void) | null = null
    vi.mocked(harnessService.stopThread).mockImplementation(
      () =>
        new Promise<HarnessThreadStopResultDTO>((resolve) => {
          releaseStop = resolve
        }),
    )
    vi.mocked(harnessService.getThreadSnapshot).mockImplementation((threadId) =>
      Promise.resolve(snapshotOf(threadFixture({ threadId }))),
    )
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    const invalidateSpy = vi.spyOn(client, 'invalidateQueries')
    const { result, rerender } = renderHook(
      ({ tid }: { tid: string }) => useTestController(tid),
      { wrapper: clientWrapper(client), initialProps: { tid: THREAD_ID } },
    )
    await waitFor(() => expect(result.current.disabled).toBe(false))
    await act(async () => {
      void result.current.stopThread()
    })
    expect(result.current.stopReplayPending).toBe(true)
    rerender({ tid: THREAD_ID_2 })
    await act(async () => {
      releaseStop?.({
        status: 'STOPPED',
        thread: threadFixture({ threadId: THREAD_ID }),
        stoppedThreads: [],
      } as HarnessThreadStopResultDTO)
    })
    // 旧 Thread 的 snapshot 与 chats 都被失效；旧 sidecar 保留供返回后精确回放。
    expect(invalidateSpy).toHaveBeenCalledWith(
      expect.objectContaining({ queryKey: ['threads', 'snapshot', THREAD_ID] }),
    )
    expect(invalidateSpy).toHaveBeenCalledWith(expect.objectContaining({ queryKey: ['chats'] }))
    expect(localStorage.getItem(pendingStopStorageKey(THREAD_ID))).not.toBeNull()
    // 新面板未受影响：无错误、无 cancelled 消息、无 replay 阻塞。
    expect(result.current.actionError).toBeNull()
    expect(partsToText(result.current.draft)).toBe('')
    expect(result.current.stopReplayPending).toBe(false)
    invalidateSpy.mockRestore()
  })

  it('fences a late stop failure after rebind: known 409 clears the old sidecar without touching the new panel', async () => {
    let rejectStop: ((error: unknown) => void) | null = null
    vi.mocked(harnessService.stopThread).mockImplementation(
      () =>
        new Promise<HarnessThreadStopResultDTO>((_, reject) => {
          rejectStop = reject
        }),
    )
    vi.mocked(harnessService.getThreadSnapshot).mockImplementation((threadId) =>
      Promise.resolve(snapshotOf(threadFixture({ threadId }))),
    )
    const { result, rerender } = renderHook(
      ({ tid }: { tid: string }) => useTestController(tid),
      { wrapper, initialProps: { tid: THREAD_ID } },
    )
    await waitFor(() => expect(result.current.disabled).toBe(false))
    await act(async () => {
      void result.current.stopThread()
    })
    expect(localStorage.getItem(pendingStopStorageKey(THREAD_ID))).not.toBeNull()
    rerender({ tid: THREAD_ID_2 })
    await act(async () => {
      rejectStop?.(new ApiError('stale version', 409))
    })
    // 已知 409：旧 sidecar 被清除；迟到的错误不污染新面板。
    expect(localStorage.getItem(pendingStopStorageKey(THREAD_ID))).toBeNull()
    expect(result.current.actionError).toBeNull()
    expect(result.current.stopReplayPending).toBe(false)
  })

  it('fences a late non-conflict stop failure after rebind: no error surfaces on the new panel', async () => {
    let rejectStop: ((error: unknown) => void) | null = null
    vi.mocked(harnessService.stopThread).mockImplementation(
      () =>
        new Promise<HarnessThreadStopResultDTO>((_, reject) => {
          rejectStop = reject
        }),
    )
    vi.mocked(harnessService.getThreadSnapshot).mockImplementation((threadId) =>
      Promise.resolve(snapshotOf(threadFixture({ threadId }))),
    )
    const { result, rerender } = renderHook(
      ({ tid }: { tid: string }) => useTestController(tid),
      { wrapper, initialProps: { tid: THREAD_ID } },
    )
    await waitFor(() => expect(result.current.disabled).toBe(false))
    await act(async () => {
      void result.current.stopThread()
    })
    rerender({ tid: THREAD_ID_2 })
    await act(async () => {
      rejectStop?.(new Error('network lost'))
    })
    // 不确定失败保留旧 sidecar 供精确回放；新面板零污染。
    expect(localStorage.getItem(pendingStopStorageKey(THREAD_ID))).not.toBeNull()
    expect(result.current.actionError).toBeNull()
    expect(result.current.stopReplayPending).toBe(false)
  })

  it('treats RUNNING as working and IDLE with STOPPED control as idle', async () => {
    // RUNNING 表示活动未收敛；本例停止快照的活动事实是 IDLE，而非由 STOPPED 推断空闲。
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(
      snapshotOf(threadFixture({ threadId: THREAD_ID, status: 'RUNNING' })),
    )
    const { result } = renderHook(
      () => useTestController(THREAD_ID),
      { wrapper },
    )
    await waitFor(() => expect(result.current.disabled).toBe(false))
    expect(result.current.thread?.status).toBe('RUNNING')
    expect(result.current.working).toBe(true)

    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(
      snapshotOf(
        threadFixture({
          threadId: THREAD_ID_2,
          status: 'IDLE',
          executionControl: 'STOPPED',
        }),
      ),
    )
    const { result: result2 } = renderHook(
      () => useTestController(THREAD_ID_2),
      { wrapper },
    )
    await waitFor(() => expect(result2.current.disabled).toBe(false))
    expect(result2.current.thread?.status).toBe('IDLE')
    expect(result2.current.thread?.executionControl).toBe('STOPPED')
    expect(result2.current.working).toBe(false)
  })

  it('deferred send success: switches to thread B with draft/pending/inflight, late success clears origin sidecar without polluting B', async () => {
    // 测试意图：验证 Thread A 发送在途时切换至 Thread B，Thread B 具有自己的草稿与在途提交；
    // Thread A 发送成功返回后，只清除 Thread A 的持久化 sidecar，绝不清除或污染 Thread B 的草稿、pending 与 in-flight 锁
    const threadA = threadFixture({ threadId: THREAD_ID })
    const threadB = threadFixture({ threadId: THREAD_ID_2 })
    vi.mocked(harnessService.getThreadSnapshot).mockImplementation(async (tid) => {
      return snapshotOf(tid === THREAD_ID ? threadA : threadB)
    })

    const baseA = branchDraftFromThread(threadA)
    const baseB = branchDraftFromThread(threadB)

    let resolveSendA!: () => void
    const sendPromiseA = new Promise<HarnessAcceptedCommandsDTO>((resolve) => {
      resolveSendA = () => resolve(acceptedResponse(threadA))
    })
    let resolveSendB!: () => void
    const sendPromiseB = new Promise<HarnessAcceptedCommandsDTO>((resolve) => {
      resolveSendB = () => resolve(acceptedResponse(threadB))
    })

    vi.mocked(harnessService.acceptThreadCommandBatch).mockImplementation(async (threadId) => {
      if (threadId === THREAD_ID) {
        return sendPromiseA
      }
      return sendPromiseB
    })

    const { result, rerender } = renderHook(
      ({ tid }: { tid: string }) =>
        useTestController(tid, [], (parts) => {
          const curThread = tid === THREAD_ID_2 ? threadB : threadA
          const curBase = tid === THREAD_ID_2 ? baseB : baseA
          return buildBatchFor(curThread, curBase, curBase)(parts)
        }),
      { wrapper, initialProps: { tid: THREAD_ID } },
    )
    await waitFor(() => expect(result.current.disabled).toBe(false))

    // Thread A 提交消息
    act(() => result.current.setDraft([createTextPart('message A')]))
    let submitPromiseA!: Promise<void>
    act(() => {
      submitPromiseA = result.current.submitMessage()
    })
    expect(result.current.pending).toBe(true)
    expect(loadBoundPendingMessage(THREAD_ID)).not.toBeNull()

    // 切换至 Thread B
    rerender({ tid: THREAD_ID_2 })
    await waitFor(() => expect(result.current.thread?.threadId).toBe(THREAD_ID_2))

    // Thread B 不应该继承 Thread A 的 pending 状态
    expect(result.current.pending).toBe(false)

    // Thread B 输入草稿并提交消息
    act(() => result.current.setDraft([createTextPart('draft B')]))
    let submitPromiseB!: Promise<void>
    act(() => {
      submitPromiseB = result.current.submitMessage()
    })
    expect(result.current.pending).toBe(true)
    expect(loadBoundPendingMessage(THREAD_ID_2)).not.toBeNull()

    // 此时 Thread A 的请求迟到返回成功
    await act(async () => {
      resolveSendA()
      await submitPromiseA
    })

    // 关键断言：
    // 1. Thread A 的 sidecar 成功被清理
    expect(loadBoundPendingMessage(THREAD_ID)).toBeNull()
    // 2. Thread B 的 pending 状态与草稿绝未被 Thread A 的迟到成功清除或破坏
    expect(result.current.pending).toBe(true)
    expect(loadBoundPendingMessage(THREAD_ID_2)).not.toBeNull()

    // 解决 Thread B 的请求
    await act(async () => {
      resolveSendB()
      await submitPromiseB
    })
    expect(result.current.pending).toBe(false)
    expect(loadBoundPendingMessage(THREAD_ID_2)).toBeNull()
  })

  it('deferred send definite failure: switches to thread B, late definite failure clears origin sidecar and preserves origin draft without polluting B', async () => {
    // 测试意图：验证 Thread A 发送在途时切换至 Thread B，Thread A 遭遇确切业务失败（400）；
    // 迟到失败应清除 Thread A 的 pending sidecar，并将失败草稿保存在 Thread A 的 storage 中，
    // 绝不在 Thread B 面板上展示错误，也绝不覆盖 Thread B 当前编辑的草稿。
    const threadA = threadFixture({ threadId: THREAD_ID })
    const threadB = threadFixture({ threadId: THREAD_ID_2 })
    vi.mocked(harnessService.getThreadSnapshot).mockImplementation(async (tid) => {
      return snapshotOf(tid === THREAD_ID ? threadA : threadB)
    })

    let rejectSendA!: (err: unknown) => void
    const sendPromiseA = new Promise<HarnessAcceptedCommandsDTO>((_resolve, reject) => {
      rejectSendA = reject
    })

    vi.mocked(harnessService.acceptThreadCommandBatch).mockImplementation(async (threadId) => {
      if (threadId === THREAD_ID) {
        return sendPromiseA
      }
      return acceptedResponse(threadB)
    })

    const { result, rerender } = renderHook(
      ({ tid }: { tid: string }) =>
        useTestController(tid, [], (parts) => {
          const curThread = tid === THREAD_ID_2 ? threadB : threadA
          const curBase = branchDraftFromThread(curThread)
          return buildBatchFor(curThread, curBase, curBase)(parts)
        }),
      { wrapper, initialProps: { tid: THREAD_ID } },
    )
    await waitFor(() => expect(result.current.disabled).toBe(false))

    // Thread A 发送
    act(() => result.current.setDraft([createTextPart('failed msg A')]))
    let submitPromiseA!: Promise<void>
    act(() => {
      submitPromiseA = result.current.submitMessage()
    })

    // 切换至 Thread B
    rerender({ tid: THREAD_ID_2 })
    await waitFor(() => expect(result.current.thread?.threadId).toBe(THREAD_ID_2))

    // 用户在 Thread B 输入新草稿
    act(() => result.current.setDraft([createTextPart('active draft on B')]))

    // Thread A 确切失败（400）
    await act(async () => {
      rejectSendA(new ApiError('message rejected', 400))
      await submitPromiseA.catch(() => undefined)
    })

    // 关键断言：
    // 1. Thread B 界面无错误
    expect(result.current.actionError).toBeNull()
    // 2. Thread B 的草稿仍是 active draft on B，未被 Thread A 失败草稿覆盖
    expect(partsToText(result.current.draft)).toBe('active draft on B')
    // 3. Thread A 的 pending sidecar 被清除
    expect(loadBoundPendingMessage(THREAD_ID)).toBeNull()
    // 4. Thread A 自己的持久记录中安全写回了失败草稿（不污染 Thread B）
    await waitFor(async () =>
      expect(partsToText((await loadThreadDraft(THREAD_ID))?.parts ?? [])).toBe('failed msg A'),
    )
    expect(partsToText((await loadThreadDraft(THREAD_ID_2))?.parts ?? [])).toBe('active draft on B')
  })

  it('deferred send unknown failure: switches to thread B, late unknown failure marks unknown sidecar and does not surface error on B', async () => {
    // 测试意图：验证 Thread A 发送在途时切换至 Thread B，Thread A 遭遇网络异常等未知结果（非确切失败）；
    // 迟到失败应保留 Thread A 的 unknown sidecar 供重试，绝不在 Thread B 暴露错误，也绝不覆盖 Thread B 草稿
    const threadA = threadFixture({ threadId: THREAD_ID })
    const threadB = threadFixture({ threadId: THREAD_ID_2 })
    vi.mocked(harnessService.getThreadSnapshot).mockImplementation(async (tid) => {
      return snapshotOf(tid === THREAD_ID ? threadA : threadB)
    })

    let rejectSendA!: (err: unknown) => void
    const sendPromiseA = new Promise<HarnessAcceptedCommandsDTO>((_resolve, reject) => {
      rejectSendA = reject
    })

    vi.mocked(harnessService.acceptThreadCommandBatch).mockImplementation(async (threadId) => {
      if (threadId === THREAD_ID) {
        return sendPromiseA
      }
      return acceptedResponse(threadB)
    })

    const { result, rerender } = renderHook(
      ({ tid }: { tid: string }) =>
        useTestController(tid, [], (parts) => {
          const curThread = tid === THREAD_ID_2 ? threadB : threadA
          const curBase = branchDraftFromThread(curThread)
          return buildBatchFor(curThread, curBase, curBase)(parts)
        }),
      { wrapper, initialProps: { tid: THREAD_ID } },
    )
    await waitFor(() => expect(result.current.disabled).toBe(false))

    // Thread A 发送
    act(() => result.current.setDraft([createTextPart('unknown msg A')]))
    let submitPromiseA!: Promise<void>
    act(() => {
      submitPromiseA = result.current.submitMessage()
    })

    // 切换至 Thread B
    rerender({ tid: THREAD_ID_2 })
    await waitFor(() => expect(result.current.thread?.threadId).toBe(THREAD_ID_2))
    act(() => result.current.setDraft([createTextPart('B draft text')]))

    // Thread A 网络断开
    await act(async () => {
      rejectSendA(new TypeError('Network lost'))
      await submitPromiseA.catch(() => undefined)
    })

    // 断言：
    // 1. Thread B 无错误
    expect(result.current.actionError).toBeNull()
    // 2. Thread B 草稿完好
    expect(partsToText(result.current.draft)).toBe('B draft text')
    // 3. Thread A sidecar 标记 unknownOutcome
    const pendingA = loadBoundPendingMessage(THREAD_ID)
    expect(pendingA).not.toBeNull()
    expect(pendingA?.unknownOutcome).toBe(true)
  })

  it('deferred send without cursor: switching to thread B never triggers an automatic retry', async () => {
    const threadA = threadFixture({ threadId: THREAD_ID, headEntryId: 'h1', nextCommandSequence: '1' })
    const threadB = threadFixture({ threadId: THREAD_ID_2 })
    vi.mocked(harnessService.getThreadSnapshot).mockImplementation(async (tid) =>
      snapshotOf(tid === THREAD_ID_2 ? threadB : threadA))

    vi.mocked(harnessService.acceptThreadCommandBatch).mockRejectedValueOnce(
      new ApiError('stale cursor', 409, 'CONFLICT', { reason: 'STALE_COMMAND_CURSOR' }),
    )

    const { result, rerender } = renderHook(
      ({ tid }: { tid: string }) => {
        const curThread = tid === THREAD_ID_2 ? threadB : threadA
        const curBase = branchDraftFromThread(curThread)
        return useTestController(tid, [], buildBatchFor(curThread, curBase, curBase))
      },
      { wrapper, initialProps: { tid: THREAD_ID } },
    )
    await waitFor(() => expect(result.current.disabled).toBe(false))

    act(() => result.current.setDraft([createTextPart('retry msg')]))
    let submitPromise!: Promise<void>
    act(() => {
      submitPromise = result.current.submitMessage()
    })
    await waitFor(() => expect(harnessService.acceptThreadCommandBatch).toHaveBeenCalledTimes(1))
    rerender({ tid: THREAD_ID_2 })
    await waitFor(() => expect(result.current.thread?.threadId).toBe(THREAD_ID_2))
    await act(async () => {
      await submitPromise.catch(() => undefined)
    })

    expect(harnessService.acceptThreadCommandBatch).toHaveBeenCalledTimes(1)
    expect(vi.mocked(harnessService.acceptThreadCommandBatch).mock.calls[0]?.[1]).not.toHaveProperty('expectedHeadEntryId')
  })

  it('A -> B -> A switching: stale operation from epoch 0 does not clear or overwrite new state in epoch 2', async () => {
    // 测试意图：验证 A -> B -> A 重新绑定后，epoch 计数递增；来自 epoch 0 的旧迟到操作
    // 绝不清除 epoch 2 的 pendingMessage、绝不覆盖 epoch 2 的新草稿、也绝不释放 epoch 2 的锁
    const threadA = threadFixture({ threadId: THREAD_ID })
    const threadB = threadFixture({ threadId: THREAD_ID_2 })
    vi.mocked(harnessService.getThreadSnapshot).mockImplementation(async (tid) => {
      return snapshotOf(tid === THREAD_ID ? threadA : threadB)
    })

    let resolveSendEpoch0!: () => void
    const sendPromiseEpoch0 = new Promise<HarnessAcceptedCommandsDTO>((resolve) => {
      resolveSendEpoch0 = () => resolve(acceptedResponse(threadA))
    })
    let resolveSendEpoch2!: () => void
    const sendPromiseEpoch2 = new Promise<HarnessAcceptedCommandsDTO>((resolve) => {
      resolveSendEpoch2 = () => resolve(acceptedResponse(threadA))
    })

    let callCount = 0
    vi.mocked(harnessService.acceptThreadCommandBatch).mockImplementation(async () => {
      callCount += 1
      if (callCount === 1) {
        return sendPromiseEpoch0
      }
      return sendPromiseEpoch2
    })

    const { result, rerender } = renderHook(
      ({ tid }: { tid: string }) =>
        useTestController(tid, [], (parts) => {
          const curThread = tid === THREAD_ID_2 ? threadB : threadA
          const curBase = branchDraftFromThread(curThread)
          return buildBatchFor(curThread, curBase, curBase)(parts)
        }),
      { wrapper, initialProps: { tid: THREAD_ID } },
    )
    await waitFor(() => expect(result.current.disabled).toBe(false))

    // epoch 0: Thread A 发送
    act(() => result.current.setDraft([createTextPart('message in epoch 0')]))
    let submitPromiseEpoch0!: Promise<void>
    act(() => {
      submitPromiseEpoch0 = result.current.submitMessage()
    })
    expect(result.current.pending).toBe(true)

    // 切到 Thread B (epoch 1)
    rerender({ tid: THREAD_ID_2 })
    await waitFor(() => expect(result.current.thread?.threadId).toBe(THREAD_ID_2))
    expect(result.current.pending).toBe(false)

    // 切回 Thread A (epoch 2)
    rerender({ tid: THREAD_ID })
    await waitFor(() => expect(result.current.thread?.threadId).toBe(THREAD_ID))

    // 验证：维持既有未决 sidecar，不允许直接发送新消息
    expect(result.current.replayPending).toBe(true)
    act(() => result.current.setDraft([createTextPart('blocked message')]))
    await act(async () => {
      await result.current.submitMessage()
    })
    expect(result.current.actionError).not.toBeNull()

    // 显式放弃未决消息，然后发起 epoch 2 的新请求
    act(() => result.current.abandonPendingMessage())
    expect(result.current.replayPending).toBe(false)

    act(() => result.current.setDraft([createTextPart('message in epoch 2')]))
    let submitPromiseEpoch2!: Promise<void>
    act(() => {
      submitPromiseEpoch2 = result.current.submitMessage()
    })
    expect(result.current.pending).toBe(true)
    const newSidecar = loadBoundPendingMessage(THREAD_ID)
    expect(newSidecar).not.toBeNull()
    expect(partsToText(newSidecar?.localDraft ?? [])).toBe('message in epoch 2')

    // 真实 deferred 旧结果与新请求交叠：来自 epoch 0 的请求迟到返回
    await act(async () => {
      resolveSendEpoch0()
      await submitPromiseEpoch0
    })

    // 关键断言：
    // 1. 旧 operation 成功绝不能清除 epoch 2 的新 sidecar (storage 清理比较了 request identity)
    const preservedSidecar = loadBoundPendingMessage(THREAD_ID)
    expect(preservedSidecar).not.toBeNull()
    expect(partsToText(preservedSidecar?.localDraft ?? [])).toBe('message in epoch 2')
    // 2. 旧 operation 的 finally 绝不能释放 epoch 2 的新 lock 或误扣减 counter
    expect(result.current.pending).toBe(true)
    expect(result.current.replayPending).toBe(true)

    // 结束 epoch 2 的请求
    await act(async () => {
      resolveSendEpoch2()
      await submitPromiseEpoch2
    })
    expect(result.current.pending).toBe(false)
    expect(loadBoundPendingMessage(THREAD_ID)).toBeNull()
  })

  it('A -> B -> A switching: stale failed operation does not overwrite new sidecar or new stored draft', async () => {
    // 测试意图：验证 A -> B -> A 切回后发出新请求，来自旧 epoch 0 的失败操作（无论 definite 还是 unknown）
    // 绝不覆盖同 Thread 当前已存在的新 sidecar，也绝不覆盖同 Thread 正在编辑的新草稿
    const threadA = threadFixture({ threadId: THREAD_ID })
    const threadB = threadFixture({ threadId: THREAD_ID_2 })
    vi.mocked(harnessService.getThreadSnapshot).mockImplementation(async (tid) => {
      return snapshotOf(tid === THREAD_ID ? threadA : threadB)
    })

    let rejectSendEpoch0!: (err: unknown) => void
    const sendPromiseEpoch0 = new Promise<HarnessAcceptedCommandsDTO>((_resolve, reject) => {
      rejectSendEpoch0 = reject
    })
    let resolveSendEpoch2!: () => void
    const sendPromiseEpoch2 = new Promise<HarnessAcceptedCommandsDTO>((resolve) => {
      resolveSendEpoch2 = () => resolve(acceptedResponse(threadA))
    })

    let callCount = 0
    vi.mocked(harnessService.acceptThreadCommandBatch).mockImplementation(async () => {
      callCount += 1
      if (callCount === 1) {
        return sendPromiseEpoch0
      }
      return sendPromiseEpoch2
    })

    const { result, rerender } = renderHook(
      ({ tid }: { tid: string }) =>
        useTestController(tid, [], (parts) => {
          const curThread = tid === THREAD_ID_2 ? threadB : threadA
          const curBase = branchDraftFromThread(curThread)
          return buildBatchFor(curThread, curBase, curBase)(parts)
        }),
      { wrapper, initialProps: { tid: THREAD_ID } },
    )
    await waitFor(() => expect(result.current.disabled).toBe(false))

    // epoch 0 发送
    act(() => result.current.setDraft([createTextPart('old message in epoch 0')]))
    let submitPromiseEpoch0!: Promise<void>
    act(() => {
      submitPromiseEpoch0 = result.current.submitMessage()
    })

    // 切到 Thread B 再切回 Thread A
    rerender({ tid: THREAD_ID_2 })
    await waitFor(() => expect(result.current.thread?.threadId).toBe(THREAD_ID_2))
    rerender({ tid: THREAD_ID })
    await waitFor(() => expect(result.current.thread?.threadId).toBe(THREAD_ID))

    // 放弃旧未决并发送 epoch 2 新请求
    act(() => result.current.abandonPendingMessage())
    act(() => result.current.setDraft([createTextPart('new message in epoch 2')]))
    let submitPromiseEpoch2!: Promise<void>
    act(() => {
      submitPromiseEpoch2 = result.current.submitMessage()
    })

    // 此时 epoch 0 遭遇未知异常（网络断开）失败
    await act(async () => {
      rejectSendEpoch0(new TypeError('network failed'))
      await submitPromiseEpoch0.catch(() => undefined)
    })

    // 关键断言：
    // 1. 旧 unknown 失败绝不覆盖同 Thread 正在在途的新 sidecar
    const storedSidecar = loadBoundPendingMessage(THREAD_ID)
    expect(storedSidecar).not.toBeNull()
    expect(partsToText(storedSidecar?.localDraft ?? [])).toBe('new message in epoch 2')
    // 2. 当前 UI 内存中的新在途 pending 依然处于正常在途状态，未被旧 failure 污染
    expect(result.current.pendingMessage?.unknownOutcome).toBe(false)
    expect(partsToText(result.current.pendingMessage?.localDraft ?? [])).toBe('new message in epoch 2')
    // 3. 新在途操作不受旧 failure 影响，lock 未被释放
    expect(result.current.pending).toBe(true)

    // 解决 epoch 2
    await act(async () => {
      resolveSendEpoch2()
      await submitPromiseEpoch2
    })
    expect(result.current.pending).toBe(false)
    expect(loadBoundPendingMessage(THREAD_ID)).toBeNull()
  })

  it('deferred approval and compaction: late responses after rebind do not pollute new panel state', async () => {
    // 测试意图：验证 Thread A 发起 approval 或 compaction 操作后切换至 Thread B，
    // 迟到的失败或成功绝不清除或污染 Thread B 的 conflict 与错误提示
    const threadA = threadFixture({ threadId: THREAD_ID })
    const threadB = threadFixture({ threadId: THREAD_ID_2 })
    vi.mocked(harnessService.getThreadSnapshot).mockImplementation(async (tid) => {
      return snapshotOf(tid === THREAD_ID ? threadA : threadB)
    })

    let rejectApprovalA!: (err: unknown) => void
    const approvalPromiseA = new Promise<ToolInvocationDTO>((_resolve, reject) => {
      rejectApprovalA = reject
    })
    vi.mocked(harnessService.decideApproval).mockImplementation(async () => approvalPromiseA)

    const { result, rerender } = renderHook(
      ({ tid }: { tid: string }) => useTestController(tid),
      { wrapper, initialProps: { tid: THREAD_ID } },
    )
    await waitFor(() => expect(result.current.disabled).toBe(false))

    // Thread A 发起审批
    let approvalAction!: Promise<void>
    act(() => {
      approvalAction = result.current.decideApproval('inv-1', 'ALLOW')
    })

    // 切换至 Thread B
    rerender({ tid: THREAD_ID_2 })
    await waitFor(() => expect(result.current.thread?.threadId).toBe(THREAD_ID_2))

    // Thread A 审批迟到失败
    await act(async () => {
      rejectApprovalA(new Error('approval backend error'))
      await approvalAction
    })

    // 断言 Thread B 绝不受影响
    expect(result.current.actionError).toBeNull()
    expect(result.current.conflict).toBeNull()
  })

  it('A -> B -> A switching: stale compact success from epoch 0 does not clear current conflict in epoch 2', async () => {
    const threadA = threadFixture({ threadId: THREAD_ID })
    const threadB = threadFixture({ threadId: THREAD_ID_2 })
    vi.mocked(harnessService.getThreadSnapshot).mockImplementation(async (tid) => {
      return snapshotOf(tid === THREAD_ID ? threadA : threadB)
    })

    let resolveCompactEpoch0!: (res: HarnessThreadCompactResultDTO) => void
    const compactPromiseEpoch0 = new Promise<HarnessThreadCompactResultDTO>((resolve) => {
      resolveCompactEpoch0 = resolve
    })

    let compactCallCount = 0
    vi.mocked(harnessService.compactThread).mockImplementation(async () => {
      compactCallCount += 1
      if (compactCallCount === 1) {
        return compactPromiseEpoch0
      }
      return {
        thread: threadFixture({ threadId: THREAD_ID, version: '1' }),
        turnStartEntryId: 't1',
        childThreadId: null,
      }
    })

    const { result, rerender } = renderHook(
      ({ tid }: { tid: string }) => useTestController(tid),
      { wrapper, initialProps: { tid: THREAD_ID } },
    )
    await waitFor(() => expect(result.current.disabled).toBe(false))

    // 1. Thread A 在 epoch 0 触发 compactThread
    let compactActionEpoch0!: Promise<void>
    act(() => {
      compactActionEpoch0 = result.current.compactThread()
    })
    await waitFor(() => expect(harnessService.compactThread).toHaveBeenCalledTimes(1))

    // 2. 切换至 Thread B，再切回 Thread A（进入 epoch 2）
    rerender({ tid: THREAD_ID_2 })
    await waitFor(() => expect(result.current.thread?.threadId).toBe(THREAD_ID_2))
    rerender({ tid: THREAD_ID })
    await waitFor(() => expect(result.current.thread?.threadId).toBe(THREAD_ID))

    // 3. 在 epoch 2 制造一个 conflict
    vi.mocked(harnessService.decideApproval).mockRejectedValueOnce(
      new ApiError('stale conflict', 409, 'CONFLICT', { reason: 'STALE_VERSION' }),
    )
    await act(async () => {
      await result.current.decideApproval('inv-conflict', 'ALLOW').catch(() => undefined)
    })
    expect(result.current.conflict?.reason).toBe('STALE_VERSION')

    // 4. 此时 epoch 0 的 compact 请求迟到成功返回
    await act(async () => {
      resolveCompactEpoch0({
        thread: threadFixture({ threadId: THREAD_ID, version: '1' }),
        turnStartEntryId: 't1',
        childThreadId: null,
      })
      await compactActionEpoch0
    })

    // 5. 关键断言：旧 epoch 0 的 compact success 绝不能清除 epoch 2 当前的 conflict
    expect(result.current.conflict?.reason).toBe('STALE_VERSION')
  })

  it('A -> B -> A switching: stale approval success from epoch 0 does not delete decisionId for epoch 2, so epoch 2 retry retains decisionId2', async () => {
    const threadA = threadFixture({ threadId: THREAD_ID })
    const threadB = threadFixture({ threadId: THREAD_ID_2 })
    vi.mocked(harnessService.getThreadSnapshot).mockImplementation(async (tid) => {
      return snapshotOf(tid === THREAD_ID ? threadA : threadB)
    })

    let resolveApprovalEpoch0!: (res: ToolInvocationDTO) => void
    const approvalPromiseEpoch0 = new Promise<ToolInvocationDTO>((resolve) => {
      resolveApprovalEpoch0 = resolve
    })

    const decisionIdsPassed: string[] = []
    let callIndex = 0
    vi.mocked(harnessService.decideApproval).mockImplementation(async (_tid, _invId, body) => {
      callIndex += 1
      decisionIdsPassed.push(body.decisionId)
      if (callIndex === 1) {
        return approvalPromiseEpoch0
      }
      if (callIndex === 2) {
        throw new TypeError('network failed in epoch 2')
      }
      return toolInvocationFixture({
        id: 'inv-1',
        toolName: 'test',
        rendererKey: 'test',
        approvalJson: JSON.stringify({ decision: 'ALLOW', decisionId: body.decisionId, reason: null }),
      })
    })

    const { result, rerender } = renderHook(
      ({ tid }: { tid: string }) => useTestController(tid),
      { wrapper, initialProps: { tid: THREAD_ID } },
    )
    await waitFor(() => expect(result.current.disabled).toBe(false))

    // 1. Thread A 在 epoch 0 发起审批
    let approvalActionEpoch0!: Promise<void>
    act(() => {
      approvalActionEpoch0 = result.current.decideApproval('inv-1', 'ALLOW')
    })
    await waitFor(() => expect(decisionIdsPassed).toHaveLength(1))
    const decisionIdEpoch0 = decisionIdsPassed[0]

    // 2. 切换至 Thread B，再切回 Thread A（进入 epoch 2）
    rerender({ tid: THREAD_ID_2 })
    await waitFor(() => expect(result.current.thread?.threadId).toBe(THREAD_ID_2))
    rerender({ tid: THREAD_ID })
    await waitFor(() => expect(result.current.thread?.threadId).toBe(THREAD_ID))

    // 3. 在 epoch 2 重新发起针对相同 invocation 的审批，遇到网络 unknown 失败
    await act(async () => {
      await result.current.decideApproval('inv-1', 'ALLOW').catch(() => undefined)
    })
    expect(decisionIdsPassed).toHaveLength(2)
    const decisionIdEpoch2 = decisionIdsPassed[1]
    expect(decisionIdEpoch2).not.toBe(decisionIdEpoch0)

    // 4. 此时 epoch 0 的旧审批成功返回
    await act(async () => {
      resolveApprovalEpoch0(
        toolInvocationFixture({
          id: 'inv-1',
          toolName: 'test',
          rendererKey: 'test',
          approvalJson: JSON.stringify({ decision: 'ALLOW', decisionId: decisionIdEpoch0, reason: null }),
        }),
      )
      await approvalActionEpoch0
    })

    // 5. 在 epoch 2 重试该审批
    await act(async () => {
      await result.current.decideApproval('inv-1', 'ALLOW')
    })
    expect(decisionIdsPassed).toHaveLength(3)
    // 关键断言：epoch 2 重试仍然使用 decisionIdEpoch2，没有因为旧 success 误删导致生成新的 decisionId
    expect(decisionIdsPassed[2]).toBe(decisionIdEpoch2)
  })
})

/**
 * 草稿恢复与回执合并的行为契约。
 *
 * IndexedDB 事务时机与失败无法在 jsdom 中确定性地延迟或失败，因此这些用例逐个
 * 覆盖存储边界 mock；草稿保存、快照与 Stop 流程仍走真实实现。每个用例开始时
 * mock 都已重置回真实实现（resetControllerTestEnvironment），覆盖不会泄漏。
 */
describe('useAgentThreadController draft restore', () => {
  beforeEach(resetControllerTestEnvironment)

  it('never writes a late receipt merge into a thread bound after the Stop started', async () => {
    const gate = deferred<StopReceiptMergeResult>()
    applyStopReceiptMock.mockReturnValue(gate.promise)
    vi.mocked(harnessService.stopThread).mockResolvedValue({
      status: 'STOPPED',
      thread: threadFixture(),
      stoppedThreads: [stopReceipt(THREAD_ID, 'stop-late', [cancelledUserMessage(1, 'cancelled')])],
    } as HarnessThreadStopResultDTO)

    const { result, rerender } = renderHook(
      ({ threadId }: { threadId: string }) => useTestController(threadId),
      { initialProps: { threadId: THREAD_ID }, wrapper },
    )
    await waitFor(() => expect(result.current.disabled).toBe(false))
    act(() => result.current.setDraft([createTextPart('draft on A')]))

    let stopPromise: Promise<void> | undefined
    act(() => {
      stopPromise = result.current.stopThread()
    })
    await waitFor(() => expect(applyStopReceiptMock).toHaveBeenCalledTimes(1))

    // Stop 响应已返回、回执合并仍在途时重绑到另一个 Thread 并输入自己的草稿。
    rerender({ threadId: THREAD_ID_2 })
    await waitFor(() => expect(result.current.disabled).toBe(false))
    act(() => result.current.setDraft([createTextPart('draft on B')]))

    await act(async () => {
      gate.resolve(recordOf(THREAD_ID, [createTextPart('cancelled')], ['stop-late']))
      await stopPromise
    })

    // 旧 Thread 的合并结果绝不写进新 Thread 的编辑区。
    expect(partsToText(result.current.draft)).toBe('draft on B')
    expect(await readDraftText(THREAD_ID_2)).toBe('draft on B')
    expect(await readDraftText(THREAD_ID)).toBe('draft on A')
  })

  it('keeps the newly bound thread stop marker when the previous thread stop completes late', async () => {
    // 测试意图：A 的 Stop 已成功但回执合并仍挂起时宿主强制切到 B，B 立刻发起自己的 Stop
    // 且结果未知（保留 pending 与 sidecar）。A 的 late completion 只能清理 A 自己的 sidecar，
    // 绝不能让 B 看起来「没有未决 Stop」，否则 B 的下一次 Stop 会铸新 id 而不是精确重放。
    const gate = deferred<StopReceiptMergeResult>()
    applyStopReceiptMock.mockReturnValue(gate.promise)
    vi.mocked(harnessService.stopThread)
      // A：真实响应已返回，回执合并被 gate 扣住
      .mockResolvedValueOnce({
        status: 'STOPPED',
        thread: threadFixture(),
        stoppedThreads: [stopReceipt(THREAD_ID, 'stop-a', [cancelledUserMessage(1, 'cancelled on A')])],
      } as HarnessThreadStopResultDTO)
      // B：网络未知失败，pending 与 sidecar 必须保留
      .mockRejectedValueOnce(new Error('network connection lost'))

    const { result, rerender } = renderHook(
      ({ threadId }: { threadId: string }) => useTestController(threadId),
      { initialProps: { threadId: THREAD_ID }, wrapper },
    )
    await waitFor(() => expect(result.current.disabled).toBe(false))

    let stopOnA: Promise<void> | undefined
    act(() => {
      stopOnA = result.current.stopThread()
    })
    await waitFor(() => expect(applyStopReceiptMock).toHaveBeenCalledTimes(1))

    // 宿主强制重绑到 B：B 的 snapshot 与 A 的 basis 不同，A 的待决操作在这里退役。
    rerender({ threadId: THREAD_ID_2 })
    await waitFor(() => expect(result.current.disabled).toBe(false))

    // B 发起自己的 Stop，结果未知：pending 与 sidecar 保留下来供精确重放。
    await act(async () => {
      await result.current.stopThread()
    })
    await waitFor(() => expect(result.current.stopReplayPending).toBe(true))
    const pendingOnB = loadPendingStop(THREAD_ID_2)
    expect(pendingOnB).not.toBeNull()

    // 放行 A 的迟到回执合并：只能清理 A 自己的 sidecar 与状态，不得触碰 B 的待决标记。
    await act(async () => {
      gate.resolve(recordOf(THREAD_ID, [createTextPart('cancelled on A')], ['stop-a']))
      await stopOnA
    })

    expect(result.current.stopReplayPending).toBe(true)
    expect(loadPendingStop(THREAD_ID_2)).toEqual(pendingOnB)
    // A 自己的未知操作本来就不存在（它已成功），其 sidecar 保持为空。
    expect(loadPendingStop(THREAD_ID)).toBeNull()
  })

  it('keeps a newer user edit when the receipt merge resolves late and still persists both', async () => {
    const gate = deferred<StopReceiptMergeResult>()
    applyStopReceiptMock.mockReturnValue(gate.promise)
    vi.mocked(harnessService.stopThread).mockResolvedValue({
      status: 'STOPPED',
      thread: threadFixture(),
      stoppedThreads: [stopReceipt(THREAD_ID, 'stop-edit', [cancelledUserMessage(1, 'cancelled')])],
    } as HarnessThreadStopResultDTO)

    const { result } = renderHook(() => useTestController(THREAD_ID), { wrapper })
    await waitFor(() => expect(result.current.disabled).toBe(false))
    act(() => result.current.setDraft([createTextPart('draft before stop')]))

    let stopPromise: Promise<void> | undefined
    act(() => {
      stopPromise = result.current.stopThread()
    })
    await waitFor(() => expect(applyStopReceiptMock).toHaveBeenCalledTimes(1))

    // 合并入队之后用户继续编辑：迟到的合并结果不得覆盖这段新输入。
    act(() => result.current.setDraft([createTextPart('newer user edit')]))

    await act(async () => {
      gate.resolve(recordOf(THREAD_ID, [createTextPart('cancelled\n\n')], ['stop-edit']))
      await stopPromise
    })

    expect(partsToText(result.current.draft)).toBe('cancelled\n\nnewer user edit')
    await waitFor(async () => {
      expect(await readDraftText(THREAD_ID)).toBe('cancelled\n\nnewer user edit')
    })
  })

  it('aligns the editor with the record of a receipt already merged by another tab', async () => {
    // 另一个标签页已完成合并：这里只能拿到既有记录，仍以记录对齐，且同一回执集合只处理一次。
    applyStopReceiptMock.mockResolvedValue(
      recordOf(THREAD_ID, [createTextPart('cancelled once\n\nother tab draft')], ['stop-shared']),
    )
    const receipt = stopReceipt(THREAD_ID, 'stop-shared', [cancelledUserMessage(1, 'cancelled once')])
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(
      snapshotOf(threadFixture(), { stopReceipts: [receipt] }),
    )

    const { result, rerender } = renderHook(() => useTestController(THREAD_ID), { wrapper })
    await waitFor(() => expect(result.current.disabled).toBe(false))
    expect(partsToText(result.current.draft)).toBe('cancelled once\n\nother tab draft')

    rerender()
    await act(async () => {
      await Promise.resolve()
    })
    expect(applyStopReceiptMock).toHaveBeenCalledTimes(1)
  })

  it('neither reverts nor duplicates an editor edit when the same receipt arrives twice', async () => {
    // Stop 响应与快照双通道投递同一回执：第二次投递在用户继续编辑之后才落地，
    // 既不得把编辑区回退到记录里的旧内容，也不得重复追加回填文本。
    const receipt = stopReceipt(THREAD_ID, 'stop-twice', [cancelledUserMessage(1, 'cancelled')])
    // 首次快照（挂载）没有回执；Stop 之后的重新取数才带上它。
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValueOnce(snapshotOf(threadFixture()))
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(
      snapshotOf(threadFixture(), { stopReceipts: [receipt] }),
    )
    vi.mocked(harnessService.stopThread).mockResolvedValue({
      status: 'STOPPED',
      thread: threadFixture(),
      stoppedThreads: [receipt],
    } as HarnessThreadStopResultDTO)
    const secondDelivery = deferred<ThreadDraftRecord>()
    applyStopReceiptMock
      // Stop 响应通道：本次事务完成合并，记录包含回填内容与当时的草稿。
      .mockResolvedValueOnce(
        recordOf(THREAD_ID, [createTextPart('cancelled\n\ndraft before stop')], ['stop-twice']),
      )
      // 快照通道：重复投递（在途）。
      .mockReturnValueOnce(secondDelivery.promise)

    const { result } = renderHook(() => useTestController(THREAD_ID), { wrapper })
    await waitFor(() => expect(result.current.disabled).toBe(false))
    act(() => result.current.setDraft([createTextPart('draft before stop')]))

    await act(async () => {
      await result.current.stopThread()
    })
    expect(partsToText(result.current.draft)).toBe('cancelled\n\ndraft before stop')

    await waitFor(() => expect(applyStopReceiptMock).toHaveBeenCalledTimes(2))
    // 重复投递仍在途时用户继续编辑。
    act(() => result.current.setDraft([
      createTextPart('cancelled\n\ndraft before stop\n\nnewer edit'),
    ]))

    await act(async () => {
      // 重复投递返回的是合并当时的记录（不含随后编辑）。
      secondDelivery.resolve(
        recordOf(THREAD_ID, [createTextPart('cancelled\n\ndraft before stop')], ['stop-twice']),
      )
      await Promise.resolve()
    })

    const text = partsToText(result.current.draft)
    expect(text).toBe('cancelled\n\ndraft before stop\n\nnewer edit')
    expect(text.match(/cancelled/g) ?? []).toHaveLength(1)
  })

  it('surfaces a failed receipt merge, keeps the receipt, and restores it on manual retry', async () => {
    applyStopReceiptMock.mockRejectedValueOnce(new Error('IndexedDB transaction failed'))
    vi.mocked(harnessService.stopThread).mockResolvedValue({
      status: 'STOPPED',
      thread: threadFixture(),
      stoppedThreads: [
        stopReceipt(THREAD_ID, 'stop-retry', [cancelledUserMessage(1, 'cancelled once')]),
      ],
    } as HarnessThreadStopResultDTO)

    const { result } = renderHook(() => useTestController(THREAD_ID), { wrapper })
    await waitFor(() => expect(result.current.disabled).toBe(false))
    act(() => result.current.setDraft([createTextPart('draft before stop')]))

    await act(async () => {
      await result.current.stopThread()
    })

    // Stop 已完成（不是 stopFailed），只有草稿恢复失败对用户可见。
    expect(result.current.pendingMessage).toBeNull()
    expect(result.current.actionError).toBeNull()
    expect(result.current.draftRestoreError).toBe(t('ai.runtime.action.draftRestoreFailed'))
    expect(partsToText(result.current.draft)).toBe('draft before stop')

    applyStopReceiptMock.mockResolvedValue(
      recordOf(THREAD_ID, [createTextPart('cancelled once\n\n')], ['stop-retry']),
    )
    await act(async () => {
      result.current.retryDraftRestore()
    })
    await waitFor(() => expect(result.current.draftRestoreError).toBeNull())
    expect(applyStopReceiptMock).toHaveBeenCalledTimes(2)
    expect(partsToText(result.current.draft)).toBe('cancelled once\n\n')
  })

  it('does not auto-retry a failed snapshot receipt but allows another manual retry', async () => {
    applyStopReceiptMock.mockRejectedValue(new Error('IndexedDB transaction failed'))
    const receipt = stopReceipt(THREAD_ID, 'stop-auto', [cancelledUserMessage(1, 'cancelled once')])
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(
      snapshotOf(threadFixture(), { stopReceipts: [receipt] }),
    )

    const { result, rerender } = renderHook(() => useTestController(THREAD_ID), { wrapper })
    await waitFor(() =>
      expect(result.current.draftRestoreError).toBe(t('ai.runtime.action.draftRestoreFailed')))
    expect(applyStopReceiptMock).toHaveBeenCalledTimes(1)

    rerender()
    rerender()
    await act(async () => {
      await Promise.resolve()
    })
    expect(applyStopReceiptMock).toHaveBeenCalledTimes(1)

    applyStopReceiptMock.mockResolvedValue(
      recordOf(THREAD_ID, [createTextPart('cancelled once')], ['stop-auto']),
    )
    await act(async () => {
      result.current.retryDraftRestore()
    })
    await waitFor(() => expect(result.current.draftRestoreError).toBeNull())
    expect(applyStopReceiptMock).toHaveBeenCalledTimes(2)
    expect(partsToText(result.current.draft)).toBe('cancelled once')
  })

  it('ignores a stale-save completion that lands after the binding moved to another thread', async () => {
    // 旧 Thread 的在途整份写入随后被判陈旧：绝不改新 Thread 的 refs、提示与回执收尾。
    const gate = deferred<DraftSaveOutcome>()
    saveThreadDraftPartsMock.mockReturnValueOnce(gate.promise)

    const { result, rerender } = renderHook(
      ({ threadId }: { threadId: string }) => useTestController(threadId),
      { initialProps: { threadId: THREAD_ID }, wrapper },
    )
    await waitFor(() => expect(result.current.disabled).toBe(false))
    act(() => result.current.setDraft([createTextPart('old thread edit')]))

    rerender({ threadId: THREAD_ID_2 })
    await waitFor(() => expect(result.current.disabled).toBe(false))
    act(() => result.current.setDraft([createTextPart('new thread edit')]))

    await act(async () => {
      gate.resolve({
        status: 'STALE',
        record: recordOf(THREAD_ID, [createTextPart('cancelled\n\n')], ['stop-old']),
      })
      await Promise.resolve()
    })

    expect(result.current.draftRestoreError).toBeNull()
    expect(result.current.actionError).toBeNull()
    expect(partsToText(result.current.draft)).toBe('new thread edit')
  })

  it('ignores a late save/failure and a late receipt failure from the previous binding', async () => {
    // 旧 Thread 的 goal 写入失败与回执合并失败都晚于重绑：新 Thread 不得出现任何错误提示或回执收尾。
    const saveGate = deferred<DraftSaveOutcome>()
    const receiptGate = deferred<ThreadDraftRecord>()
    saveThreadGoalTextMock.mockReturnValueOnce(saveGate.promise)
    applyStopReceiptMock.mockReturnValueOnce(receiptGate.promise)
    const receipt = stopReceipt(THREAD_ID, 'stop-late-fail', [cancelledUserMessage(1, 'cancelled')])
    vi.mocked(harnessService.stopThread).mockResolvedValue({
      status: 'STOPPED',
      thread: threadFixture(),
      stoppedThreads: [receipt],
    } as HarnessThreadStopResultDTO)

    const { result, rerender } = renderHook(
      ({ threadId }: { threadId: string }) => useTestController(threadId),
      { initialProps: { threadId: THREAD_ID }, wrapper },
    )
    await waitFor(() => expect(result.current.disabled).toBe(false))
    act(() => result.current.setGoalDraft('old thread goal'))
    let stopPromise: Promise<void> | undefined
    act(() => {
      stopPromise = result.current.stopThread()
    })
    await waitFor(() => expect(applyStopReceiptMock).toHaveBeenCalledTimes(1))

    rerender({ threadId: THREAD_ID_2 })
    await waitFor(() => expect(result.current.disabled).toBe(false))

    await act(async () => {
      saveGate.reject(new Error('quota exceeded'))
      receiptGate.reject(new Error('IndexedDB transaction failed'))
      await stopPromise
    })

    expect(result.current.actionError).toBeNull()
    expect(result.current.draftRestoreError).toBeNull()
    expect(result.current.goalDraft).toBeNull()
    expect(partsToText(result.current.draft)).toBe('')
  })

  it('never writes a previous thread pending goal into the newly bound thread', async () => {
    // 失败写回在 await 之后完成：身份已变，绝不把原 Thread 的 Goal 文本回填进新 Thread 的编辑区。
    localStorage.setItem(boundPendingStorageKey(THREAD_ID), JSON.stringify(goalPendingMessage()))
    const gate = deferred<boolean>()
    restoreThreadGoalTextMock.mockReturnValueOnce(gate.promise)

    const { result, rerender } = renderHook(
      ({ threadId }: { threadId: string }) => useTestController(threadId),
      { initialProps: { threadId: THREAD_ID }, wrapper },
    )
    await waitFor(() => expect(result.current.disabled).toBe(false))
    expect(result.current.pendingMessage).not.toBeNull()

    let stopPromise: Promise<void> | undefined
    act(() => {
      stopPromise = result.current.stopThread()
    })
    await waitFor(() => expect(restoreThreadGoalTextMock).toHaveBeenCalledTimes(1))

    rerender({ threadId: THREAD_ID_2 })
    await waitFor(() => expect(result.current.disabled).toBe(false))

    await act(async () => {
      gate.resolve(true)
      await stopPromise
    })

    expect(result.current.goalDraft).toBeNull()
    expect(result.current.actionError).toBeNull()
  })

  it('does not advance the observed generation when the loaded record content is not adopted', async () => {
    // 记录尚未到达时用户已开始编辑：不能只推进 generation 而不采纳内容，否则后续整份写入
    // 会把记录里已恢复的文本永久覆盖掉。这里必须保留原 generation + 提示，等待回执通道同步。
    const gate = deferred<ThreadDraftRecord | null>()
    loadThreadDraftMock.mockReturnValueOnce(gate.promise)

    const { result } = renderHook(() => useTestController(THREAD_ID), { wrapper })
    act(() => result.current.setDraft([createTextPart('typed before load')]))

    await act(async () => {
      gate.resolve(recordOf(THREAD_ID, [createTextPart('cancelled\n\nrestored')], ['stop-before-load']))
      await Promise.resolve()
    })

    expect(partsToText(result.current.draft)).toBe('typed before load')
    expect(result.current.draftRestoreError).toBe(t('ai.runtime.action.draftWriteConflict'))
    // 写入仍携带未推进的 generation：存储层因此拒绝覆盖已恢复内容。
    expect(saveThreadDraftPartsMock).toHaveBeenLastCalledWith(
      THREAD_ID,
      [expect.objectContaining({ type: 'text', text: 'typed before load' })],
      [],
    )
  })

  it('does not let an older completion clear a newer unsaved composer edit', async () => {
    // 同一字段的写入 completion 乱序：旧 completion 成功不得把更新的未落盘编辑标成已保存。
    const olderSave = deferred<DraftSaveOutcome>()
    const client = testClient()
    const receipt = stopReceipt(THREAD_ID, 'stop-older', [cancelledUserMessage(1, 'cancelled')])
    const { result } = renderHook(() => useTestController(THREAD_ID), {
      wrapper: clientWrapper(client),
    })
    await waitFor(() => expect(result.current.disabled).toBe(false))

    saveThreadDraftPartsMock.mockReturnValueOnce(olderSave.promise)
    act(() => result.current.setDraft([createTextPart('first edit')]))
    saveThreadDraftPartsMock.mockRejectedValueOnce(new Error('quota exceeded'))
    act(() => result.current.setDraft([createTextPart('second edit')]))
    await waitFor(() => expect(result.current.actionError).toBe(t('ai.runtime.action.draftSaveFailed')))

    // 旧写入随后「成功」：不得清掉更新编辑的未保存标记。
    await act(async () => {
      olderSave.resolve({ status: 'SAVED' })
      await Promise.resolve()
    })

    // 回执到达时仍必须与当前输入合并（若被标成已保存，这里会用记录覆盖编辑区）。
    applyStopReceiptMock.mockResolvedValue(
      recordOf(THREAD_ID, [createTextPart('cancelled\n\n')], ['stop-older']),
    )
    await deliverReceipt(client, receipt, THREAD_ID)

    await waitFor(() => expect(partsToText(result.current.draft)).toBe('cancelled\n\nsecond edit'))
  })

  it('keeps one field completion from clearing the other field unsaved state', async () => {
    // 两个字段各自独立：goal 写入成功不得清掉 composer 尚未落盘的编辑标记。
    const client = testClient()
    const receipt = stopReceipt(THREAD_ID, 'stop-cross-field', [cancelledUserMessage(1, 'cancelled')])
    const { result } = renderHook(() => useTestController(THREAD_ID), {
      wrapper: clientWrapper(client),
    })
    await waitFor(() => expect(result.current.disabled).toBe(false))

    saveThreadDraftPartsMock.mockRejectedValueOnce(new Error('quota exceeded'))
    act(() => result.current.setDraft([createTextPart('unsaved parts edit')]))
    await waitFor(() => expect(result.current.actionError).toBe(t('ai.runtime.action.draftSaveFailed')))

    // goal 写入成功（确定性完成）：它只应清掉 goal 自己的未保存标记。
    const goalSave = deferred<DraftSaveOutcome>()
    saveThreadGoalTextMock.mockReturnValueOnce(goalSave.promise)
    act(() => result.current.setGoalDraft('goal saved'))
    await act(async () => {
      goalSave.resolve({ status: 'SAVED' })
      await Promise.resolve()
    })

    applyStopReceiptMock.mockResolvedValue(
      recordOf(THREAD_ID, [createTextPart('cancelled\n\n')], ['stop-cross-field']),
    )
    await deliverReceipt(client, receipt, THREAD_ID)

    await waitFor(() => expect(partsToText(result.current.draft)).toBe('cancelled\n\nunsaved parts edit'))
  })

  it('carries the observed receipt generation on every draft write', async () => {
    // 已观察 generation 只来自 loadThreadDraft / applyStopReceipt 的 record：写入必须原样携带，
    // 存储层才能拒绝尚未观察新回执的陈旧覆盖写。
    loadThreadDraftMock.mockResolvedValueOnce(
      recordOf(THREAD_ID, [createTextPart('loaded draft')], ['stop-loaded']),
    )

    const { result } = renderHook(() => useTestController(THREAD_ID), { wrapper })
    await waitFor(() => expect(result.current.disabled).toBe(false))

    act(() => result.current.setDraft([createTextPart('edited draft')]))
    await waitFor(() =>
      expect(saveThreadDraftPartsMock).toHaveBeenCalledWith(
        THREAD_ID,
        [expect.objectContaining({ type: 'text', text: 'edited draft' })],
        ['stop-loaded'],
      ))
  })

  it('keeps the edit, prompts, then merges the restored input once the snapshot brings the receipt', async () => {
    // 另一处已合并了本 UI 尚未观察到的恢复内容：陈旧的整份写入被拒。
    // 编辑必须保留在 UI、明确提示；快照随后带来该回执时，经已有恢复通道把恢复内容合并回来
    // （generation 同步补齐），两者都落盘。
    const receipt = stopReceipt(THREAD_ID, 'stop-stale', [cancelledUserMessage(1, 'cancelled')])
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValueOnce(snapshotOf(threadFixture()))
    saveThreadDraftPartsMock.mockResolvedValueOnce({
      status: 'STALE',
      record: recordOf(THREAD_ID, [createTextPart('cancelled\n\n')], ['stop-stale']),
    })
    applyStopReceiptMock.mockResolvedValue(
      recordOf(THREAD_ID, [createTextPart('cancelled\n\n')], ['stop-stale']),
    )
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })

    const { result } = renderHook(() => useTestController(THREAD_ID), {
      wrapper: clientWrapper(client),
    })
    await waitFor(() => expect(result.current.disabled).toBe(false))

    act(() => result.current.setDraft([createTextPart('local edit')]))
    await waitFor(() =>
      expect(result.current.draftRestoreError).toBe(t('ai.runtime.action.draftWriteConflict')))
    expect(partsToText(result.current.draft)).toBe('local edit')

    // 快照重取带来该回执：恢复内容与本地输入一起进入编辑区并按补齐后的 generation 落盘。
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(
      snapshotOf(threadFixture(), { stopReceipts: [receipt] }),
    )
    await act(async () => {
      await client.invalidateQueries({ queryKey: queryKeys.threads.snapshot(THREAD_ID) })
    })

    await waitFor(() => expect(partsToText(result.current.draft)).toBe('cancelled\n\nlocal edit'))
    await waitFor(() => expect(result.current.draftRestoreError).toBeNull())
    expect(saveThreadDraftPartsMock).toHaveBeenLastCalledWith(
      THREAD_ID,
      [expect.objectContaining({ type: 'text', text: 'cancelled\n\nlocal edit' })],
      ['stop-stale'],
    )
  })

  it('keeps the prompt and the edit when a rejected save has no receipt to resync from', async () => {
    // 没有可用于重建恢复内容的回执：不写记录、不改 generation，保留编辑并保持提示等待重试。
    saveThreadDraftPartsMock.mockResolvedValue({
      status: 'STALE',
      record: recordOf(THREAD_ID, [createTextPart('cancelled\n\n')], ['stop-elsewhere']),
    })

    const { result } = renderHook(() => useTestController(THREAD_ID), { wrapper })
    await waitFor(() => expect(result.current.disabled).toBe(false))

    act(() => result.current.setDraft([createTextPart('local edit')]))
    await waitFor(() =>
      expect(result.current.draftRestoreError).toBe(t('ai.runtime.action.draftWriteConflict')))
    expect(partsToText(result.current.draft)).toBe('local edit')

    // 后续编辑仍会尝试写入（不被永久阻塞），且仍携带未补齐的 generation。
    act(() => result.current.setDraft([createTextPart('local edit 2')]))
    await waitFor(() => expect(saveThreadDraftPartsMock).toHaveBeenCalledTimes(2))
    expect(saveThreadDraftPartsMock).toHaveBeenLastCalledWith(
      THREAD_ID,
      [expect.objectContaining({ type: 'text', text: 'local edit 2' })],
      [],
    )
    expect(partsToText(result.current.draft)).toBe('local edit 2')
  })

  it('reports a draft save failure instead of pretending the draft is persisted', async () => {
    saveThreadDraftPartsMock.mockRejectedValueOnce(new Error('quota exceeded'))

    const { result } = renderHook(() => useTestController(THREAD_ID), { wrapper })
    await waitFor(() => expect(result.current.disabled).toBe(false))

    act(() => result.current.setDraft([createTextPart('unsaved edit')]))
    await waitFor(() => expect(result.current.actionError).toBe(t('ai.runtime.action.draftSaveFailed')))
  })

  it('reports a draft load failure instead of showing a possibly stale editor', async () => {
    loadThreadDraftMock.mockRejectedValueOnce(new Error('IndexedDB open failed'))

    const { result } = renderHook(() => useTestController(THREAD_ID), { wrapper })
    await waitFor(() => expect(result.current.actionError).toBe(t('ai.runtime.action.draftLoadFailed')))
  })
})
