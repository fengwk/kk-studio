import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, cleanup, renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApplicationEventProvider } from '@/shared/app-events'
import type { HarnessActiveAssistantOutputDTO, HarnessThreadSnapshotDTO } from '@/shared/api/generated/contracts'
import { harnessService } from '@/shared/api/harness-service'
import { queryKeys } from '@/shared/lib/query-keys'
import { FakeWebSocketHarness } from '@/test-support/fake-websocket'
import { useThreadSnapshotSync } from './thread-snapshot-sync'

const THREAD = '11111111-2222-4333-8444-555555555555'
const SESSION = '22222222-2222-4333-8444-555555555555'
const ENTRY = '33333333-2222-4333-8444-555555555555'
const GENERATION = '44444444-2222-4333-8444-555555555555'

function activeOutput(sequence: string): HarnessActiveAssistantOutputDTO {
  return {
    generationId: GENERATION,
    turnStartEntryId: ENTRY,
    requestHeadEntryId: ENTRY,
    status: 'RUNNING',
    attempt: 1,
    sequence,
    contents: [{ type: 'text', text: `checkpoint-${sequence}` }],
    error: null,
    resultEntryId: null,
    createdAt: 0,
    updatedAt: Number(sequence),
  }
}

function snapshot(version = '1'): HarnessThreadSnapshotDTO {
  return {
    version,
    thread: {
      threadId: THREAD,
      name: 'Thread',
      sessionId: SESSION,
      headEntryId: ENTRY,
      parentThreadId: null,
      rootThreadId: THREAD,
      yoloEnabled: false,
      nextCommandSequence: '1',
      version,
      status: 'RUNNING',
      executionControl: 'RUNNABLE',
      branchSettings: {
        agentName: 'agent',
        execution: { driver: 'builtin', settings: {} },
        environmentName: null,
      },
      createdAt: 0,
      updatedAt: 0,
    },
    baseEntryId: null,
    entries: [{
      entryId: ENTRY,
      sessionId: SESSION,
      parentEntryId: null,
      entryType: 'ROOT',
      payload: {},
      createdAt: 0,
      usageCost: null,
    }],
    queuedCommands: [],
    activeAssistantOutput: null,
    generationFailures: [],
    toolInvocations: [],
    stopReceipts: [],
    unconfirmedExecutions: [],
  }
}

let sockets: FakeWebSocketHarness
let queryClient: QueryClient
const wrapper = ({ children }: { children: ReactNode }) => (
  <QueryClientProvider client={queryClient}>
    <ApplicationEventProvider url="ws://test/ws/events" socketFactory={sockets.factory}>
      {children}
    </ApplicationEventProvider>
  </QueryClientProvider>
)

beforeEach(() => {
  sockets = new FakeWebSocketHarness()
  queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
})

afterEach(() => {
  cleanup()
  queryClient.clear()
  vi.restoreAllMocks()
  vi.useRealTimers()
})

describe('useThreadSnapshotSync', () => {
  it('shares one lease and atomically applies same-version checkpoints without HTTP reads', async () => {
    const key = queryKeys.threads.snapshot(THREAD)
    queryClient.setQueryData(key, snapshot())
    const get = vi.spyOn(harnessService, 'getThreadSnapshot')
    const { result } = renderHook(() => [
      useThreadSnapshotSync(THREAD, true),
      useThreadSnapshotSync(THREAD, true),
    ] as const, { wrapper })
    const socket = sockets.openLatest()
    expect(socket.sentMessages()).toEqual([{
      type: 'subscribe', resource: { kind: 'thread', id: THREAD }, afterEntryId: ENTRY,
    }])

    act(() => socket.emitServer({ type: 'subscribed', resource: { kind: 'thread', id: THREAD }, cursor: '0' }))
    expect(get).not.toHaveBeenCalled()
    act(() => socket.emitServer({
      type: 'event',
      resource: { kind: 'thread', id: THREAD },
      name: 'snapshot',
      data: { ...snapshot(), activeAssistantOutput: activeOutput('7') },
    }))
    await waitFor(() => expect(queryClient.getQueryData<HarnessThreadSnapshotDTO>(key)?.activeAssistantOutput?.sequence).toBe('7'))
    expect(queryClient.getQueryData<HarnessThreadSnapshotDTO>(key)?.version).toBe('1')
    expect(get).not.toHaveBeenCalled()
    expect(result.current[0].error).toBeNull()
  })

  it('keeps projection errors through ACK and retries them with a wire refresh, not HTTP', async () => {
    queryClient.setQueryData(queryKeys.threads.snapshot(THREAD), snapshot())
    const get = vi.spyOn(harnessService, 'getThreadSnapshot')
    const { result } = renderHook(() => useThreadSnapshotSync(THREAD, true), { wrapper })
    const socket = sockets.openLatest()
    act(() => socket.emitServer({
      type: 'error', resource: { kind: 'thread', id: THREAD },
      code: 'THREAD_SNAPSHOT_FAILED', message: 'projection unavailable',
    }))
    await waitFor(() => expect(result.current.error).toBe('projection unavailable'))
    act(() => socket.emitServer({
      type: 'subscribed', resource: { kind: 'thread', id: THREAD }, cursor: '0',
    }))
    expect(result.current.error).toBe('projection unavailable')

    act(() => result.current.retry())
    expect(socket.sentMessages().at(-1)).toEqual({
      type: 'refresh', resource: { kind: 'thread', id: THREAD },
    })
    expect(get).not.toHaveBeenCalled()
    act(() => socket.emitServer({
      type: 'event', resource: { kind: 'thread', id: THREAD }, name: 'snapshot', data: snapshot(),
    }))
    await waitFor(() => expect(result.current.error).toBeNull())
  })

  it('drops a queued snapshot from an old physical connection after reconnect', async () => {
    vi.useFakeTimers()
    queryClient.setQueryData(queryKeys.threads.snapshot(THREAD), snapshot())
    const { result } = renderHook(() => useThreadSnapshotSync(THREAD, true), { wrapper })
    const oldSocket = sockets.openLatest()
    oldSocket.emitServer({
      type: 'connected',
      eventNodeId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
      connectionId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
      heartbeatIntervalMillis: '20000',
    })
    let finishCancel!: () => void
    const pendingCancel = new Promise<void>((resolve) => { finishCancel = resolve })
    const cancel = vi.spyOn(queryClient, 'cancelQueries').mockReturnValue(pendingCancel)
    await act(async () => oldSocket.emitServer({
      type: 'event', resource: { kind: 'thread', id: THREAD }, name: 'snapshot',
      data: { ...snapshot(), activeAssistantOutput: activeOutput('9') },
    }))
    expect(cancel).toHaveBeenCalledTimes(1)

    oldSocket.fail()
    await act(async () => { await vi.advanceTimersByTimeAsync(250) })
    const newSocket = sockets.openLatest()
    newSocket.emitServer({
      type: 'connected',
      eventNodeId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
      connectionId: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc',
      heartbeatIntervalMillis: '20000',
    })
    finishCancel()
    await act(async () => { await pendingCancel })
    expect(queryClient.getQueryData<HarnessThreadSnapshotDTO>(queryKeys.threads.snapshot(THREAD))?.activeAssistantOutput).toBeNull()
    expect(result.current.error).toBeNull()
  })

  it('resync suspends the old lease, reads once, and subscribes from the restored head', async () => {
    queryClient.setQueryData(queryKeys.threads.snapshot(THREAD), snapshot())
    const get = vi.spyOn(harnessService, 'getThreadSnapshot').mockResolvedValue(snapshot('2'))
    const { unmount } = renderHook(() => useThreadSnapshotSync(THREAD, true), { wrapper })
    const socket = sockets.openLatest()
    act(() => socket.emitServer({ type: 'resync', resource: { kind: 'thread', id: THREAD } }))
    await waitFor(() => expect(queryClient.getQueryData<HarnessThreadSnapshotDTO>(queryKeys.threads.snapshot(THREAD))?.version).toBe('2'))
    expect(get).toHaveBeenCalledTimes(1)
    expect(socket.sentMessages()).toEqual([
      { type: 'subscribe', resource: { kind: 'thread', id: THREAD }, afterEntryId: ENTRY },
      { type: 'unsubscribe', resource: { kind: 'thread', id: THREAD } },
      { type: 'subscribe', resource: { kind: 'thread', id: THREAD }, afterEntryId: ENTRY },
    ])
    unmount()
  })

  it('does not loop HTTP recovery when the first resubscription still cannot resume', async () => {
    queryClient.setQueryData(queryKeys.threads.snapshot(THREAD), snapshot())
    const get = vi.spyOn(harnessService, 'getThreadSnapshot').mockResolvedValue(snapshot('2'))
    const { result } = renderHook(() => useThreadSnapshotSync(THREAD, true), { wrapper })
    const socket = sockets.openLatest()
    const resync = { type: 'resync' as const, resource: { kind: 'thread' as const, id: THREAD } }
    act(() => socket.emitServer(resync))
    await waitFor(() => expect(socket.sentMessages()).toHaveLength(3))

    act(() => socket.emitServer(resync))
    await waitFor(() => expect(result.current.error).not.toBeNull())
    expect(get).toHaveBeenCalledTimes(1)
    expect(socket.sentMessages().at(-1)).toEqual({ type: 'unsubscribe', resource: resync.resource })
    expect(queryClient.getQueryData<HarnessThreadSnapshotDTO>(queryKeys.threads.snapshot(THREAD))?.version).toBe('2')
  })

  it('keeps the prior snapshot and exposes a manual retry after recovery failure', async () => {
    queryClient.setQueryData(queryKeys.threads.snapshot(THREAD), snapshot())
    const get = vi.spyOn(harnessService, 'getThreadSnapshot')
      .mockRejectedValueOnce(new Error('offline'))
      .mockResolvedValue(snapshot('2'))
    const { result } = renderHook(() => useThreadSnapshotSync(THREAD, true), { wrapper })
    const socket = sockets.openLatest()
    act(() => socket.emitServer({ type: 'resync', resource: { kind: 'thread', id: THREAD } }))
    await waitFor(() => expect(result.current.error).toBe('offline'))
    expect(queryClient.getQueryData<HarnessThreadSnapshotDTO>(queryKeys.threads.snapshot(THREAD))?.version).toBe('1')
    expect(socket.sentMessages().at(-1)).toEqual({ type: 'unsubscribe', resource: { kind: 'thread', id: THREAD } })

    act(() => result.current.retry())
    await waitFor(() => expect(result.current.error).toBeNull())
    expect(queryClient.getQueryData<HarnessThreadSnapshotDTO>(queryKeys.threads.snapshot(THREAD))?.version).toBe('2')
    expect(get).toHaveBeenCalledTimes(2)
    expect(socket.sentMessages().at(-1)).toEqual({
      type: 'subscribe', resource: { kind: 'thread', id: THREAD }, afterEntryId: ENTRY,
    })
  })
})
