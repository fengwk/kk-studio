import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import type {
  HarnessCancelledInputDTO,
  HarnessStoppedThreadReceiptDTO,
  HarnessThreadDTO,
  HarnessThreadSnapshotDTO,
  HarnessThreadStopResultDTO,
} from '@/shared/api/contracts/ai-runtime'
import { rootYoloPolicy } from '@/test-support/thread-yolo-policy'
import { createTextPart, partsToText } from '@/features/ai/composer/composer-parts'
import { useAgentThreadController } from '@/features/ai/runtime/useAgentThreadController'
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
} from '@/features/ai/runtime/thread-draft-store'
import { boundPendingStorageKey } from '@/features/ai/runtime/agent-pane/pane-target'
import { loadPendingStop } from '@/features/ai/runtime/pending-stop-sidecar'
import { translate as t } from '@/shared/i18n'
import { queryKeys } from '@/shared/lib/query-keys'
import { ApplicationEventProvider } from '@/shared/app-events'
import { FakeWebSocketHarness } from '@/shared/app-events/__tests__/fake-websocket'
import { agentService } from '@/shared/api/agent-service'
import { harnessService } from '@/shared/api/harness-service'

/**
 * 控制器层「Stop 回执合并 / 草稿持久化」的行为契约。
 *
 * 这里只替换 thread-draft-store 的持久化边界（IndexedDB 事务时机与失败），因为它无法在
 * jsdom 中确定性地延迟或失败；其余（草稿保存、快照、Stop 流程）都走真实实现。
 * 覆盖：迟到的回执合并绝不写入已重绑或已被新编辑取代的编辑区、失败回执不被当作已处理、
 * 以及保存/加载失败必须对用户可见。
 */

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

vi.mock('@/features/ai/runtime/thread-draft-store', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/features/ai/runtime/thread-draft-store')>()
  return {
    ...actual,
    applyStopReceipt: vi.fn(),
    loadThreadDraft: vi.fn(actual.loadThreadDraft),
    saveThreadDraftParts: vi.fn(actual.saveThreadDraftParts),
    saveThreadGoalText: vi.fn(actual.saveThreadGoalText),
    restoreThreadDraftParts: vi.fn(actual.restoreThreadDraftParts),
    restoreThreadGoalText: vi.fn(actual.restoreThreadGoalText),
  }
})

const THREAD_ID = '11111111-2222-4333-8444-555555555555'
const THREAD_ID_2 = 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee'

const applyStopReceiptMock = vi.mocked(applyStopReceipt)
const loadThreadDraftMock = vi.mocked(loadThreadDraft)
const saveThreadDraftPartsMock = vi.mocked(saveThreadDraftParts)
const saveThreadGoalTextMock = vi.mocked(saveThreadGoalText)
const restoreThreadDraftPartsMock = vi.mocked(restoreThreadDraftParts)
const restoreThreadGoalTextMock = vi.mocked(restoreThreadGoalText)

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

/** 需要显式触发快照重取的用例用同一个 client 渲染。 */
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

function threadFixture(
  overrides: Partial<HarnessThreadDTO> & { yoloEnabled?: boolean } = {},
): HarnessThreadDTO {
  const { yoloEnabled = false, ...rest } = overrides
  return {
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
    branchSettings: {
      agentName: 'assistant',
      model: {
        providerName: 'minimax',
        modelName: 'MiniMax',
        variant: 'default',
      },
      environmentName: null,
    },
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
      model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'default' },
      environmentName: null,
      yoloEnabled: false,
    },
    kind: 'GOAL',
    localDraft: [],
    goalText: 'pending goal',
    unknownOutcome: true,
  }
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

describe('useAgentThreadController draft restore', () => {
  beforeEach(() => {
    vi.clearAllMocks()
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
      totalCount: 0,
      results: [],
    })
    vi.mocked(harnessService.getThreadSnapshot).mockImplementation(async (threadId: string) =>
      snapshotOf(threadFixture({ threadId })))
    vi.mocked(harnessService.acceptThreadCommandBatch).mockResolvedValue([])
    vi.mocked(harnessService.stopThread).mockResolvedValue({
      status: 'STOPPED',
      thread: threadFixture(),
      stoppedThreads: [],
    } as HarnessThreadStopResultDTO)
  })

  afterEach(() => {
    applyStopReceiptMock.mockReset()
    loadThreadDraftMock.mockReset()
    saveThreadDraftPartsMock.mockReset()
    saveThreadGoalTextMock.mockReset()
    restoreThreadDraftPartsMock.mockReset()
    restoreThreadGoalTextMock.mockReset()
  })

  it('never writes a late receipt merge into a thread bound after the Stop started', async () => {
    const gate = deferred<StopReceiptMergeResult>()
    applyStopReceiptMock.mockReturnValue(gate.promise)
    vi.mocked(harnessService.stopThread).mockResolvedValue({
      status: 'STOPPED',
      thread: threadFixture(),
      stoppedThreads: [stopReceipt(THREAD_ID, 'stop-late', [cancelledUserMessage(1, 'cancelled')])],
    } as HarnessThreadStopResultDTO)

    const { result, rerender } = renderHook(
      ({ threadId }: { threadId: string }) => useAgentThreadController(threadId),
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
      ({ threadId }: { threadId: string }) => useAgentThreadController(threadId),
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

    const { result } = renderHook(() => useAgentThreadController(THREAD_ID), { wrapper })
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

    const { result, rerender } = renderHook(() => useAgentThreadController(THREAD_ID), { wrapper })
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

    const { result } = renderHook(() => useAgentThreadController(THREAD_ID), { wrapper })
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

    const { result } = renderHook(() => useAgentThreadController(THREAD_ID), { wrapper })
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

    const { result, rerender } = renderHook(() => useAgentThreadController(THREAD_ID), { wrapper })
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
      ({ threadId }: { threadId: string }) => useAgentThreadController(threadId),
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
      ({ threadId }: { threadId: string }) => useAgentThreadController(threadId),
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
      ({ threadId }: { threadId: string }) => useAgentThreadController(threadId),
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

    const { result } = renderHook(() => useAgentThreadController(THREAD_ID), { wrapper })
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
    const { result } = renderHook(() => useAgentThreadController(THREAD_ID), {
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
    const { result } = renderHook(() => useAgentThreadController(THREAD_ID), {
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

    const { result } = renderHook(() => useAgentThreadController(THREAD_ID), { wrapper })
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

    const { result } = renderHook(() => useAgentThreadController(THREAD_ID), {
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

    const { result } = renderHook(() => useAgentThreadController(THREAD_ID), { wrapper })
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

    const { result } = renderHook(() => useAgentThreadController(THREAD_ID), { wrapper })
    await waitFor(() => expect(result.current.disabled).toBe(false))

    act(() => result.current.setDraft([createTextPart('unsaved edit')]))
    await waitFor(() => expect(result.current.actionError).toBe(t('ai.runtime.action.draftSaveFailed')))
  })

  it('reports a draft load failure instead of showing a possibly stale editor', async () => {
    loadThreadDraftMock.mockRejectedValueOnce(new Error('IndexedDB open failed'))

    const { result } = renderHook(() => useAgentThreadController(THREAD_ID), { wrapper })
    await waitFor(() => expect(result.current.actionError).toBe(t('ai.runtime.action.draftLoadFailed')))
  })
})
