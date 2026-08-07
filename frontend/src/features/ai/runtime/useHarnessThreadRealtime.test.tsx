import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}
import { useHarnessThreadRealtime } from '@/features/ai/runtime/useHarnessThreadRealtime'
import type { ModelInvocationDTO, ToolInvocationDTO } from '@/shared/api/contracts/ai-runtime'
import { harnessService } from '@/shared/api/harness-service'

vi.mock('@/shared/api/harness-service', () => ({ harnessService: { createThreadRealtimeStream: vi.fn() } }))

class FakeEventSource {
  readonly close = vi.fn()
  onerror: ((event: Event) => void) | null = null
  private readonly listeners = new Map<string, EventListener[]>()

  addEventListener(type: string, listener: EventListener) {
    this.listeners.set(type, [...(this.listeners.get(type) ?? []), listener])
  }

  emit(type: string, data = '{}') {
    this.listeners.get(type)?.forEach((listener) => listener({ data } as MessageEvent<string>))
  }

  fail() {
    this.onerror?.(new Event('error'))
  }
}

function wrapper(queryClient: QueryClient) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  }
}

describe('useHarnessThreadRealtime', () => {
  afterEach(() => vi.clearAllMocks())

  it('uses one native EventSource, invalidates durable signals, and overlays the next delta', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const invalidate = vi.spyOn(client, 'invalidateQueries')
    const source = new FakeEventSource()
    vi.mocked(harnessService.createThreadRealtimeStream).mockReturnValue(source as EventSource)
    const { result, rerender } = renderHook(
      ({ revision }) =>
        useHarnessThreadRealtime('thread-1', true, revision, modelInvocation(), []),
      { initialProps: { revision: '42' }, wrapper: wrapper(client) },
    )

    await waitFor(() =>
      expect(harnessService.createThreadRealtimeStream).toHaveBeenCalledWith('thread-1', '42'),
    )
    act(() => source.emit('realtime', realtime(1, 'partial')))
    await waitFor(() => expect(result.current?.modelStream?.text).toBe('partial'))
    act(() => {
      source.emit('revision')
      source.emit('resync')
      source.fail()
      source.fail()
    })
    rerender({ revision: '43' })
    expect(harnessService.createThreadRealtimeStream).toHaveBeenCalledTimes(1)
    expect(invalidate).toHaveBeenCalledTimes(4)
  })

  it('single-flight gap recovery re-refetches with bounded backoff until the checkpoint catches up', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const invalidate = vi.spyOn(client, 'invalidateQueries')
    const source = new FakeEventSource()
    vi.mocked(harnessService.createThreadRealtimeStream).mockReturnValue(source as EventSource)
    const { result, rerender } = renderHook(
      ({ invocation }) =>
        useHarnessThreadRealtime('thread-1', true, '42', invocation, []),
      { initialProps: { invocation: modelInvocation() }, wrapper: wrapper(client) },
    )

    // 重复且相同的 gap delta 只触发一次恢复循环（永不按每条 delta 单独 refetch）。
    act(() => source.emit('realtime', realtime(3, 'missing')))
    act(() => source.emit('realtime', realtime(3, 'missing')))
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
      const sources = new Map<string, FakeEventSource>()
      vi.mocked(harnessService.createThreadRealtimeStream).mockImplementation((threadId) => {
        const source = new FakeEventSource()
        sources.set(String(threadId), source)
        return source as EventSource
      })
      // 首次 refetch（旧 Thread A 的恢复）会一直处于 in-flight，直到手动 resolve。
      let resolveFirstRefetch: (() => void) | null = null
      let firstRefetch = true
      invalidate.mockImplementation(async (options) => {
        const key = (options?.queryKey ?? []) as readonly unknown[]
        const refetchThreadId = String(key[key.length - 1] ?? '')
        if (firstRefetch && refetchThreadId === 'thread-A') {
          firstRefetch = false
          return new Promise<void>((resolve) => {
            resolveFirstRefetch = resolve
          })
        }
        return Promise.resolve()
      })

      const { result, rerender } = renderHook(
        ({ threadId, invocation }) =>
          useHarnessThreadRealtime(threadId, true, '42', invocation, []),
        {
          initialProps: {
            threadId: 'thread-A',
            invocation: modelInvocation('inv-A', 'thread-A'),
          },
          wrapper: wrapper(client),
        },
      )

      // Thread A 的 gap delta 会挂起一次恢复 A；它的首次 tick 会卡在 in-flight 的 refetch 上。
      act(() => sources.get('thread-A')?.emit('realtime', realtime(3, 'missing', 'thread-A', 'inv-A')))
      await act(async () => {
        await vi.advanceTimersByTimeAsync(200)
      })
      expect(invalidate).toHaveBeenCalledTimes(1)

      // 在 A 的 refetch 仍在 in-flight 时切到 Thread B；B 自己的 gap 独立计算。
      // 由于旧 tick 仍持有 busy flag，新挂起的调度被跳过。B 的订阅 effect
      // 还需要再渲染一次（订阅状态的回路往返）。
      rerender({ threadId: 'thread-B', invocation: modelInvocation('inv-B', 'thread-B') })
      await act(async () => {
        await vi.advanceTimersByTimeAsync(0)
      })
      expect(sources.has('thread-B')).toBe(true)
      act(() => sources.get('thread-B')?.emit('realtime', realtime(3, 'missing', 'thread-B', 'inv-B')))

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
          (call) => String((call[0]?.queryKey as readonly unknown[])?.at(-1)) === 'thread-B',
        ),
      ).toBe(true)

      // B 追平：overlay 出现，循环停止。
      rerender({
        threadId: 'thread-B',
        invocation: {
          ...modelInvocation('inv-B', 'thread-B'),
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
    const source = new FakeEventSource()
    vi.mocked(harnessService.createThreadRealtimeStream).mockReturnValue(source as EventSource)
    // 首次 refetch 失败（网络/后端故障），后续 refetch 成功。循环绝不能因
    // 残留的 recoveryRef 又没有定时器而冻结。
    invalidate
      .mockRejectedValueOnce(new Error('refetch failed'))
      .mockResolvedValue(undefined)
    const { result, rerender } = renderHook(
      ({ invocation }) =>
        useHarnessThreadRealtime('thread-1', true, '42', invocation, []),
      { initialProps: { invocation: modelInvocation() }, wrapper: wrapper(client) },
    )

    act(() => source.emit('realtime', realtime(3, 'missing')))
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
    const source = new FakeEventSource()
    vi.mocked(harnessService.createThreadRealtimeStream).mockReturnValue(source as EventSource)
    const { result, rerender } = renderHook(
      ({ invocation }) =>
        useHarnessThreadRealtime('thread-1', true, '42', invocation, []),
      { initialProps: { invocation: modelInvocation() }, wrapper: wrapper(client) },
    )

    // 进行中的 delta 按正常方式叠加。
    act(() => source.emit('realtime', realtime(1, 'first')))
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
    act(() => source.emit('realtime', realtime(2, '-late-after-result')))
    await waitFor(() =>
      expect(result.current?.modelStream?.text).toBe('first'),
    )

    // errorJson 也是终止态栅栏：此时 checkpoint 为空，解析出的 message 被冻结
    // 作为临时文本；status 为 'error'，绝不会是 'streaming'。
    rerender({ invocation: { ...modelInvocation(), errorJson: '{"message":"boom"}' } })
    await waitFor(() => expect(result.current?.modelStream?.status).toBe('error'))
    expect(result.current?.modelStream?.text).toBe('boom')
    expect(result.current?.modelStream?.errorText).toBe('boom')
    act(() => source.emit('realtime', realtime(2, '-late-after-error')))
    await waitFor(() =>
      expect(result.current?.modelStream?.text).toBe('boom'),
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
    act(() => source.emit('realtime', realtime(2, '-late-after-entry')))
    await waitFor(() => expect(result.current?.modelStream).toBeNull())
  })

  it('replaces a higher-sequence streaming overlay with the durable terminal projection', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const source = new FakeEventSource()
    vi.mocked(harnessService.createThreadRealtimeStream).mockReturnValue(source as EventSource)
    const { result, rerender } = renderHook(
      ({ invocation }) =>
        useHarnessThreadRealtime('thread-1', true, '42', invocation, []),
      { initialProps: { invocation: modelInvocation() }, wrapper: wrapper(client) },
    )

    // 持久化 checkpoint 在 seq5；Redis 流式到达 seq8。
    rerender({
      invocation: {
        ...modelInvocation(),
        streamCheckpointJson: '{"attempt":1,"text":"safe five","thinking":"","sequence":5}',
      },
    })
    await waitFor(() => expect(result.current?.modelStream?.text).toBe('safe five'))
    act(() => source.emit('realtime', realtime(6, '-six')))
    act(() => source.emit('realtime', realtime(7, '-seven')))
    act(() => source.emit('realtime', realtime(8, '-eight')))
    await waitFor(() =>
      expect(result.current?.modelStream?.text).toBe('safe five-six-seven-eight'),
    )
    expect(result.current?.modelStream?.status).toBe('streaming')

    // checkpoint 仍落后于 seq5 时终态 resultJson 到达：完整的
    // 持久化投影必须无条件取代 seq8 的 Redis overlay。
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
    // 迟到的 Redis delta 绝不会追加到持久化投影上。
    act(() => source.emit('realtime', realtime(9, '-late')))
    await waitFor(() =>
      expect(result.current?.modelStream?.text).toBe('durable complete answer'),
    )

    // errorJson：checkpoint 冻结，status 为 'error'，解析出的消息对外可见，不再追加。
    rerender({
      invocation: {
        ...modelInvocation(),
        streamCheckpointJson: '{"attempt":1,"text":"frozen text","thinking":"","sequence":5}',
        errorJson: '{"kind":"PROVIDER_ERROR","message":"provider exploded"}',
      },
    })
    await waitFor(() => expect(result.current?.modelStream?.status).toBe('error'))
    expect(result.current?.modelStream?.text).toBe('frozen text')
    expect(result.current?.modelStream?.errorText).toBe('provider exploded')
    act(() => source.emit('realtime', realtime(6, '-after-error')))
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
    const source = new FakeEventSource()
    vi.mocked(harnessService.createThreadRealtimeStream).mockReturnValue(source as EventSource)
    const active: ToolInvocationDTO = {
      id: 'inv-tool-1',
      modelInvocationId: 'inv-1',
      assistantEntryId: 'entry-2',
      ordinal: 0,
      status: 'RUNNING',
      attempt: 1,
      toolCallId: 'call-1',
      toolName: 'web-search',
      toolVersion: null,
      toolType: 'PLATFORM',
      environmentName: null,
      argumentsJson: '{}',
      approvalJson: null,
      resultJson: null,
      errorJson: null,
      resultEntryId: null,
      createTime: '2026-01-01T00:00:00Z',
      updateTime: '2026-01-01T00:00:00Z',
    }
    const { result, rerender } = renderHook(
      ({ invocations }) =>
        useHarnessThreadRealtime('thread-1', true, '42', modelInvocation(), invocations),
      { initialProps: { invocations: [active] }, wrapper: wrapper(client) },
    )

    // 完全相同的 Redis 重投递不能追加两次。
    act(() => source.emit('realtime', toolPartial('one')))
    await waitFor(() => expect(result.current?.toolStreams.get('inv-tool-1')?.text).toBe('one'))
    act(() => source.emit('realtime', toolPartial('one')))
    await waitFor(() =>
      expect(result.current?.toolStreams.get('inv-tool-1')?.text).toBe('one'),
    )
    // 不同的块仍然会追加。
    act(() => source.emit('realtime', toolPartial('two')))
    await waitFor(() =>
      expect(result.current?.toolStreams.get('inv-tool-1')?.text).toBe('onetwo'),
    )

    // attempt 变化会重置指纹范围：旧 attempt 事件的重投递被忽略，
    // 而新 attempt 上相同文本的事件是新块。
    rerender({ invocations: [{ ...active, attempt: 2 }] })
    await waitFor(() =>
      expect(result.current?.toolStreams.get('inv-tool-1')?.attempt).toBe(2),
    )
    act(() => source.emit('realtime', toolPartial('one', 'inv-tool-1', 1)))
    await waitFor(() =>
      expect(result.current?.toolStreams.get('inv-tool-1')?.text).toBe(''),
    )
    act(() => source.emit('realtime', toolPartial('one', 'inv-tool-1', 2)))
    await waitFor(() =>
      expect(result.current?.toolStreams.get('inv-tool-1')?.text).toBe('one'),
    )
  })

  it('evicts only the oldest TOOL_PARTIAL fingerprint at the capacity boundary (FIFO)', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const source = new FakeEventSource()
    vi.mocked(harnessService.createThreadRealtimeStream).mockReturnValue(source as EventSource)
    const active: ToolInvocationDTO = {
      id: 'inv-tool-1',
      modelInvocationId: 'inv-1',
      assistantEntryId: 'entry-2',
      ordinal: 0,
      status: 'RUNNING',
      attempt: 1,
      toolCallId: 'call-1',
      toolName: 'web-search',
      toolVersion: null,
      toolType: 'PLATFORM',
      environmentName: null,
      argumentsJson: '{}',
      approvalJson: null,
      resultJson: null,
      errorJson: null,
      resultEntryId: null,
      createTime: '2026-01-01T00:00:00Z',
      updateTime: '2026-01-01T00:00:00Z',
    }
    const { result } = renderHook(
      () => useHarnessThreadRealtime('thread-1', true, '42', modelInvocation(), [active]),
      { wrapper: wrapper(client) },
    )

    // 256 个不同块恰好填满指纹集合；第 257 个只淘汰最旧的一个。
    for (let i = 0; i < 257; i++) {
      act(() => source.emit('realtime', toolPartial(`chunk-${i}`)))
    }
    await waitFor(() =>
      expect(result.current?.toolStreams.get('inv-tool-1')?.text).toContain('chunk-256'),
    )
    const textAfterFill = result.current?.toolStreams.get('inv-tool-1')?.text
    expect(textAfterFill?.length ?? 0).toBeGreaterThan(0)

    // 最近块的重新投递必须被去重。整体 clear() 会忘记
    // 一切并再次追加；FIFO 保留最近 N 个指纹。
    act(() => source.emit('realtime', toolPartial('chunk-256')))
    await waitFor(() =>
      expect(result.current?.toolStreams.get('inv-tool-1')?.text).toBe(textAfterFill),
    )

    // 最旧的块已被淘汰：它的重投递再次成为新块（有界内存，
    // 只有最近 N 个受保护）。
    act(() => source.emit('realtime', toolPartial('chunk-0')))
    await waitFor(() =>
      expect(result.current?.toolStreams.get('inv-tool-1')?.text).toBe(`${textAfterFill}chunk-0`),
    )
  })

  it('restores the durable checkpoint and leaves native reconnect on one EventSource', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const invalidate = vi.spyOn(client, 'invalidateQueries')
    const source = new FakeEventSource()
    vi.mocked(harnessService.createThreadRealtimeStream).mockReturnValue(source as EventSource)
    const restored = {
      ...modelInvocation(),
      streamCheckpointJson: '{"attempt":1,"text":"safe","thinking":"plan","sequence":2}',
    }
    const { result } = renderHook(
      () => useHarnessThreadRealtime('thread-1', true, '42', restored, []),
      { wrapper: wrapper(client) },
    )

    await waitFor(() =>
      expect(result.current?.modelStream).toMatchObject({ text: 'safe', thinking: 'plan', sequence: 2 }),
    )
    act(() => {
      source.fail()
      source.fail()
      source.fail()
    })
    expect(harnessService.createThreadRealtimeStream).toHaveBeenCalledTimes(1)
    expect(invalidate).toHaveBeenCalledTimes(3)
  })

  it('aggregates TOOL_PARTIAL overlays per invocation and clears on the durable terminal result', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const source = new FakeEventSource()
    vi.mocked(harnessService.createThreadRealtimeStream).mockReturnValue(source as EventSource)
    const active: ToolInvocationDTO = {
      id: 'inv-tool-1',
      modelInvocationId: 'inv-1',
      assistantEntryId: 'entry-2',
      ordinal: 0,
      status: 'RUNNING',
      attempt: 1,
      toolCallId: 'call-1',
      toolName: 'web-search',
      toolVersion: null,
      toolType: 'PLATFORM',
      environmentName: null,
      argumentsJson: '{}',
      approvalJson: null,
      resultJson: null,
      errorJson: null,
      resultEntryId: null,
      createTime: '2026-01-01T00:00:00Z',
      updateTime: '2026-01-01T00:00:00Z',
    }
    const { result, rerender } = renderHook(
      ({ invocations }) =>
        useHarnessThreadRealtime('thread-1', true, '42', modelInvocation(), invocations),
      { initialProps: { invocations: [active] }, wrapper: wrapper(client) },
    )

    act(() => source.emit('realtime', toolPartial('partial-one')))
    await waitFor(() =>
      expect(result.current?.toolStreams.get('inv-tool-1')?.text).toBe('partial-one'),
    )
    act(() => source.emit('realtime', toolPartial('partial-two')))
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

    // 持久化 result Entry 挂接：Entry 成为 transcript 的事实来源。
    rerender({
      invocations: [{ ...active, resultJson: '{"contents":[]}', resultEntryId: 'entry-9' }],
    })
    await waitFor(() => expect(result.current?.toolStreams.size).toBe(0))

    // invocation 消失：overlay 也一并清除。
    rerender({ invocations: [{ ...active, resultJson: '{"contents":[]}' }] })
    await waitFor(() =>
      expect(result.current?.toolStreams.get('inv-tool-1')?.text).toBe(''),
    )
    rerender({ invocations: [] })
    await waitFor(() => expect(result.current?.toolStreams.size).toBe(0))
  })


  it('ignores TOOL_PARTIAL after the terminal projection and for unknown/retry-attempt invocations', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const source = new FakeEventSource()
    vi.mocked(harnessService.createThreadRealtimeStream).mockReturnValue(source as EventSource)
    const active: ToolInvocationDTO = {
      id: 'inv-tool-1',
      modelInvocationId: 'inv-1',
      assistantEntryId: 'entry-2',
      ordinal: 0,
      status: 'RUNNING',
      attempt: 1,
      toolCallId: 'call-1',
      toolName: 'web-search',
      toolVersion: null,
      toolType: 'PLATFORM',
      environmentName: null,
      argumentsJson: '{}',
      approvalJson: null,
      resultJson: null,
      errorJson: null,
      resultEntryId: null,
      createTime: '2026-01-01T00:00:00Z',
      updateTime: '2026-01-01T00:00:00Z',
    }
    const { result, rerender } = renderHook(
      ({ invocations }) =>
        useHarnessThreadRealtime('thread-1', true, '42', modelInvocation(), invocations),
      { initialProps: { invocations: [active] }, wrapper: wrapper(client) },
    )

    act(() => source.emit('realtime', toolPartial('one')))
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

    // 迟到的 Redis partial 绝不能追加到完整的终态投影上。
    act(() => source.emit('realtime', toolPartial('-late')))
    await waitFor(() =>
      expect(result.current?.toolStreams.get('inv-tool-1')?.text).toBe('terminal answer'),
    )

    // 持久化 snapshot 中未知的 invocation 永不展示。
    act(() => source.emit('realtime', toolPartial('ghost', 'inv-ghost')))
    await waitFor(() =>
      expect(result.current?.toolStreams.has('inv-ghost')).toBe(false),
    )

    // snapshot 前移后，来自过期 retry attempt 的 partial 被忽略。
    rerender({ invocations: [{ ...active, attempt: 2 }] })
    await waitFor(() =>
      expect(result.current?.toolStreams.get('inv-tool-1')?.attempt).toBe(2),
    )
    act(() => source.emit('realtime', toolPartial('stale-attempt', 'inv-tool-1', 1)))
    await waitFor(() =>
      expect(result.current?.toolStreams.get('inv-tool-1')?.text).toBe(''),
    )
  })

  it('projects terminal errorJson (error text) while the durable result Entry is pending', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const source = new FakeEventSource()
    vi.mocked(harnessService.createThreadRealtimeStream).mockReturnValue(source as EventSource)
    const active: ToolInvocationDTO = {
      id: 'inv-tool-1',
      modelInvocationId: 'inv-1',
      assistantEntryId: 'entry-2',
      ordinal: 0,
      status: 'RUNNING',
      attempt: 1,
      toolCallId: 'call-1',
      toolName: 'web-search',
      toolVersion: null,
      toolType: 'PLATFORM',
      environmentName: null,
      argumentsJson: '{}',
      approvalJson: null,
      resultJson: null,
      errorJson: null,
      resultEntryId: null,
      createTime: '2026-01-01T00:00:00Z',
      updateTime: '2026-01-01T00:00:00Z',
    }
    const { result, rerender } = renderHook(
      ({ invocations }) =>
        useHarnessThreadRealtime('thread-1', true, '42', modelInvocation(), invocations),
      { initialProps: { invocations: [active] }, wrapper: wrapper(client) },
    )

    act(() => source.emit('realtime', toolPartial('partial-one')))
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
})

function realtime(
  sequence: number,
  text: string,
  threadId = 'thread-1',
  invocationId = 'inv-1',
) {
  return JSON.stringify({
    threadId, subjectKind: 'MODEL_INVOCATION', subjectId: invocationId, attempt: 1, sequence,
    type: 'MODEL_DELTA', payload: { kind: 'TEXT_DELTA', text }, createdAt: '2026-01-01T00:00:00Z',
  })
}

function toolPartial(text: string, invocationId = 'inv-tool-1', attempt = 1) {
  return JSON.stringify({
    threadId: 'thread-1', subjectKind: 'TOOL_INVOCATION', subjectId: invocationId, attempt,
    type: 'TOOL_PARTIAL', payload: { toolCallId: 'call-1', contents: [{ type: 'text', text }], error: null, details: null },
    createdAt: '2026-01-01T00:00:00Z',
  })
}

function modelInvocation(
  invocationId = 'inv-1',
  threadId = 'thread-1',
): ModelInvocationDTO {
  return {
    id: invocationId,
    threadId,
    turnStartEntryId: 'entry-1',
    basisHeadEntryId: 'entry-1',
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
