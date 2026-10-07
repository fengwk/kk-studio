import { useState } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ActiveThreadTree } from '@/features/ai/runtime/ActiveThreadTree'
import { SubagentTreePanel } from '@/features/ai/runtime/SubagentTreePanel'
import { useActiveThreadTree } from '@/features/ai/runtime/useActiveThreadTree'
import { ThreadNavigationContext } from '@/features/ai/runtime/thread-navigation-context'
import { harnessService } from '@/shared/api/harness-service'
import { ApplicationEventProvider } from '@/shared/app-events'
import { FakeWebSocketHarness } from '@/shared/app-events/__tests__/fake-websocket'
import { setLocale } from '@/shared/i18n'

vi.mock('@/shared/api/harness-service', () => ({
  harnessService: {
    getThreadTree: vi.fn(),
  },
}))

const ROOT_ID = '00000000-0000-4000-8000-000000000001'
const WORKER_ID = '00000000-0000-4000-8000-000000000002'
const IDLE_ID = '00000000-0000-4000-8000-000000000003'

function treeNode(
  threadId: string,
  parentThreadId: string | null,
  processing: boolean,
  name: string,
) {
  return {
    threadId,
    parentThreadId,
    name,
    agentName: 'assistant',
    model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'default' },
    status: processing ? 'MODEL_STREAM' : 'IDLE',
    processing,
    turnCount: 1,
    toolCallCount: 0,
    outcome: null,
    updateTime: '2026-03-31T12:00:00.000Z',
  }
}

/** 查询由根面板单点持有：测试同样只挂一次 useActiveThreadTree，再交给展示组件。 */
function TreeHarness({ rootId, currentThreadId, history = false }: { rootId: string; currentThreadId?: string; history?: boolean }) {
  const tree = useActiveThreadTree(rootId)
  return history
    ? <SubagentTreePanel tree={tree} currentThreadId={currentThreadId} />
    : <ActiveThreadTree tree={tree} currentThreadId={currentThreadId} />
}

function renderTree(rootId: string, currentThreadId?: string, history = false) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const sockets = new FakeWebSocketHarness()
  return {
    queryClient,
    sockets,
    ...render(
      <QueryClientProvider client={queryClient}>
        <ApplicationEventProvider url="ws://test/events/v1" socketFactory={sockets.factory}>
          <MemoryRouter>
            <TreeHarness rootId={rootId} currentThreadId={currentThreadId} history={history} />
          </MemoryRouter>
        </ApplicationEventProvider>
      </QueryClientProvider>,
    ),
  }
}

function TreePane({ rootId }: { rootId: string }) {
  return <ActiveThreadTree tree={useActiveThreadTree(rootId)} />
}

/** 常驻 Provider 的可增删面板床：wire 订阅只随面板增减变化，Provider 断开不是释放路径。 */
function RefcountHarness({ rootId }: { rootId: string }) {
  const [panes, setPanes] = useState(2)
  return (
    <div>
      <button type="button" onClick={() => setPanes(1)}>
        remove-pane
      </button>
      <button type="button" onClick={() => setPanes(0)}>
        remove-all-panes
      </button>
      {panes >= 1 ? <TreePane rootId={rootId} /> : null}
      {panes >= 2 ? <TreePane rootId={rootId} /> : null}
    </div>
  )
}

/** 执行树读取失败/加载中都不许出现“没有活跃”这类断言式空态。 */
function containerTextAbsent(): boolean {
  return !document.body.textContent?.includes('没有活跃')
}

describe('ActiveThreadTree', () => {
  it('does not claim empty history while loading or failed, and retries to a genuine empty tree', async () => {
    let rejectTree: (reason: Error) => void = () => {}
    vi.mocked(harnessService.getThreadTree).mockReturnValue(new Promise((_, reject) => { rejectTree = reject }))
    renderTree(ROOT_ID, undefined, true)
    expect(screen.getByRole('status')).toBeInTheDocument()
    expect(screen.queryByText('暂无 subagent 执行')).not.toBeInTheDocument()
    await act(async () => { rejectTree(new Error('offline')) })
    expect(await screen.findByRole('alert')).toHaveTextContent('加载失败')
    expect(screen.queryByText('暂无 subagent 执行')).not.toBeInTheDocument()
    vi.mocked(harnessService.getThreadTree).mockResolvedValue([treeNode(ROOT_ID, null, false, 'root')])
    fireEvent.click(screen.getByRole('button', { name: '重试' }))
    expect(await screen.findByText('暂无 subagent 执行')).toBeInTheDocument()
  })

  it('subscribes to the response root from a bound child and reads back its own cached key', async () => {
    const worker = { ...treeNode(WORKER_ID, ROOT_ID, true, 'main'), agentName: 'Explorer', turnCount: 7, toolCallCount: 12 }
    vi.mocked(harnessService.getThreadTree).mockResolvedValue([
      treeNode(ROOT_ID, null, false, 'root'), worker,
      { ...treeNode(IDLE_ID, WORKER_ID, false, 'main'), agentName: 'Reviewer', outcome: 'FAILED' },
    ])
    const { sockets, queryClient } = renderTree(WORKER_ID, WORKER_ID, true)
    const link = await screen.findByRole('link', { name: /Explorer/ })
    expect(link).toHaveTextContent('turns: 7 · tools: 12')
    expect(screen.getByRole('link', { name: /Reviewer/ })).toHaveTextContent('失败')
    expect(link.closest('li')).toHaveAttribute('aria-current', 'true')
    const socket = sockets.openLatest()
    await waitFor(() => expect(socket.sentMessages()).toEqual([
      { version: 1, type: 'subscribe', resource: { kind: 'tree', id: ROOT_ID } },
    ]))
    vi.mocked(harnessService.getThreadTree).mockResolvedValue([
      treeNode(ROOT_ID, null, false, 'root'), { ...worker, processing: false, status: 'IDLE', outcome: 'STOPPED' },
    ])
    act(() => socket.emitServer({ type: 'event', resource: { kind: 'tree', id: ROOT_ID }, name: 'changed', data: {} }))
    await waitFor(() => expect(screen.getByRole('link', { name: /Explorer/ })).toHaveTextContent('停止'))
    expect(harnessService.getThreadTree).toHaveBeenLastCalledWith(WORKER_ID)
    expect(queryClient.getQueryData(['threads', 'tree', WORKER_ID])).toBeDefined()
    expect(queryClient.getQueryData(['threads', 'tree', ROOT_ID])).toBeUndefined()
  })

  it('retains history on malformed refresh instead of accepting conflicting snapshots', async () => {
    const worker = treeNode(WORKER_ID, ROOT_ID, false, 'worker')
    vi.mocked(harnessService.getThreadTree).mockResolvedValue([treeNode(ROOT_ID, null, false, 'root'), worker])
    const { queryClient } = renderTree(ROOT_ID, undefined, true)
    await screen.findByRole('link', { name: /worker/ })
    vi.mocked(harnessService.getThreadTree).mockResolvedValue([
      treeNode(ROOT_ID, null, false, 'root'), worker, { ...worker, turnCount: 99 },
    ])
    await act(async () => { await queryClient.invalidateQueries({ queryKey: ['threads', 'tree', ROOT_ID] }) })
    expect(await screen.findByRole('alert')).toHaveTextContent('刷新失败')
    expect(screen.getByRole('link', { name: /worker/ })).toHaveTextContent('turns: 1')
  })

  beforeEach(() => {
    vi.clearAllMocks()
    setLocale('zh-CN')
  })

  it('renders only active subagents plus ancestors, with whole-row links and no root row', async () => {
    vi.mocked(harnessService.getThreadTree).mockResolvedValue([
      treeNode(ROOT_ID, null, false, 'root'),
      treeNode(WORKER_ID, ROOT_ID, true, 'worker'),
      treeNode(IDLE_ID, ROOT_ID, false, 'idle-child'),
    ])

    renderTree(ROOT_ID)

    await waitFor(() => {
      expect(screen.getByRole('link', { name: /worker/ })).toBeInTheDocument()
    })
    expect(screen.queryByRole('link', { name: 'root' })).not.toBeInTheDocument()
    expect(screen.queryByText('idle-child')).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: /worker/ })).toHaveAttribute(
      'href',
      `/threads/${encodeURIComponent(WORKER_ID)}`,
    )
    expect(screen.getByText('1 个活跃')).toBeInTheDocument()
  })

  it('stays hidden with no active descendants and refreshes on the root tree change event', async () => {
    vi.mocked(harnessService.getThreadTree).mockResolvedValue([
      treeNode(ROOT_ID, null, true, 'root'),
      treeNode(IDLE_ID, ROOT_ID, false, 'idle-child'),
    ])

    const { container, sockets } = renderTree(ROOT_ID)

    await waitFor(() => {
      expect(harnessService.getThreadTree).toHaveBeenCalledWith(ROOT_ID)
    })
    expect(container.querySelector('.thread-widget-panel')).toBeNull()
    const socket = sockets.openLatest()
    await waitFor(() => expect(socket.sentMessages()).toEqual([
      { version: 1, type: 'subscribe', resource: { kind: 'tree', id: ROOT_ID } },
    ]))

    // 子代理写入由服务端聚合到真实执行根后推送：changed 只提示回读该根，不需要固定轮询。
    vi.mocked(harnessService.getThreadTree).mockResolvedValue([
      treeNode(ROOT_ID, null, true, 'root'),
      treeNode(IDLE_ID, ROOT_ID, false, 'idle-child'),
      treeNode(WORKER_ID, ROOT_ID, true, 'worker'),
    ])
    act(() => {
      socket.emitServer({
        type: 'event',
        resource: { kind: 'tree', id: ROOT_ID },
        name: 'changed',
        data: {},
      })
    })

    expect(await screen.findByRole('link', { name: /worker/ })).toBeInTheDocument()
    expect(screen.getByText('1 个活跃')).toBeInTheDocument()
    expect(harnessService.getThreadTree).toHaveBeenCalledTimes(2)
  })

  it('intercepts ordinary row clicks through the pane navigation provider', async () => {
    vi.mocked(harnessService.getThreadTree).mockResolvedValue([
      treeNode(ROOT_ID, null, false, 'root'),
      treeNode(WORKER_ID, ROOT_ID, true, 'worker'),
    ])
    const observe = vi.fn()
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const sockets = new FakeWebSocketHarness()
    render(
      <QueryClientProvider client={queryClient}>
        <ApplicationEventProvider url="ws://test/events/v1" socketFactory={sockets.factory}>
          <MemoryRouter>
            <ThreadNavigationContext.Provider value={observe}>
              <TreeHarness rootId={ROOT_ID} currentThreadId={WORKER_ID} />
            </ThreadNavigationContext.Provider>
          </MemoryRouter>
        </ApplicationEventProvider>
      </QueryClientProvider>,
    )

    const link = await screen.findByRole('link', { name: /worker/ })
    expect(link.closest('li')).toHaveAttribute('aria-current', 'true')
    fireEvent.click(link, { button: 0 })
    expect(observe).toHaveBeenCalledWith(WORKER_ID)
  })

  it('surfaces refresh failure with stale rows instead of pretending they are current', async () => {
    vi.mocked(harnessService.getThreadTree).mockResolvedValue([
      treeNode(ROOT_ID, null, false, 'root'),
      treeNode(WORKER_ID, ROOT_ID, true, 'worker'),
    ])
    const { queryClient } = renderTree(ROOT_ID)
    await screen.findByRole('link', { name: /worker/ })

    vi.mocked(harnessService.getThreadTree).mockRejectedValue(new Error('boom'))
    await act(async () => {
      await queryClient.invalidateQueries({ queryKey: ['threads', 'tree', ROOT_ID] })
    })

    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent('Agent 关系刷新失败')
    })
    expect(screen.getByRole('link', { name: /worker/ })).toBeInTheDocument()
  })

  it('keeps known active descendants while a refetch is still in flight', async () => {
    // 刷新在途（loading）不能把已知的活跃后代清空成“没有活跃”：Stop 可用性与
    // 活跃判断都不允许把加载态当成空闲。
    vi.mocked(harnessService.getThreadTree).mockResolvedValue([
      treeNode(ROOT_ID, null, false, 'root'),
      treeNode(WORKER_ID, ROOT_ID, true, 'worker'),
    ])
    const { queryClient } = renderTree(ROOT_ID)
    await screen.findByRole('link', { name: /worker/ })

    let resolveTree: ((value: ReturnType<typeof treeNode>[]) => void) | null = null
    vi.mocked(harnessService.getThreadTree).mockReturnValue(
      new Promise((resolve) => {
        resolveTree = resolve
      }),
    )
    await act(async () => {
      void queryClient.invalidateQueries({ queryKey: ['threads', 'tree', ROOT_ID] })
    })

    // 请求未返回：仍是已知的活跃后代，没有错误提示，也没有“没有活跃”的假象。
    expect(screen.getByRole('link', { name: /worker/ })).toBeInTheDocument()
    expect(screen.getByText('1 个活跃')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(containerTextAbsent()).toBe(true)

    await act(async () => {
      resolveTree?.([treeNode(ROOT_ID, null, false, 'root')])
    })
    await waitFor(() => {
      expect(screen.queryByRole('link', { name: /worker/ })).not.toBeInTheDocument()
    })
  })

  it('shows an explicit error and retries when the first tree query fails', async () => {
    vi.mocked(harnessService.getThreadTree).mockRejectedValueOnce(new Error('boom'))
    renderTree(ROOT_ID)

    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent('Agent 关系加载失败')
    })

    vi.mocked(harnessService.getThreadTree).mockResolvedValue([
      treeNode(ROOT_ID, null, false, 'root'),
      treeNode(WORKER_ID, ROOT_ID, true, 'worker'),
    ])
    fireEvent.click(screen.getByRole('button', { name: '重试' }))
    await screen.findByRole('link', { name: /worker/ })
  })

  /** fake timers 下 waitFor/findBy 的超时依赖真实定时器会挂起，这里显式清空零延迟定时器与微任务。 */
  async function flushApplicationEvents(): Promise<void> {
    for (let round = 0; round < 5; round += 1) {
      await act(async () => {
        await vi.advanceTimersByTimeAsync(0)
      })
    }
  }

  /** wire 上的 subscribe/unsubscribe 在 open/释放后的微任务里发送：断言前必须先清空微任务。 */
  async function flushMicrotasks(): Promise<void> {
    for (let round = 0; round < 5; round += 1) {
      await act(async () => {
        await Promise.resolve()
      })
    }
  }

  it('reads back the root tree when a reconnect re-subscribes and acks', async () => {
    vi.mocked(harnessService.getThreadTree).mockResolvedValue([
      treeNode(ROOT_ID, null, true, 'root'),
      treeNode(WORKER_ID, ROOT_ID, true, 'worker'),
    ])
    const { sockets } = renderTree(ROOT_ID)
    await screen.findByRole('link', { name: /worker/ })
    expect(harnessService.getThreadTree).toHaveBeenCalledTimes(1)
    const first = sockets.openLatest()
    expect(first.sentMessages()).toEqual([
      { version: 1, type: 'subscribe', resource: { kind: 'tree', id: ROOT_ID } },
    ])

    // 断线后管理器按退避重连：重连必须重新订阅同一执行根，并在服务端 ack 后回读，
    // 否则面板会永远停在断线前的执行树状态。
    vi.useFakeTimers()
    try {
      first.closeWith()
      await act(async () => {
        await vi.advanceTimersByTimeAsync(1000)
      })
      expect(sockets.sockets).toHaveLength(2)
      // openLatest() 已经完成 open：重连后管理器必须重新订阅同一执行根。
      const reconnected = sockets.openLatest()
      await flushApplicationEvents()
      expect(reconnected.sentMessages()).toEqual([
        { version: 1, type: 'subscribe', resource: { kind: 'tree', id: ROOT_ID } },
      ])

      act(() => {
        reconnected.emitServer({
          type: 'subscribed',
          resource: { kind: 'tree', id: ROOT_ID },
          cursor: '0',
        })
      })
      await flushApplicationEvents()
      expect(harnessService.getThreadTree).toHaveBeenCalledTimes(2)
    } finally {
      vi.useRealTimers()
    }
  })

  it('reads back the root tree on resync', async () => {
    vi.mocked(harnessService.getThreadTree).mockResolvedValue([
      treeNode(ROOT_ID, null, true, 'root'),
      treeNode(WORKER_ID, ROOT_ID, true, 'worker'),
    ])
    const { sockets } = renderTree(ROOT_ID)
    await screen.findByRole('link', { name: /worker/ })
    expect(harnessService.getThreadTree).toHaveBeenCalledTimes(1)
    const socket = sockets.openLatest()

    // resync 表示服务端要求整体回读：此时不能装作本地状态仍然权威。
    act(() => {
      socket.emitServer({ type: 'resync', resource: { kind: 'tree', id: ROOT_ID } })
    })
    await waitFor(() => {
      expect(harnessService.getThreadTree).toHaveBeenCalledTimes(2)
    })
  })

  it('refcounts panes on one root and ignores events after the last release', async () => {
    vi.mocked(harnessService.getThreadTree).mockResolvedValue([
      treeNode(ROOT_ID, null, true, 'root'),
      treeNode(WORKER_ID, ROOT_ID, true, 'worker'),
    ])
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const sockets = new FakeWebSocketHarness()
    const { unmount } = render(
      <QueryClientProvider client={queryClient}>
        <ApplicationEventProvider url="ws://test/events/v1" socketFactory={sockets.factory}>
          <MemoryRouter>
            <RefcountHarness rootId={ROOT_ID} />
          </MemoryRouter>
        </ApplicationEventProvider>
      </QueryClientProvider>,
    )

    await waitFor(() => {
      expect(screen.getAllByRole('link', { name: /worker/ })).toHaveLength(2)
    })
    // 同一执行根上的两个面板只有一条 wire 订阅与一次权威回读。
    expect(harnessService.getThreadTree).toHaveBeenCalledTimes(1)
    const socket = sockets.openLatest()
    await flushMicrotasks()
    expect(socket.sentMessages()).toEqual([
      { version: 1, type: 'subscribe', resource: { kind: 'tree', id: ROOT_ID } },
    ])

    // 释放一个面板：仍有消费者，wire 订阅不拆，也不产生多余回读。
    fireEvent.click(screen.getByRole('button', { name: 'remove-pane' }))
    await flushMicrotasks()
    expect(harnessService.getThreadTree).toHaveBeenCalledTimes(1)
    expect(socket.sentMessages()).toEqual([
      { version: 1, type: 'subscribe', resource: { kind: 'tree', id: ROOT_ID } },
    ])

    // 末次释放拆掉 wire 订阅；此后的迟到事件不得触发任何回读。
    fireEvent.click(screen.getByRole('button', { name: 'remove-all-panes' }))
    await flushMicrotasks()
    expect(socket.sentMessages()).toEqual([
      { version: 1, type: 'subscribe', resource: { kind: 'tree', id: ROOT_ID } },
      { version: 1, type: 'unsubscribe', resource: { kind: 'tree', id: ROOT_ID } },
    ])
    act(() => {
      socket.emitServer({
        type: 'event',
        resource: { kind: 'tree', id: ROOT_ID },
        name: 'changed',
        data: {},
      })
    })
    await flushMicrotasks()
    expect(harnessService.getThreadTree).toHaveBeenCalledTimes(1)

    // Provider 卸载即断开唯一连接：之后不再有任何 wire 消费者。
    unmount()
    expect(socket.closed).toBe(true)
    expect(harnessService.getThreadTree).toHaveBeenCalledTimes(1)
  })
})
