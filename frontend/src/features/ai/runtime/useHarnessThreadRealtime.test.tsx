import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}
import { ApplicationEventProvider } from '@/shared/app-events'
import { FakeWebSocketHarness } from '@/shared/app-events/__tests__/fake-websocket'
import { useHarnessThreadRealtime } from '@/features/ai/runtime/useHarnessThreadRealtime'
import type { RealtimeFrameSchedulerOptions } from '@/features/ai/runtime/realtime-frame-scheduler'
import type {
  ModelAttemptFailureDTO,
  ModelInvocationDTO,
  ToolInvocationDTO,
} from '@/shared/api/contracts/ai-runtime'

const THREAD_ID = '11111111-2222-4333-8444-555555555555'
const THREAD_A_ID = 'aaaaaaaa-0000-4000-8000-000000000001'
const THREAD_B_ID = 'bbbbbbbb-0000-4000-8000-000000000002'
const threadResource = { kind: 'thread', id: THREAD_ID } as const

function resource(threadId: string) {
  return { kind: 'thread', id: threadId } as const
}

interface RealtimeProps {
  threadId?: string
  version?: string
  enabled?: boolean
  invocation?: ModelInvocationDTO | null
  invocations?: ToolInvocationDTO[]
  modelAttemptFailures?: ModelAttemptFailureDTO[]
  schedulerOptions?: Partial<RealtimeFrameSchedulerOptions>
}

function renderRealtime(client: QueryClient, initialProps: RealtimeProps = {}) {
  const sockets = new FakeWebSocketHarness()
  const rendered = renderHook(
    (props: RealtimeProps) =>
      useHarnessThreadRealtime(
        props.threadId ?? THREAD_ID,
        props.enabled ?? true,
        props.version ?? '42',
        props.invocation ?? modelInvocation(),
        props.invocations ?? [],
        props.modelAttemptFailures ?? [],
        props.schedulerOptions,
      ),
    {
      initialProps,
      wrapper: ({ children }: { children: ReactNode }) => (
        <QueryClientProvider client={client}>
          <ApplicationEventProvider url="ws://test/events/v1" socketFactory={sockets.factory}>
            {children}
          </ApplicationEventProvider>
        </QueryClientProvider>
      ),
    },
  )
  return { ...rendered, sockets }
}

function emitRealtime(sockets: FakeWebSocketHarness, data: string, threadId = THREAD_ID) {
  const socket = sockets.latest
  if (socket == null) {
    throw new Error('no socket created')
  }
  // 协议 data 是 delta envelope 的 JSON 对象。
  act(() =>
    socket.emitServer({
      type: 'event',
      resource: resource(threadId),
      name: 'realtime',
      data: JSON.parse(data),
    }),
  )
}

describe('useHarnessThreadRealtime', () => {
  afterEach(() => vi.clearAllMocks())

  it('uses one wire subscription, invalidates durable signals, and overlays the next delta', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const invalidate = vi.spyOn(client, 'invalidateQueries')
    const { result, rerender, sockets } = renderRealtime(client, { version: '42' })
    const socket = sockets.openLatest()

    // 订阅建立（subscribed ack）与 durable 信号都触发 snapshot 对账；
    // realtime 事件只走 overlay reducer，绝不触发 invalidate。
    act(() => emitRealtime(sockets, realtime(1, 'partial')))
    await waitFor(() => expect(result.current?.modelStream?.text).toBe('partial'))
    act(() => {
      socket.emitServer({ type: 'subscribed', resource: threadResource, cursor: '42' })
      socket.emitServer({
        type: 'event',
        resource: threadResource,
        name: 'version',
        data: { version: '43' },
        cursor: '43',
      })
      socket.emitServer({ type: 'resync', resource: threadResource })
      socket.emitServer({ type: 'error', resource: threadResource, code: 'SUBSCRIBE_FAILED', message: 'boom' })
    })
    // version 前进不重建订阅：wire 上始终只有一条 subscribe。
    rerender({ version: '43' })
    expect(socket.sentMessages()).toEqual([{ version: 1, type: 'subscribe', resource: threadResource }])
    expect(invalidate).toHaveBeenCalledTimes(4)
  })

  it('single-flight gap recovery re-refetches with bounded backoff until the checkpoint catches up', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const invalidate = vi.spyOn(client, 'invalidateQueries')
    const { result, rerender, sockets } = renderRealtime(client, { invocation: modelInvocation() })
    sockets.openLatest()

    // 重复且相同的 gap delta 只触发一次恢复循环（永不按每条 delta 单独 refetch）。
    emitRealtime(sockets, realtime(3, 'missing'))
    emitRealtime(sockets, realtime(3, 'missing'))
    expect(result.current?.modelStream).toBeNull()
    await waitFor(() => expect(invalidate.mock.calls.length).toBe(1), { timeout: 2000 })


    // 首次 refetch 发现 checkpoint 仍然落后（数据未变化）：循环会按 backoff
    // 重新挂载自己并再次 refetch，而不是停滞。
    await waitFor(
      () => expect(invalidate.mock.calls.length).toBeGreaterThanOrEqual(2),
      { timeout: 3000 },
    )

    // checkpoint 追平：overlay 出现，循环停止。
    rerender({
      invocation: {
        ...modelInvocation(),
        streamCheckpointJson: '{"attempt":1,"text":"recovered","thinking":"","sequence":3}',
      },
    })
    await waitFor(() => expect(result.current?.modelStream?.text).toBe('recovered'))

    const callsAfterCatchUp = invalidate.mock.calls.length
    await sleep(600)
    expect(invalidate.mock.calls.length).toBe(callsAfterCatchUp)

    // 终止态结果但没有 resultEntryId：完整的持久 result 投影会替换
    // overlay（status 'done'），并一直保留到 resultEntryId 被设置。
    rerender({
      invocation: {
        ...modelInvocation(),
        resultJson:
          '{"text":"recovered","thinking":"","toolCalls":[],"stopReason":"stop","usage":{},"cost":{}}',
      },
    })
    await waitFor(() =>
      expect(result.current?.modelStream?.text).toBe('recovered'),
    )
    expect(result.current?.modelStream?.status).toBe('done')
    // 设置 resultEntryId 后：持久 Entry 才是 transcript 的真实来源。
    rerender({ invocation: { ...modelInvocation(), resultJson: '{}', resultEntryId: 'e-9' } })
    await waitFor(() => expect(result.current?.modelStream).toBeNull())
  })

  it('does not let a stale Thread recovery tick cancel or starve a new Thread recovery', async () => {
    vi.useFakeTimers()
    try {
      const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
      const invalidate = vi.spyOn(client, 'invalidateQueries')
      // 首次 refetch（旧 Thread A 的恢复）会一直处于 in-flight，直到手动 resolve。
      let resolveFirstRefetch: (() => void) | null = null
      let firstRefetch = true
      invalidate.mockImplementation(async (options) => {
        const key = (options?.queryKey ?? []) as readonly unknown[]
        const refetchThreadId = String(key[key.length - 1] ?? '')
        if (firstRefetch && refetchThreadId === THREAD_A_ID) {
          firstRefetch = false
          return new Promise<void>((resolve) => {
            resolveFirstRefetch = resolve
          })
        }
        return Promise.resolve()
      })

      const { result, rerender, sockets } = renderRealtime(client, {
        threadId: THREAD_A_ID,
        invocation: modelInvocation('inv-A', THREAD_A_ID),
      })
      sockets.openLatest()

      // Thread A 的 gap delta 会挂起一次恢复 A；它的首次 tick 会卡在 in-flight 的 refetch 上。
      emitRealtime(sockets, realtime(3, 'missing', THREAD_A_ID, 'inv-A'), THREAD_A_ID)
      await act(async () => {
        await vi.advanceTimersByTimeAsync(200)
      })
      expect(invalidate).toHaveBeenCalledTimes(1)

      // 在 A 的 refetch 仍在 in-flight 时切到 Thread B；B 自己的 gap 独立计算。
      // 由于旧 tick 仍持有 busy flag，新挂起的调度被跳过。B 的订阅 effect
      // 还需要再渲染一次（订阅状态的回路往返）。
      rerender({ threadId: THREAD_B_ID, invocation: modelInvocation('inv-B', THREAD_B_ID) })
      await act(async () => {
        await vi.advanceTimersByTimeAsync(0)
      })
      emitRealtime(sockets, realtime(3, 'missing', THREAD_B_ID, 'inv-B'), THREAD_B_ID)

      // A 的陈旧 refetch 完成：绝不能因此清除 B 的恢复，并且重新挂起时
      // 必须调度 B（旧 tick 的 finally 会清除 busy flag）。
      await act(async () => {
        resolveFirstRefetch?.()
        await vi.advanceTimersByTimeAsync(0)
      })
      await act(async () => {
        await vi.advanceTimersByTimeAsync(200)
      })
      expect(
        invalidate.mock.calls.some(
          (call) => String((call[0]?.queryKey as readonly unknown[])?.at(-1)) === THREAD_B_ID,
        ),
      ).toBe(true)

      // B 追平：overlay 出现，循环停止。
      rerender({
        threadId: THREAD_B_ID,
        invocation: {
          ...modelInvocation('inv-B', THREAD_B_ID),
          streamCheckpointJson: '{"attempt":1,"text":"recovered","thinking":"","sequence":3}',
        },
      })
      await act(async () => {
        await vi.advanceTimersByTimeAsync(0)
      })
      expect(result.current?.modelStream?.text).toBe('recovered')
      // 让循环再跑一次追平检测，然后验证循环已停止。
      await act(async () => {
        await vi.advanceTimersByTimeAsync(800)
      })
      const callsAfterCatchUp = invalidate.mock.calls.length
      await act(async () => {
        await vi.advanceTimersByTimeAsync(1200)
      })
      expect(invalidate.mock.calls.length).toBe(callsAfterCatchUp)
    } finally {
      vi.useRealTimers()
    }
  })

  it('survives a failed refetch: keeps backing off and catches up on a later attempt', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const invalidate = vi.spyOn(client, 'invalidateQueries')
    // 首次 refetch 失败（网络/后端故障），后续 refetch 成功。循环绝不能因
    // 残留的 recoveryRef 又没有定时器而冻结。
    invalidate
      .mockRejectedValueOnce(new Error('refetch failed'))
      .mockResolvedValue(undefined)
    const { result, rerender, sockets } = renderRealtime(client, { invocation: modelInvocation() })
    sockets.openLatest()

    emitRealtime(sockets, realtime(3, 'missing'))
    expect(result.current?.modelStream).toBeNull()
    // 首次 tick 失败仍会重新挂起：必须接着执行第二次（成功的）tick。
    await waitFor(() => expect(invalidate).toHaveBeenCalledTimes(2), { timeout: 4000 })

    // 在后续一次 refetch 中 checkpoint 追平，循环随后停止。
    rerender({
      invocation: {
        ...modelInvocation(),
        streamCheckpointJson: '{"attempt":1,"text":"recovered","thinking":"","sequence":3}',
      },
    })
    await waitFor(() => expect(result.current?.modelStream?.text).toBe('recovered'))
    const callsAfterCatchUp = invalidate.mock.calls.length
    await sleep(600)
    expect(invalidate.mock.calls.length).toBe(callsAfterCatchUp)
  })

  it('rejects late MODEL_DELTA after a durable terminal result and after the Entry lands', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const { result, rerender, sockets } = renderRealtime(client, { invocation: modelInvocation() })
    sockets.openLatest()

    // 进行中的 delta 按正常方式叠加。
    emitRealtime(sockets, realtime(1, 'first'))
    await waitFor(() => expect(result.current?.modelStream?.text).toBe('first'))

    // resultJson 到达（终止态，尚未有 Entry）：完整的持久 result 投影会
    // 替换 overlay；迟到的 delta 永远不会追加到终止态输出。
    rerender({
      invocation: {
        ...modelInvocation(),
        resultJson:
          '{"text":"first","thinking":"","toolCalls":[],"stopReason":"stop","usage":{},"cost":{}}',
      },
    })
    await waitFor(() =>
      expect(result.current?.modelStream?.text).toBe('first'),
    )
    expect(result.current?.modelStream?.status).toBe('done')
    emitRealtime(sockets, realtime(2, '-late-after-result'))
    await waitFor(() =>
      expect(result.current?.modelStream?.text).toBe('first'),
    )

    // errorJson 也是终止态栅栏：checkpoint partial 与错误详情保持分离；
    // status 为 'error'，绝不会是 'streaming'。
    rerender({
      invocation: {
        ...modelInvocation(),
        errorJson: '{"kind":"TRANSIENT","message":"boom"}',
      },
    })
    await waitFor(() => expect(result.current?.modelStream?.status).toBe('error'))
    expect(result.current?.modelStream?.text).toBe('')
    expect(result.current?.modelStream?.errorCode).toBe('TRANSIENT')
    expect(result.current?.modelStream?.errorText).toBe('boom')
    emitRealtime(sockets, realtime(2, '-late-after-error'))
    await waitFor(() =>
      expect(result.current?.modelStream?.text).toBe(''),
    )

    // Entry 落地：overlay 被清空，此后的迟到 delta 一律被拒绝。
    rerender({
      invocation: {
        ...modelInvocation(),
        resultJson: '{"text":"first","thinking":""}',
        resultEntryId: 'e-9',
      },
    })
    await waitFor(() => expect(result.current?.modelStream).toBeNull())
    emitRealtime(sockets, realtime(2, '-late-after-entry'))
    await waitFor(() => expect(result.current?.modelStream).toBeNull())
  })

  it('rejects late MODEL_DELTA for failed attempts in READY retry state without updating overlay or triggering gap recovery', async () => {
    // 验证 READY retry 状态收到已失败 attempt 的 delta 时：既不更新 overlay，也不错误启动 gap recovery
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const invalidate = vi.spyOn(client, 'invalidateQueries')
    const retryInvocation: ModelInvocationDTO = {
      ...modelInvocation(),
      status: 'READY',
      attempt: 1,
      streamCheckpointJson: null,
    }
    const failures = [modelAttemptFailure(1, 'inv-1', '2')]
    const { result, sockets } = renderRealtime(client, {
      invocation: retryInvocation,
      modelAttemptFailures: failures,
    })
    sockets.openLatest()

    // 初始状态下 READY 无 checkpoint，overlay 保持为 null
    expect(result.current?.modelStream).toBeNull()

    // 1. 旧 attempt 1 的 sequence = 1 迟到 delta：不追加 overlay，overlay 保持 null
    emitRealtime(sockets, realtime(1, 'stale-attempt-1', THREAD_ID, 'inv-1', 1))
    await sleep(100)
    expect(result.current?.modelStream).toBeNull()

    // 2. 旧 attempt 1 的 sequence = 3 迟到 gap delta：不启动 gap recovery，不触发 snapshot refetch
    emitRealtime(sockets, realtime(3, 'stale-gap-3', THREAD_ID, 'inv-1', 1))
    await sleep(250)
    expect(invalidate).not.toHaveBeenCalled()
    expect(result.current?.modelStream).toBeNull()
  })

  it('terminates in-flight gap recovery loop when the attempt is recorded as failed', async () => {
    // 验证在途 gap recovery 循环在 attempt 记录到 modelAttemptFailures 时被判定为 terminal 并停止
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const invalidate = vi.spyOn(client, 'invalidateQueries')
    const { result, rerender, sockets } = renderRealtime(client, { invocation: modelInvocation() })
    sockets.openLatest()

    // 发送 gap delta 启动 gap 恢复循环
    emitRealtime(sockets, realtime(3, 'missing'))
    expect(result.current?.modelStream).toBeNull()
    await waitFor(() => expect(invalidate.mock.calls.length).toBe(1), { timeout: 2000 })

    // Snapshot 更新为 READY retry 且包含该 attempt 的失败记录
    rerender({
      invocation: {
        ...modelInvocation(),
        status: 'READY',
        attempt: 1,
        streamCheckpointJson: null,
      },
      modelAttemptFailures: [modelAttemptFailure(1, 'inv-1', '2')],
    })

    // 等待超过退避时间，确认 recovery 循环已停止，不再继续发起 invalidate
    const callsAfterFailure = invalidate.mock.calls.length
    await sleep(600)
    expect(invalidate.mock.calls.length).toBe(callsAfterFailure)
  })

  it('allows MODEL_DELTA for active attempt while rejecting late MODEL_DELTA for previously failed attempts', async () => {
    // 验证当前 attempt 2 为活跃流式时，既能拒绝旧 attempt 1 的迟到 delta，又可正常应用当前 attempt 2 的 delta
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const runningAttempt2: ModelInvocationDTO = {
      ...modelInvocation(),
      status: 'RUNNING',
      attempt: 2,
      streamCheckpointJson: '{"attempt":2,"text":"","thinking":"","sequence":0}',
    }
    const failures = [modelAttemptFailure(1, 'inv-1', '2')]
    const { result, sockets } = renderRealtime(client, {
      invocation: runningAttempt2,
      modelAttemptFailures: failures,
    })
    sockets.openLatest()

    // 1. 旧 attempt 1 的迟到 delta：被拒绝
    emitRealtime(sockets, realtime(1, 'late-attempt-1', THREAD_ID, 'inv-1', 1))
    await sleep(100)
    expect(result.current?.modelStream).toBeNull()

    // 2. 当前 attempt 2 的 active delta：正常流式应用
    emitRealtime(sockets, realtime(1, 'active-attempt-2', THREAD_ID, 'inv-1', 2))
    await waitFor(() => expect(result.current?.modelStream?.text).toBe('active-attempt-2'))
    expect(result.current?.modelStream?.attempt).toBe(2)
  })

  it('does not reject MODEL_DELTA when failure belongs to a different invocation', async () => {
    // 验证 modelAttemptFailures 中若属于其他 invocationId，不误伤当前 invocation 的相同 attempt 序号
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const runningInvocation: ModelInvocationDTO = {
      ...modelInvocation('inv-current'),
      status: 'RUNNING',
      attempt: 1,
      streamCheckpointJson: '{"attempt":1,"text":"","thinking":"","sequence":0}',
    }
    // 属于另一个 invocation 的 attempt 1 失败记录
    const otherFailures = [modelAttemptFailure(1, 'inv-other', '2')]
    const { result, sockets } = renderRealtime(client, {
      invocation: runningInvocation,
      modelAttemptFailures: otherFailures,
    })
    sockets.openLatest()

    // 当前 invocation 'inv-current' 的 attempt 1 delta 应当被正常接收与应用
    emitRealtime(sockets, realtime(1, 'hello-current', THREAD_ID, 'inv-current', 1))
    await waitFor(() => expect(result.current?.modelStream?.text).toBe('hello-current'))
  })

  it('replaces a higher-sequence streaming overlay with the durable terminal projection', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const { result, rerender, sockets } = renderRealtime(client, { invocation: modelInvocation() })
    sockets.openLatest()

    // 持久化 checkpoint 在 seq5；lossy realtime delta 流式到达 seq8。
    rerender({
      invocation: {
        ...modelInvocation(),
        streamCheckpointJson: '{"attempt":1,"text":"safe five","thinking":"","sequence":5}',
      },
    })
    await waitFor(() => expect(result.current?.modelStream?.text).toBe('safe five'))
    emitRealtime(sockets, realtime(6, '-six'))
    emitRealtime(sockets, realtime(7, '-seven'))
    emitRealtime(sockets, realtime(8, '-eight'))
    await waitFor(() =>
      expect(result.current?.modelStream?.text).toBe('safe five-six-seven-eight'),
    )
    expect(result.current?.modelStream?.status).toBe('streaming')

    // checkpoint 仍落后于 seq5 时终态 resultJson 到达：完整的
    // 持久化投影必须无条件取代 seq8 的 lossy realtime overlay。
    rerender({
      invocation: {
        ...modelInvocation(),
        streamCheckpointJson: '{"attempt":1,"text":"safe five","thinking":"","sequence":5}',
        resultJson:
          '{"text":"durable complete answer","thinking":"durable plan","toolCalls":[],"stopReason":"stop","usage":{},"cost":{}}',
      },
    })
    await waitFor(() =>
      expect(result.current?.modelStream?.text).toBe('durable complete answer'),
    )
    expect(result.current?.modelStream?.thinking).toBe('durable plan')
    expect(result.current?.modelStream?.status).toBe('done')
    // 迟到的 lossy realtime delta 绝不会追加到持久化投影上。
    emitRealtime(sockets, realtime(9, '-late'))
    await waitFor(() =>
      expect(result.current?.modelStream?.text).toBe('durable complete answer'),
    )

    // errorJson：checkpoint 冻结，status 为 'error'，解析出的消息对外可见，不再追加。
    rerender({
      invocation: {
        ...modelInvocation(),
        streamCheckpointJson: '{"attempt":1,"text":"frozen text","thinking":"","sequence":5}',
        errorJson: '{"kind":"INVALID_REQUEST","message":"provider exploded"}',
      },
    })
    await waitFor(() => expect(result.current?.modelStream?.status).toBe('error'))
    expect(result.current?.modelStream?.text).toBe('frozen text')
    expect(result.current?.modelStream?.errorCode).toBe('INVALID_REQUEST')
    expect(result.current?.modelStream?.errorText).toBe('provider exploded')
    emitRealtime(sockets, realtime(6, '-after-error'))
    await waitFor(() =>
      expect(result.current?.modelStream?.text).toBe('frozen text'),
    )

    // 持久化 result Entry 到达：Entry 才是 transcript 的事实来源；overlay 消失。
    rerender({
      invocation: {
        ...modelInvocation(),
        resultJson: '{"text":"x","thinking":""}',
        resultEntryId: 'e-9',
      },
    })
    await waitFor(() => expect(result.current?.modelStream).toBeNull())
  })

  it('deduplicates exact TOOL_PARTIAL redelivery and resets fingerprints on attempt change', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const active: ToolInvocationDTO = {
      id: 'inv-tool-1',
      modelInvocationId: 'inv-1',
      assistantEntryId: 'entry-2',
      callIndex: 0,
      status: 'RUNNING',
      attempt: 1,
      toolCallId: 'call-1',
      toolName: 'web-search',
      rendererKey: 'web-search',
      environment: null,
      argumentsJson: '{}',
      approvalJson: null,
      resultJson: null,
      errorJson: null,
      createTime: '2026-01-01T00:00:00Z',
      updateTime: '2026-01-01T00:00:00Z',
    }
    const { result, rerender, sockets } = renderRealtime(client, { invocations: [active] })
    sockets.openLatest()

    // 完全相同的 realtime notification 重投递不能追加两次。
    emitRealtime(sockets, toolPartial('one'))
    await waitFor(() => expect(result.current?.toolStreams.get('inv-tool-1')?.text).toBe('one'))
    emitRealtime(sockets, toolPartial('one'))
    await waitFor(() =>
      expect(result.current?.toolStreams.get('inv-tool-1')?.text).toBe('one'),
    )
    // 不同的块仍然会追加。
    emitRealtime(sockets, toolPartial('two'))
    await waitFor(() =>
      expect(result.current?.toolStreams.get('inv-tool-1')?.text).toBe('onetwo'),
    )

    // attempt 变化会重置指纹范围：旧 attempt 事件的重投递被忽略，
    // 而新 attempt 上相同文本的事件是新块。
    rerender({ invocations: [{ ...active, attempt: 2 }] })
    await waitFor(() =>
      expect(result.current?.toolStreams.get('inv-tool-1')?.attempt).toBe(2),
    )
    emitRealtime(sockets, toolPartial('one', 'inv-tool-1', 1))
    await waitFor(() =>
      expect(result.current?.toolStreams.get('inv-tool-1')?.text).toBe(''),
    )
    emitRealtime(sockets, toolPartial('one', 'inv-tool-1', 2))
    await waitFor(() =>
      expect(result.current?.toolStreams.get('inv-tool-1')?.text).toBe('one'),
    )
  })

  it('evicts only the oldest TOOL_PARTIAL fingerprint at the capacity boundary (FIFO)', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const active: ToolInvocationDTO = {
      id: 'inv-tool-1',
      modelInvocationId: 'inv-1',
      assistantEntryId: 'entry-2',
      callIndex: 0,
      status: 'RUNNING',
      attempt: 1,
      toolCallId: 'call-1',
      toolName: 'web-search',
      rendererKey: 'web-search',
      environment: null,
      argumentsJson: '{}',
      approvalJson: null,
      resultJson: null,
      errorJson: null,
      createTime: '2026-01-01T00:00:00Z',
      updateTime: '2026-01-01T00:00:00Z',
    }
    const { result, sockets } = renderRealtime(client, { invocations: [active] })
    sockets.openLatest()

    // 256 个不同块恰好填满指纹集合；第 257 个只淘汰最旧的一个。
    for (let i = 0; i < 257; i++) {
      emitRealtime(sockets, toolPartial(`chunk-${i}`))
    }
    await waitFor(() =>
      expect(result.current?.toolStreams.get('inv-tool-1')?.text).toContain('chunk-256'),
    )
    const textAfterFill = result.current?.toolStreams.get('inv-tool-1')?.text
    expect(textAfterFill?.length ?? 0).toBeGreaterThan(0)

    // 最近块的重新投递必须被去重。整体 clear() 会忘记
    // 一切并再次追加；FIFO 保留最近 N 个指纹。
    emitRealtime(sockets, toolPartial('chunk-256'))
    await waitFor(() =>
      expect(result.current?.toolStreams.get('inv-tool-1')?.text).toBe(textAfterFill),
    )

    // 最旧的块已被淘汰：它的重投递再次成为新块（有界内存，
    // 只有最近 N 个受保护）。
    emitRealtime(sockets, toolPartial('chunk-0'))
    await waitFor(() =>
      expect(result.current?.toolStreams.get('inv-tool-1')?.text).toBe(`${textAfterFill}chunk-0`),
    )
  })

  it('restores the durable checkpoint and keeps one subscription across shared-connection reconnects', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const invalidate = vi.spyOn(client, 'invalidateQueries')
    const restored = {
      ...modelInvocation(),
      streamCheckpointJson: '{"attempt":1,"text":"safe","thinking":"plan","sequence":2}',
    }
    const { result, sockets } = renderRealtime(client, { invocation: restored })
    const first = sockets.openLatest()

    await waitFor(() =>
      expect(result.current?.modelStream).toMatchObject({ text: 'safe', thinking: 'plan', sequence: 2 }),
    )

    // 断线重连由共享 Connection 负责：hook 不重建订阅，每个新 socket 上
    // 仍只有一条 subscribe（manager 重发）；重连后的 subscribed ack 触发
    // snapshot 对账，关闭断线窗口。
    for (let attempt = 0; attempt < 2; attempt++) {
      sockets.latest?.fail()
      await waitFor(() => expect(sockets.sockets.length).toBe(attempt + 2), { timeout: 2000 })
      const next = sockets.openLatest()
      expect(next.sentMessages()).toEqual([{ version: 1, type: 'subscribe', resource: threadResource }])
      act(() => next.emitServer({ type: 'subscribed', resource: threadResource, cursor: '42' }))
    }
    expect(first.sentMessages()).toEqual([{ version: 1, type: 'subscribe', resource: threadResource }])
    // 每次重连的 subscribed ack 各触发一次对账。
    expect(invalidate).toHaveBeenCalledTimes(2)
  })

  it('aggregates TOOL_PARTIAL overlays per invocation and clears when the invocation disappears with the durable result Entry', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const active: ToolInvocationDTO = {
      id: 'inv-tool-1',
      modelInvocationId: 'inv-1',
      assistantEntryId: 'entry-2',
      callIndex: 0,
      status: 'RUNNING',
      attempt: 1,
      toolCallId: 'call-1',
      toolName: 'web-search',
      rendererKey: 'web-search',
      environment: null,
      argumentsJson: '{}',
      approvalJson: null,
      resultJson: null,
      errorJson: null,
      createTime: '2026-01-01T00:00:00Z',
      updateTime: '2026-01-01T00:00:00Z',
    }
    const { result, rerender, sockets } = renderRealtime(client, { invocations: [active] })
    sockets.openLatest()

    emitRealtime(sockets, toolPartial('partial-one'))
    await waitFor(() =>
      expect(result.current?.toolStreams.get('inv-tool-1')?.text).toBe('partial-one'),
    )
    emitRealtime(sockets, toolPartial('partial-two'))
    await waitFor(() =>
      expect(result.current?.toolStreams.get('inv-tool-1')?.text).toBe('partial-onepartial-two'),
    )
    // 尚无持久化 result Entry 时的终态 resultJson：overlay 被终态
    // 投影（text/error/attachments）取代，而不是被删除。
    rerender({
      invocations: [
        {
          ...active,
          resultJson: JSON.stringify({
            toolCallId: 'call-1',
            contents: [{ type: 'text', text: 'terminal answer' }],
            error: false,
            details: null,
          }),
        },
      ],
    })
    await waitFor(() =>
      expect(result.current?.toolStreams.get('inv-tool-1')?.text).toBe('terminal answer'),
    )
    expect(result.current?.toolStreams.get('inv-tool-1')?.error).toBe(false)

    // invocation 仍在 snapshot（终态空结果）：终态投影继续保留 —— ToolResult
    // Entry 写入与 invocation 删除原子提交，只有 invocation 消失 overlay 才清除。
    rerender({
      invocations: [{ ...active, resultJson: '{"contents":[]}' }],
    })
    await waitFor(() =>
      expect(result.current?.toolStreams.get('inv-tool-1')?.text).toBe(''),
    )

    // 持久 ToolResult Entry 落地：invocation 随同一事务从 snapshot 消失，overlay 清除。
    rerender({ invocations: [] })
    await waitFor(() => expect(result.current?.toolStreams.size).toBe(0))
  })


  it('ignores TOOL_PARTIAL after the terminal projection and for unknown/retry-attempt invocations', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const active: ToolInvocationDTO = {
      id: 'inv-tool-1',
      modelInvocationId: 'inv-1',
      assistantEntryId: 'entry-2',
      callIndex: 0,
      status: 'RUNNING',
      attempt: 1,
      toolCallId: 'call-1',
      toolName: 'web-search',
      rendererKey: 'web-search',
      environment: null,
      argumentsJson: '{}',
      approvalJson: null,
      resultJson: null,
      errorJson: null,
      createTime: '2026-01-01T00:00:00Z',
      updateTime: '2026-01-01T00:00:00Z',
    }
    const { result, rerender, sockets } = renderRealtime(client, { invocations: [active] })
    sockets.openLatest()

    emitRealtime(sockets, toolPartial('one'))
    await waitFor(() =>
      expect(result.current?.toolStreams.get('inv-tool-1')?.text).toBe('one'),
    )

    // 终态 resultJson 到达 snapshot：其投影取代 overlay。
    rerender({
      invocations: [
        {
          ...active,
          resultJson: JSON.stringify({
            toolCallId: 'call-1',
            contents: [{ type: 'text', text: 'terminal answer' }],
            error: false,
            details: null,
          }),
        },
      ],
    })
    await waitFor(() =>
      expect(result.current?.toolStreams.get('inv-tool-1')?.text).toBe('terminal answer'),
    )

    // 迟到的 lossy realtime partial 绝不能追加到完整的终态投影上。
    emitRealtime(sockets, toolPartial('-late'))
    await waitFor(() =>
      expect(result.current?.toolStreams.get('inv-tool-1')?.text).toBe('terminal answer'),
    )

    // 持久化 snapshot 中未知的 invocation 永不展示。
    emitRealtime(sockets, toolPartial('ghost', 'inv-ghost'))
    await waitFor(() =>
      expect(result.current?.toolStreams.has('inv-ghost')).toBe(false),
    )

    // snapshot 前移后，来自过期 retry attempt 的 partial 被忽略。
    rerender({ invocations: [{ ...active, attempt: 2 }] })
    await waitFor(() =>
      expect(result.current?.toolStreams.get('inv-tool-1')?.attempt).toBe(2),
    )
    emitRealtime(sockets, toolPartial('stale-attempt', 'inv-tool-1', 1))
    await waitFor(() =>
      expect(result.current?.toolStreams.get('inv-tool-1')?.text).toBe(''),
    )
  })

  it('projects terminal errorJson (error text) while the durable result Entry is pending', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const active: ToolInvocationDTO = {
      id: 'inv-tool-1',
      modelInvocationId: 'inv-1',
      assistantEntryId: 'entry-2',
      callIndex: 0,
      status: 'RUNNING',
      attempt: 1,
      toolCallId: 'call-1',
      toolName: 'web-search',
      rendererKey: 'web-search',
      environment: null,
      argumentsJson: '{}',
      approvalJson: null,
      resultJson: null,
      errorJson: null,
      createTime: '2026-01-01T00:00:00Z',
      updateTime: '2026-01-01T00:00:00Z',
    }
    const { result, rerender, sockets } = renderRealtime(client, { invocations: [active] })
    sockets.openLatest()

    emitRealtime(sockets, toolPartial('partial-one'))
    await waitFor(() =>
      expect(result.current?.toolStreams.get('inv-tool-1')?.text).toBe('partial-one'),
    )

    // 无 result Entry 的 errorJson 保持 error overlay 可见（绝不显示为 "done"）。
    rerender({ invocations: [{ ...active, errorJson: '{"message":"boom"}' }] })
    await waitFor(() =>
      expect(result.current?.toolStreams.get('inv-tool-1')).toMatchObject({
        error: true,
        errorText: 'boom',
      }),
    )
  })

  it('keeps the snapshot-seeded terminal tool overlay across the disabled -> ready transition', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    // 稳定引用：过渡 render 期间 reconcile effect 依赖不变，不会重新播种。
    const invocation = modelInvocation()
    const noInvocations: ToolInvocationDTO[] = []
    const terminal: ToolInvocationDTO = {
      id: 'inv-tool-1',
      modelInvocationId: 'inv-1',
      assistantEntryId: 'entry-2',
      callIndex: 0,
      status: 'RUNNING',
      attempt: 1,
      toolCallId: 'call-1',
      toolName: 'web-search',
      rendererKey: 'web-search',
      environment: null,
      argumentsJson: '{}',
      approvalJson: null,
      resultJson: JSON.stringify({
        toolCallId: 'call-1',
        contents: [{ type: 'text', text: 'terminal answer' }],
        error: false,
        details: null,
      }),
      errorJson: null,
      createTime: '2026-01-01T00:00:00Z',
      updateTime: '2026-01-01T00:00:00Z',
    }

    // disabled 期不订阅；首次 snapshot（终态 resultJson）与 ready 同批到达：
    // reconcile effect 播种终态 tool overlay，随后订阅 state 尚为
    // null 的过渡 render 不得清空它（下一 render 依赖不变不会重新播种）。
    const { result, rerender, sockets } = renderRealtime(client, {
      enabled: false,
      invocation,
      invocations: noInvocations,
    })
    sockets.openLatest()
    expect(sockets.latest?.sentMessages() ?? []).toHaveLength(0)

    rerender({ enabled: true, invocation, invocations: [terminal] })
    // 订阅初始化完成（wire 上出现 subscribe）后，snapshot-seeded overlay 仍在。
    await waitFor(() =>
      expect(sockets.latest?.sentMessages()).toEqual([
        { version: 1, type: 'subscribe', resource: threadResource },
      ]),
    )
    await waitFor(() =>
      expect(result.current?.toolStreams.get('inv-tool-1')?.text).toBe('terminal answer'),
    )
  })

  it('abandons the gap recovery loop after exceeding the max attempt budget', async () => {
    vi.useFakeTimers()
    try {
      const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
      const invalidate = vi.spyOn(client, 'invalidateQueries')
      const { result, sockets } = renderRealtime(client, {
        invocation: modelInvocation(),
      })
      sockets.openLatest()

      // checkpoint 永不追平（snapshot 数据不变）：每次 tick 都判定未追上并
      // 递增 attempts，最终超过 RECOVERY_MAX_ATTEMPTS(8) 后放弃（L565）。
      emitRealtime(sockets, realtime(3, 'missing'))
      await act(async () => {
        await vi.advanceTimersByTimeAsync(200) // attempts 0 -> 1
      })
      await act(async () => {
        await vi.advanceTimersByTimeAsync(400) // attempts 1 -> 2
      })
      await act(async () => {
        await vi.advanceTimersByTimeAsync(800) // attempts 2 -> 3
      })
      await act(async () => {
        await vi.advanceTimersByTimeAsync(1600) // attempts 3 -> 4
      })
      await act(async () => {
        await vi.advanceTimersByTimeAsync(2000) // attempts 4 -> 5
      })
      await act(async () => {
        await vi.advanceTimersByTimeAsync(2000) // attempts 5 -> 6
      })
      await act(async () => {
        await vi.advanceTimersByTimeAsync(2000) // attempts 6 -> 7
      })
      await act(async () => {
        await vi.advanceTimersByTimeAsync(2000) // attempts 7 -> 8
      })
      await act(async () => {
        await vi.advanceTimersByTimeAsync(2000) // attempts 8 -> 放弃（L565）
      })
      const callsAfterGiveUp = invalidate.mock.calls.length
      expect(callsAfterGiveUp).toBeGreaterThanOrEqual(8)
      // 放弃后不再有新的 refetch。
      await act(async () => {
        await vi.advanceTimersByTimeAsync(3000)
      })
      expect(invalidate.mock.calls.length).toBe(callsAfterGiveUp)
      // 放弃后 checkpoint 仍未追平：overlay 保持为空。
      expect(result.current?.modelStream).toBeNull()
    } finally {
      vi.useRealTimers()
    }
  })

  it('clears the recorded gap when the checkpoint catches up and stops requesting recovery', async () => {
    vi.useFakeTimers()
    try {
      const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
      const invalidate = vi.spyOn(client, 'invalidateQueries')
      const { result, rerender, sockets } = renderRealtime(client, {
        invocation: modelInvocation(),
      })
      sockets.openLatest()

      // 初始 checkpoint（sequence 0）落后于 seq3 的 delta：记录 gap 并单飞恢复。
      emitRealtime(sockets, realtime(3, 'missing'))
      await act(async () => {
        await vi.advanceTimersByTimeAsync(200)
      })
      expect(invalidate).toHaveBeenCalledTimes(1)

      // checkpoint 追平到 seq3：reconcile effect 刷新 overlay 并清空 gapRef。
      rerender({
        invocation: {
          ...modelInvocation(),
          streamCheckpointJson: '{"attempt":1,"text":"recovered","thinking":"","sequence":3}',
        },
      })
      await act(async () => {
        await vi.advanceTimersByTimeAsync(0)
      })
      expect(result.current?.modelStream?.text).toBe('recovered')

      // 追平后循环终止：不再有新的 refetch。追平后 reconcile effect 本身
      // 不再触发 invalidate（不是订阅信号），因此等待超过下一个退避窗口
      // 后调用数必须保持稳定 —— 恢复循环已彻底停止。
      // 注意：在途 tick 的 finally 出口会重新武装一次（busy 标志清除后
      // 调度 CURRENT recovery），因此等待窗口内可能看到一次「收尾」调用；
      // 断言关键语义：追平后模型 overlay 可见且后续没有持续增长。
      const callsAfterCatchUp = invalidate.mock.calls.length
      expect(result.current?.modelStream).toMatchObject({ text: 'recovered', sequence: 3 })
      await act(async () => {
        await vi.advanceTimersByTimeAsync(600)
      })
      await act(async () => {
        await vi.advanceTimersByTimeAsync(1400)
      })
      expect(invalidate.mock.calls.length).toBeLessThanOrEqual(callsAfterCatchUp + 1)
      expect(invalidate.mock.calls.length).toBeGreaterThanOrEqual(callsAfterCatchUp)
    } finally {
      vi.useRealTimers()
    }
  })

  it('clears the gap through the terminal projection path when a durable result arrives', async () => {
    vi.useFakeTimers()
    try {
      const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
      const invalidate = vi.spyOn(client, 'invalidateQueries')
      const { result, rerender, sockets } = renderRealtime(client, {
        invocation: modelInvocation(),
      })
      sockets.openLatest()

      // seq3 gap 记录后，持久化终态 resultJson 到达（streaming 之外的
      // terminal 分支）：非 streaming 投影无条件取代 overlay，并清空 gapRef。
      emitRealtime(sockets, realtime(3, 'missing'))
      await act(async () => {
        await vi.advanceTimersByTimeAsync(200)
      })
      rerender({
        invocation: {
          ...modelInvocation(),
          streamCheckpointJson: '{"attempt":1,"text":"frozen","thinking":"","sequence":2}',
          resultJson: '{"text":"durable done","thinking":"","toolCalls":[],"stopReason":"stop","usage":{},"cost":{}}',
        },
      })
      await act(async () => {
        await vi.advanceTimersByTimeAsync(0)
      })
      expect(result.current?.modelStream).toMatchObject({ text: 'durable done', status: 'done' })
      // 终态投影后 gap 已清空（L104）：再等一个恢复窗口，确认没有新的 refetch。
      const callsAfterTerminal = invalidate.mock.calls.length
      await act(async () => {
        await vi.advanceTimersByTimeAsync(600)
      })
      expect(invalidate.mock.calls.length).toBeLessThanOrEqual(callsAfterTerminal + 1)
    } finally {
      vi.useRealTimers()
    }
  })

  it('clears the gap when a terminal snapshot arrives without a pending recovery', async () => {
    vi.useFakeTimers()
    try {
      const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
      const invalidate = vi.spyOn(client, 'invalidateQueries')
      const { result, rerender, sockets } = renderRealtime(client, {
        invocation: modelInvocation(),
      })
      sockets.openLatest()

      // gapRef 已在非 streaming 分支置位（L104 行内条件全部满足）：
      // 先有 gap delta（record gap），再让终态 snapshot 的 sequence 赶上
      // gap.sequence，走「gap != null 且 snapshot.sequence >= gap.sequence」清理。
      emitRealtime(sockets, realtime(3, 'missing'))
      await act(async () => {
        await vi.advanceTimersByTimeAsync(200)
      })
      // 终态 snapshot：sequence 2 >= gap 2？不 —— 用 sequence 3 的 checkpoint。
      rerender({
        invocation: {
          ...modelInvocation(),
          streamCheckpointJson: '{"attempt":1,"text":"frozen","thinking":"","sequence":3}',
          errorJson: '{"kind":"TRANSIENT","message":"boom"}',
        },
      })
      await act(async () => {
        await vi.advanceTimersByTimeAsync(0)
      })
      expect(result.current?.modelStream).toMatchObject({
        text: 'frozen',
        status: 'error',
        errorText: 'boom',
      })
      // 后续 gap delta（同 invocation/attempt）不再触发恢复（gapRef 已清空）。
      const callsBefore = invalidate.mock.calls.length
      emitRealtime(sockets, realtime(4, 'late'))
      await act(async () => {
        await vi.advanceTimersByTimeAsync(200)
      })
      expect(invalidate.mock.calls.length).toBe(callsBefore)
    } finally {
      vi.useRealTimers()
    }
  })

  it('ignores realtime deltas whose snapshot does not match the delta invocation', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const invalidate = vi.spyOn(client, 'invalidateQueries')
    const { result, sockets } = renderRealtime(client, {
      invocation: modelInvocation('inv-1'),
    })
    sockets.openLatest()

    // durable snapshot 的 invocationId 与 delta 不同（或 attempt 不同）：
    // 事件被整体拒绝，不产生 overlay，也不触发 gap 恢复。
    emitRealtime(sockets, realtime(1, 'foreign', THREAD_ID, 'inv-other'))
    emitRealtime(sockets, JSON.stringify({
      threadId: THREAD_ID, subjectKind: 'MODEL_INVOCATION', subjectId: 'inv-1', attempt: 2, sequence: 1,
      type: 'MODEL_DELTA', payload: { kind: 'TEXT_DELTA', text: 'other-attempt' }, createdAt: '2026-01-01T00:00:00Z',
    }))
    await waitFor(() => expect(result.current?.modelStream).toBeNull())
    // 没有 gap 记录 → 不触发任何 refetch。
    await sleep(300)
    expect(invalidate).not.toHaveBeenCalled()
  })

  it('ignores TOOL_PARTIAL events from other threads', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const active: ToolInvocationDTO = {
      id: 'inv-tool-1',
      modelInvocationId: 'inv-1',
      assistantEntryId: 'entry-2',
      callIndex: 0,
      status: 'RUNNING',
      attempt: 1,
      toolCallId: 'call-1',
      toolName: 'web-search',
      rendererKey: 'web-search',
      environment: null,
      argumentsJson: '{}',
      approvalJson: null,
      resultJson: null,
      errorJson: null,
      createTime: '2026-01-01T00:00:00Z',
      updateTime: '2026-01-01T00:00:00Z',
    }
    const { result, sockets } = renderRealtime(client, { invocations: [active] })
    sockets.openLatest()

    // 事件 threadId 与当前订阅 Thread 不一致：不聚合、不报错。
    // 注意：snapshot seed 会先为同一 invocation 播种空 overlay（threadId 正确），
    // 因此断言的是「空 overlay 未被外来 partial 污染」。
    act(() =>
      sockets.latest!.emitServer({
        type: 'event',
        resource: resource(THREAD_B_ID),
        name: 'realtime',
        data: JSON.parse(toolPartial('x', 'inv-tool-1', 1)),
      }),
    )
    await waitFor(() =>
      expect(result.current?.toolStreams.get('inv-tool-1')).toMatchObject({
        threadId: THREAD_ID,
        text: '',
        error: false,
      }),
    )
    // 非法 JSON 的 realtime data：parseRealtimeToolPartial 返回 null → 直接忽略。
    act(() =>
      sockets.latest!.emitServer({
        type: 'event',
        resource: resource(THREAD_ID),
        name: 'realtime',
        data: { broken: true },
      }),
    )
    await waitFor(() =>
      expect(result.current?.toolStreams.get('inv-tool-1')).toMatchObject({
        text: '',
        error: false,
      }),
    )
  })

  it('reuses the same overlay object when the durable snapshot is re-seeded identically', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const { result, rerender, sockets } = renderRealtime(client, {
      invocation: modelInvocation(),
    })
    sockets.openLatest()

    // 流式 overlay 带 toolCalls draft；再次渲染相同内容的 snapshot 时
    // sameModelStream/sameToolCallDrafts 命中，ref 对象保持稳定（无重渲染抖动）。
    emitRealtime(sockets, JSON.stringify({
      threadId: THREAD_ID, subjectKind: 'MODEL_INVOCATION', subjectId: 'inv-1', attempt: 1, sequence: 1,
      type: 'MODEL_DELTA', payload: { kind: 'TOOL_CALL_DELTA', index: 0, id: 'call-1', name: 'bash', argumentsJson: '{"cmd":"ls"}' }, createdAt: '2026-01-01T00:00:00Z',
    }))
    await waitFor(() =>
      expect(result.current?.modelStream?.toolCalls).toEqual([
        { index: 0, id: 'call-1', name: 'bash', argumentsJson: '{"cmd":"ls"}' },
      ]),
    )
    const before = result.current?.modelStream
    rerender({ invocation: { ...modelInvocation(), streamCheckpointJson: '{"attempt":1,"text":"","thinking":"","sequence":1}' } })
    await waitFor(() => expect(result.current?.modelStream).toBe(before))
  })

  it('does not schedule recovery ticks after the loop is cleared', async () => {
    vi.useFakeTimers()
    try {
      const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
      const invalidate = vi.spyOn(client, 'invalidateQueries')
      const { rerender, sockets } = renderRealtime(client, {
        invocation: modelInvocation(),
      })
      sockets.openLatest()

      // gap delta 安装恢复；随后禁用订阅会 clearRecoveryLoop（timer + recovery 清空）。
      emitRealtime(sockets, realtime(3, 'missing'))
      await act(async () => {
        await vi.advanceTimersByTimeAsync(200)
      })
      expect(invalidate).toHaveBeenCalledTimes(1)

      // 恢复循环被禁用路径清空后，不应再有新的 refetch（L497/L512 空 recovery 返回）。
      rerender({ enabled: false })
      await act(async () => {
        await vi.advanceTimersByTimeAsync(200)
      })
      await act(async () => {
        await vi.advanceTimersByTimeAsync(2000)
      })
      expect(invalidate.mock.calls.length).toBe(1)
    } finally {
      vi.useRealTimers()
    }
  })

  it('coalesces many sequential deltas before a single frame into one render with all text, thinking, and tool content', () => {
    let frameCallback: FrameRequestCallback | null = null
    const raf = vi.fn((cb: FrameRequestCallback) => {
      frameCallback = cb
      return 100
    })
    const caf = vi.fn()
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const toolInv: ToolInvocationDTO = {
      id: 'inv-tool-1',
      threadId: THREAD_ID,
      turnStartEntryId: 'entry-1',
      requestHeadEntryId: 'entry-1',
      status: 'RUNNING',
      attempt: 1,
      name: 'testTool',
      resultJson: null,
      errorJson: null,
      createTime: '2026-01-01T00:00:00Z',
      updateTime: '2026-01-01T00:00:00Z',
    }
    const { result, sockets } = renderRealtime(client, {
      invocation: modelInvocation(),
      invocations: [toolInv],
      schedulerOptions: { raf, caf },
    })
    sockets.openLatest()

    // 连续发送多个 deltas：text, thinking, tool partial
    emitRealtime(sockets, realtime(1, 'Hello '))
    emitRealtime(sockets, thinkingRealtime(2, 'Thinking part 1. '))
    emitRealtime(sockets, realtime(3, 'World!'))
    emitRealtime(sockets, thinkingRealtime(4, 'Thinking part 2.'))
    emitRealtime(sockets, toolPartial('tool output chunk 1', 'inv-tool-1', 1))
    emitRealtime(sockets, toolPartial(' and chunk 2', 'inv-tool-1', 1))

    // 帧触发前，React state 仍保持未发布状态，未进行中间字符级/碎片刷新
    expect(result.current.modelStream).toBeNull()
    expect(result.current.toolStreams.get('inv-tool-1')?.text).toBe('')
    expect(raf).toHaveBeenCalledTimes(1)

    // 触发单帧
    act(() => {
      frameCallback!(performance.now())
    })

    // 单次更新立即发布全部累积内容
    expect(result.current.modelStream?.sequence).toBe(4)
    expect(result.current.modelStream?.text).toBe('Hello World!')
    expect(result.current.modelStream?.thinking).toBe('Thinking part 1. Thinking part 2.')
    expect(result.current.toolStreams.get('inv-tool-1')?.text).toBe('tool output chunk 1 and chunk 2')
  })

  it('publishes huge single delta as a whole at the frame, and when done arrives before frame there is no delayed trickle', () => {
    let frameCallback: FrameRequestCallback | null = null
    const raf = vi.fn((cb: FrameRequestCallback) => {
      frameCallback = cb
      return 101
    })
    const caf = vi.fn()
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const { result, rerender, sockets } = renderRealtime(client, {
      invocation: modelInvocation(),
      schedulerOptions: { raf, caf },
    })
    sockets.openLatest()

    const hugeText = 'A'.repeat(5000)
    emitRealtime(sockets, realtime(1, hugeText))
    expect(result.current.modelStream).toBeNull()

    // 帧到达时整块 5000 字符立即全量渲染，绝无逐字 trickle
    act(() => {
      frameCallback!(performance.now())
    })
    expect(result.current.modelStream?.text).toBe(hugeText)

    // 下一个 delta 调度新帧，但在帧触发前，权威快照已变为 COMPLETED
    emitRealtime(sockets, realtime(2, 'B'.repeat(100)))

    const doneInvocation: ModelInvocationDTO = {
      ...modelInvocation(),
      status: 'COMPLETED',
      resultJson: JSON.stringify({ text: 'Authoritative completed text', thinking: '', toolCalls: [] }),
    }
    rerender({ invocation: doneInvocation })

    // 快照对账立即生效权威终态
    expect(result.current.modelStream?.status).toBe('done')
    expect(result.current.modelStream?.text).toBe('Authoritative completed text')

    // 此时即使触发旧帧回调，也不会复活旧 delta 或产生 trickle 覆盖
    act(() => {
      if (frameCallback) {
        frameCallback(performance.now())
      }
    })
    expect(result.current.modelStream?.text).toBe('Authoritative completed text')
    expect(result.current.modelStream?.status).toBe('done')
  })

  it('does not drop accumulated deltas when version/resync signal arrives before snapshot returns', async () => {
    let frameCallback: FrameRequestCallback | null = null
    const raf = vi.fn((cb: FrameRequestCallback) => {
      frameCallback = cb
      return 102
    })
    const caf = vi.fn()
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const invalidateSpy = vi.spyOn(client, 'invalidateQueries')
    const { result, sockets } = renderRealtime(client, {
      invocation: modelInvocation(),
      schedulerOptions: { raf, caf },
    })
    const socket = sockets.openLatest()

    // 发送流式 delta，帧已调度但尚未触发
    emitRealtime(sockets, realtime(1, 'streaming text'))
    expect(result.current.modelStream).toBeNull()

    // WebSocket 收到 version 信号，触发 invalidateSnapshot
    act(() => {
      socket.emitServer({
        type: 'event',
        resource: threadResource,
        name: 'version',
        data: { version: '43' },
        cursor: '43',
      })
    })
    expect(invalidateSpy).toHaveBeenCalled()

    // 重点审查验证：version 信号不能 cancel 丢弃已累积的 delta 帧
    // 待帧触发时，已累积内容必须正常发布到 React state
    act(() => {
      frameCallback!(performance.now())
    })
    expect(result.current.modelStream?.text).toBe('streaming text')
    expect(result.current.modelStream?.sequence).toBe(1)
  })

  it('does not rollback un-published latest ref when intermediate unrelated render repeats same snapshot', () => {
    let frameCallback: FrameRequestCallback | null = null
    const raf = vi.fn((cb: FrameRequestCallback) => {
      frameCallback = cb
      return 103
    })
    const caf = vi.fn()
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const initialInvocation = modelInvocation()
    const { result, rerender, sockets } = renderRealtime(client, {
      invocation: initialInvocation,
      schedulerOptions: { raf, caf },
    })
    sockets.openLatest()

    // Delta 1 到达，处于 ref 累积中
    emitRealtime(sockets, realtime(1, 'accumulated content'))

    // 发生无关父组件重新渲染（传入相同的 snapshot 引用与内容）
    rerender({ invocation: { ...initialInvocation } })

    // 审查保证：未发布的最新 ref 不被 snapshot 的 seq 0 回退
    // 触发帧发布后，依然是最新的 'accumulated content'
    act(() => {
      if (frameCallback) {
        frameCallback(performance.now())
      }
    })
    expect(result.current.modelStream?.text).toBe('accumulated content')
    expect(result.current.modelStream?.sequence).toBe(1)
  })

  it('does not drop pending tool partial when model reaches terminal, and vice versa', () => {
    const raf = vi.fn((_cb: FrameRequestCallback) => 104)
    const caf = vi.fn()
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const toolInv: ToolInvocationDTO = {
      id: 'inv-tool-1',
      threadId: THREAD_ID,
      turnStartEntryId: 'entry-1',
      requestHeadEntryId: 'entry-1',
      status: 'RUNNING',
      attempt: 1,
      name: 'testTool',
      resultJson: null,
      errorJson: null,
      createTime: '2026-01-01T00:00:00Z',
      updateTime: '2026-01-01T00:00:00Z',
    }
    const { result, rerender, sockets } = renderRealtime(client, {
      invocation: modelInvocation(),
      invocations: [toolInv],
      schedulerOptions: { raf, caf },
    })
    sockets.openLatest()

    // Tool 发送 partial，进入 dirty 状态
    emitRealtime(sockets, toolPartial('tool partial data', 'inv-tool-1', 1))

    // Model 到达终态快照，但 Tool 依然活跃
    const terminalModel: ModelInvocationDTO = {
      ...modelInvocation(),
      status: 'COMPLETED',
      resultJson: JSON.stringify({ text: 'Model done', thinking: '', toolCalls: [] }),
    }
    rerender({ invocation: terminalModel, invocations: [toolInv] })

    // 审查保证：Model 到达终态不能导致 Tool dirty partial 永久丢失！
    expect(result.current.modelStream?.status).toBe('done')
    expect(result.current.modelStream?.text).toBe('Model done')
    // Tool partial 被正确更新
    expect(result.current.toolStreams.get('inv-tool-1')?.text).toBe('tool partial data')
  })

  it('cancels pending frame on thread change and unmount so future frame cannot resurrect state', () => {
    const raf = vi.fn((_cb: FrameRequestCallback) => 105)
    const caf = vi.fn()
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const { result, rerender, unmount, sockets } = renderRealtime(client, {
      invocation: modelInvocation(),
      schedulerOptions: { raf, caf },
    })
    sockets.openLatest()

    emitRealtime(sockets, realtime(1, 'delta before thread switch'))
    expect(raf).toHaveBeenCalled()

    // 切换到新线程
    rerender({ threadId: THREAD_B_ID, invocation: modelInvocation('inv-2', THREAD_B_ID) })
    expect(caf).toHaveBeenCalled()
    expect(result.current.modelStream).toBeNull()

    // 再次发送 delta 并卸载组件
    emitRealtime(sockets, realtime(1, 'delta before unmount', THREAD_B_ID, 'inv-2'), THREAD_B_ID)
    caf.mockClear()
    unmount()
    expect(caf).toHaveBeenCalled()
  })

  it('handles process.output streaming with contiguous APPEND, duplicate/overlap dedup, and gap healing via SNAPSHOT', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const active: ToolInvocationDTO = {
      id: 'inv-tool-1',
      modelInvocationId: 'inv-1',
      assistantEntryId: 'entry-2',
      callIndex: 0,
      status: 'RUNNING',
      attempt: 1,
      toolCallId: 'call-1',
      toolName: 'bash',
      rendererKey: 'bash',
      environment: null,
      argumentsJson: '{"command":"build.sh"}',
      approvalJson: null,
      resultJson: null,
      errorJson: null,
      createTime: '2026-01-01T00:00:00Z',
      updateTime: '2026-01-01T00:00:00Z',
    }
    const { result, sockets } = renderRealtime(client, { invocations: [active] })
    sockets.openLatest()

    // 1. 连续 APPEND
    emitRealtime(sockets, toolProcessOutputPartial('chunk1\n', 'APPEND', 0, 7, 7))
    await waitFor(() =>
      expect(result.current?.toolStreams.get('inv-tool-1')?.text).toBe('chunk1\n'),
    )

    // 2. 重复/重叠帧被忽略
    emitRealtime(sockets, toolProcessOutputPartial('chunk1\n', 'APPEND', 0, 7, 7))
    emitRealtime(sockets, toolProcessOutputPartial('unk1\n', 'APPEND', 2, 7, 7))
    await waitFor(() =>
      expect(result.current?.toolStreams.get('inv-tool-1')?.text).toBe('chunk1\n'),
    )

    // 3. 连续下一个 APPEND
    emitRealtime(sockets, toolProcessOutputPartial('chunk2\n', 'APPEND', 7, 14, 14))
    await waitFor(() =>
      expect(result.current?.toolStreams.get('inv-tool-1')?.text).toBe('chunk1\nchunk2\n'),
    )

    // 4. 出现缺口（startOffset 25 > 14）：不追加
    emitRealtime(sockets, toolProcessOutputPartial('chunk4\n', 'APPEND', 25, 32, 32))
    await waitFor(() =>
      expect(result.current?.toolStreams.get('inv-tool-1')?.text).toBe('chunk1\nchunk2\n'),
    )

    // 5. SNAPSHOT 到达：修复缺口并替换为最新完整窗口
    emitRealtime(
      sockets,
      toolProcessOutputPartial('chunk1\nchunk2\nchunk3\nchunk4\n', 'SNAPSHOT', 0, 28, 28),
    )
    await waitFor(() =>
      expect(result.current?.toolStreams.get('inv-tool-1')?.text).toBe(
        'chunk1\nchunk2\nchunk3\nchunk4\n',
      ),
    )
  })

  it('handles high-volume process.output chunks batching and keeps UI budget bounded', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const active: ToolInvocationDTO = {
      id: 'inv-tool-1',
      modelInvocationId: 'inv-1',
      assistantEntryId: 'entry-2',
      callIndex: 0,
      status: 'RUNNING',
      attempt: 1,
      toolCallId: 'call-1',
      toolName: 'bash',
      rendererKey: 'bash',
      environment: null,
      argumentsJson: '{"command":"generate-lots-of-logs.sh"}',
      approvalJson: null,
      resultJson: null,
      errorJson: null,
      createTime: '2026-01-01T00:00:00Z',
      updateTime: '2026-01-01T00:00:00Z',
    }
    const { result, sockets } = renderRealtime(client, { invocations: [active] })
    sockets.openLatest()

    // 连续发射 2200 行输出（超过 2000 行 UI 预算）
    let offset = 0
    for (let i = 0; i < 2200; i++) {
      const line = `log line ${i}\n`
      const len = line.length
      emitRealtime(
        sockets,
        toolProcessOutputPartial(line, 'APPEND', offset, offset + len, offset + len),
      )
      offset += len
    }

    await waitFor(() => {
      const stream = result.current?.toolStreams.get('inv-tool-1')
      expect(stream).toBeDefined()
      expect(stream?.text).toContain('log line 2199')
    })

    const stream = result.current?.toolStreams.get('inv-tool-1')
    expect(stream?.text.startsWith('... [output omitted] ...\n')).toBe(true)
    const lines = stream?.text.split('\n') ?? []
    // 1 marker line + 2000 content lines + 1 trailing empty string from split
    expect(lines.length).toBeLessThanOrEqual(2002)
    expect(lines[lines.length - 2]).toBe('log line 2199')
  })

  it('replaces transient process.output progress immediately when durable terminal result arrives and ignores late progress', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const active: ToolInvocationDTO = {
      id: 'inv-tool-1',
      modelInvocationId: 'inv-1',
      assistantEntryId: 'entry-2',
      callIndex: 0,
      status: 'RUNNING',
      attempt: 1,
      toolCallId: 'call-1',
      toolName: 'bash',
      rendererKey: 'bash',
      environment: null,
      argumentsJson: '{"command":"test.sh"}',
      approvalJson: null,
      resultJson: null,
      errorJson: null,
      createTime: '2026-01-01T00:00:00Z',
      updateTime: '2026-01-01T00:00:00Z',
    }
    const { result, rerender, sockets } = renderRealtime(client, { invocations: [active] })
    sockets.openLatest()

    emitRealtime(sockets, toolProcessOutputPartial('running step 1\n', 'APPEND', 0, 15, 15))
    await waitFor(() =>
      expect(result.current?.toolStreams.get('inv-tool-1')?.text).toBe('running step 1\n'),
    )

    // 持久化终态到达：快照 resultJson 成为权威
    const terminalInvocation: ToolInvocationDTO = {
      ...active,
      resultJson: JSON.stringify({
        toolCallId: 'call-1',
        contents: [{ type: 'text', text: 'durable completed result' }],
        error: false,
        details: {},
      }),
    }
    rerender({ invocations: [terminalInvocation] })
    await waitFor(() =>
      expect(result.current?.toolStreams.get('inv-tool-1')?.text).toBe('durable completed result'),
    )

    // 终态后到达的迟到 partial 进度被严格忽略
    emitRealtime(sockets, toolProcessOutputPartial('late progress\n', 'APPEND', 15, 29, 29))
    await waitFor(() =>
      expect(result.current?.toolStreams.get('inv-tool-1')?.text).toBe('durable completed result'),
    )
  })
})

function toolProcessOutputPartial(
  text: string,
  mode: 'APPEND' | 'SNAPSHOT',
  startOffset: number,
  endOffset: number,
  observedBytes: number,
  invocationId = 'inv-tool-1',
  attempt = 1,
) {
  return JSON.stringify({
    threadId: THREAD_ID,
    subjectKind: 'TOOL_INVOCATION',
    subjectId: invocationId,
    attempt,
    type: 'TOOL_PARTIAL',
    payload: {
      toolCallId: 'call-1',
      contents: [{ type: 'text', text }],
      error: false,
      details: {
        kind: 'process.output',
        mode,
        startOffset,
        endOffset,
        observedBytes,
      },
    },
    createdAt: '2026-01-01T00:00:00Z',
  })
}

function realtime(
  sequence: number,
  text: string,
  threadId = THREAD_ID,
  invocationId = 'inv-1',
  attempt = 1,
) {
  return JSON.stringify({
    threadId, subjectKind: 'MODEL_INVOCATION', subjectId: invocationId, attempt, sequence,
    type: 'MODEL_DELTA', payload: { kind: 'TEXT_DELTA', text }, createdAt: '2026-01-01T00:00:00Z',
  })
}

function thinkingRealtime(
  sequence: number,
  thinking: string,
  threadId = THREAD_ID,
  invocationId = 'inv-1',
  attempt = 1,
) {
  return JSON.stringify({
    threadId,
    subjectKind: 'MODEL_INVOCATION',
    subjectId: invocationId,
    attempt,
    sequence,
    type: 'MODEL_DELTA',
    payload: { kind: 'THINKING_DELTA', text: thinking },
    createdAt: '2026-01-01T00:00:00Z',
  })
}

function modelAttemptFailure(
  attempt = 1,
  invocationId = 'inv-1',
  sequence = '2',
): ModelAttemptFailureDTO {
  return {
    modelInvocationId: invocationId,
    turnStartEntryId: 'entry-1',
    requestHeadEntryId: 'entry-1',
    attempt,
    sequence,
    text: 'partial',
    thinking: '',
    errorCode: 'TRANSIENT',
    errorMessage: 'failure',
    failedAt: '2026-01-01T00:00:00Z',
    retryAt: '2026-01-01T00:00:02Z',
  }
}

function toolPartial(text: string, invocationId = 'inv-tool-1', attempt = 1) {
  return JSON.stringify({
    threadId: THREAD_ID, subjectKind: 'TOOL_INVOCATION', subjectId: invocationId, attempt,
    type: 'TOOL_PARTIAL', payload: { toolCallId: 'call-1', contents: [{ type: 'text', text }], error: null, details: null },
    createdAt: '2026-01-01T00:00:00Z',
  })
}

function modelInvocation(
  invocationId = 'inv-1',
  threadId = THREAD_ID,
): ModelInvocationDTO {
  return {
    id: invocationId,
    threadId,
    turnStartEntryId: 'entry-1',
    requestHeadEntryId: 'entry-1',
    status: 'RUNNING',
    attempt: 1,
    streamCheckpointJson: '{"attempt":1,"text":"","thinking":"","sequence":0}',
    resultJson: null,
    errorJson: null,
    resultEntryId: null,
    createTime: '2026-01-01T00:00:00Z',
    updateTime: '2026-01-01T00:00:00Z',
  }
}
