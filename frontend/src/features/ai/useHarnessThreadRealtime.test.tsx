import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useHarnessThreadRealtime } from '@/features/ai/useHarnessThreadRealtime'
import { harnessService } from '@/shared/api/harness-service'
import { queryKeys } from '@/shared/lib/query-keys'

vi.mock('@/shared/api/harness-service', () => ({
  harnessService: {
    createThreadRealtimeStream: vi.fn(),
  },
}))

class FakeEventSource {
  readonly close = vi.fn()
  onerror: ((event: Event) => void) | null = null
  private readonly listeners = new Map<string, EventListener[]>()

  addEventListener(type: string, listener: EventListener) {
    this.listeners.set(type, [...(this.listeners.get(type) ?? []), listener])
  }

  emit(type: string, data = '{}') {
    for (const listener of this.listeners.get(type) ?? []) {
      listener({ data } as MessageEvent<string>)
    }
  }

  fail() {
    this.onerror?.(new Event('error'))
  }
}

function createWrapper(queryClient: QueryClient) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  }
}

describe('useHarnessThreadRealtime', () => {
  afterEach(() => {
    vi.useRealTimers()
    vi.clearAllMocks()
  })

  it('opens one source with the snapshot revision and invalidates only its snapshot on durable events', async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')
    const source = new FakeEventSource()
    vi.mocked(harnessService.createThreadRealtimeStream).mockReturnValue(source as EventSource)

    const { rerender } = renderHook(
      ({ revision }) => useHarnessThreadRealtime('thread-1', true, revision, []),
      { initialProps: { revision: '42' }, wrapper: createWrapper(queryClient) },
    )

    await waitFor(() =>
      expect(harnessService.createThreadRealtimeStream).toHaveBeenCalledWith('thread-1', '42'),
    )
    rerender({ revision: '42' })
    expect(harnessService.createThreadRealtimeStream).toHaveBeenCalledTimes(1)

    invalidateSpy.mockClear()
    act(() => source.emit('revision', '{"revision":"43"}'))
    act(() => source.emit('resync'))
    await waitFor(() => expect(invalidateSpy).toHaveBeenCalledTimes(2))
    expect(invalidateSpy).toHaveBeenNthCalledWith(1, {
      queryKey: queryKeys.threads.snapshot('thread-1'),
    })
    expect(invalidateSpy).toHaveBeenNthCalledWith(2, {
      queryKey: queryKeys.threads.snapshot('thread-1'),
    })

    invalidateSpy.mockClear()
    act(() =>
      source.emit(
        'realtime',
        '{"threadId":"thread-1","subjectKind":"MODEL_INVOCATION","subjectId":"inv-1","attempt":1,"type":"MODEL_DELTA","payload":{"kind":"TEXT_DELTA","text":"partial"},"createdAt":"2026-01-01T00:00:00Z"}',
      ),
    )
    expect(invalidateSpy).not.toHaveBeenCalled()

    act(() => source.emit('realtime', 'not-json'))
    act(() =>
      source.emit(
        'realtime',
        '{"threadId":"another-thread","subjectKind":"MODEL_INVOCATION","subjectId":"inv-1","attempt":1,"type":"MODEL_DELTA","payload":{"kind":"TEXT_DELTA","text":"partial"},"createdAt":"2026-01-01T00:00:00Z"}',
      ),
    )
    expect(invalidateSpy).not.toHaveBeenCalled()
  })

  it('coalesces repeated transport errors into one scheduled reconnect', async () => {
    vi.useFakeTimers()
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const first = new FakeEventSource()
    const second = new FakeEventSource()
    vi.mocked(harnessService.createThreadRealtimeStream)
      .mockReturnValueOnce(first as EventSource)
      .mockReturnValueOnce(second as EventSource)

    renderHook(() => useHarnessThreadRealtime('thread-1', true, '42', []), {
      wrapper: createWrapper(queryClient),
    })
    expect(harnessService.createThreadRealtimeStream).toHaveBeenCalledTimes(1)

    act(() => {
      first.fail()
      first.fail()
      vi.advanceTimersByTime(250)
    })
    expect(first.close).toHaveBeenCalled()
    expect(harnessService.createThreadRealtimeStream).toHaveBeenCalledTimes(2)
  })

  it('does not subscribe until a successful snapshot supplies a revision', () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })

    renderHook(() => useHarnessThreadRealtime('thread-1', false, undefined, []), {
      wrapper: createWrapper(queryClient),
    })

    expect(harnessService.createThreadRealtimeStream).not.toHaveBeenCalled()
  })

  it('does not expose a prior Thread transient overlay after switching Threads', async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const source = new FakeEventSource()
    vi.mocked(harnessService.createThreadRealtimeStream).mockReturnValue(source as EventSource)
    const { result, rerender } = renderHook(
      ({ threadId }) => useHarnessThreadRealtime(threadId, true, '42', []),
      { initialProps: { threadId: 'thread-1' }, wrapper: createWrapper(queryClient) },
    )

    await waitFor(() => expect(harnessService.createThreadRealtimeStream).toHaveBeenCalledTimes(1))
    act(() =>
      source.emit(
        'realtime',
        '{"threadId":"thread-1","subjectKind":"MODEL_INVOCATION","subjectId":"inv-1","attempt":1,"type":"MODEL_DELTA","payload":{"kind":"TEXT_DELTA","text":"partial"},"createdAt":"2026-01-01T00:00:00Z"}',
      ),
    )
    await waitFor(() => expect(result.current?.text).toBe('partial'))

    rerender({ threadId: 'thread-2' })
    expect(result.current).toBeNull()
  })

  it('falls back to the snapshot after the bounded reconnect budget is exhausted', () => {
    vi.useFakeTimers()
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')
    const sources = [new FakeEventSource(), new FakeEventSource(), new FakeEventSource(), new FakeEventSource()]
    let nextSource = 0
    vi.mocked(harnessService.createThreadRealtimeStream).mockImplementation(
      () => sources[nextSource++] as EventSource,
    )

    renderHook(() => useHarnessThreadRealtime('thread-1', true, '42', []), {
      wrapper: createWrapper(queryClient),
    })
    for (let attempt = 0; attempt < 3; attempt += 1) {
      act(() => {
        sources[attempt].fail()
        vi.advanceTimersByTime((attempt + 1) * 250)
      })
    }
    act(() => sources[3].fail())

    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: queryKeys.threads.snapshot('thread-1') })
  })
})
