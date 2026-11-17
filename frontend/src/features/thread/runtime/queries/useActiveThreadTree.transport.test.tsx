import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { HarnessThreadTreeNodeDTO } from '@/shared/api/generated/contracts'
import { ApplicationEventProvider } from '@/shared/app-events'
import { FakeWebSocketHarness } from '@/test-support/fake-websocket'
import { SubagentTreePanel } from '../panes/SubagentTreePanel'
import { useActiveThreadTree } from './useActiveThreadTree'

const transport = vi.fn()

const ROOT = '00000000-0000-4000-8000-000000000001'
const CHILD = '00000000-0000-4000-8000-000000000002'
const OTHER_ROOT = '00000000-0000-4000-8000-000000000003'
function node(threadId: string, parentThreadId: string | null): HarnessThreadTreeNodeDTO {
  return {
    threadId, kind: parentThreadId == null ? 'USER' : 'SUBAGENT', parentThreadId,
    name: 'main', agentName: 'Explorer', execution: { driver: 'builtin', settings: {} },
    updatedAt: 1774958400.125,
    executionControl: 'RUNNABLE', status: 'IDLE', turnCount: 3, toolCallCount: 5, outcome: 'COMPLETED',
  }
}
function reply(nodes: unknown[]) {
  transport.mockImplementation(async () => new Response(JSON.stringify(nodes), {
    headers: { 'Content-Type': 'application/json' },
  }))
}
function Panel() {
  const tree = useActiveThreadTree(CHILD)
  return (
    <>
      <SubagentTreePanel tree={tree} currentThreadId={CHILD} />
      <button onClick={tree.refetch}>refresh tree</button>
    </>
  )
}
function renderPanel() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const sockets = new FakeWebSocketHarness()
  render(
    <QueryClientProvider client={client}>
      <ApplicationEventProvider url="ws://test/ws/events" socketFactory={sockets.factory}>
        <MemoryRouter><Panel /></MemoryRouter>
      </ApplicationEventProvider>
    </QueryClientProvider>,
  )
  return { client, sockets }
}

describe('execution tree HTTP Instant contract', () => {
  beforeEach(() => {
    transport.mockReset()
    vi.stubGlobal('fetch', transport)
  })
  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('uses HTTP for bootstrap then subscribes to the actual root without subscribed reread', async () => {
    reply([node(ROOT, null), node(CHILD, ROOT)])
    const { client, sockets } = renderPanel()
    expect(await screen.findByRole('link', { name: /Explorer/ })).toHaveTextContent('turns: 3 · tools: 5')
    expect(transport).toHaveBeenCalledTimes(1)
    expect(transport.mock.calls[0][0]).toBe(`/api/harness/threads/${CHILD}/tree`)
    expect(client.getQueryData(['threads', 'tree', CHILD])).toEqual([node(ROOT, null), node(CHILD, ROOT)])
    const socket = sockets.openLatest()
    await waitFor(() => expect(socket.sentMessages()).toEqual([
      { type: 'subscribe', resource: { kind: 'thread-tree', id: ROOT } },
    ]))
    socket.emitServer({
      type: 'subscribed', resource: { kind: 'thread-tree', id: ROOT }, cursor: '0',
    })
    await act(async () => { await Promise.resolve() })
    expect(transport).toHaveBeenCalledTimes(1)
  })

  it.each([
    ['empty', []],
    ['foreign', [node(ROOT, null)]],
    ['missing time', [node(ROOT, null), { ...node(CHILD, ROOT), updatedAt: undefined }]],
    ['null time', [node(ROOT, null), { ...node(CHILD, ROOT), updatedAt: null }]],
    ['array time', [node(ROOT, null), { ...node(CHILD, ROOT), updatedAt: [2026, 3, 31] }]],
  ])('rejects %s HTTP tree without caching or subscribing', async (_, nodes) => {
    reply(nodes as unknown[])
    const { client, sockets } = renderPanel()
    await screen.findByRole('alert')
    expect(client.getQueryData(['threads', 'tree', CHILD])).toBeUndefined()
    expect(client.getQueryState(['threads', 'tree', CHILD])?.status).toBe('error')
    const socket = sockets.openLatest()
    await act(async () => { await Promise.resolve() })
    expect(socket.sentMessages()).toEqual([])
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
  })

  it('applies authoritative tree snapshots directly without an event-triggered GET', async () => {
    const initial = [node(ROOT, null), node(CHILD, ROOT)]
    reply(initial)
    const { client, sockets } = renderPanel()
    await screen.findByRole('link')
    const socket = sockets.openLatest()
    await waitFor(() => expect(socket.sentMessages()).toHaveLength(1))
    socket.emitServer({
      type: 'subscribed', resource: { kind: 'thread-tree', id: ROOT }, cursor: '0',
    })
    await act(async () => { await Promise.resolve() })
    expect(transport).toHaveBeenCalledTimes(1)
    fireEvent.click(screen.getByRole('button', { name: 'refresh tree' }))
    expect(socket.sentMessages()).toEqual([
      { type: 'subscribe', resource: { kind: 'thread-tree', id: ROOT } },
      { type: 'refresh', resource: { kind: 'thread-tree', id: ROOT } },
    ])
    const callsBeforeSnapshot = transport.mock.calls.length
    const pushed = [node(ROOT, null), { ...node(CHILD, ROOT), name: 'pushed latest' }]

    socket.emitServer({
      type: 'event', resource: { kind: 'thread-tree', id: ROOT }, name: 'snapshot',
      data: { nodes: pushed },
    })

    await waitFor(() => expect(client.getQueryData(['threads', 'tree', CHILD])).toEqual(pushed))
    expect(transport).toHaveBeenCalledTimes(callsBeforeSnapshot)
    expect(screen.getByRole('link')).toHaveTextContent('pushed latest')
  })

  it('keeps the cache and reports a malformed-scope snapshot without an HTTP second writer', async () => {
    const initial = [node(ROOT, null), node(CHILD, ROOT)]
    reply(initial)
    const { client, sockets } = renderPanel()
    await screen.findByRole('link')
    const socket = sockets.openLatest()
    await waitFor(() => expect(socket.sentMessages()).toHaveLength(1))
    const oldScope = [node(OTHER_ROOT, null), node(CHILD, OTHER_ROOT)]

    act(() => socket.emitServer({
      type: 'event',
      resource: { kind: 'thread-tree', id: ROOT },
      name: 'snapshot',
      data: { nodes: oldScope },
    }))

    await screen.findByRole('alert')
    expect(client.getQueryData(['threads', 'tree', CHILD])).toEqual(initial)
    expect(transport).toHaveBeenCalledTimes(1)
    expect(socket.sentMessages()).toEqual([{ type: 'subscribe', resource: { kind: 'thread-tree', id: ROOT } }])
  })

  it('does not auto-retry snapshot failure and lets an explicit refresh recover over WS', async () => {
    const valid = [node(ROOT, null), node(CHILD, ROOT)]
    reply(valid)
    const { client, sockets } = renderPanel()
    await screen.findByRole('link')
    const socket = sockets.openLatest()
    await waitFor(() => expect(socket.sentMessages()).toHaveLength(1))

    socket.emitServer({
      type: 'error',
      resource: { kind: 'thread-tree', id: ROOT },
      code: 'TREE_SNAPSHOT_FAILED',
      message: 'snapshot failed',
    })
    await screen.findByRole('alert')
    expect(client.getQueryData(['threads', 'tree', CHILD])).toEqual(valid)
    expect(transport).toHaveBeenCalledTimes(1)
    expect(socket.sentMessages()).toEqual([
      { type: 'subscribe', resource: { kind: 'thread-tree', id: ROOT } },
    ])

    fireEvent.click(screen.getByRole('button', { name: 'refresh tree' }))
    expect(socket.sentMessages().at(-1)).toEqual({
      type: 'refresh', resource: { kind: 'thread-tree', id: ROOT },
    })
    expect(transport).toHaveBeenCalledTimes(1)

    socket.emitServer({
      type: 'event',
      resource: { kind: 'thread-tree', id: ROOT },
      name: 'snapshot',
      data: { nodes: valid },
    })
    await waitFor(() => expect(screen.queryByRole('alert')).not.toBeInTheDocument())
  })

  it('resubscribes after reconnect and accepts the new scope snapshot without HTTP', async () => {
    const initial = [node(ROOT, null), node(CHILD, ROOT)]
    reply(initial)
    const { client, sockets } = renderPanel()
    await screen.findByRole('link')
    expect(client.getQueryData(['threads', 'tree', CHILD])).toEqual(initial)
    vi.useFakeTimers()
    vi.spyOn(Math, 'random').mockReturnValue(0)
    const firstSocket = sockets.openLatest()
    expect(firstSocket.sentMessages()).toEqual([
      { type: 'subscribe', resource: { kind: 'thread-tree', id: ROOT } },
    ])

    act(() => firstSocket.fail())
    await act(async () => {
      vi.advanceTimersByTime(500)
      await Promise.resolve()
    })
    const reconnected = sockets.openLatest()
    expect(reconnected.sentMessages()).toEqual([
      { type: 'subscribe', resource: { kind: 'thread-tree', id: ROOT } },
    ])
    act(() => reconnected.emitServer({
      type: 'subscribed', resource: { kind: 'thread-tree', id: ROOT }, cursor: '0',
    }))
    const recovered = [node(ROOT, null), { ...node(CHILD, ROOT), name: 'reconnected' }]
    act(() => reconnected.emitServer({
      type: 'event', resource: { kind: 'thread-tree', id: ROOT }, name: 'snapshot',
      data: { nodes: recovered },
    }))

    expect(client.getQueryData(['threads', 'tree', CHILD])).toEqual(recovered)
    expect(transport).toHaveBeenCalledTimes(1)
  })

  it('keeps bootstrap failure visible and retries it with HTTP on explicit refetch', async () => {
    reply([])
    const { client } = renderPanel()
    await screen.findByRole('alert')
    expect(client.getQueryData(['threads', 'tree', CHILD])).toBeUndefined()
    expect(transport).toHaveBeenCalledTimes(1)

    reply([node(ROOT, null), node(CHILD, ROOT)])
    fireEvent.click(screen.getByRole('button', { name: 'refresh tree' }))
    await screen.findByRole('link')
    expect(transport).toHaveBeenCalledTimes(2)
  })

  it('cancels a stale HTTP response when WS becomes live and keeps the pushed tree', async () => {
    const initial = [node(ROOT, null), node(CHILD, ROOT)]
    reply(initial)
    const { client, sockets } = renderPanel()
    await screen.findByRole('link')
    const socket = sockets.openLatest()
    await waitFor(() => expect(socket.sentMessages()).toHaveLength(1))

    let resolveLate!: (response: Response) => void
    transport.mockImplementationOnce(
      () => new Promise<Response>((resolve) => { resolveLate = resolve }),
    )
    void client.invalidateQueries({ queryKey: ['threads', 'tree', CHILD] })
    await waitFor(() => expect(transport).toHaveBeenCalledTimes(2))
    act(() => socket.emitServer({
      type: 'subscribed', resource: { kind: 'thread-tree', id: ROOT }, cursor: '0',
    }))
    await act(async () => {
      await client.invalidateQueries({ queryKey: ['threads', 'tree', CHILD] })
    })
    expect(transport).toHaveBeenCalledTimes(2)

    const pushed = [node(ROOT, null), { ...node(CHILD, ROOT), name: 'WS latest' }]
    socket.emitServer({
      type: 'event', resource: { kind: 'thread-tree', id: ROOT }, name: 'snapshot',
      data: { nodes: pushed },
    })
    await waitFor(() => expect(client.getQueryData(['threads', 'tree', CHILD])).toEqual(pushed))
    await act(async () => {
      resolveLate(new Response(JSON.stringify(initial), { headers: { 'Content-Type': 'application/json' } }))
      await Promise.resolve()
    })

    expect(client.getQueryData(['threads', 'tree', CHILD])).toEqual(pushed)
    expect(transport).toHaveBeenCalledTimes(2)
  })
})
