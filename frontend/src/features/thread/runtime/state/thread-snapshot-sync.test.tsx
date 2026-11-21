import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, cleanup, render, renderHook, screen, waitFor, within } from '@testing-library/react'
import type { ReactNode } from 'react'
import { MemoryRouter } from 'react-router'
import { AgentPane } from '@/features/thread/runtime/panes/AgentPane'
import { agentService } from '@/shared/api/agent-service'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApplicationEventProvider } from '@/shared/app-events'
import { decodeServerMessage } from '@/shared/app-events/protocol'
import type { HarnessActiveAssistantOutputDTO, HarnessThreadSnapshotDTO, ToolInvocationDTO } from '@/shared/api/generated/contracts'
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

const readyOutput = { ...activeOutput('0'), status: 'READY', attempt: 0, contents: [] } satisfies HarnessActiveAssistantOutputDTO
const waitingTool = {
  id: '55555555-2222-4333-8444-555555555555',
  threadId: THREAD,
  childThreadId: null,
  turnStartEntryId: ENTRY,
  assistantEntryId: ENTRY,
  callIndex: 0,
  status: 'WAITING_INPUT',
  attempt: 0,
  toolCallId: 'call-1',
  queuedBehindToolCallId: null,
  toolName: 'ask_user',
  rendererKey: 'ask_user',
  environmentId: null,
  requiredEnvironmentId: null,
  requiredEnvironmentName: null,
  environmentWaitFreshnessAt: null,
  waitingForEnvironment: false,
  argumentsJson: '{}',
  approval: null,
  result: null,
  error: null,
  createdAt: 0,
  updatedAt: 0,
} satisfies ToolInvocationDTO

function snapshotFrame<T>(data: T) {
  return { type: 'event', resource: { kind: 'thread', id: THREAD }, name: 'snapshot', data } as const
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
  vi.unstubAllGlobals()
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

  it('applies zero-attempt model and tool snapshots before completing on the same socket', async () => {
    const key = queryKeys.threads.snapshot(THREAD)
    queryClient.setQueryData(key, snapshot())
    const get = vi.spyOn(harnessService, 'getThreadSnapshot').mockResolvedValue(snapshot())
    const { result } = renderHook(() => useThreadSnapshotSync(THREAD, true), { wrapper })
    const socket = sockets.openLatest()
    act(() => socket.emitServer({ type: 'subscribed', resource: { kind: 'thread', id: THREAD }, cursor: '0' }))

    // Neither pending invocation has started; rejecting either frame would close the socket.
    await act(async () => socket.emitServer(snapshotFrame({ ...snapshot('2'), activeAssistantOutput: readyOutput })))
    expect(queryClient.getQueryData<HarnessThreadSnapshotDTO>(key)?.activeAssistantOutput).toEqual(readyOutput)

    const waiting = snapshot('3')
    waiting.thread.status = 'WAITING'
    waiting.toolInvocations = [waitingTool]
    await act(async () => socket.emitServer(snapshotFrame(waiting)))
    expect(queryClient.getQueryData<HarnessThreadSnapshotDTO>(key)?.toolInvocations).toEqual([waitingTool])

    const terminal = snapshot('4')
    const endId = '66666666-2222-4333-8444-555555555555'
    terminal.thread.status = 'IDLE'
    terminal.thread.headEntryId = endId
    terminal.entries.push({
      entryId: endId, sessionId: SESSION, parentEntryId: ENTRY, entryType: 'TURN_END',
      payload: { outcome: 'COMPLETED', turnStartEntryId: ENTRY }, createdAt: 1, usageCost: null,
    })
    await act(async () => socket.emitServer(snapshotFrame(terminal)))
    expect(queryClient.getQueryData(key)).toEqual(terminal)
    expect(socket.closed).toBe(false)
    expect(sockets.sockets).toEqual([socket])
    expect(get).not.toHaveBeenCalled()
    expect(result.current.error).toBeNull()
  })

  it.each([-1, 0.5, '0', null, Number.MAX_SAFE_INTEGER + 1])('rejects invalid attempt counts (%j) in either snapshot field', (attempt) => {
    // Isolate each field so invalid data in one cannot mask validation of the other.
    expect(decodeServerMessage(JSON.stringify(snapshotFrame({
      ...snapshot(), activeAssistantOutput: { ...readyOutput, attempt },
    })))).toMatchObject({ type: 'error', code: 'INVALID_EVENT_PAYLOAD', resource: snapshotFrame(null).resource })
    expect(decodeServerMessage(JSON.stringify(snapshotFrame({
      ...snapshot(), toolInvocations: [{ ...waitingTool, attempt }],
    })))).toMatchObject({ type: 'error', code: 'INVALID_EVENT_PAYLOAD', resource: snapshotFrame(null).resource })
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

  it('keeps rejected Thread history read-only across reconnect and remount without touching a healthy Thread', async () => {
    vi.useFakeTimers()
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('fixture does not use network')))
    vi.spyOn(agentService, 'listAgents').mockResolvedValue([])
    vi.spyOn(agentService, 'listModels').mockResolvedValue([])
    const get = vi.spyOn(harnessService, 'getThreadSnapshot').mockResolvedValue(snapshot())
    queryClient.setDefaultOptions({ queries: { staleTime: Infinity, retry: false } })
    const resource = snapshotFrame(null).resource
    const healthyId = '77777777-2222-4333-8444-555555555555'
    const healthyResource = { kind: 'thread', id: healthyId } as const
    const key = queryKeys.threads.snapshot(THREAD)
    const cached = snapshot()
    cached.toolInvocations = [waitingTool]
    const messageId = '66666666-2222-4333-8444-555555555555'
    cached.thread.headEntryId = messageId
    cached.entries.push({
      ...cached.entries[0], entryId: messageId, parentEntryId: ENTRY, entryType: 'MESSAGE',
      payload: { message: { role: 'USER', contents: [{ type: 'text', text: 'Retained Thread history' }] } },
    })
    queryClient.setQueryData(key, cached)
    const healthy = (version: string) => {
      const value = snapshot(version)
      return { ...value, thread: { ...value.thread, threadId: healthyId, rootThreadId: healthyId } }
    }
    queryClient.setQueryData(queryKeys.threads.snapshot(healthyId), healthy('1'))
    function Fixture({ show }: { show: boolean }) {
      useThreadSnapshotSync(healthyId, true)
      return show ? <MemoryRouter><AgentPane paneId={THREAD} agents={[]} /></MemoryRouter> : null
    }
    const { container, rerender } = render(<Fixture show />, { wrapper })
    const socket = sockets.openLatest()
    expect(container.querySelector('.thread-working')).not.toBeNull()
    expect(screen.getByRole('textbox')).toBeInTheDocument()
    expect(screen.getByText('Retained Thread history')).toBeInTheDocument()

    await act(async () => {
      // A queued snapshot must not overwrite facts after the permanent refusal.
      socket.emitServer(snapshotFrame(snapshot('9')))
      socket.emitServer({ type: 'error', code: 'RESOURCE_NOT_FOUND', message: 'Resource not found', resource })
      socket.emitServer({ ...snapshotFrame(healthy('2')), resource: healthyResource })
    })
    const history = within(screen.getByRole('log'))
    expect(history.getByRole('alert')).toHaveTextContent('此 Thread 已不存在')
    expect(history.getByText('Retained Thread history')).toBeInTheDocument()
    expect(within(history.getByRole('alert')).queryByRole('button')).not.toBeInTheDocument()
    expect(container.querySelector('.thread-working')).toBeNull()
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
    expect(queryClient.getQueryData(key)).toEqual(cached)
    expect(queryClient.getQueryData(queryKeys.threads.snapshot(healthyId))).toEqual(healthy('2'))
    expect(socket.closed).toBe(false)
    expect(socket.sentMessages().filter((frame) => 'resource' in frame && frame.resource.kind === 'thread'
      && frame.resource.id === THREAD)).toEqual([
      { type: 'subscribe', resource, afterEntryId: messageId },
      { type: 'unsubscribe', resource },
    ])

    await act(async () => socket.fail())
    await act(async () => { await vi.advanceTimersByTimeAsync(300) })
    const next = sockets.openLatest()
    rerender(<Fixture show={false} />)
    rerender(<Fixture show />)
    await act(async () => { await queryClient.invalidateQueries({ queryKey: key, exact: true }) })
    expect(next.sentMessages()).toEqual([{ type: 'subscribe', resource: healthyResource, afterEntryId: ENTRY }])
    expect(screen.getByRole('alert')).toHaveTextContent('此 Thread 已不存在')
    expect(get).not.toHaveBeenCalled()
  })

  it('retries a missing wire subscription once per connection and explicitly thereafter', async () => {
    vi.useFakeTimers()
    queryClient.setQueryData(queryKeys.threads.snapshot(THREAD), snapshot())
    const get = vi.spyOn(harnessService, 'getThreadSnapshot').mockResolvedValue(snapshot())
    const { result } = renderHook(() => [useThreadSnapshotSync(THREAD, true), useThreadSnapshotSync(THREAD, true)], { wrapper })
    const resource = snapshotFrame(null).resource
    const subscribe = { type: 'subscribe', resource, afterEntryId: ENTRY }
    const missing = { type: 'error', code: 'RESOURCE_NOT_SUBSCRIBED', message: 'Not subscribed', resource } as const
    const ack = { type: 'subscribed', resource, cursor: '0' } as const
    const refresh = { type: 'refresh', resource }
    const connected = (connectionId: string) => ({
      type: 'connected', eventNodeId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
      connectionId, heartbeatIntervalMillis: '20000',
    } as const)
    const socket = sockets.openLatest()
    act(() => socket.emitServer(connected('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb')))
    act(() => result.current[0].retry())
    act(() => { socket.emitServer(missing); socket.emitServer(missing) })
    expect(socket.sentMessages()).toEqual([subscribe, refresh, subscribe])
    expect(result.current.map(({ error }) => error)).toEqual(['Not subscribed', 'Not subscribed'])
    act(() => result.current[1].retry())
    expect(socket.sentMessages()).toEqual([subscribe, refresh, subscribe, subscribe])
    expect(result.current[0].error).toBe('Not subscribed')
    act(() => socket.emitServer(ack))
    expect(result.current.map(({ error }) => error)).toEqual([null, null])
    act(() => result.current[0].retry())
    act(() => socket.emitServer(missing))
    expect(socket.sentMessages()).toEqual([subscribe, refresh, subscribe, subscribe, refresh])
    expect(socket.closed).toBe(false)

    act(() => socket.fail())
    await act(async () => { await vi.advanceTimersByTimeAsync(300) })
    const next = sockets.openLatest()
    act(() => next.emitServer(connected('cccccccc-cccc-4ccc-8ccc-cccccccccccc')))
    expect(result.current[0].error).toBe('Not subscribed')
    act(() => { next.emitServer(missing); next.emitServer(missing) })
    expect(next.sentMessages()).toEqual([subscribe, subscribe])
    act(() => next.emitServer(ack))
    expect(result.current[0].error).toBeNull()
    expect(get).not.toHaveBeenCalled()
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
    await act(async () => { await vi.advanceTimersByTimeAsync(300) })
    const newSocket = sockets.openLatest()
    expect(newSocket).not.toBe(oldSocket)
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
    expect(socket.sentMessages()).toHaveLength(3)
    expect(queryClient.getQueryData<HarnessThreadSnapshotDTO>(queryKeys.threads.snapshot(THREAD))?.version).toBe('2')

    await act(async () => socket.emitServer(snapshotFrame(snapshot('3'))))
    expect(result.current.error).toBeNull()
    act(() => socket.emitServer(resync))
    await waitFor(() => expect(get).toHaveBeenCalledTimes(2))
  })

  it('reads each uncovered overflow frontier and resumes chained live snapshots', async () => {
    const terminalId = '66666666-2222-4333-8444-555555555555'
    const base = snapshot('5')
    const terminal = {
      ...base,
      thread: { ...base.thread, headEntryId: terminalId, status: 'IDLE' as const },
      entries: [...base.entries, {
        ...base.entries[0], entryId: terminalId, parentEntryId: ENTRY, entryType: 'MESSAGE' as const,
        payload: { message: { role: 'ASSISTANT', contents: [{ type: 'text', text: 'result'.repeat(2048) }] } },
      }],
    }
    queryClient.setQueryData(queryKeys.threads.snapshot(THREAD), snapshot())
    const get = vi.spyOn(harnessService, 'getThreadSnapshot')
      .mockResolvedValueOnce(snapshot('3'))
      .mockResolvedValueOnce(terminal)
    const { result } = renderHook(() => useThreadSnapshotSync(THREAD, true), { wrapper })
    const socket = sockets.openLatest()
    const ref = (version: string) => ({ type: 'snapshot-ref' as const, resource: snapshotFrame(null).resource, version })

    await act(async () => socket.emitServer(ref('2')))
    expect(socket.sentMessages()).toHaveLength(3)
    await act(async () => {
      socket.emitServer(ref('2'))
      socket.emitServer(ref('3'))
    })
    expect(socket.sentMessages()).toHaveLength(3)
    expect(get).toHaveBeenCalledTimes(1)
    expect(result.current.error).toBeNull()

    await act(async () => socket.emitServer(ref('5')))
    expect(get).toHaveBeenCalledTimes(2)
    expect(socket.sentMessages().at(-1)).toMatchObject({ type: 'subscribe', afterEntryId: terminalId })
    expect(queryClient.getQueryData(queryKeys.threads.snapshot(THREAD))).toEqual(terminal)
    const liveThread = { ...terminal.thread, name: 'Updated on the same connection' }
    await act(async () => socket.emitServer(snapshotFrame({
      ...terminal, thread: liveThread, baseEntryId: terminalId, entries: [],
    })))
    expect(queryClient.getQueryData(queryKeys.threads.snapshot(THREAD))).toEqual({ ...terminal, thread: liveThread })
    expect(get).toHaveBeenCalledTimes(2)
    expect(result.current.error).toBeNull()
  })

  it('isolates corrupt snapshots, bounds recovery, and separately exposes a terminal envelope error', async () => {
    queryClient.setQueryData(queryKeys.threads.snapshot(THREAD), snapshot())
    const get = vi.spyOn(harnessService, 'getThreadSnapshot').mockResolvedValue(snapshot('2'))
    const { result } = renderHook(() => useThreadSnapshotSync(THREAD, true), { wrapper })
    const socket = sockets.openLatest()
    act(() => socket.emitServer({
      type: 'connected', eventNodeId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
      connectionId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', heartbeatIntervalMillis: '20000',
    }))
    const corrupt = snapshotFrame({ ...snapshot(), version: 1 })
    await act(async () => socket.onmessage?.({ data: JSON.stringify(corrupt) }))
    expect(get).toHaveBeenCalledTimes(1)
    expect(result.current.error).toBeNull()
    await act(async () => socket.onmessage?.({ data: JSON.stringify(corrupt) }))
    expect(get).toHaveBeenCalledTimes(1)
    expect(result.current.error).toContain('invalid')

    await act(async () => socket.emitServer(snapshotFrame(snapshot('3'))))
    expect(result.current.error).toBeNull()
    await act(async () => socket.onmessage?.({ data: JSON.stringify({ ...snapshotFrame(snapshot()), name: 'unknown' }) }))
    expect(result.current.error).toContain('此 Thread 的内容可能已过期')
    expect(queryClient.getQueryData(queryKeys.threads.snapshot(THREAD))).toEqual(snapshot('3'))
  })

  it.each(['newer ref', 'sendable gap'])('recovers from a failed overflow read on the same socket via %s', async (trigger) => {
    const key = queryKeys.threads.snapshot(THREAD)
    const initial = snapshot()
    const next = snapshot('3')
    const terminalId = '66666666-2222-4333-8444-555555555555'
    const terminal: HarnessThreadSnapshotDTO = {
      ...next,
      thread: { ...next.thread, status: 'IDLE', headEntryId: terminalId },
      entries: [...next.entries, {
        ...next.entries[0], entryId: terminalId, parentEntryId: ENTRY, entryType: 'MESSAGE',
        payload: { message: { role: 'ASSISTANT', contents: [{ type: 'text', text: 'done' }] } },
      }],
    }
    queryClient.setQueryData(key, initial)
    const get = vi.spyOn(harnessService, 'getThreadSnapshot')
      .mockRejectedValueOnce(new Error('offline'))
      .mockResolvedValueOnce(terminal)
    const { result } = renderHook(() => useThreadSnapshotSync(THREAD, true), { wrapper })
    const socket = sockets.openLatest()
    const ref = { type: 'snapshot-ref' as const, resource: snapshotFrame(null).resource, version: '2' }

    await act(async () => socket.emitServer(ref))
    expect(result.current.error).toBe('offline')
    expect(queryClient.getQueryData(key)).toEqual(initial)
    expect(socket.sentMessages().at(-1)).toMatchObject({ type: 'subscribe', afterEntryId: ENTRY })
    await act(async () => socket.emitServer(ref))
    expect(get).toHaveBeenCalledTimes(1)
    expect(result.current.error).toBe('offline')
    expect(socket.sentMessages()).toHaveLength(3)

    if (trigger === 'newer ref') {
      await act(async () => socket.emitServer({ ...ref, version: '3' }))
    } else {
      await act(async () => socket.emitServer(snapshotFrame({ ...terminal, baseEntryId: terminalId, entries: [] })))
    }
    expect(get).toHaveBeenCalledTimes(2)
    expect(queryClient.getQueryData(key)).toEqual(terminal)
    expect(result.current.error).toBeNull()
    expect(sockets.sockets).toEqual([socket])
  })

  it('rereads a same-version overflow once after a physical reconnect', async () => {
    vi.useFakeTimers()
    const key = queryKeys.threads.snapshot(THREAD)
    queryClient.setQueryData(key, snapshot())
    const refreshed = { ...snapshot('2'), activeAssistantOutput: activeOutput('9') }
    const get = vi.spyOn(harnessService, 'getThreadSnapshot')
      .mockResolvedValueOnce(snapshot('2'))
      .mockResolvedValueOnce(refreshed)
    renderHook(() => useThreadSnapshotSync(THREAD, true), { wrapper })
    const socket = sockets.openLatest()
    const connected = {
      type: 'connected' as const, eventNodeId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
      connectionId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', heartbeatIntervalMillis: '20000',
    }
    const ref = { type: 'snapshot-ref' as const, resource: snapshotFrame(null).resource, version: '2' }
    await act(async () => {
      socket.emitServer(connected)
      socket.emitServer(ref)
    })
    await act(async () => socket.emitServer(ref))
    expect(get).toHaveBeenCalledTimes(1)

    await act(async () => {
      socket.fail()
      await vi.advanceTimersByTimeAsync(300)
    })
    const reconnected = sockets.openLatest()
    expect(reconnected).not.toBe(socket)
    await act(async () => {
      reconnected.emitServer({ ...connected, connectionId: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc' })
      reconnected.emitServer(ref)
    })
    expect(queryClient.getQueryData(key)).toEqual(refreshed)
    await act(async () => reconnected.emitServer(ref))
    expect(get).toHaveBeenCalledTimes(2)
  })

  it('fences a recovery response from a disconnected socket', async () => {
    vi.useFakeTimers()
    const key = queryKeys.threads.snapshot(THREAD)
    queryClient.setQueryData(key, snapshot())
    let resolveRead!: (value: HarnessThreadSnapshotDTO) => void
    const get = vi.spyOn(harnessService, 'getThreadSnapshot').mockReturnValue(
      new Promise((resolve) => { resolveRead = resolve }),
    )
    renderHook(() => useThreadSnapshotSync(THREAD, true), { wrapper })
    const oldSocket = sockets.openLatest()
    const connected = {
      type: 'connected' as const, eventNodeId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
      connectionId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', heartbeatIntervalMillis: '20000',
    }
    await act(async () => {
      oldSocket.emitServer(connected)
      oldSocket.emitServer({ type: 'snapshot-ref', resource: snapshotFrame(null).resource, version: '2' })
    })
    expect(get).toHaveBeenCalledTimes(1)
    await act(async () => {
      oldSocket.fail()
      await vi.advanceTimersByTimeAsync(300)
    })
    const newSocket = sockets.openLatest()
    expect(newSocket).not.toBe(oldSocket)
    newSocket.emitServer({ ...connected, connectionId: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc' })
    // The old transport may return even after abort; it must not publish or claim the gap was covered.
    await act(async () => resolveRead(snapshot('2')))
    expect(queryClient.getQueryData(key)).toEqual(snapshot())
    expect(newSocket.sentMessages().at(-1)).toMatchObject({ type: 'subscribe', afterEntryId: ENTRY })
    await act(async () => newSocket.emitServer(snapshotFrame(snapshot('3'))))
    expect(queryClient.getQueryData(key)).toEqual(snapshot('3'))
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
    expect(socket.sentMessages().at(-1)).toMatchObject({ type: 'subscribe', afterEntryId: ENTRY })
    // The automatic retry is bounded even when the fresh subscription immediately reports the gap again.
    await act(async () => socket.emitServer({ type: 'resync', resource: { kind: 'thread', id: THREAD } }))
    expect(get).toHaveBeenCalledTimes(1)
    expect(result.current.error).toBe('offline')

    act(() => result.current.retry())
    await waitFor(() => expect(result.current.error).toBeNull())
    expect(queryClient.getQueryData<HarnessThreadSnapshotDTO>(queryKeys.threads.snapshot(THREAD))?.version).toBe('2')
    expect(get).toHaveBeenCalledTimes(2)
    expect(socket.sentMessages().at(-1)).toEqual({
      type: 'subscribe', resource: { kind: 'thread', id: THREAD }, afterEntryId: ENTRY,
    })
  })
})
