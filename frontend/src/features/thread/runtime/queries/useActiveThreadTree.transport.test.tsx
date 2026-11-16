import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor } from '@testing-library/react'
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
  return <SubagentTreePanel tree={useActiveThreadTree(CHILD)} currentThreadId={CHILD} />
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
  afterEach(() => vi.unstubAllGlobals())

  it('passes JSON epoch seconds through the real service and subscribes to the actual root', async () => {
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
    await waitFor(() => expect(transport).toHaveBeenCalledTimes(2))
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

  it('does not write a snapshot from an old root scope into the bound cache', async () => {
    const initial = [node(ROOT, null), node(CHILD, ROOT)]
    reply(initial)
    const { client, sockets } = renderPanel()
    await screen.findByRole('link')
    const socket = sockets.openLatest()
    await waitFor(() => expect(socket.sentMessages()).toHaveLength(1))
    let resolveRefresh!: (response: Response) => void
    transport.mockImplementationOnce(
      () => new Promise<Response>((resolve) => { resolveRefresh = resolve }),
    )
    const oldScope = [node(OTHER_ROOT, null), node(CHILD, OTHER_ROOT)]

    act(() => socket.emitServer({
      type: 'event',
      resource: { kind: 'thread-tree', id: ROOT },
      name: 'snapshot',
      data: { nodes: oldScope },
    }))

    await waitFor(() => expect(transport).toHaveBeenCalledTimes(2))
    expect(client.getQueryData(['threads', 'tree', CHILD])).toEqual(initial)
    await act(async () => {
      resolveRefresh(
        new Response(JSON.stringify(initial), { headers: { 'Content-Type': 'application/json' } }),
      )
      await Promise.resolve()
    })
  })

  it('keeps the valid cached tree and root subscription when a refresh returns a foreign tree', async () => {
    const valid = [node(ROOT, null), node(CHILD, ROOT)]
    reply(valid)
    const { client, sockets } = renderPanel()
    await screen.findByRole('link')
    const socket = sockets.openLatest()
    await waitFor(() => expect(socket.sentMessages()).toHaveLength(1))
    reply([node('foreign-root', null)])
    await act(async () => { await client.invalidateQueries({ queryKey: ['threads', 'tree', CHILD] }) })
    await screen.findByRole('alert')
    expect(client.getQueryData(['threads', 'tree', CHILD])).toEqual(valid)
    expect(screen.getByRole('link')).toBeInTheDocument()
    expect(socket.sentMessages()).toEqual([
      { type: 'subscribe', resource: { kind: 'thread-tree', id: ROOT } },
    ])
  })
})
