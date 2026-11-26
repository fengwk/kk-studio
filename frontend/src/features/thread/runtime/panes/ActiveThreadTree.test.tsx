import { useState } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ActiveThreadTree } from '@/features/thread/runtime/panes/ActiveThreadTree'
import { useActiveThreadTree } from '@/features/thread/runtime/queries/useActiveThreadTree'
import { ThreadNavigationContext } from '@/features/thread/runtime/panes/thread-navigation-context'
import { harnessService } from '@/shared/api/harness-service'
import { ApplicationEventProvider } from '@/shared/app-events'
import { FakeWebSocketHarness } from '@/test-support/fake-websocket'
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
  active: boolean,
  name: string,
  status: 'RUNNING' | 'WAITING' | 'EXIT_PENDING' | 'IDLE' = active ? 'RUNNING' : 'IDLE',
  executionControl: 'RUNNABLE' | 'STOPPED' = 'RUNNABLE',
) {
  return {
    threadId,
    kind: parentThreadId == null ? 'USER' as const : 'SUBAGENT' as const,
    parentThreadId,
    name,
    agentName: 'assistant',
    execution: { driver: 'builtin', settings: { modelName: 'MiniMax', variant: 'default' } },
    status,
    executionControl,
    turnCount: 1,
    toolCallCount: 0,
    outcome: null,
    updatedAt: 1774958400,
  }
}

/** 查询由根面板单点持有：测试同样只挂一次 useActiveThreadTree，再交给展示组件。 */
function TreeHarness({ rootId, currentThreadId }: { rootId: string; currentThreadId?: string }) {
  const tree = useActiveThreadTree(rootId)
  return <ActiveThreadTree tree={tree} currentThreadId={currentThreadId} />
}

function renderTree(rootId: string, currentThreadId?: string, onOpenThread = vi.fn()) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const sockets = new FakeWebSocketHarness()
  return {
    queryClient,
    sockets,
    ...render(
      <QueryClientProvider client={queryClient}>
        <ApplicationEventProvider url="ws://test/ws/events" socketFactory={sockets.factory}>
          <ThreadNavigationContext.Provider value={onOpenThread}>
            <TreeHarness rootId={rootId} currentThreadId={currentThreadId} />
          </ThreadNavigationContext.Provider>
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
  it('keeps the initial loading state empty, then retries a failed active tree query', async () => {
    let rejectTree: (reason: Error) => void = () => {}
    vi.mocked(harnessService.getThreadTree).mockReturnValue(new Promise((_, reject) => { rejectTree = reject }))
    const { container } = renderTree(ROOT_ID)
    expect(container.querySelector('.thread-widget-panel')).toBeNull()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()

    await act(async () => { rejectTree(new Error('offline')) })
    expect(await screen.findByRole('alert')).toHaveTextContent('Agent 关系加载失败')
    vi.mocked(harnessService.getThreadTree).mockResolvedValue([
      treeNode(ROOT_ID, null, false, 'root'),
      treeNode(WORKER_ID, ROOT_ID, true, 'worker'),
    ])
    fireEvent.click(screen.getByRole('button', { name: '重试' }))
    expect(await screen.findByRole('button', { name: /worker/ })).toBeInTheDocument()
  })

  it('subscribes to the response root from a bound child and reads back its own cached key', async () => {
    const worker = { ...treeNode(WORKER_ID, ROOT_ID, true, 'main'), agentName: 'Explorer', turnCount: 7, toolCallCount: 12 }
    vi.mocked(harnessService.getThreadTree).mockResolvedValue([
      treeNode(ROOT_ID, null, false, 'root'), worker,
      { ...treeNode(IDLE_ID, WORKER_ID, true, 'review'), agentName: 'Reviewer', status: 'WAITING' },
    ])
    const { sockets, queryClient } = renderTree(WORKER_ID, WORKER_ID)
    const link = await screen.findByRole('button', { name: /Explorer/ })
    expect(link).toHaveTextContent('轮次：7 · 工具：12')
    expect(screen.getByRole('button', { name: /Reviewer/ })).toBeInTheDocument()
    expect(link.closest('li')).toHaveAttribute('aria-current', 'true')
    const socket = sockets.openLatest()
    await waitFor(() => expect(socket.sentMessages()).toEqual([
      { type: 'subscribe', resource: { kind: 'thread-tree', id: ROOT_ID } },
    ]))
    const stoppedWorker = { ...worker, executionControl: 'STOPPED' as const }
    const pushed = [treeNode(ROOT_ID, null, false, 'root'), stoppedWorker]
    act(() => socket.emitServer({
      type: 'event',
      resource: { kind: 'thread-tree', id: ROOT_ID },
      name: 'snapshot',
      data: { nodes: pushed },
    }))
    await waitFor(() => expect(screen.getByRole('button', { name: /Explorer/ })).toHaveTextContent('已停止'))
    expect(harnessService.getThreadTree).toHaveBeenCalledTimes(1)
    expect(harnessService.getThreadTree).toHaveBeenLastCalledWith(
      WORKER_ID,
      { signal: expect.any(AbortSignal) },
    )
    expect(queryClient.getQueryData(['threads', 'tree', WORKER_ID])).toEqual(pushed)
    expect(queryClient.getQueryData(['threads', 'tree', ROOT_ID])).toBeUndefined()
  })

  it('retains active rows on malformed refresh instead of accepting conflicting snapshots', async () => {
    const worker = treeNode(WORKER_ID, ROOT_ID, true, 'worker')
    vi.mocked(harnessService.getThreadTree).mockResolvedValue([treeNode(ROOT_ID, null, false, 'root'), worker])
    const { queryClient } = renderTree(ROOT_ID)
    await screen.findByRole('button', { name: /worker/ })
    vi.mocked(harnessService.getThreadTree).mockResolvedValue([
      treeNode(ROOT_ID, null, false, 'root'), worker, { ...worker, turnCount: 99 },
    ])
    await act(async () => { await queryClient.invalidateQueries({ queryKey: ['threads', 'tree', ROOT_ID] }) })
    expect(await screen.findByRole('alert')).toHaveTextContent('刷新失败')
    expect(screen.getByRole('button', { name: /worker/ })).toHaveTextContent('轮次：1')
  })

  beforeEach(() => {
    vi.clearAllMocks()
    setLocale('zh-CN')
  })

  it('renders only active subagents plus ancestors, with whole-row buttons and no root row', async () => {
    vi.mocked(harnessService.getThreadTree).mockResolvedValue([
      treeNode(ROOT_ID, null, false, 'root'),
      treeNode(WORKER_ID, ROOT_ID, true, 'worker'),
      treeNode(IDLE_ID, ROOT_ID, false, 'idle-child'),
    ])

    renderTree(ROOT_ID)

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /worker/ })).toBeInTheDocument()
    })
    expect(screen.queryByRole('button', { name: 'root' })).not.toBeInTheDocument()
    expect(screen.queryByText('idle-child')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: /worker/ })).toHaveAttribute('type', 'button')
    expect(screen.getByText('1 个活跃')).toBeInTheDocument()
  })

  it('stays hidden with no active descendants and refreshes on the root tree change event', async () => {
    vi.mocked(harnessService.getThreadTree).mockResolvedValue([
      treeNode(ROOT_ID, null, true, 'root'),
      treeNode(IDLE_ID, ROOT_ID, false, 'idle-child'),
    ])

    const { container, sockets, queryClient } = renderTree(ROOT_ID)

    await waitFor(() => {
      expect(harnessService.getThreadTree).toHaveBeenCalledWith(
        ROOT_ID,
        { signal: expect.any(AbortSignal) },
      )
    })
    expect(container.querySelector('.thread-widget-panel')).toBeNull()
    const socket = sockets.openLatest()
    await waitFor(() => expect(socket.sentMessages()).toEqual([
      { type: 'subscribe', resource: { kind: 'thread-tree', id: ROOT_ID } },
    ]))

    // 子代理写入由服务端聚合到真实执行根后推送权威快照；changed 不负责触发 GET。
    const pushed = [
      treeNode(ROOT_ID, null, true, 'root'),
      treeNode(IDLE_ID, ROOT_ID, false, 'idle-child'),
      treeNode(WORKER_ID, ROOT_ID, true, 'worker'),
    ]
    act(() => {
      socket.emitServer({
        type: 'event',
        resource: { kind: 'thread-tree', id: ROOT_ID },
        name: 'snapshot',
        data: { nodes: pushed },
      })
    })

    expect(await screen.findByRole('button', { name: /worker/ })).toBeInTheDocument()
    expect(screen.getByText('1 个活跃')).toBeInTheDocument()
    expect(harnessService.getThreadTree).toHaveBeenCalledTimes(1)
    expect(queryClient.getQueryData(['threads', 'tree', ROOT_ID])).toEqual(pushed)
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
        <ApplicationEventProvider url="ws://test/ws/events" socketFactory={sockets.factory}>
          <ThreadNavigationContext.Provider value={observe}>
            <TreeHarness rootId={ROOT_ID} currentThreadId={WORKER_ID} />
          </ThreadNavigationContext.Provider>
        </ApplicationEventProvider>
      </QueryClientProvider>,
    )

    const button = await screen.findByRole('button', { name: /worker/ })
    expect(button.closest('li')).toHaveAttribute('aria-current', 'true')
    fireEvent.click(button)
    expect(observe).toHaveBeenCalledWith(WORKER_ID)
  })

  it('surfaces refresh failure with stale rows instead of pretending they are current', async () => {
    vi.mocked(harnessService.getThreadTree).mockResolvedValue([
      treeNode(ROOT_ID, null, false, 'root'),
      treeNode(WORKER_ID, ROOT_ID, true, 'worker'),
    ])
    const { queryClient } = renderTree(ROOT_ID)
    await screen.findByRole('button', { name: /worker/ })

    vi.mocked(harnessService.getThreadTree).mockRejectedValue(new Error('boom'))
    await act(async () => {
      await queryClient.invalidateQueries({ queryKey: ['threads', 'tree', ROOT_ID] })
    })

    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent('Agent 关系刷新失败')
    })
    expect(screen.getByRole('button', { name: /worker/ })).toBeInTheDocument()
  })

  it('keeps known active descendants while a refetch is still in flight', async () => {
    // 刷新在途（loading）不能把已知的活跃后代清空成“没有活跃”：Stop 可用性与
    // 活跃判断都不允许把加载态当成空闲。
    vi.mocked(harnessService.getThreadTree).mockResolvedValue([
      treeNode(ROOT_ID, null, false, 'root'),
      treeNode(WORKER_ID, ROOT_ID, true, 'worker'),
    ])
    const { queryClient } = renderTree(ROOT_ID)
    await screen.findByRole('button', { name: /worker/ })

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
    expect(screen.getByRole('button', { name: /worker/ })).toBeInTheDocument()
    expect(screen.getByText('1 个活跃')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(containerTextAbsent()).toBe(true)

    await act(async () => {
      resolveTree?.([treeNode(ROOT_ID, null, false, 'root')])
    })
    await waitFor(() => {
      expect(screen.queryByRole('button', { name: /worker/ })).not.toBeInTheDocument()
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
    await screen.findByRole('button', { name: /worker/ })
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

  it('accepts the server snapshot when a reconnect re-subscribes and acks', async () => {
    vi.mocked(harnessService.getThreadTree).mockResolvedValue([
      treeNode(ROOT_ID, null, true, 'root'),
      treeNode(WORKER_ID, ROOT_ID, true, 'worker'),
    ])
    const { sockets } = renderTree(ROOT_ID)
    await screen.findByRole('button', { name: /worker/ })
    expect(harnessService.getThreadTree).toHaveBeenCalledTimes(1)
    const first = sockets.openLatest()
    expect(first.sentMessages()).toEqual([
      { type: 'subscribe', resource: { kind: 'thread-tree', id: ROOT_ID } },
    ])

    // 断线后重新订阅同一执行根；服务端 ack 后推送权威快照，不触发第二个 HTTP writer。
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
        { type: 'subscribe', resource: { kind: 'thread-tree', id: ROOT_ID } },
      ])

      act(() => {
        reconnected.emitServer({
          type: 'subscribed',
          resource: { kind: 'thread-tree', id: ROOT_ID },
          cursor: '0',
        })
      })
      await flushApplicationEvents()
      expect(harnessService.getThreadTree).toHaveBeenCalledTimes(1)
      const pushed = [
        treeNode(ROOT_ID, null, true, 'root'),
        treeNode(WORKER_ID, ROOT_ID, true, 'reconnected worker'),
      ]
      act(() => reconnected.emitServer({
        type: 'event',
        resource: { kind: 'thread-tree', id: ROOT_ID },
        name: 'snapshot',
        data: { nodes: pushed },
      }))
      await flushApplicationEvents()
      expect(screen.getByRole('button', { name: /reconnected worker/ })).toBeInTheDocument()
      expect(harnessService.getThreadTree).toHaveBeenCalledTimes(1)
    } finally {
      vi.useRealTimers()
    }
  })

  it('requests and accepts a root snapshot over WS on resync', async () => {
    vi.mocked(harnessService.getThreadTree).mockResolvedValue([
      treeNode(ROOT_ID, null, true, 'root'),
      treeNode(WORKER_ID, ROOT_ID, true, 'worker'),
    ])
    const { sockets, queryClient } = renderTree(ROOT_ID)
    await screen.findByRole('button', { name: /worker/ })
    expect(harnessService.getThreadTree).toHaveBeenCalledTimes(1)
    const socket = sockets.openLatest()

    // resync 要求在同一 WS 资源上 refresh；恢复内容经服务端快照推送，不发 HTTP GET。
    act(() => {
      socket.emitServer({ type: 'resync', resource: { kind: 'thread-tree', id: ROOT_ID } })
    })
    await waitFor(() => expect(socket.sentMessages()).toEqual([
      { type: 'subscribe', resource: { kind: 'thread-tree', id: ROOT_ID } },
      { type: 'refresh', resource: { kind: 'thread-tree', id: ROOT_ID } },
    ]))
    const pushed = [
      treeNode(ROOT_ID, null, true, 'root'),
      treeNode(WORKER_ID, ROOT_ID, true, 'resynced worker'),
    ]
    act(() => socket.emitServer({
      type: 'event',
      resource: { kind: 'thread-tree', id: ROOT_ID },
      name: 'snapshot',
      data: { nodes: pushed },
    }))
    expect(await screen.findByRole('button', { name: /resynced worker/ })).toBeInTheDocument()
    expect(harnessService.getThreadTree).toHaveBeenCalledTimes(1)
    expect(queryClient.getQueryData(['threads', 'tree', ROOT_ID])).toEqual(pushed)
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
        <ApplicationEventProvider url="ws://test/ws/events" socketFactory={sockets.factory}>
          <ThreadNavigationContext.Provider value={vi.fn()}>
            <RefcountHarness rootId={ROOT_ID} />
          </ThreadNavigationContext.Provider>
        </ApplicationEventProvider>
      </QueryClientProvider>,
    )

    await waitFor(() => {
      expect(screen.getAllByRole('button', { name: /worker/ })).toHaveLength(2)
    })
    // 同一执行根上的两个面板只有一条 wire 订阅与一次权威回读。
    expect(harnessService.getThreadTree).toHaveBeenCalledTimes(1)
    const socket = sockets.openLatest()
    await flushMicrotasks()
    expect(socket.sentMessages()).toEqual([
      { type: 'subscribe', resource: { kind: 'thread-tree', id: ROOT_ID } },
    ])

    // 释放一个面板：仍有消费者，wire 订阅不拆，也不产生多余回读。
    fireEvent.click(screen.getByRole('button', { name: 'remove-pane' }))
    await flushMicrotasks()
    expect(harnessService.getThreadTree).toHaveBeenCalledTimes(1)
    expect(socket.sentMessages()).toEqual([
      { type: 'subscribe', resource: { kind: 'thread-tree', id: ROOT_ID } },
    ])

    // 末次释放拆掉 wire 订阅；此后的迟到事件不得触发任何回读。
    fireEvent.click(screen.getByRole('button', { name: 'remove-all-panes' }))
    await flushMicrotasks()
    expect(socket.sentMessages()).toEqual([
      { type: 'subscribe', resource: { kind: 'thread-tree', id: ROOT_ID } },
      { type: 'unsubscribe', resource: { kind: 'thread-tree', id: ROOT_ID } },
    ])
    act(() => {
      socket.emitServer({
        type: 'event',
        resource: { kind: 'thread-tree', id: ROOT_ID },
        name: 'snapshot',
        data: { nodes: [] },
      })
    })
    await flushMicrotasks()
    expect(harnessService.getThreadTree).toHaveBeenCalledTimes(1)

    // Provider 卸载即断开唯一连接：之后不再有任何 wire 消费者。
    unmount()
    expect(socket.closed).toBe(true)
    expect(harnessService.getThreadTree).toHaveBeenCalledTimes(1)
  })

  it('keeps async waiting text while showing ordinary running as a labeled icon', async () => {
    const runningNode = treeNode(WORKER_ID, ROOT_ID, true, 'running-worker', 'RUNNING')
    const waitingNode = treeNode(IDLE_ID, ROOT_ID, true, 'waiting-worker', 'WAITING')
    vi.mocked(harnessService.getThreadTree).mockResolvedValue([
      treeNode(ROOT_ID, null, false, 'root'),
      runningNode,
      waitingNode,
    ])

    renderTree(ROOT_ID)

    const runningLink = await screen.findByRole('button', { name: /running-worker/ })
    const waitingLink = await screen.findByRole('button', { name: /waiting-worker/ })

    // 普通运行状态用动态小图标表达，避免重复状态文字。
    const runningStatus = runningLink.querySelector('.thread-tree-status')
    expect(runningStatus).toHaveTextContent('')
    expect(runningStatus).toHaveAttribute('aria-label', '运行中')
    expect(runningStatus?.querySelector('.ui-list-row-running .ui-loading-spinner-icon')).not.toBeNull()
    expect(runningStatus).toHaveClass('is-running')

    // 异步等待用静态可读图标，避免把非操作状态堆成文字。
    const waitingStatus = waitingLink.querySelector('.thread-tree-status')
    expect(waitingStatus).toHaveTextContent('')
    expect(waitingStatus).toHaveAttribute('aria-label', '等待异步结果')
    expect(waitingStatus).toHaveClass('is-waiting')
    expect(waitingStatus?.className).not.toMatch(/pulse|spin|streaming|generating/)
  })

  it('preserves status and displays STOPPED as execution control badge without masking status', async () => {
    const stoppedRunningNode = treeNode(
      WORKER_ID,
      ROOT_ID,
      true,
      'stopped-running',
      'RUNNING',
      'STOPPED',
    )
    vi.mocked(harnessService.getThreadTree).mockResolvedValue([
      treeNode(ROOT_ID, null, false, 'root'),
      stoppedRunningNode,
    ])

    renderTree(ROOT_ID)

    const link = await screen.findByRole('button', { name: /stopped-running/ })
    // 运行态图标仍表达运行中，绝不被 STOPPED 遮盖为“已停止”。
    expect(link.querySelector('.thread-tree-status')).toHaveAttribute('aria-label', '运行中')
    // STOPPED 作为独立 execution control 标签呈现
    expect(link.querySelector('.thread-tree-control.is-stopped')).toHaveTextContent('已停止')
  })

  it('shows exit-pending as a quiet static icon and retains its accessible status', async () => {
    vi.mocked(harnessService.getThreadTree).mockResolvedValue([
      treeNode(ROOT_ID, null, false, 'root'),
      treeNode(WORKER_ID, ROOT_ID, true, 'closing-worker', 'EXIT_PENDING'),
    ])
    renderTree(ROOT_ID)
    const link = await screen.findByRole('button', { name: /closing-worker/ })
    const status = link.querySelector('.thread-tree-status')
    expect(status).toHaveTextContent('')
    expect(status).toHaveAttribute('aria-label', '等待退出确认')
    expect(status?.querySelector('svg')).not.toHaveClass('thread-tree-status-running-icon')
  })

  it('suppresses false COMPLETED outcome on active non-IDLE nodes', async () => {
    const falseCompletedNode = {
      ...treeNode(WORKER_ID, ROOT_ID, true, 'worker', 'RUNNING'),
      outcome: 'COMPLETED' as const, // 不信旧 fixture，非 IDLE 不暴露 COMPLETED
    }
    vi.mocked(harnessService.getThreadTree).mockResolvedValue([
      treeNode(ROOT_ID, null, false, 'root'),
      falseCompletedNode,
    ])

    renderTree(ROOT_ID)

    const link = await screen.findByRole('button', { name: /worker/ })
    expect(link.querySelector('.thread-tree-status')).toHaveAttribute('aria-label', '运行中')
    expect(link).not.toHaveTextContent('已完成')
  })
})
