import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useHarnessThreadRealtime } from '@/features/ai/runtime/useHarnessThreadRealtime'
import type { ModelInvocationDTO } from '@/shared/api/contracts'
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
    const invocations = [invocation('thread-1')]
    vi.mocked(harnessService.createThreadRealtimeStream).mockReturnValue(source as EventSource)
    const { result, rerender } = renderHook(
      ({ revision }) => useHarnessThreadRealtime('thread-1', true, revision, invocations),
      { initialProps: { revision: '42' }, wrapper: wrapper(client) },
    )

    await waitFor(() =>
      expect(harnessService.createThreadRealtimeStream).toHaveBeenCalledWith('thread-1', '42'),
    )
    act(() => source.emit('realtime', realtime(1, 'partial')))
    await waitFor(() => expect(result.current?.text).toBe('partial'))
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

  it('refetches once for a repeated sequence gap and clears the overlay when invocation is applied', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const invalidate = vi.spyOn(client, 'invalidateQueries')
    const source = new FakeEventSource()
    vi.mocked(harnessService.createThreadRealtimeStream).mockReturnValue(source as EventSource)
    const { result, rerender } = renderHook(
      ({ invocations }) => useHarnessThreadRealtime('thread-1', true, '42', invocations),
      { initialProps: { invocations: [invocation('thread-1')] }, wrapper: wrapper(client) },
    )

    act(() => source.emit('realtime', realtime(3, 'missing')))
    act(() => source.emit('realtime', realtime(3, 'missing')))
    expect(invalidate).toHaveBeenCalledTimes(1)
    expect(result.current).toBeNull()
    rerender({
      invocations: [
        {
          ...invocation('thread-1'),
          safeStreamSnapshotJson: '{"text":"recovered","thinking":"","sequence":3}',
        },
      ],
    })
    await waitFor(() => expect(result.current?.text).toBe('recovered'))
    rerender({ invocations: [{ ...invocation('thread-1'), appliedAt: '2026-01-01T00:00:01Z' }] })
    await waitFor(() => expect(result.current).toBeNull())
  })

  it('restores the durable safe snapshot and leaves native reconnect on one EventSource', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const invalidate = vi.spyOn(client, 'invalidateQueries')
    const source = new FakeEventSource()
    vi.mocked(harnessService.createThreadRealtimeStream).mockReturnValue(source as EventSource)
    const restored = {
      ...invocation('thread-1'),
      safeStreamSnapshotJson: '{"text":"safe","thinking":"plan","sequence":2}',
    }
    const { result } = renderHook(
      () => useHarnessThreadRealtime('thread-1', true, '42', [restored]),
      { wrapper: wrapper(client) },
    )

    await waitFor(() =>
      expect(result.current).toMatchObject({ text: 'safe', thinking: 'plan', sequence: 2 }),
    )
    act(() => {
      source.fail()
      source.fail()
      source.fail()
    })
    expect(harnessService.createThreadRealtimeStream).toHaveBeenCalledTimes(1)
    expect(invalidate).toHaveBeenCalledTimes(3)
  })
})

function realtime(sequence: number, text: string) {
  return JSON.stringify({
    threadId: 'thread-1', subjectKind: 'MODEL_INVOCATION', subjectId: 'inv-1', attempt: 1, sequence,
    type: 'MODEL_DELTA', payload: { kind: 'TEXT_DELTA', text }, createdAt: '2026-01-01T00:00:00Z',
  })
}

function invocation(threadId: string): ModelInvocationDTO {
  return {
    id: 'inv-1', threadId, sourceHeadEntryId: 'entry-1', executionEpoch: '1', requestJson: '{}',
    status: 'RUNNING', attempt: 1, nextAttemptAt: null, workerUntil: null, deadlineAt: null,
    lastActivityAt: null, resultJson: null, errorJson: null, appliedAt: null,
    createdAt: '2026-01-01T00:00:00Z', startedAt: '2026-01-01T00:00:00Z', finishedAt: null,
    safeStreamSnapshotJson: '{"text":"","thinking":"","sequence":0}',
  }
}
