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

    // Repeated identical gap deltas arm ONE recovery loop (never per-delta refetches).
    act(() => source.emit('realtime', realtime(3, 'missing')))
    act(() => source.emit('realtime', realtime(3, 'missing')))
    expect(result.current?.modelStream).toBeNull()
    await waitFor(() => expect(invalidate.mock.calls.length).toBe(1), { timeout: 2000 })


    // The first refetch found the checkpoint still behind (no data change): the loop re-arms
    // itself with backoff and refetches again instead of freezing.
    await waitFor(
      () => expect(invalidate.mock.calls.length).toBeGreaterThanOrEqual(2),
      { timeout: 3000 },
    )

    // The checkpoint catches up: the overlay appears and the loop stops.
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

    // Terminal result WITHOUT resultEntryId: the complete durable result projection replaces
    // the overlay (status 'done') and stays until resultEntryId is set.
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
    // resultEntryId set: the durable Entry is the transcript truth.
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
      // The FIRST refetch (old Thread A recovery) stays in flight until manually resolved.
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

      // Thread A gap delta arms recovery A; its first tick hangs on the in-flight refetch.
      act(() => sources.get('thread-A')?.emit('realtime', realtime(3, 'missing', 'thread-A', 'inv-A')))
      await act(async () => {
        await vi.advanceTimersByTimeAsync(200)
      })
      expect(invalidate).toHaveBeenCalledTimes(1)

      // Switch to Thread B while A's refetch is still in flight; B gets its own gap. The
      // pending schedule is skipped because the old tick still holds the busy flag. The B
      // subscription effect needs one extra render (subscription state round-trip).
      rerender({ threadId: 'thread-B', invocation: modelInvocation('inv-B', 'thread-B') })
      await act(async () => {
        await vi.advanceTimersByTimeAsync(0)
      })
      expect(sources.has('thread-B')).toBe(true)
      act(() => sources.get('thread-B')?.emit('realtime', realtime(3, 'missing', 'thread-B', 'inv-B')))

      // A's stale refetch resolves: it must NOT delete B's recovery, and the re-arm must
      // schedule B (the old tick's finally clears the busy flag).
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

      // B catches up: the overlay appears and the loop stops.
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
      // Give the loop its final caught-up check tick, then verify it stopped.
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
    // First refetch rejects (network/back-end failure); later refetches succeed. The loop must
    // never freeze with a dangling recoveryRef and no timer.
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
    // The failed first tick still re-arms: a second (successful) tick must run.
    await waitFor(() => expect(invalidate).toHaveBeenCalledTimes(2), { timeout: 4000 })

    // The checkpoint catches up on a later refetch and the loop stops.
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

    // In-flight deltas overlay normally.
    act(() => source.emit('realtime', realtime(1, 'first')))
    await waitFor(() => expect(result.current?.modelStream?.text).toBe('first'))

    // resultJson arrives (terminal, no Entry yet): the complete durable result projection
    // replaces the overlay; late deltas are never appended to terminal output.
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

    // errorJson terminal fence as well: the checkpoint is blank here, so the parsed message
    // becomes the frozen temporary text; status is 'error', never 'streaming'.
    rerender({ invocation: { ...modelInvocation(), errorJson: '{"message":"boom"}' } })
    await waitFor(() => expect(result.current?.modelStream?.status).toBe('error'))
    expect(result.current?.modelStream?.text).toBe('boom')
    expect(result.current?.modelStream?.errorText).toBe('boom')
    act(() => source.emit('realtime', realtime(2, '-late-after-error')))
    await waitFor(() =>
      expect(result.current?.modelStream?.text).toBe('boom'),
    )

    // Entry lands: overlay cleared, and any further late delta stays rejected.
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

    // Durable checkpoint at seq5; Redis streaming reaches seq8.
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

    // Terminal resultJson arrives while the checkpoint still lags at seq5: the complete
    // durable projection must unconditionally supersede the seq8 Redis overlay.
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
    // Late Redis deltas never append to the durable projection.
    act(() => source.emit('realtime', realtime(9, '-late')))
    await waitFor(() =>
      expect(result.current?.modelStream?.text).toBe('durable complete answer'),
    )

    // errorJson: checkpoint frozen, status 'error', parsed message surfaced, no appends.
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

    // The durable result Entry lands: the Entry is the transcript truth; overlay gone.
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
      environmentId: null,
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

    // The exact same Redis redelivery must not append twice.
    act(() => source.emit('realtime', toolPartial('one')))
    await waitFor(() => expect(result.current?.toolStreams.get('inv-tool-1')?.text).toBe('one'))
    act(() => source.emit('realtime', toolPartial('one')))
    await waitFor(() =>
      expect(result.current?.toolStreams.get('inv-tool-1')?.text).toBe('one'),
    )
    // A distinct chunk still appends.
    act(() => source.emit('realtime', toolPartial('two')))
    await waitFor(() =>
      expect(result.current?.toolStreams.get('inv-tool-1')?.text).toBe('onetwo'),
    )

    // Attempt change resets the fingerprint scope: a redelivered old-attempt event is ignored,
    // and a same-text event on the NEW attempt is a fresh chunk.
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
      environmentId: null,
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

    // 256 distinct chunks fill the fingerprint set exactly; the 257th evicts ONLY the oldest.
    for (let i = 0; i < 257; i++) {
      act(() => source.emit('realtime', toolPartial(`chunk-${i}`)))
    }
    await waitFor(() =>
      expect(result.current?.toolStreams.get('inv-tool-1')?.text).toContain('chunk-256'),
    )
    const textAfterFill = result.current?.toolStreams.get('inv-tool-1')?.text
    expect(textAfterFill?.length ?? 0).toBeGreaterThan(0)

    // Redelivery of the most recent chunk must be deduplicated. A full clear() would forget
    // everything and append it again; FIFO keeps the recent N fingerprints.
    act(() => source.emit('realtime', toolPartial('chunk-256')))
    await waitFor(() =>
      expect(result.current?.toolStreams.get('inv-tool-1')?.text).toBe(textAfterFill),
    )

    // The oldest chunk was evicted: its redelivery is a fresh chunk again (bounded memory,
    // only the most recent N are protected).
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
      environmentId: null,
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
    // Terminal resultJson with NO durable result Entry yet: the overlay is replaced by the
    // terminal projection (text/error/attachments) instead of being deleted.
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

    // The durable result Entry attaches: the Entry becomes the transcript truth.
    rerender({
      invocations: [{ ...active, resultJson: '{"contents":[]}', resultEntryId: 'entry-9' }],
    })
    await waitFor(() => expect(result.current?.toolStreams.size).toBe(0))

    // The invocation disappears: overlays are cleared as well.
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
      environmentId: null,
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

    // The terminal resultJson arrives in the snapshot: its projection replaces the overlay.
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

    // A late Redis partial must never append to the complete terminal projection.
    act(() => source.emit('realtime', toolPartial('-late')))
    await waitFor(() =>
      expect(result.current?.toolStreams.get('inv-tool-1')?.text).toBe('terminal answer'),
    )

    // An invocation unknown to the durable snapshot is never displayed.
    act(() => source.emit('realtime', toolPartial('ghost', 'inv-ghost')))
    await waitFor(() =>
      expect(result.current?.toolStreams.has('inv-ghost')).toBe(false),
    )

    // A partial from a stale retry attempt is ignored once the snapshot moved on.
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
      environmentId: null,
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

    // errorJson without a result Entry keeps an error overlay visible (never "done").
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
