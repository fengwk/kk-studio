import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  canRetryStaleMessageBatch,
  useAgentThreadController,
  retireStaleStopPending,
} from '@/features/ai/runtime/useAgentThreadController'
import {
  buildMessageBatchPlan,
  type CommandBatchPlan,
} from '@/features/ai/chat/command-batch-plan'
import { branchDraftFromThread, type BranchDraft } from '@/features/ai/chat/branch-draft'
import {
  createAttachmentPart,
  createTextPart,
  partsToMessageContents,
  partsToText,
  type ComposerPart,
} from '@/features/ai/composer/composer-parts'
import { loadThreadDraft } from '@/features/ai/runtime/thread-draft-store'
import { pendingStopStorageKey } from '@/features/ai/runtime/pending-stop-sidecar'
import { loadBoundPendingMessage } from '@/features/ai/runtime/agent-pane/pane-target'
import { ApplicationEventProvider } from '@/shared/app-events'
import { FakeWebSocketHarness } from '@/shared/app-events/__tests__/fake-websocket'
import { agentService } from '@/shared/api/agent-service'
import { ApiError } from '@/shared/api/client'
import { harnessService } from '@/shared/api/harness-service'
import type {
  HarnessBranchSettingsDTO,
  HarnessModelSelectionDTO,
  HarnessSessionEntryDTO,
  HarnessThreadCommandDTO,
  HarnessThreadDTO,
  HarnessThreadSnapshotDTO,
  HarnessThreadStopResultDTO,
  ToolInvocationDTO,
} from '@/shared/api/contracts/ai-runtime'
import { rootYoloPolicy } from '@/test-support/thread-yolo-policy'

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
    previewProviderRequest: vi.fn(),
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
    agentName: 'assistant',
    model: modelSelection(),
    environmentName: null,
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
    yoloPolicy: rootYoloPolicy(yoloEnabled),
    nextCommandSequence: '1',
    version: '0',
    status: 'IDLE',
    processing: false,
    executionControl: 'RUNNABLE',
    branchSettings: branchSettings(),
    createTime: null,
    updateTime: null,
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
    entries: [],
    queuedCommands: [],
    modelInvocation: null,
    toolInvocations: [],
    modelAttemptFailures: [],
    stopReceipts: [],
    ...extras,
  }
}

const assistantAgentEntry = {
  name: 'assistant',
  description: null,
  systemPrompt: null,
  model: 'minimax/MiniMax',
  variant: 'default',
  config: { inheritParentEnvironment: true, tools: [], skills: [], subagents: [] },
  version: '0',
  createTime: null,
  updateTime: null,
}

let realtimeSockets: FakeWebSocketHarness

function wrapper({ children }: { children: ReactNode }) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return (
    <QueryClientProvider client={client}>
      <ApplicationEventProvider url="ws://test/events/v1" socketFactory={realtimeSockets.factory}>
        {children}
      </ApplicationEventProvider>
    </QueryClientProvider>
  )
}

function clientWrapper(client: QueryClient) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={client}>
        <ApplicationEventProvider url="ws://test/events/v1" socketFactory={realtimeSockets.factory}>
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

describe('useAgentThreadController', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    realtimeSockets = new FakeWebSocketHarness()
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
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(snapshotOf(threadFixture()))
    vi.mocked(harnessService.acceptThreadCommandBatch).mockResolvedValue(
      [] as HarnessThreadCommandDTO[],
    )
    vi.mocked(harnessService.stopThread).mockResolvedValue({
      status: 'STOPPED',
      thread: threadFixture(),
      stoppedThreads: [],
    } as HarnessThreadStopResultDTO)
    vi.mocked(harnessService.decideApproval).mockImplementation(
      async (_threadId, invocationId) =>
        ({
          id: invocationId,
          modelInvocationId: 'm1',
          assistantEntryId: 'a1',
          callIndex: 0,
          status: 'APPROVED',
          attempt: 1,
          toolCallId: 'call-1',
          toolName: 'demo',
          rendererKey: 'demo',
          environmentId: null,
          argumentsJson: '{}',
          approvalJson: '{}',
          resultJson: null,
          errorJson: null,
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
        useAgentThreadController(
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
      expect(harnessService.getThreadSnapshot.mock.calls.length).toBeGreaterThan(
        snapshotCallsBefore,
      ),
    )
    // 失败的 draft 会被恢复，用户无需重新输入即可重试。
    expect(partsToText(result.current.draft)).toBe('stale message')
  })

  it('refreshes and retries a pure message after a typed stale cursor conflict', async () => {
    const currentThread = threadFixture()
    const advancedThread = threadFixture({
      headEntryId: 'h2',
      nextCommandSequence: '2',
      version: '2',
      status: 'MODEL_STREAMING',
      processing: true,
    })
    vi.mocked(harnessService.getThreadSnapshot)
      .mockResolvedValueOnce(snapshotOf(currentThread))
      .mockResolvedValue(
        snapshotOf(advancedThread, {
          entries: [
            {
              entryId: 'h1',
              sessionId: 's1',
              parentEntryId: null,
              entryType: 'ROOT',
              payloadJson: '{}',
              createTime: null,
            },
            {
              entryId: 'h2',
              sessionId: 's1',
              parentEntryId: 'h1',
              entryType: 'TURN_START',
              payloadJson: '{}',
              createTime: null,
            },
          ],
        }),
      )
    vi.mocked(harnessService.acceptThreadCommandBatch)
      .mockRejectedValueOnce(
        new ApiError(
          'The request conflicts with the current resource state.',
          409,
          'CONFLICT',
          { reason: 'STALE_COMMAND_CURSOR' },
        ),
      )
      .mockResolvedValueOnce([] as HarnessThreadCommandDTO[])
    const base = branchDraftFromThread(currentThread)

    const { result } = renderHook(
      () =>
        useAgentThreadController(
          currentThread.threadId,
          [],
          buildBatchFor(currentThread, base, base),
        ),
      { wrapper },
    )
    await waitFor(() => expect(result.current.disabled).toBe(false))

    act(() => result.current.setDraft([createTextPart('queue after current turn')]))
    await act(async () => {
      await result.current.submitMessage()
    })

    const calls = vi.mocked(harnessService.acceptThreadCommandBatch).mock.calls
    expect(calls).toHaveLength(2)
    expect(calls[0]?.[0]).toBe(THREAD_ID)
    expect(calls[0]?.[1]).toMatchObject({
      expectedHeadEntryId: 'h1',
      expectedNextCommandSequence: '1',
    })
    expect(calls[1]?.[0]).toBe(THREAD_ID)
    expect(calls[1]?.[1]).toMatchObject({
      expectedHeadEntryId: 'h2',
      expectedNextCommandSequence: '2',
    })
    expect(calls[1]?.[1].commands[0]?.idempotencyKey).toBe(
      calls[0]?.[1].commands[0]?.idempotencyKey,
    )
    expect(result.current.actionError).toBeNull()
    expect(partsToText(result.current.draft)).toBe('')
  })

  it('does not retry a stale message cursor after the current branch changed', async () => {
    const currentThread = threadFixture()
    const switchedThread = threadFixture({
      headEntryId: 'other-head',
      nextCommandSequence: '2',
      version: '2',
    })
    vi.mocked(harnessService.getThreadSnapshot)
      .mockResolvedValueOnce(snapshotOf(currentThread))
      .mockResolvedValue(
        snapshotOf(switchedThread, {
          entries: [
            {
              entryId: 'other-head',
              sessionId: 's1',
              parentEntryId: null,
              entryType: 'ROOT',
              payloadJson: '{}',
              createTime: null,
            },
          ],
        }),
      )
    vi.mocked(harnessService.acceptThreadCommandBatch).mockRejectedValueOnce(
      new ApiError('stale', 409, 'CONFLICT', { reason: 'STALE_COMMAND_CURSOR' }),
    )
    const base = branchDraftFromThread(currentThread)

    const { result } = renderHook(
      () =>
        useAgentThreadController(
          currentThread.threadId,
          [],
          buildBatchFor(currentThread, base, base),
        ),
      { wrapper },
    )
    await waitFor(() => expect(result.current.disabled).toBe(false))

    act(() => result.current.setDraft([createTextPart('do not move across branches')]))
    await act(async () => {
      await result.current.submitMessage()
    })

    expect(harnessService.acceptThreadCommandBatch).toHaveBeenCalledTimes(1)
    expect(result.current.conflict?.reason).toBe('STALE_COMMAND_CURSOR')
    expect(partsToText(result.current.draft)).toBe('do not move across branches')
  })

  it('only retries stale batches that contain USER_MESSAGE commands on the same branch', () => {
    const currentThread = threadFixture()
    const base = branchDraftFromThread(currentThread)
    const messagePlan = buildMessageBatchPlan({
      thread: currentThread,
      effectiveBase: base,
      draft: base,
      parts: [createTextPart('hello')],
      createCommandId: () => 'message-id',
    })
    const advanced = snapshotOf(
      threadFixture({ headEntryId: 'h2', nextCommandSequence: '2' }),
      {
        entries: [
          {
            entryId: 'h1',
            sessionId: 's1',
            parentEntryId: null,
            entryType: 'ROOT',
            payloadJson: '{}',
            createTime: null,
          },
        ],
      },
    )
    expect(canRetryStaleMessageBatch(messagePlan, advanced)).toBe(true)

    const settingsPlan = buildMessageBatchPlan({
      thread: currentThread,
      effectiveBase: base,
      draft: { ...base, agentName: 'coder' },
      parts: [createTextPart('hello')],
      createCommandId: () => 'settings-id',
    })
    expect(canRetryStaleMessageBatch(settingsPlan, advanced)).toBe(false)
    expect(
      canRetryStaleMessageBatch(
        messagePlan,
        snapshotOf(
          threadFixture({
            headEntryId: 'h2',
            nextCommandSequence: '2',
            branchSettings: branchSettings({ agentName: 'other-agent' }),
          }),
          {
            entries: [
              {
                entryId: 'h1',
                sessionId: 's1',
                parentEntryId: null,
                entryType: 'ROOT',
                payloadJson: '{}',
                createTime: null,
              },
            ],
          },
        ),
      ),
    ).toBe(false)
    expect(
      canRetryStaleMessageBatch(
        messagePlan,
        snapshotOf(threadFixture({ headEntryId: 'other', nextCommandSequence: '2' }), {
          entries: [],
        }),
      ),
    ).toBe(false)
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
        useAgentThreadController(
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
      .mockResolvedValueOnce([] as HarnessThreadCommandDTO[])

    const { result } = renderHook(
      () =>
        useAgentThreadController(
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
        return [] as HarnessThreadCommandDTO[]
      })

    const { result } = renderHook(
      () =>
        useAgentThreadController(
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
      .mockResolvedValue([] as HarnessThreadCommandDTO[])

    const { result } = renderHook(
      () =>
        useAgentThreadController(
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
        useAgentThreadController(
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
        useAgentThreadController(
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

    const { result } = renderHook(() => useAgentThreadController(THREAD_ID), { wrapper })
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
    const { result } = renderHook(() => useAgentThreadController(THREAD_ID), { wrapper })
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
    const { result } = renderHook(() => useAgentThreadController(THREAD_ID), { wrapper })
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
    const first = renderHook(() => useAgentThreadController(THREAD_ID), { wrapper })
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
    const second = renderHook(() => useAgentThreadController(THREAD_ID), { wrapper })
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
    const { result } = renderHook(() => useAgentThreadController(THREAD_ID), { wrapper })
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
    const { result } = renderHook(() => useAgentThreadController(THREAD_ID), { wrapper })
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
    const { result } = renderHook(() => useAgentThreadController(THREAD_ID), { wrapper })
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
    const { result } = renderHook(() => useAgentThreadController(THREAD_ID), { wrapper })
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
    const { result } = renderHook(() => useAgentThreadController(THREAD_ID), { wrapper })
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
        callIndex: 0,
        status: 'APPROVED',
        attempt: 1,
        toolCallId: 'call-1',
        toolName: 'demo',
        rendererKey: 'demo',
        environmentId: null,
        argumentsJson: '{}',
        approvalJson: '{}',
        resultJson: null,
        errorJson: null,
        createTime: null,
        updateTime: null,
      } as ToolInvocationDTO)

    const { result } = renderHook(() => useAgentThreadController(THREAD_ID), { wrapper })
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
    const { result } = renderHook(() => useAgentThreadController(THREAD_ID), { wrapper })
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
      .mockResolvedValueOnce({
        id: 'tool-child',
        modelInvocationId: 'm1',
        assistantEntryId: 'a1',
        callIndex: 0,
        status: 'APPROVED',
        attempt: 1,
        toolCallId: 'call-1',
        toolName: 'bash',
        rendererKey: 'bash',
        environmentId: null,
        argumentsJson: '{}',
        approvalJson: '{}',
        resultJson: null,
        errorJson: null,
        createTime: null,
        updateTime: null,
      } as ToolInvocationDTO)

    const { result } = renderHook(() => useAgentThreadController(THREAD_ID), { wrapper })
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
    const { result } = renderHook(() => useAgentThreadController(THREAD_ID), {
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
    const { result } = renderHook(() => useAgentThreadController(THREAD_ID), { wrapper })
    await waitFor(() => expect(result.current.disabled).toBe(false))

    const initialCalls = vi.mocked(harnessService.getThreadSnapshot).mock.calls.length
    await act(async () => {
      await result.current.stopThread()
    })
    await waitFor(() =>
      expect(harnessService.getThreadSnapshot.mock.calls.length).toBeGreaterThan(initialCalls),
    )
  })

  it('exposes runtime facts from the snapshot branch settings', async () => {
    vi.mocked(agentService.listAgents).mockResolvedValue({
      pageNumber: 1,
      pageSize: 50,
      totalCount: 1,
      results: [
        {
          name: 'assistant',
          description: null,
          systemPrompt: null,
          model: 'minimax/MiniMax',
          variant: 'default',
          environmentId: 'env-local',
          config: { inheritParentEnvironment: true, tools: [], skills: [], subagents: [] },
          version: '1',
          createTime: null,
          updateTime: null,
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

    const { result } = renderHook(() => useAgentThreadController(currentThread.threadId), {
      wrapper,
    })
    await waitFor(() => expect(result.current.disabled).toBe(false))
    expect(result.current.runtimeLabels.environmentName).toBe('dev-cluster')
    expect(result.current.runtimeLabels.contextWindow).toBe(128000)
    expect(result.current.thread?.headEntryId).toBe('h1')
    expect(result.current.thread?.nextCommandSequence).toBe('1')
  })

  it('derives Branch Usage from completed TURN_END summaries in the current snapshot', async () => {
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
      createTime: null,
    })
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(
      snapshotOf(currentThread, {
        entries: [
          entry('turn-1', 'TURN_START', { reason: 'USER_MESSAGE' }),
          entry('assistant-1', 'MESSAGE', {
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
              cost: { total: 0.25 },
            },
          }),
          entry('end-1', 'TURN_END', { outcome: 'COMPLETED', continueModel: false }),
        ],
      }),
    )

    const { result } = renderHook(() => useAgentThreadController(currentThread.threadId), {
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
      cost: 0.25,
      decodeTokens: null,
      decodeDurationMillis: null,
      contextInputTokens: 1_540,
    })
  })

  it('clears the exact replay on 409 so the retry mints fresh command ids and cursors', async () => {
    const currentThread = threadFixture({
      version: '1',
      nextCommandSequence: '3',
      headEntryId: 'h1',
    })
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(snapshotOf(currentThread))
    vi.mocked(harnessService.acceptThreadCommandBatch).mockRejectedValueOnce(
      new ApiError('conflict', 409, 'CONFLICT'),
    )
    vi.mocked(harnessService.acceptThreadCommandBatch).mockResolvedValueOnce([])
    const base = branchDraftFromThread(currentThread)

    const { result } = renderHook(
      () =>
        useAgentThreadController(
          currentThread.threadId,
          [],
          buildBatchFor(currentThread, base, base),
        ),
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
    expect(calls[0]?.[0]).toBe(THREAD_ID)
    const first = calls[0]?.[1] as {
      expectedHeadEntryId: string
      expectedNextCommandSequence: string
      commands: Array<{ idempotencyKey: string }>
    }
    const second = calls[1]?.[1] as {
      expectedHeadEntryId: string
      expectedNextCommandSequence: string
      commands: Array<{ idempotencyKey: string }>
    }
    // 409 = 该 batch 未被接受：重试使用刷新后的 head/nextSequence
    // 以及全新的 command id，而不是回放陈旧的 batch。
    expect(second.expectedHeadEntryId).toBe('h1')
    expect(second.expectedNextCommandSequence).toBe('3')
    expect(second.commands[0]?.idempotencyKey).not.toBe(first.commands[0]?.idempotencyKey)
    // 网络/不确定失败会保留精确 batch；409 不能这样做。
    void first
    void second
  })

  it('mints a new decision id when the user switches ALLOW -> DENY for the same invocation', async () => {
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(snapshotOf(threadFixture()))
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    const { result } = renderHook(() => useAgentThreadController(THREAD_ID), {
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
        useAgentThreadController(
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
    const { result } = renderHook(() => useAgentThreadController(THREAD_ID), { wrapper })
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
    const { result } = renderHook(() => useAgentThreadController(THREAD_ID), { wrapper })
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
    const { result } = renderHook(() => useAgentThreadController(currentThread.threadId), {
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
        useAgentThreadController(
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
        useAgentThreadController(
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
    let releaseFirst!: (val: HarnessThreadCommandDTO[]) => void
    vi.mocked(harnessService.acceptThreadCommandBatch)
      .mockImplementationOnce(
        () =>
          new Promise<HarnessThreadCommandDTO[]>((resolve) => {
            releaseFirst = resolve
          }),
      )
      .mockResolvedValueOnce([] as HarnessThreadCommandDTO[])
    const currentThread = threadFixture()
    const base = branchDraftFromThread(currentThread)
    const { result } = renderHook(
      () =>
        useAgentThreadController(
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
      releaseFirst([] as HarnessThreadCommandDTO[])
    })
    await waitFor(() => expect(result.current.pending).toBe(false))

    // 首请求完成后，新请求可以正常发送
    await act(async () => {
      await result.current.submitMessage()
    })
    expect(harnessService.acceptThreadCommandBatch).toHaveBeenCalledTimes(2)
  })

  it('preserves latest rebuilt cursor across error handling when stale cursor rebuild fails subsequently', async () => {
    const currentThread = threadFixture({
      version: '1',
      headEntryId: 'h1',
      nextCommandSequence: '3',
    })
    const updatedThread = {
      ...currentThread,
      version: '2',
      headEntryId: 'h1',
      nextCommandSequence: '4',
    }
    vi.mocked(harnessService.getThreadSnapshot)
      .mockResolvedValueOnce(snapshotOf(currentThread))
      .mockResolvedValueOnce(snapshotOf(updatedThread))

    // 第一次调用返回 STALE_COMMAND_CURSOR，触发游标更新为 h1/4；第二次重试调用遭遇未知网络故障
    vi.mocked(harnessService.acceptThreadCommandBatch)
      .mockRejectedValueOnce(new ApiError('stale cursor', 409, 'CONFLICT', { reason: 'STALE_COMMAND_CURSOR' }))
      .mockRejectedValueOnce(new Error('Network drop on rebuilt request'))

    const base = branchDraftFromThread(currentThread)
    const { result } = renderHook(
      () =>
        useAgentThreadController(
          currentThread.threadId,
          [],
          buildBatchFor(currentThread, base, base),
        ),
      { wrapper },
    )
    await waitFor(() => expect(result.current.disabled).toBe(false))

    act(() => result.current.setDraft([createTextPart('rebuild cursor test')]))
    await act(async () => {
      await result.current.submitMessage()
    })

    // 验证第二次调用确实使用了更新后的 h1/4 游标
    expect(harnessService.acceptThreadCommandBatch).toHaveBeenCalledTimes(2)
    const secondReq = vi.mocked(harnessService.acceptThreadCommandBatch).mock.calls[1]?.[1]
    expect(secondReq?.expectedHeadEntryId).toBe('h1')
    expect(secondReq?.expectedNextCommandSequence).toBe('4')

    // 关键回归点：随后的 unknown 错误处理保留确切最新已发的 request（h1/4），绝不被旧闭包覆盖
    expect(result.current.pendingMessage?.unknownOutcome).toBe(true)
    expect(result.current.pendingMessage?.request.expectedHeadEntryId).toBe('h1')
    expect(result.current.pendingMessage?.request.expectedNextCommandSequence).toBe('4')
  })

  it('fails closed when persisting rebuilt cursor in stale retry loop fails', async () => {
    const currentThread = threadFixture({ version: '1', headEntryId: 'h1', nextCommandSequence: '3' })
    const updatedThread = { ...currentThread, version: '2', headEntryId: 'h1', nextCommandSequence: '4' }
    vi.mocked(harnessService.getThreadSnapshot)
      .mockResolvedValueOnce(snapshotOf(currentThread))
      .mockResolvedValueOnce(snapshotOf(updatedThread))

    vi.mocked(harnessService.acceptThreadCommandBatch).mockRejectedValueOnce(
      new ApiError('stale cursor', 409, 'CONFLICT', { reason: 'STALE_COMMAND_CURSOR' }),
    )

    const originalSetItem = localStorage.setItem.bind(localStorage)
    let pendingSaveCount = 0
    const storageSpy = vi.spyOn(Storage.prototype, 'setItem').mockImplementation((key, val) => {
      if (key.includes('agent-thread-pending')) {
        pendingSaveCount += 1
        if (pendingSaveCount > 1) {
          throw new Error('QuotaExceededOnRebuild')
        }
      }
      return originalSetItem(key, val)
    })

    try {
      const base = branchDraftFromThread(currentThread)
      const { result } = renderHook(
        () =>
          useAgentThreadController(
            currentThread.threadId,
            [],
            buildBatchFor(currentThread, base, base),
          ),
        { wrapper },
      )
      await waitFor(() => expect(result.current.disabled).toBe(false))

      act(() => result.current.setDraft([createTextPart('rebuild fail-closed')]))
      await act(async () => {
        await result.current.submitMessage()
      })

      // 验证 fail-closed：持久化失败后中止重试，绝未发出第二次 acceptThreadCommandBatch
      expect(harnessService.acceptThreadCommandBatch).toHaveBeenCalledTimes(1)
      expect(result.current.actionError).toMatch(/QuotaExceededOnRebuild/)
    } finally {
      storageSpy.mockRestore()
    }
  })

  it('rejects stale retry when targetDraft diverges from projected settings in snapshot', async () => {
    const currentThread = threadFixture({ version: '1', headEntryId: 'h1', nextCommandSequence: '3' })
    // 服务端 snapshot 已经变更了 agentName 为 'planner'
    const divergentThread = threadFixture({
      version: '2',
      headEntryId: 'h1',
      nextCommandSequence: '4',
      branchSettings: branchSettings({ agentName: 'planner' }),
    })
    vi.mocked(harnessService.getThreadSnapshot)
      .mockResolvedValueOnce(snapshotOf(currentThread))
      .mockResolvedValueOnce(snapshotOf(divergentThread))

    vi.mocked(harnessService.acceptThreadCommandBatch).mockRejectedValueOnce(
      new ApiError('stale cursor', 409, 'CONFLICT', { reason: 'STALE_COMMAND_CURSOR' }),
    )

    const base = branchDraftFromThread(currentThread)
    const { result } = renderHook(
      () =>
        useAgentThreadController(
          currentThread.threadId,
          [],
          buildBatchFor(currentThread, base, base),
        ),
      { wrapper },
    )
    await waitFor(() => expect(result.current.disabled).toBe(false))

    act(() => result.current.setDraft([createTextPart('target draft guard test')]))
    await act(async () => {
      await result.current.submitMessage()
    })

    // 验证：因配置不匹配（targetDraft 守护），canRetryStaleMessageBatch 拦截，未进行自动重试
    expect(harnessService.acceptThreadCommandBatch).toHaveBeenCalledTimes(1)
  })

  it('persists edit drafts to the thread record and skips persistence for history sources', async () => {
    const currentThread = threadFixture()
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(snapshotOf(currentThread))
    const { result } = renderHook(() => useAgentThreadController(currentThread.threadId), {
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

  it('refuses stale retry when cursors are unchanged or commands are empty, and never adds owner/target to bound requests', () => {
    const currentThread = threadFixture()
    const base = branchDraftFromThread(currentThread)
    const messagePlan = buildMessageBatchPlan({
      thread: currentThread,
      effectiveBase: base,
      draft: base,
      parts: [createTextPart('hello')],
      createCommandId: () => 'message-id',
    })
    // 快照 cursor 与目标完全一致：没有需要追平的推进。
    expect(canRetryStaleMessageBatch(messagePlan, snapshotOf(currentThread))).toBe(false)
    // 空 command batch：没有 USER_MESSAGE 可精确重放。
    const emptyPlan: CommandBatchPlan = {
      ...messagePlan,
      request: { ...messagePlan.request, commands: [] },
    }
    expect(
      canRetryStaleMessageBatch(
        emptyPlan,
        snapshotOf(threadFixture({ headEntryId: 'h2', nextCommandSequence: '2' })),
      ),
    ).toBe(false)
    // 既有 Thread 的请求不再携带 owner/target：绑定写入只按 CAS 游标与命令判定可回放性，
    // 创建型 target 永远不会进入这条回放通道。
    expect(messagePlan.request).not.toHaveProperty('owner')
    expect(messagePlan.request).not.toHaveProperty('target')
  })

  it('runs manual compaction with the snapshot version and clears conflict on success', async () => {
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(
      snapshotOf(threadFixture(), { manualCompaction: { available: true, disabledReason: null } }),
    )
    vi.mocked(harnessService.compactThread).mockResolvedValue({
      thread: threadFixture({ version: '1' }),
      turnStartEntryId: 't1',
      modelInvocationId: null,
    })
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    const invalidateSpy = vi.spyOn(client, 'invalidateQueries')
    const { result } = renderHook(() => useAgentThreadController(THREAD_ID), {
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
      snapshotOf(threadFixture(), { manualCompaction: { available: true, disabledReason: null } }),
    )
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    const invalidateSpy = vi.spyOn(client, 'invalidateQueries')
    // 409：conflict 展示 + 目标 snapshot 失效；draft/错误通道不受影响。
    vi.mocked(harnessService.compactThread).mockRejectedValueOnce(
      new ApiError('stale compact version', 409, 'CONFLICT', { reason: 'STALE_VERSION' }),
    )
    const { result } = renderHook(() => useAgentThreadController(THREAD_ID), {
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

  it('reports compaction unavailability when the advisory sidecar is missing', async () => {
    const { result } = renderHook(() => useAgentThreadController(THREAD_ID), { wrapper })
    await waitFor(() => expect(result.current.disabled).toBe(false))
    await act(async () => {
      await result.current.compactThread()
    })
    expect(harnessService.compactThread).not.toHaveBeenCalled()
    // 无 advisory sidecar：使用 fallback 的 disabledReason。
    expect(result.current.actionError).toContain('Thread snapshot is not loaded')
  })

  it('routes runCommand to stop/compact and reports unknown commands', async () => {
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(
      snapshotOf(threadFixture(), { manualCompaction: { available: true, disabledReason: null } }),
    )
    vi.mocked(harnessService.compactThread).mockResolvedValue({
      thread: threadFixture(),
      turnStartEntryId: 't1',
      modelInvocationId: null,
    })
    const { result } = renderHook(() => useAgentThreadController(THREAD_ID), { wrapper })
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
      ({ tid }: { tid: string }) => useAgentThreadController(tid),
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
      ({ tid }: { tid: string }) => useAgentThreadController(tid),
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
      ({ tid }: { tid: string }) => useAgentThreadController(tid),
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

  it('treats QUEUED as working while IDLE and STOPPED are idle states', async () => {
    // 测试意图：status 只描述该 Thread 自身执行阶段；QUEUED（非静止）必须 working=true，
    // 而 STOPPED 与 IDLE 一样是静止状态（processing=false → working=false）。
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(
      snapshotOf(threadFixture({ threadId: THREAD_ID, status: 'QUEUED', processing: false })),
    )
    const { result } = renderHook(
      () => useAgentThreadController(THREAD_ID),
      { wrapper },
    )
    await waitFor(() => expect(result.current.disabled).toBe(false))
    expect(result.current.thread?.status).toBe('QUEUED')
    expect(result.current.working).toBe(true)

    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(
      snapshotOf(
        threadFixture({
          threadId: THREAD_ID_2,
          status: 'STOPPED',
          processing: false,
          executionControl: 'STOPPED',
        }),
      ),
    )
    const { result: result2 } = renderHook(
      () => useAgentThreadController(THREAD_ID_2),
      { wrapper },
    )
    await waitFor(() => expect(result2.current.disabled).toBe(false))
    expect(result2.current.thread?.status).toBe('STOPPED')
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
    const sendPromiseA = new Promise<HarnessThreadCommandDTO[]>((resolve) => {
      resolveSendA = () => resolve([])
    })
    let resolveSendB!: () => void
    const sendPromiseB = new Promise<HarnessThreadCommandDTO[]>((resolve) => {
      resolveSendB = () => resolve([])
    })

    vi.mocked(harnessService.acceptThreadCommandBatch).mockImplementation(async (threadId) => {
      if (threadId === THREAD_ID) {
        return sendPromiseA
      }
      return sendPromiseB
    })

    const { result, rerender } = renderHook(
      ({ tid }: { tid: string }) =>
        useAgentThreadController(tid, [], (parts) => {
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
    const sendPromiseA = new Promise<HarnessThreadCommandDTO[]>((_resolve, reject) => {
      rejectSendA = reject
    })

    vi.mocked(harnessService.acceptThreadCommandBatch).mockImplementation(async (threadId) => {
      if (threadId === THREAD_ID) {
        return sendPromiseA
      }
      return []
    })

    const { result, rerender } = renderHook(
      ({ tid }: { tid: string }) =>
        useAgentThreadController(tid, [], (parts) => {
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
    const sendPromiseA = new Promise<HarnessThreadCommandDTO[]>((_resolve, reject) => {
      rejectSendA = reject
    })

    vi.mocked(harnessService.acceptThreadCommandBatch).mockImplementation(async (threadId) => {
      if (threadId === THREAD_ID) {
        return sendPromiseA
      }
      return []
    })

    const { result, rerender } = renderHook(
      ({ tid }: { tid: string }) =>
        useAgentThreadController(tid, [], (parts) => {
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

  it('deferred stale cursor fetch: switches to thread B during snapshot fetch, aborts auto-retry and avoids cross-binding send', async () => {
    // 测试意图：验证 Thread A 在遭遇 STALE_COMMAND_CURSOR 并在 getThreadSnapshot 获取权威快照期间，
    // 若用户切换到了 Thread B，快照返回后必须被 guard 拦截，绝不跨 binding 自动发起第二次 acceptThreadCommandBatch
    const threadA = threadFixture({ threadId: THREAD_ID, headEntryId: 'h1', nextCommandSequence: '1' })
    const threadB = threadFixture({ threadId: THREAD_ID_2 })

    let resolveSnapshotA!: (snap: HarnessThreadSnapshotDTO) => void
    const snapshotPromiseA = new Promise<HarnessThreadSnapshotDTO>((resolve) => {
      resolveSnapshotA = resolve
    })

    vi.mocked(harnessService.getThreadSnapshot).mockImplementation(async (tid) => {
      if (tid === THREAD_ID_2) {
        return snapshotOf(threadB)
      }
      return snapshotPromiseA
    })

    // 首次 getThreadSnapshot 立即返回 threadA
    resolveSnapshotA(snapshotOf(threadA))

    const { result, rerender } = renderHook(
      ({ tid }: { tid: string }) =>
        useAgentThreadController(tid, [], (parts) => {
          const curThread = tid === THREAD_ID_2 ? threadB : threadA
          const curBase = branchDraftFromThread(curThread)
          return buildBatchFor(curThread, curBase, curBase)(parts)
        }),
      { wrapper, initialProps: { tid: THREAD_ID } },
    )
    await waitFor(() => expect(result.current.disabled).toBe(false))

    // 重新准备 snapshotPromiseA 用于 stale cursor 重试阶段
    let resolveStaleSnapshot!: (snap: HarnessThreadSnapshotDTO) => void
    const staleSnapshotPromise = new Promise<HarnessThreadSnapshotDTO>((resolve) => {
      resolveStaleSnapshot = resolve
    })
    vi.mocked(harnessService.getThreadSnapshot).mockImplementation(async (tid) => {
      if (tid === THREAD_ID_2) {
        return snapshotOf(threadB)
      }
      return staleSnapshotPromise
    })

    // 第一次 acceptThreadCommandBatch 抛出 STALE_COMMAND_CURSOR
    vi.mocked(harnessService.acceptThreadCommandBatch).mockRejectedValueOnce(
      new ApiError(409, 'STALE_COMMAND_CURSOR', 'stale cursor'),
    )

    act(() => result.current.setDraft([createTextPart('retry msg')]))
    let submitPromise!: Promise<void>
    act(() => {
      submitPromise = result.current.submitMessage()
    })

    await waitFor(() => expect(harnessService.acceptThreadCommandBatch).toHaveBeenCalledTimes(1))

    // 在 getThreadSnapshot 挂起期间，切换至 Thread B
    rerender({ tid: THREAD_ID_2 })
    await waitFor(() => expect(result.current.thread?.threadId).toBe(THREAD_ID_2))

    // 释放 Thread A 的 snapshot
    const advancedA = { ...threadA, headEntryId: 'h2', nextCommandSequence: '2' }
    await act(async () => {
      resolveStaleSnapshot(snapshotOf(advancedA, { entries: [{ entryId: 'h1', sessionId: 's1', parentEntryId: null, entryType: 'MESSAGE', payloadJson: '{}', createTime: null }] }))
      await submitPromise.catch(() => undefined)
    })

    // 关键断言：绝不发出第二次 acceptThreadCommandBatch 重试调用
    expect(harnessService.acceptThreadCommandBatch).toHaveBeenCalledTimes(1)
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
    const sendPromiseEpoch0 = new Promise<HarnessThreadCommandDTO[]>((resolve) => {
      resolveSendEpoch0 = () => resolve([])
    })
    let resolveSendEpoch2!: () => void
    const sendPromiseEpoch2 = new Promise<HarnessThreadCommandDTO[]>((resolve) => {
      resolveSendEpoch2 = () => resolve([])
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
        useAgentThreadController(tid, [], (parts) => {
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
    const sendPromiseEpoch0 = new Promise<HarnessThreadCommandDTO[]>((_resolve, reject) => {
      rejectSendEpoch0 = reject
    })
    let resolveSendEpoch2!: () => void
    const sendPromiseEpoch2 = new Promise<HarnessThreadCommandDTO[]>((resolve) => {
      resolveSendEpoch2 = () => resolve([])
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
        useAgentThreadController(tid, [], (parts) => {
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
      ({ tid }: { tid: string }) => useAgentThreadController(tid),
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
      return snapshotOf(tid === THREAD_ID ? threadA : threadB, {
        manualCompaction: { available: true, disabledReason: null },
      })
    })

    let resolveCompactEpoch0!: (res: unknown) => void
    const compactPromiseEpoch0 = new Promise((resolve) => {
      resolveCompactEpoch0 = resolve
    })

    let compactCallCount = 0
    vi.mocked(harnessService.compactThread).mockImplementation(async () => {
      compactCallCount += 1
      if (compactCallCount === 1) {
        return compactPromiseEpoch0 as Promise<{
          thread: typeof threadA
          turnStartEntryId: string
          modelInvocationId: string | null
        }>
      }
      return {
        thread: threadFixture({ threadId: THREAD_ID, version: '1' }),
        turnStartEntryId: 't1',
        modelInvocationId: null,
      }
    })

    const { result, rerender } = renderHook(
      ({ tid }: { tid: string }) => useAgentThreadController(tid),
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
        modelInvocationId: null,
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
      return {
        invocationId: 'inv-1',
        toolName: 'test',
        input: {},
        output: null,
        error: null,
        approval: { decision: 'ALLOW', decisionId: body.decisionId, reason: null },
      }
    })

    const { result, rerender } = renderHook(
      ({ tid }: { tid: string }) => useAgentThreadController(tid),
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
      resolveApprovalEpoch0({
        invocationId: 'inv-1',
        toolName: 'test',
        input: {},
        output: null,
        error: null,
        approval: { decision: 'ALLOW', decisionId: decisionIdEpoch0, reason: null },
      })
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
