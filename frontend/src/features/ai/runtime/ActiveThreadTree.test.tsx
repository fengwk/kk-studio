import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ActiveThreadTree } from '@/features/ai/runtime/ActiveThreadTree'
import { useActiveThreadTree } from '@/features/ai/runtime/useActiveThreadTree'
import { ThreadNavigationContext } from '@/features/ai/runtime/ThreadLink'
import { harnessService } from '@/shared/api/harness-service'
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
  }
}

/** 查询由根面板单点持有：测试同样只挂一次 useActiveThreadTree，再交给展示组件。 */
function TreeHarness({ rootId, currentThreadId }: { rootId: string; currentThreadId?: string }) {
  const tree = useActiveThreadTree(rootId)
  return <ActiveThreadTree tree={tree} currentThreadId={currentThreadId} />
}

function renderTree(rootId: string, currentThreadId?: string) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return {
    queryClient,
    ...render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter>
          <TreeHarness rootId={rootId} currentThreadId={currentThreadId} />
        </MemoryRouter>
      </QueryClientProvider>,
    ),
  }
}

describe('ActiveThreadTree', () => {
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

  it('stays hidden with no active descendants but keeps polling the execution root', async () => {
    vi.mocked(harnessService.getThreadTree).mockResolvedValue([
      treeNode(ROOT_ID, null, true, 'root'),
      treeNode(IDLE_ID, ROOT_ID, false, 'idle-child'),
    ])

    const { container } = renderTree(ROOT_ID)

    await waitFor(() => {
      expect(harnessService.getThreadTree).toHaveBeenCalledWith(ROOT_ID)
    })
    expect(container.querySelector('.thread-widget-panel')).toBeNull()
  })

  it('intercepts ordinary row clicks through the pane navigation provider', async () => {
    vi.mocked(harnessService.getThreadTree).mockResolvedValue([
      treeNode(ROOT_ID, null, false, 'root'),
      treeNode(WORKER_ID, ROOT_ID, true, 'worker'),
    ])
    const observe = vi.fn()
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter>
          <ThreadNavigationContext.Provider value={observe}>
            <TreeHarness rootId={ROOT_ID} currentThreadId={WORKER_ID} />
          </ThreadNavigationContext.Provider>
        </MemoryRouter>
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
})
