import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ThreadAgentTreePanel } from '@/features/ai/runtime/ThreadAgentTreePanel'
import { harnessService } from '@/shared/api/harness-service'
import type { HarnessThreadTreeNodeDTO } from '@/shared/api/contracts/ai-runtime'
import { setLocale } from '@/shared/i18n'
import { queryKeys } from '@/shared/lib/query-keys'

vi.mock('@/shared/api/harness-service', () => ({
  harnessService: {
    getThreadTree: vi.fn(),
  },
}))

const ROOT = '00000000-0000-4000-8000-000000000001'
const CHILD_A = '00000000-0000-4000-8000-000000000002'
const CHILD_B = '00000000-0000-4000-8000-000000000003'
const GRAND = '00000000-0000-4000-8000-000000000004'

function node(overrides: Partial<HarnessThreadTreeNodeDTO>): HarnessThreadTreeNodeDTO {
  return {
    threadId: ROOT,
    parentThreadId: null,
    name: 'Main',
    agentName: 'assistant',
    model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'default' },
    status: 'WAITING_CHILDREN',
    processing: true,
    turnCount: 3,
    toolCallCount: 2,
    outcome: null,
    ...overrides,
  }
}

function tree(): HarnessThreadTreeNodeDTO[] {
  return [
    node({ threadId: CHILD_B, parentThreadId: ROOT, name: 'Same', agentName: 'reviewer', status: 'IDLE', processing: false, turnCount: 1, toolCallCount: 0, outcome: 'FAILED', model: { providerName: 'anthropic', modelName: 'Claude', variant: 'fast' } }),
    node({ threadId: GRAND, parentThreadId: CHILD_A, name: 'Grand', agentName: 'coder', status: 'MODEL_RUNNING', processing: true, turnCount: 4, toolCallCount: 5, outcome: null }),
    node({ threadId: CHILD_A, parentThreadId: ROOT, name: 'Same', agentName: 'coder', status: 'TOOL_WAITING_APPROVAL', processing: true, turnCount: 2, toolCallCount: 1, outcome: null }),
    node({}),
  ]
}

function renderPanel(threadId = CHILD_A) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  const view = render(
    <QueryClientProvider client={client}>
      <ThreadAgentTreePanel threadId={threadId} panelId="agent-tree-test" />
    </QueryClientProvider>,
  )
  return { ...view, client }
}

describe('ThreadAgentTreePanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    setLocale('zh-CN')
    vi.mocked(harnessService.getThreadTree).mockResolvedValue(tree())
  })

  it('renders the real root tree with status, counts, model, and exact links', async () => {
    renderPanel()

    expect(harnessService.getThreadTree).toHaveBeenCalledWith(CHILD_A)
    const rows = await screen.findAllByRole('listitem')
    expect(rows.map((row) => row.getAttribute('data-thread-id'))).toEqual([ROOT, CHILD_A, GRAND, CHILD_B])
    expect(rows.map((row) => row.getAttribute('data-depth'))).toEqual(['0', '1', '2', '1'])
    expect(rows[1]).toHaveAttribute('aria-current', 'true')
    expect(rows[0]).toHaveTextContent('主 Agent')
    expect(rows[1]).toHaveTextContent('当前')
    expect(rows[0]).toHaveTextContent('等待子 Thread')
    expect(rows[1]).toHaveTextContent('等待审批')
    expect(rows[2]).toHaveTextContent('模型运行中')
    expect(rows[3]).toHaveTextContent('失败')
    expect(rows[3]).not.toHaveTextContent('已完成')
    expect(rows[1]).toHaveTextContent('2 回合')
    expect(rows[2]).toHaveTextContent('5 次工具调用')
    expect(rows[3]).toHaveTextContent('anthropic/Claude/fast')
    expect(rows[0]).toHaveClass('is-processing')
    expect(rows[3]).not.toHaveClass('is-processing')
    const sameLinks = screen.getAllByRole('link', { name: 'Same' })
    expect(sameLinks).toHaveLength(2)
    expect(sameLinks.map((link) => link.getAttribute('href'))).toEqual([
      `/threads/${CHILD_A}`,
      `/threads/${CHILD_B}`,
    ])
    expect(screen.getByRole('link', { name: 'Main' })).toHaveAttribute('target', '_blank')
  })

  it('keeps the last valid tree when refresh fails and restores it after retry', async () => {
    // 网络错误和非法树都不能覆盖已展示的关系；重试成功后才替换。
    const user = userEvent.setup()
    renderPanel()
    expect(await screen.findByRole('link', { name: 'Main' })).toBeInTheDocument()

    vi.mocked(harnessService.getThreadTree).mockRejectedValueOnce(new Error('tree down'))
    await user.click(screen.getByRole('button', { name: '刷新' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Agent 关系刷新失败')
    expect(screen.getByRole('link', { name: 'Main' })).toHaveAttribute('href', `/threads/${ROOT}`)

    vi.mocked(harnessService.getThreadTree).mockResolvedValueOnce([
      node({ threadId: CHILD_A, parentThreadId: 'missing-parent', name: 'Broken' }),
    ])
    await user.click(screen.getByRole('button', { name: '重试' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Agent 关系刷新失败')
    expect(screen.queryByRole('link', { name: 'Broken' })).not.toBeInTheDocument()
    expect(screen.getAllByRole('listitem')).toHaveLength(4)

    const updated = tree()
    updated[3] = node({ name: 'Main updated', turnCount: 8 })
    vi.mocked(harnessService.getThreadTree).mockResolvedValueOnce(updated)
    await user.click(screen.getByRole('button', { name: '重试' }))
    expect(await screen.findByRole('link', { name: 'Main updated' })).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.getByText('8 回合')).toBeInTheDocument()
  })

  it('shows a load error for the first invalid tree instead of an empty relationship', async () => {
    vi.mocked(harnessService.getThreadTree).mockResolvedValueOnce([
      node({ threadId: CHILD_A, parentThreadId: 'missing-parent', name: 'Broken' }),
    ])
    renderPanel()

    expect(await screen.findByRole('alert')).toHaveTextContent('Agent 关系加载失败')
    expect(screen.queryByText('没有 Agent 关系')).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Broken' })).not.toBeInTheDocument()
  })

  it('does not reuse the previous thread tree after the panel is remounted', async () => {
    const view = renderPanel(CHILD_A)
    expect(await screen.findByRole('link', { name: 'Main' })).toBeInTheDocument()

    view.client.setQueryData(queryKeys.threads.tree(ROOT), tree())
    vi.mocked(harnessService.getThreadTree).mockImplementation(() => new Promise(() => undefined))
    view.rerender(
      <QueryClientProvider client={view.client}>
        <ThreadAgentTreePanel key={CHILD_B} threadId={CHILD_B} panelId="agent-tree-test" />
      </QueryClientProvider>,
    )

    expect(await screen.findByText('正在加载 Agent 关系…')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Main' })).not.toBeInTheDocument()
    await waitFor(() => expect(harnessService.getThreadTree).toHaveBeenCalledWith(CHILD_B))
  })

  it('shows an initial error and retries an empty tree without reusing another thread cache', async () => {
    const user = userEvent.setup()
    vi.mocked(harnessService.getThreadTree).mockRejectedValueOnce(new Error('tree down'))
    const view = renderPanel(CHILD_A)

    expect(await screen.findByRole('alert')).toHaveTextContent('Agent 关系加载失败')
    vi.mocked(harnessService.getThreadTree).mockResolvedValueOnce([])
    await user.click(screen.getByRole('button', { name: '重试' }))
    expect(await screen.findByText('没有 Agent 关系')).toBeInTheDocument()

    view.client.setQueryData(queryKeys.threads.tree(ROOT), tree())
    view.rerender(
      <QueryClientProvider client={view.client}>
        <ThreadAgentTreePanel key={CHILD_B} threadId={CHILD_B} panelId="agent-tree-test" />
      </QueryClientProvider>,
    )
    await waitFor(() => expect(harnessService.getThreadTree).toHaveBeenCalledWith(CHILD_B))
    expect(screen.queryByText('Main')).not.toBeInTheDocument()
  })
})
