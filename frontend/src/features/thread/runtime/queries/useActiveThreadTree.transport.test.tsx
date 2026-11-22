import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { HarnessThreadTreeNodeDTO } from '@/shared/api/generated/contracts'
import { ApplicationEventProvider } from '@/shared/app-events'
import { useResourceSync } from '@/shared/app-events/useResourceSync'
import { FakeWebSocketHarness } from '@/test-support/fake-websocket'
import { SubagentTreePanel } from '../panes/SubagentTreePanel'
import { useActiveThreadTree } from './useActiveThreadTree'

const transport = vi.fn()
/** 同一 socket 上另一个资源订阅收到的提示次数，用来证明共享连接仍健康。 */
let projectsChanged = 0

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

/** 同一物理 socket 上的另一个资源订阅：证明执行树恢复提示不拖垮共享连接。 */
function ProjectsHint() {
  useResourceSync({ kind: 'projects' }, {
    sync: () => {},
    syncOnSubscribed: false,
    onEvent: () => {
      projectsChanged += 1
    },
  })
  return null
}
/** 与 RootAgentPane 一致：草稿或非执行根目标把 null 交给执行树 hook，面板整体不渲染。 */
function BoundTreePanel({ threadId }: { threadId: string | null }) {
  const tree = useActiveThreadTree(threadId)
  return threadId == null ? null : <SubagentTreePanel tree={tree} currentThreadId={threadId} />
}
/** 同一个 QueryClient 与 socket 上的重复渲染外壳，用于在测试中切换 bound 目标。 */
function eventHost(client: QueryClient, sockets: FakeWebSocketHarness) {
  return (children: ReactNode) => (
    <QueryClientProvider client={client}>
      <ApplicationEventProvider url="ws://test/ws/events" socketFactory={sockets.factory}>
        <MemoryRouter>{children}</MemoryRouter>
      </ApplicationEventProvider>
    </QueryClientProvider>
  )
}
function renderPanel({ withProjectsHint = false }: { withProjectsHint?: boolean } = {}) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const sockets = new FakeWebSocketHarness()
  render(eventHost(client, sockets)(
    <>
      <Panel />
      {withProjectsHint ? <ProjectsHint /> : null}
    </>,
  ))
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
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(await screen.findByRole('link', { name: /Explorer/ })).toHaveTextContent('轮次：3 · 工具：5')
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

  it('rebootstraps over HTTP when a Thread is re-entered after its cached tree was collected', async () => {
    reply([node(ROOT, null), node(CHILD, ROOT)])
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const sockets = new FakeWebSocketHarness()
    const host = eventHost(client, sockets)
    const { rerender } = render(host(<BoundTreePanel threadId={CHILD} />))
    await screen.findByRole('link', { name: /Explorer/ })
    const socket = sockets.openLatest()
    await waitFor(() => expect(socket.sentMessages()).toHaveLength(1))
    socket.emitServer({ type: 'subscribed', resource: { kind: 'thread-tree', id: ROOT }, cursor: '0' })
    await act(async () => { await Promise.resolve() })
    expect(transport).toHaveBeenCalledTimes(1)

    // 离开执行根（草稿目标），并让 TanStack Query 回收该 key：模拟长时间停留在别处。
    rerender(host(<BoundTreePanel threadId={null} />))
    client.removeQueries({ queryKey: ['threads', 'tree', CHILD] })
    await act(async () => { await Promise.resolve() })

    // 回到同一个 Thread 必须重新走一次权威 HTTP bootstrap，而不是沿用切换前的 live 标记。
    rerender(host(<BoundTreePanel threadId={CHILD} />))
    await waitFor(() => expect(transport).toHaveBeenCalledTimes(2))
    expect(transport.mock.calls[1][0]).toBe(`/api/harness/threads/${CHILD}/tree`)
    expect(await screen.findByRole('link', { name: /Explorer/ })).toHaveTextContent('轮次：3 · 工具：5')
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

  it.each(['TREE_SNAPSHOT_FAILED', 'RESOURCE_NOT_FOUND'])('reports %s without auto-retry and lets an explicit refresh recover over WS', async (code) => {
    const valid = [node(ROOT, null), node(CHILD, ROOT)]
    reply(valid)
    const { client, sockets } = renderPanel()
    await screen.findByRole('link')
    const socket = sockets.openLatest()
    await waitFor(() => expect(socket.sentMessages()).toHaveLength(1))

    socket.emitServer({
      type: 'error',
      resource: { kind: 'thread-tree', id: ROOT },
      code,
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
    fireEvent.click(screen.getByRole('button', { name: 'refresh tree' }))
    expect(screen.getByRole('alert')).toBeInTheDocument()
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

  it('reads the oversized tree over HTTP once and keeps accepting later live WS snapshots', async () => {
    const initial = [node(ROOT, null), node(CHILD, ROOT)]
    reply(initial)
    const { client, sockets } = renderPanel()
    await screen.findByRole('link')
    const socket = sockets.openLatest()
    await waitFor(() => expect(socket.sentMessages()).toHaveLength(1))
    expect(transport).toHaveBeenCalledTimes(1)

    // 完整树超出单帧预算：执行树的 snapshot-ref 不带 version。
    const oversized = [node(ROOT, null), { ...node(CHILD, ROOT), name: 'oversized' }]
    reply(oversized)
    act(() => socket.emitServer({ type: 'snapshot-ref', resource: { kind: 'thread-tree', id: ROOT } }))

    await waitFor(() => expect(transport).toHaveBeenCalledTimes(2))
    await waitFor(() => expect(client.getQueryData(['threads', 'tree', CHILD])).toEqual(oversized))
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    // 只把这次恢复提示当成一次 HTTP 回读：不重订阅、不发送 refresh、也不轮询。
    expect(socket.sentMessages()).toEqual([
      { type: 'subscribe', resource: { kind: 'thread-tree', id: ROOT } },
    ])

    // 同一 socket 上后续健康的 WS 快照照常生效，且不再追加 HTTP 读取。
    const live = [node(ROOT, null), { ...node(CHILD, ROOT), name: 'live after overflow' }]
    act(() => socket.emitServer({
      type: 'event', resource: { kind: 'thread-tree', id: ROOT }, name: 'snapshot',
      data: { nodes: live },
    }))
    await waitFor(() => expect(client.getQueryData(['threads', 'tree', CHILD])).toEqual(live))
    expect(transport).toHaveBeenCalledTimes(2)

    // 稳定在同一版快照上：没有新增提示就没有新增读取。
    act(() => socket.emitServer({
      type: 'event', resource: { kind: 'thread-tree', id: ROOT }, name: 'snapshot',
      data: { nodes: live },
    }))
    await act(async () => { await Promise.resolve() })
    expect(transport).toHaveBeenCalledTimes(2)
  })

  it('coalesces repeated overflow notices into at most one trailing read', async () => {
    const initial = [node(ROOT, null), node(CHILD, ROOT)]
    reply(initial)
    const { client, sockets } = renderPanel()
    await screen.findByRole('link')
    const socket = sockets.openLatest()
    await waitFor(() => expect(socket.sentMessages()).toHaveLength(1))

    // 首个回读保持挂起，期间的重复提示只能合并，不得并发发请求。
    let resolveFirst!: (response: Response) => void
    transport.mockImplementationOnce(
      () => new Promise<Response>((resolve) => { resolveFirst = resolve }),
    )
    const notice = { type: 'snapshot-ref', resource: { kind: 'thread-tree', id: ROOT } } as const
    act(() => socket.emitServer(notice))
    await waitFor(() => expect(transport).toHaveBeenCalledTimes(2))
    act(() => socket.emitServer(notice))
    act(() => socket.emitServer(notice))
    await act(async () => { await Promise.resolve() })
    expect(transport).toHaveBeenCalledTimes(2)

    // 收尾：首读返回后只补一次尾随读取，总数是 3 而不是随提示数增长。
    const oversized = [node(ROOT, null), { ...node(CHILD, ROOT), name: 'trailing' }]
    reply(oversized)
    await act(async () => {
      resolveFirst(new Response(JSON.stringify(initial), {
        headers: { 'Content-Type': 'application/json' },
      }))
      await Promise.resolve()
    })
    await waitFor(() => expect(transport).toHaveBeenCalledTimes(3))
    await waitFor(() => expect(client.getQueryData(['threads', 'tree', CHILD])).toEqual(oversized))
    await act(async () => { await Promise.resolve() })
    expect(transport).toHaveBeenCalledTimes(3)
    expect(socket.sentMessages()).toEqual([
      { type: 'subscribe', resource: { kind: 'thread-tree', id: ROOT } },
    ])
  })

  it('fences an in-flight overflow read so a newer live tree is not overwritten', async () => {
    const initial = [node(ROOT, null), node(CHILD, ROOT)]
    reply(initial)
    const { client, sockets } = renderPanel()
    await screen.findByRole('link')
    const socket = sockets.openLatest()
    await waitFor(() => expect(socket.sentMessages()).toHaveLength(1))

    let resolveRead!: (response: Response) => void
    transport.mockImplementationOnce(
      () => new Promise<Response>((resolve) => { resolveRead = resolve }),
    )
    act(() => socket.emitServer({ type: 'snapshot-ref', resource: { kind: 'thread-tree', id: ROOT } }))
    await waitFor(() => expect(transport).toHaveBeenCalledTimes(2))

    // 更快的合法 WS 树先到达：它必须立刻生效，并让迟到的 HTTP 结果失效。
    const live = [node(ROOT, null), { ...node(CHILD, ROOT), name: 'live wins' }]
    act(() => socket.emitServer({
      type: 'event', resource: { kind: 'thread-tree', id: ROOT }, name: 'snapshot',
      data: { nodes: live },
    }))
    await waitFor(() => expect(client.getQueryData(['threads', 'tree', CHILD])).toEqual(live))

    const staleRead = [node(ROOT, null), { ...node(CHILD, ROOT), name: 'stale read' }]
    await act(async () => {
      resolveRead(new Response(JSON.stringify(staleRead), {
        headers: { 'Content-Type': 'application/json' },
      }))
      await Promise.resolve()
    })

    expect(client.getQueryData(['threads', 'tree', CHILD])).toEqual(live)
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(transport).toHaveBeenCalledTimes(2)
    expect(socket.sentMessages()).toEqual([
      { type: 'subscribe', resource: { kind: 'thread-tree', id: ROOT } },
    ])
  })

  it('fences the old overflow read at subscribe ACK before an immediate new ref', async () => {
    reply([node(ROOT, null), node(CHILD, ROOT)])
    const { client, sockets } = renderPanel()
    await screen.findByRole('link')
    const socket = sockets.openLatest()
    const resource = { kind: 'thread-tree', id: ROOT } as const
    const notice = { type: 'snapshot-ref', resource } as const
    let resolveOld!: (response: Response) => void
    transport.mockImplementationOnce(() => new Promise((resolve) => { resolveOld = resolve }))
    act(() => socket.emitServer(notice))
    await waitFor(() => expect(transport).toHaveBeenCalledTimes(2))

    const current = [node(ROOT, null), { ...node(CHILD, ROOT), name: 'new subscription' }]
    reply(current)
    act(() => {
      socket.emitServer({ type: 'subscribed', resource, cursor: '0' })
      socket.emitServer(notice)
    })
    await waitFor(() => expect(client.getQueryData(['threads', 'tree', CHILD])).toEqual(current))
    await act(async () => resolveOld(new Response('late failure', { status: 500 })))
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(transport).toHaveBeenCalledTimes(3)
  })

  it('reports a failed overflow read and recovers on a later explicit refresh', async () => {
    const initial = [node(ROOT, null), node(CHILD, ROOT)]
    reply(initial)
    const { client, sockets } = renderPanel()
    await screen.findByRole('link')
    const socket = sockets.openLatest()
    await waitFor(() => expect(socket.sentMessages()).toHaveLength(1))

    transport.mockImplementationOnce(async () => new Response('boom', {
      status: 503,
      headers: { 'Content-Type': 'text/plain' },
    }))
    act(() => socket.emitServer({ type: 'snapshot-ref', resource: { kind: 'thread-tree', id: ROOT } }))

    // 失败保持旧缓存可见并显示刷新失败；不自动重试、不重订阅。
    await screen.findByRole('alert')
    expect(client.getQueryData(['threads', 'tree', CHILD])).toEqual(initial)
    expect(transport).toHaveBeenCalledTimes(2)
    expect(socket.sentMessages()).toEqual([
      { type: 'subscribe', resource: { kind: 'thread-tree', id: ROOT } },
    ])

    // 用户显式刷新仍走既定 WS refresh 路径，不新增 HTTP 写缓存方。
    fireEvent.click(screen.getByRole('button', { name: 'refresh tree' }))
    expect(socket.sentMessages().at(-1)).toEqual({
      type: 'refresh', resource: { kind: 'thread-tree', id: ROOT },
    })
    expect(transport).toHaveBeenCalledTimes(2)

    // 后续合法 WS 快照恢复显示并清掉错误。
    const recovered = [node(ROOT, null), { ...node(CHILD, ROOT), name: 'recovered' }]
    act(() => socket.emitServer({
      type: 'event', resource: { kind: 'thread-tree', id: ROOT }, name: 'snapshot',
      data: { nodes: recovered },
    }))
    await waitFor(() => expect(screen.queryByRole('alert')).not.toBeInTheDocument())
    expect(client.getQueryData(['threads', 'tree', CHILD])).toEqual(recovered)
  })

  it('keeps a shared socket healthy: other resource hints still arrive after an oversized tree', async () => {
    projectsChanged = 0
    const initial = [node(ROOT, null), node(CHILD, ROOT)]
    reply(initial)
    const { client, sockets } = renderPanel({ withProjectsHint: true })
    await screen.findByRole('link')
    const socket = sockets.openLatest()
    await waitFor(() => expect(socket.sentMessages()).toHaveLength(2))
    expect(socket.sentMessages()).toEqual(expect.arrayContaining([
      { type: 'subscribe', resource: { kind: 'thread-tree', id: ROOT } },
      { type: 'subscribe', resource: { kind: 'projects' } },
    ]))

    const oversized = [node(ROOT, null), { ...node(CHILD, ROOT), name: 'oversized shared' }]
    reply(oversized)
    act(() => socket.emitServer({ type: 'snapshot-ref', resource: { kind: 'thread-tree', id: ROOT } }))
    await waitFor(() => expect(client.getQueryData(['threads', 'tree', CHILD])).toEqual(oversized))

    // 溢出提示只影响该资源：物理连接与另一个订阅仍然工作。
    act(() => socket.emitServer({
      type: 'event', resource: { kind: 'projects' }, name: 'changed', data: { projectId: ROOT },
    }))
    expect(projectsChanged).toBe(1)
    const live = [node(ROOT, null), { ...node(CHILD, ROOT), name: 'live shared' }]
    act(() => socket.emitServer({
      type: 'event', resource: { kind: 'thread-tree', id: ROOT }, name: 'snapshot',
      data: { nodes: live },
    }))
    await waitFor(() => expect(client.getQueryData(['threads', 'tree', CHILD])).toEqual(live))
    expect(transport).toHaveBeenCalledTimes(2)
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })
})
