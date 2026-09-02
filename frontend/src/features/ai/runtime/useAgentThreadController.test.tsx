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
import { composerDraftStorageKey } from '@/features/ai/composer/composer-draft'
import { pendingStopStorageKey } from '@/features/ai/runtime/pending-stop-sidecar'
import { ApplicationEventProvider } from '@/shared/app-events'
import { FakeWebSocketHarness } from '@/shared/app-events/__tests__/fake-websocket'
import { agentService } from '@/shared/api/agent-service'
import { ApiError } from '@/shared/api/client'
import { harnessService } from '@/shared/api/harness-service'
import { agentPaneService } from '@/shared/api/agent-pane-service'
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
vi.mock('@/shared/api/agent-pane-service', () => ({
  agentPaneService: {
    getThreadSnapshot: vi.fn(),
    acceptCommandBatch: vi.fn(),
    compactThread: vi.fn(),
  },
}))
vi.mock('@/shared/api/harness-service', () => ({
  harnessService: {
    getSystemPromptPreview: vi.fn(),
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
    workspacePath: null,
    agentName: 'assistant',
    model: modelSelection(),
    ...overrides,
  }
}

function threadFixture(overrides: Partial<HarnessThreadDTO> = {}): HarnessThreadDTO {
  return {
    threadId: THREAD_ID,
    sessionId: 's1',
    headEntryId: 'h1',
    yoloEnabled: false,
    nextCommandSequence: '1',
    version: '0',
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
    version: currentThread.version,
    thread: currentThread,
    entries: [],
    queuedCommands: [],
    modelInvocation: null,
    toolInvocations: [],
    modelAttemptFailures: [],
    ...extras,
  }
}

const assistantAgentEntry = {
  name: 'assistant',
  description: null,
  systemPrompt: null,
  model: 'minimax/MiniMax',
  variant: 'default',
  config: { toolIds: [], skills: [], subagents: [] },
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
    vi.mocked(agentPaneService.getThreadSnapshot).mockResolvedValue(snapshotOf(threadFixture()))
    vi.mocked(agentPaneService.acceptCommandBatch).mockResolvedValue(
      [] as HarnessThreadCommandDTO[],
    )
    vi.mocked(harnessService.stopThread).mockResolvedValue({
      status: 'IDLE',
      thread: threadFixture(),
      stoppedTurnEndEntryId: null,
      cancelledCommandCount: 0,
      cancelledUserMessages: [],
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
          toolVersion: '1',
          rendererKey: 'demo',
          toolId: 'base.demo',
          environment: null,
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
    vi.mocked(agentPaneService.getThreadSnapshot).mockResolvedValue(snapshotOf(currentThread))

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
          undefined,
          buildBatchFor(currentThread, base, draft),
          new Map(),
        ),
      { wrapper },
    )
    await waitFor(() => expect(result.current.disabled).toBe(false))

    act(() => result.current.setDraft([createTextPart('hello world')]))
    await act(async () => {
      await result.current.submitMessage()
    })

    await waitFor(() => expect(agentPaneService.acceptCommandBatch).toHaveBeenCalledTimes(1))
    const [batchArg] = vi.mocked(agentPaneService.acceptCommandBatch).mock.calls[0]!
    expect(batchArg.target.expectedHeadEntryId).toBe('h1')
    expect(batchArg.target.expectedNextCommandSequence).toBe('1')
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
    vi.mocked(agentPaneService.acceptCommandBatch).mockRejectedValueOnce(
      new ApiError('expected version mismatch', 409),
    )
    const currentThread = threadFixture()
    const base = branchDraftFromThread(currentThread)
    const draft = base
    const snapshotCallsBefore = vi.mocked(agentPaneService.getThreadSnapshot).mock.calls.length

    const { result } = renderHook(
      () =>
        useAgentThreadController(
          currentThread.threadId,
          [],
          undefined,
          buildBatchFor(currentThread, base, draft),
          new Map(),
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
      expect(agentPaneService.getThreadSnapshot.mock.calls.length).toBeGreaterThan(
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
    vi.mocked(agentPaneService.getThreadSnapshot)
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
    vi.mocked(agentPaneService.acceptCommandBatch)
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
          undefined,
          buildBatchFor(currentThread, base, base),
          new Map(),
        ),
      { wrapper },
    )
    await waitFor(() => expect(result.current.disabled).toBe(false))

    act(() => result.current.setDraft([createTextPart('queue after current turn')]))
    await act(async () => {
      await result.current.submitMessage()
    })

    const calls = vi.mocked(agentPaneService.acceptCommandBatch).mock.calls
    expect(calls).toHaveLength(2)
    expect(calls[0]?.[0]).toMatchObject({
      target: {
        expectedHeadEntryId: 'h1',
        expectedNextCommandSequence: '1',
      },
    })
    expect(calls[1]?.[0]).toMatchObject({
      target: {
        expectedHeadEntryId: 'h2',
        expectedNextCommandSequence: '2',
      },
    })
    expect(calls[1]?.[0].commands[0]?.idempotencyKey).toBe(
      calls[0]?.[0].commands[0]?.idempotencyKey,
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
    vi.mocked(agentPaneService.getThreadSnapshot)
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
    vi.mocked(agentPaneService.acceptCommandBatch).mockRejectedValueOnce(
      new ApiError('stale', 409, 'CONFLICT', { reason: 'STALE_COMMAND_CURSOR' }),
    )
    const base = branchDraftFromThread(currentThread)

    const { result } = renderHook(
      () =>
        useAgentThreadController(
          currentThread.threadId,
          [],
          undefined,
          buildBatchFor(currentThread, base, base),
          new Map(),
        ),
      { wrapper },
    )
    await waitFor(() => expect(result.current.disabled).toBe(false))

    act(() => result.current.setDraft([createTextPart('do not move across branches')]))
    await act(async () => {
      await result.current.submitMessage()
    })

    expect(agentPaneService.acceptCommandBatch).toHaveBeenCalledTimes(1)
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
    vi.mocked(agentPaneService.acceptCommandBatch).mockRejectedValueOnce(
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
          undefined,
          buildBatchFor(currentThread, base, draft),
          new Map(),
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
    const batch = vi.mocked(agentPaneService.acceptCommandBatch).mock.calls[0]?.[0]
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
    vi.mocked(agentPaneService.acceptCommandBatch)
      .mockRejectedValueOnce(new Error('queue full'))
      .mockResolvedValueOnce([] as HarnessThreadCommandDTO[])

    const { result } = renderHook(
      () =>
        useAgentThreadController(
          currentThread.threadId,
          [],
          undefined,
          buildBatchFor(currentThread, base, draft),
          new Map(),
        ),
      { wrapper },
    )
    await waitFor(() => expect(result.current.disabled).toBe(false))

    act(() => result.current.setDraft([createTextPart('retry me')]))
    await act(async () => {
      await result.current.submitMessage()
    })
    await waitFor(() => expect(agentPaneService.acceptCommandBatch).toHaveBeenCalledTimes(1))
    const firstBatch = vi.mocked(agentPaneService.acceptCommandBatch).mock.calls[0]?.[0]
    expect(firstBatch).toBeDefined()
    expect(partsToText(result.current.draft)).toBe('retry me')

    await act(async () => {
      await result.current.submitMessage()
    })
    await waitFor(() => expect(agentPaneService.acceptCommandBatch).toHaveBeenCalledTimes(2))
    const secondBatch = vi.mocked(agentPaneService.acceptCommandBatch).mock.calls[1]?.[0]
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
    vi.mocked(agentPaneService.acceptCommandBatch)
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
          undefined,
          buildBatch,
          new Map(),
        ),
      { wrapper },
    )
    await waitFor(() => expect(result.current.disabled).toBe(false))

    act(() => result.current.setDraft([createTextPart('retry me')]))
    await act(async () => {
      await result.current.submitMessage()
    })
    await waitFor(() => expect(agentPaneService.acceptCommandBatch).toHaveBeenCalledTimes(1))
    const firstBatch = vi.mocked(agentPaneService.acceptCommandBatch).mock.calls[0]?.[0]
    expect(firstBatch?.commands).toHaveLength(2) // SET_AGENT + USER_MESSAGE

    await act(async () => {
      await result.current.submitMessage()
    })
    await waitFor(() => expect(agentPaneService.acceptCommandBatch).toHaveBeenCalledTimes(2))
    const secondBatch = vi.mocked(agentPaneService.acceptCommandBatch).mock.calls[1]?.[0]
    // 精确回放：必须复用同一个 batch 对象（SET_AGENT + USER_MESSAGE），不能退化为只含消息的 batch。
    expect(secondBatch).toBe(firstBatch)
    expect(secondBatch?.commands).toHaveLength(2)
  })

  it('mints a new batch when the draft diverges from the failed replay', async () => {
    const currentThread = threadFixture()
    const base = branchDraftFromThread(currentThread)
    const draft = base
    vi.mocked(agentPaneService.acceptCommandBatch)
      .mockRejectedValueOnce(new Error('queue full'))
      .mockResolvedValue([] as HarnessThreadCommandDTO[])

    const { result } = renderHook(
      () =>
        useAgentThreadController(
          currentThread.threadId,
          [],
          undefined,
          buildBatchFor(currentThread, base, draft),
          new Map(),
        ),
      { wrapper },
    )
    await waitFor(() => expect(result.current.disabled).toBe(false))

    act(() => result.current.setDraft([createTextPart('retry me')]))
    await act(async () => {
      await result.current.submitMessage()
    })
    const firstId = vi.mocked(agentPaneService.acceptCommandBatch).mock.calls[0]?.[0]
      .commands[0]?.idempotencyKey

    // 将 composer 编辑成不同内容时，会重置回放身份。
    act(() => result.current.setDraft([createTextPart('changed content')]))
    await act(async () => {
      await result.current.submitMessage()
    })
    const secondId = vi.mocked(agentPaneService.acceptCommandBatch).mock.calls[1]?.[0]
      .commands[0]?.idempotencyKey
    expect(secondId).toBeTruthy()
    expect(secondId).not.toBe(firstId)
  })

  it('surfaces non-conflict send failures with the draft restored', async () => {
    vi.mocked(agentPaneService.acceptCommandBatch).mockRejectedValueOnce(new Error('queue full'))
    const currentThread = threadFixture()
    const base = branchDraftFromThread(currentThread)
    const draft = base

    const { result } = renderHook(
      () =>
        useAgentThreadController(
          currentThread.threadId,
          [],
          undefined,
          buildBatchFor(currentThread, base, draft),
          new Map(),
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
          undefined,
          buildBatchFor(currentThread, base, draft),
          new Map(),
        ),
      { wrapper },
    )
    await waitFor(() => expect(result.current.disabled).toBe(false))

    act(() => result.current.setDraft([createTextPart('hello world')]))
    await act(async () => {
      await result.current.submitMessage()
    })
    await waitFor(() => expect(agentPaneService.acceptCommandBatch).toHaveBeenCalledTimes(1))
    expect(partsToText(result.current.draft)).toBe('')
  })

  it('stops the Thread with a stable stopRequestId across a retry after failure', async () => {
    vi.mocked(harnessService.stopThread)
      .mockRejectedValueOnce(new Error('network unavailable'))
      .mockResolvedValueOnce({
        status: 'IDLE',
        thread: threadFixture(),
        stoppedTurnEndEntryId: null,
        cancelledCommandCount: 0,
        cancelledUserMessages: [],
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
        stoppedTurnEndEntryId: null,
        cancelledCommandCount: 2,
        cancelledUserMessages: [
          {
            sequence: '1',
            idempotencyKey: 'c1',
            messageJson:
              '{"role":"USER","contents":[{"type":"text","text":"cancelled"}]}',
          },
          {
            sequence: '2',
            idempotencyKey: 'c2',
            messageJson:
              '{"role":"USER","contents":[{"type":"resource","blobId":"00000000-0000-0000-0000-000000000001","name":"a.txt","preview":"p"}]}',
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
      stoppedTurnEndEntryId: null,
      cancelledCommandCount: 1,
      cancelledUserMessages: [
        {
          sequence: '1',
          idempotencyKey: 'c1',
          messageJson:
            '{"role":"USER","contents":[{"type":"text","text":"cancelled"}]}',
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
      stoppedTurnEndEntryId: null,
      cancelledCommandCount: 0,
      cancelledUserMessages: [],
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
    vi.mocked(agentPaneService.getThreadSnapshot)
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
      status: 'IDLE',
      thread: threadFixture({ headEntryId: 'e-turn1-end', version: '2' }),
      stoppedTurnEndEntryId: null,
      cancelledCommandCount: 0,
      cancelledUserMessages: [],
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
        status: 'IDLE',
        thread: threadFixture(),
        stoppedTurnEndEntryId: null,
        cancelledCommandCount: 0,
        cancelledUserMessages: [],
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
        status: 'IDLE',
        thread: threadFixture(),
        stoppedTurnEndEntryId: null,
        cancelledCommandCount: 0,
        cancelledUserMessages: [],
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
    vi.mocked(agentPaneService.getThreadSnapshot)
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
      status: 'IDLE',
      thread: turn2,
      stoppedTurnEndEntryId: null,
      cancelledCommandCount: 0,
      cancelledUserMessages: [],
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
        toolVersion: '1',
        rendererKey: 'demo',
        toolId: 'base.demo',
        environment: null,
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
      actor: 'web',
      reason: null,
    })
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
        toolVersion: '1',
        rendererKey: 'bash',
        toolId: 'base.bash',
        environment: null,
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

    const initialCalls = vi.mocked(agentPaneService.getThreadSnapshot).mock.calls.length
    await act(async () => {
      await result.current.stopThread()
    })
    await waitFor(() =>
      expect(agentPaneService.getThreadSnapshot.mock.calls.length).toBeGreaterThan(initialCalls),
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
          config: { toolIds: [], skills: [], subagents: [] },
          version: '1',
          createTime: null,
          updateTime: null,
        },
      ],
    })
    const currentThread = threadFixture({
      branchSettings: branchSettings({
        workspacePath: 'proj/a',
        agentName: 'assistant',
      }),
    })
    vi.mocked(agentPaneService.getThreadSnapshot).mockResolvedValue(snapshotOf(currentThread))

    const { result } = renderHook(() => useAgentThreadController(currentThread.threadId), {
      wrapper,
    })
    await waitFor(() => expect(result.current.disabled).toBe(false))
    expect(result.current.runtimeLabels.environment).toEqual({
      environmentId: 'env-local',
      workspacePath: 'proj/a',
    })
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
    vi.mocked(agentPaneService.getThreadSnapshot).mockResolvedValue(
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
    })
  })

  it('clears the exact replay on 409 so the retry mints fresh command ids and cursors', async () => {
    const currentThread = threadFixture({
      version: '1',
      nextCommandSequence: '3',
      headEntryId: 'h1',
    })
    vi.mocked(agentPaneService.getThreadSnapshot).mockResolvedValue(snapshotOf(currentThread))
    vi.mocked(agentPaneService.acceptCommandBatch).mockRejectedValueOnce(
      new ApiError('conflict', 409, 'CONFLICT'),
    )
    vi.mocked(agentPaneService.acceptCommandBatch).mockResolvedValueOnce([])
    const base = branchDraftFromThread(currentThread)

    const { result } = renderHook(
      () =>
        useAgentThreadController(
          currentThread.threadId,
          [],
          undefined,
          buildBatchFor(currentThread, base, base),
          new Map(),
        ),
      { wrapper },
    )
    await waitFor(() => expect(result.current.disabled).toBe(false))

    act(() => result.current.setDraft([createTextPart('retry me')]))
    await act(async () => {
      await result.current.submitMessage()
      await result.current.submitMessage()
    })

    const calls = vi.mocked(agentPaneService.acceptCommandBatch).mock.calls
    expect(calls).toHaveLength(2)
    const first = calls[0]?.[0] as {
      target: {
        expectedHeadEntryId: string
        expectedNextCommandSequence: string
      }
      commands: Array<{ idempotencyKey: string }>
    }
    const second = calls[1]?.[0] as {
      target: {
        expectedHeadEntryId: string
        expectedNextCommandSequence: string
      }
      commands: Array<{ idempotencyKey: string }>
    }
    // 409 = 该 batch 未被接受：重试使用刷新后的 head/nextSequence
    // 以及全新的 command id，而不是回放陈旧的 batch。
    expect(second.target.expectedHeadEntryId).toBe('h1')
    expect(second.target.expectedNextCommandSequence).toBe('3')
    expect(second.commands[0]?.idempotencyKey).not.toBe(first.commands[0]?.idempotencyKey)
    // 网络/不确定失败会保留精确 batch；409 不能这样做。
    void first
    void second
  })

  it('mints a new decision id when the user switches ALLOW -> DENY for the same invocation', async () => {
    vi.mocked(agentPaneService.getThreadSnapshot).mockResolvedValue(snapshotOf(threadFixture()))
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
    vi.mocked(agentPaneService.acceptCommandBatch).mockRejectedValueOnce(null)
    const currentThread = threadFixture()
    const base = branchDraftFromThread(currentThread)
    const { result } = renderHook(
      () =>
        useAgentThreadController(
          currentThread.threadId,
          [],
          undefined,
          buildBatchFor(currentThread, base, base),
          new Map(),
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
    vi.mocked(agentPaneService.getThreadSnapshot).mockReturnValue(
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
    expect(agentPaneService.compactThread).not.toHaveBeenCalled()
    expect(result.current.actionError).toBeNull()
  })

  it('reports threadNotLoaded without a batch builder and silently no-ops a null plan', async () => {
    const currentThread = threadFixture()
    vi.mocked(agentPaneService.getThreadSnapshot).mockResolvedValue(snapshotOf(currentThread))
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
    expect(agentPaneService.acceptCommandBatch).not.toHaveBeenCalled()

    // buildBatch 返回 null（不可构建的 payload）：静默 no-op，draft 原样保留。
    const { result: nullPlan } = renderHook(
      () =>
        useAgentThreadController(
          currentThread.threadId,
          [],
          undefined,
          () => null,
          new Map(),
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
    expect(agentPaneService.acceptCommandBatch).not.toHaveBeenCalled()
  })

  it('never overwrites a newer composer draft when an earlier send fails', async () => {
    vi.mocked(agentPaneService.acceptCommandBatch).mockRejectedValueOnce(new Error('queue full'))
    const currentThread = threadFixture()
    const base = branchDraftFromThread(currentThread)
    const { result } = renderHook(
      () =>
        useAgentThreadController(
          currentThread.threadId,
          [],
          undefined,
          buildBatchFor(currentThread, base, base),
          new Map(),
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
    // 失败时 composer 已有更新内容：绝不覆盖为失败快照；编辑本身已重置回放身份。
    expect(partsToText(result.current.draft)).toBe('newer draft')
    expect(result.current.replayPending).toBe(false)
  })

  it('only clears the exact replay when the completing request is still the latest one', async () => {
    const releases: Array<() => void> = []
    vi.mocked(agentPaneService.acceptCommandBatch).mockImplementation(
      () =>
        new Promise<HarnessThreadCommandDTO[]>((resolve) => {
          releases.push(() => resolve([] as HarnessThreadCommandDTO[]))
        }),
    )
    const currentThread = threadFixture()
    const base = branchDraftFromThread(currentThread)
    const { result } = renderHook(
      () =>
        useAgentThreadController(
          currentThread.threadId,
          [],
          undefined,
          buildBatchFor(currentThread, base, base),
          new Map(),
        ),
      { wrapper },
    )
    await waitFor(() => expect(result.current.disabled).toBe(false))
    act(() => result.current.setDraft([createTextPart('first')]))
    await act(async () => {
      // 两个请求都 deferred：不能 await 完成，只启动它们。
      void result.current.submitMessage()
    })
    act(() => result.current.setDraft([createTextPart('second')]))
    await act(async () => {
      void result.current.submitMessage()
    })
    expect(agentPaneService.acceptCommandBatch).toHaveBeenCalledTimes(2)
    const firstPlan = vi.mocked(agentPaneService.acceptCommandBatch).mock.calls[0]?.[0]
    const secondPlan = vi.mocked(agentPaneService.acceptCommandBatch).mock.calls[1]?.[0]
    expect(secondPlan?.commands[0]?.idempotencyKey).not.toBe(
      firstPlan?.commands[0]?.idempotencyKey,
    )
    // 较新的请求先完成：replay 被清空。
    await act(async () => {
      releases[1]?.()
    })
    await waitFor(() => expect(result.current.replayPending).toBe(false))
    expect(result.current.pending).toBe(true)
    // 较旧的请求随后完成：身份已不匹配，绝不再触碰 replay/状态。
    await act(async () => {
      releases[0]?.()
    })
    await waitFor(() => expect(result.current.pending).toBe(false))
    expect(result.current.actionError).toBeNull()
    expect(result.current.replayPending).toBe(false)
  })

  it('skips localStorage persistence for non-edit draft change sources', async () => {
    const currentThread = threadFixture()
    vi.mocked(agentPaneService.getThreadSnapshot).mockResolvedValue(snapshotOf(currentThread))
    const { result } = renderHook(() => useAgentThreadController(currentThread.threadId), {
      wrapper,
    })
    await waitFor(() => expect(result.current.disabled).toBe(false))
    act(() => result.current.setDraft([createTextPart('edit draft')]))
    const persisted = localStorage.getItem(composerDraftStorageKey(`thread:${THREAD_ID}`))
    expect(persisted).toContain('edit draft')
    // history 来源不写 storage：旧值原样保留。
    act(() => result.current.setDraft([createTextPart('history draft')], 'history'))
    expect(localStorage.getItem(composerDraftStorageKey(`thread:${THREAD_ID}`))).toBe(persisted)
    expect(partsToText(result.current.draft)).toBe('history draft')
  })

  it('refuses stale retry when cursors are unchanged, commands are empty, or the target is not a THREAD', () => {
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
    // 非 THREAD target（ENTRY）：必须重建而不是回放。
    const entryPlan: CommandBatchPlan = {
      ...messagePlan,
      request: {
        ...messagePlan.request,
        target: {
          type: 'ENTRY',
          sessionId: 's1',
          startEntryId: 'e1',
          threadId: THREAD_ID,
          yoloEnabled: false,
        },
      },
    }
    expect(
      canRetryStaleMessageBatch(
        entryPlan,
        snapshotOf(threadFixture({ headEntryId: 'h2', nextCommandSequence: '2' })),
      ),
    ).toBe(false)
  })

  it('runs manual compaction with the snapshot version and clears conflict on success', async () => {
    vi.mocked(agentPaneService.getThreadSnapshot).mockResolvedValue(
      snapshotOf(threadFixture(), { manualCompaction: { available: true, disabledReason: null } }),
    )
    vi.mocked(agentPaneService.compactThread).mockResolvedValue({
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
    expect(agentPaneService.compactThread).toHaveBeenCalledWith(THREAD_ID, {
      expectedVersion: '0',
    })
    expect(invalidateSpy).toHaveBeenCalledWith(
      expect.objectContaining({ queryKey: ['threads', 'snapshot', THREAD_ID] }),
    )
    expect(result.current.actionError).toBeNull()
    invalidateSpy.mockRestore()
  })

  it('reports compaction unavailability and surfaces 409/non-conflict failures distinctly', async () => {
    vi.mocked(agentPaneService.getThreadSnapshot).mockResolvedValue(
      snapshotOf(threadFixture(), { manualCompaction: { available: true, disabledReason: null } }),
    )
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    const invalidateSpy = vi.spyOn(client, 'invalidateQueries')
    // 409：conflict 展示 + 目标 snapshot 失效；draft/错误通道不受影响。
    vi.mocked(agentPaneService.compactThread).mockRejectedValueOnce(
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
    vi.mocked(agentPaneService.compactThread).mockRejectedValueOnce(new Error('compact boom'))
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
    expect(agentPaneService.compactThread).not.toHaveBeenCalled()
    // 无 advisory sidecar：使用 fallback 的 disabledReason。
    expect(result.current.actionError).toContain('Thread snapshot is not loaded')
  })

  it('routes runCommand to stop/compact and reports unknown commands', async () => {
    vi.mocked(agentPaneService.getThreadSnapshot).mockResolvedValue(
      snapshotOf(threadFixture(), { manualCompaction: { available: true, disabledReason: null } }),
    )
    vi.mocked(agentPaneService.compactThread).mockResolvedValue({
      thread: threadFixture(),
      turnStartEntryId: 't1',
      modelInvocationId: null,
    })
    const { result } = renderHook(() => useAgentThreadController(THREAD_ID), { wrapper })
    await waitFor(() => expect(result.current.disabled).toBe(false))
    act(() => result.current.runCommand({ id: 'stop', label: 'stop', description: 'stop' }))
    await waitFor(() => expect(harnessService.stopThread).toHaveBeenCalledTimes(1))
    act(() => result.current.runCommand({ id: 'compact', label: 'compact', description: 'compact' }))
    await waitFor(() => expect(agentPaneService.compactThread).toHaveBeenCalledTimes(1))
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
    vi.mocked(agentPaneService.getThreadSnapshot).mockImplementation((threadId) =>
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
        status: 'IDLE',
        thread: threadFixture({ threadId: THREAD_ID }),
        stoppedTurnEndEntryId: null,
        cancelledCommandCount: 0,
        cancelledUserMessages: [],
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
    vi.mocked(agentPaneService.getThreadSnapshot).mockImplementation((threadId) =>
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
    vi.mocked(agentPaneService.getThreadSnapshot).mockImplementation((threadId) =>
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
})
