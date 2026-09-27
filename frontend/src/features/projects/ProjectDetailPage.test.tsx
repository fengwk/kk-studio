import { describe, expect, it, vi } from 'vitest'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ProjectDetailPage } from './ProjectDetailPage'
import type { ProjectsApi } from './projects-api'
import type { IssueDetailDTO, ProjectSnapshotDTO } from './types'
import { invalidateProjectQueries } from './projects-invalidation'

vi.mock('@/shared/app-events', () => ({
  useApplicationEvents: () => ({
    subscribe: vi.fn().mockReturnValue(() => {}),
    connect: vi.fn(),
    disconnect: vi.fn(),
  }),
  ApplicationEventProvider: ({ children }: { children?: React.ReactNode }) => <>{children}</>,
}))

function renderPage(ui: React.ReactElement, client?: QueryClient) {
  const queryClient = client ?? new QueryClient({
    defaultOptions: {
      queries: {
        retry: false,
        refetchOnWindowFocus: false,
      },
    },
  })
  const rendered = render(
    <QueryClientProvider client={queryClient}>
      {ui}
    </QueryClientProvider>,
  )
  return { ...rendered, queryClient }
}

describe('ProjectDetailPage', () => {
  const projectId = 'a0000000-0000-0000-0000-000000000001'

  const mockSnapshot: ProjectSnapshotDTO = {
    project: {
      id: projectId,
      title: 'Awesome Platform',
      description: 'Building next gen studio',
      workflow: {
        states: [
          { state: 'INIT', name: '待开始', next: ['IN_PROGRESS', 'DONE'] },
          { state: 'IN_PROGRESS', name: '进行中', next: ['DONE'] },
          { state: 'BLOCKED', name: '业务阻塞' },
          { state: 'DONE', name: '完成' },
        ],
      },
      yoloEnabled: true,
      nextIssueNumber: '5',
      version: '2',
      archivedAt: null,
      createdAt: '2026-09-14T00:00:00Z',
      updatedAt: '2026-09-14T01:00:00Z',
    },
    issues: [
      {
        issue: {
          id: 'b0000000-0000-0000-0000-000000000001',
          projectId,
          number: '1',
          title: 'Design DB schema',
          description: 'Postgres tables',
          state: 'INIT',
          paused: false,
          version: '1',
          archivedAt: null,
          createdAt: '2026-09-14T00:00:00Z',
          updatedAt: '2026-09-14T00:00:00Z',
        },
        run: null,
      },
      {
        issue: {
          id: 'b0000000-0000-0000-0000-000000000002',
          projectId,
          number: '2',
          title: 'Implement REST API',
          description: 'Controller endpoints',
          state: 'IN_PROGRESS',
          paused: false,
          version: '2',
          archivedAt: null,
          createdAt: '2026-09-14T00:00:00Z',
          updatedAt: '2026-09-14T01:00:00Z',
        },
        run: {
          id: 'd0000000-0000-0000-0000-000000000001',
          issueId: 'b0000000-0000-0000-0000-000000000002',
          ordinal: '1',
          state: 'IN_PROGRESS',
          agentName: 'backend-dev',
          status: 'RUNNING',
          startEntryId: 'entry-1',
          endEntryId: null,
          remainingExecutionMs: '10000',
          createdAt: '2026-09-14T00:30:00Z',
          updatedAt: '2026-09-14T00:35:00Z',
        },
      },
    ],
  }

  const mockIssueDetail: IssueDetailDTO = {
    issue: mockSnapshot.issues[0].issue,
    activities: [],
    stageBudgets: [],
    runs: [],
    agentThreads: [],
    evidences: [],
  }

  const createMockApi = (overrides: Partial<ProjectsApi> = {}): ProjectsApi => ({
    listProjects: vi.fn(),
    createProject: vi.fn(),
    getProject: vi.fn().mockResolvedValue(mockSnapshot.project),
    updateProject: vi.fn().mockResolvedValue(mockSnapshot.project),
    updateWorkflow: vi.fn().mockResolvedValue(mockSnapshot.project),
    deleteProject: vi.fn(),
    archiveProject: vi.fn(),
    unarchiveProject: vi.fn(),
    getProjectSnapshot: vi.fn().mockResolvedValue(mockSnapshot),
    createIssue: vi.fn(),
    getIssue: vi.fn().mockResolvedValue(mockIssueDetail),
    updateIssue: vi.fn(),
    transitionIssue: vi.fn().mockResolvedValue(mockSnapshot.issues[0].issue),
    blockIssue: vi.fn().mockResolvedValue(mockSnapshot.issues[0].issue),
    recoverIssue: vi.fn().mockResolvedValue(mockSnapshot.issues[0].issue),
    reopenIssue: vi.fn().mockResolvedValue(mockSnapshot.issues[0].issue),
    resolveUnknownIssue: vi.fn().mockResolvedValue(mockSnapshot.issues[0].issue),
    pauseIssue: vi.fn().mockResolvedValue(mockSnapshot.issues[0].issue),
    stopIssue: vi.fn().mockResolvedValue(mockSnapshot.issues[0].issue),
    resetStageBudget: vi.fn().mockResolvedValue(mockSnapshot.issues[0].issue),
    appendIssueActivity: vi.fn(),
    addIssueEvidence: vi.fn(),
    deleteIssue: vi.fn().mockResolvedValue(undefined),
    archiveIssue: vi.fn().mockResolvedValue(mockSnapshot.issues[0].issue),
    unarchiveIssue: vi.fn().mockResolvedValue(mockSnapshot.issues[0].issue),
    ...overrides,
  })

  it('renders project snapshot with dynamic state columns and issues', async () => {
    // 测试意图：验证进入项目详情页后正确拉取快照，并按照 workflow states 渲染看板列
    const api = createMockApi()
    renderPage(<ProjectDetailPage projectId={projectId} onBack={vi.fn()} api={api} />)

    expect(await screen.findByText('Awesome Platform')).toBeInTheDocument()
    // 列名展示
    expect(screen.getByText('待开始')).toBeInTheDocument()
    expect(screen.getByText('进行中')).toBeInTheDocument()
    expect(screen.getByText('业务阻塞')).toBeInTheDocument()
    expect(screen.getByText('完成')).toBeInTheDocument()

    // Issue 卡片展示
    expect(screen.getByText('Design DB schema')).toBeInTheDocument()
    expect(screen.getByText('Implement REST API')).toBeInTheDocument()
  })

  it('filters issues by search query in IssueBoard', async () => {
    // 测试意图：验证看板搜索栏输入关键字能够即时过滤卡片
    const api = createMockApi()
    renderPage(<ProjectDetailPage projectId={projectId} onBack={vi.fn()} api={api} />)

    await screen.findByText('Design DB schema')
    const searchInput = screen.getByLabelText('搜索 Issue')
    fireEvent.change(searchInput, { target: { value: 'schema' } })

    expect(screen.getByText('Design DB schema')).toBeInTheDocument()
    expect(screen.queryByText('Implement REST API')).not.toBeInTheDocument()
  })

  it('triggers transitionIssue when clicking quick transition button', async () => {
    // 测试意图：验证点击卡片上的合法状态转移按钮时，向 transitionIssue 发送目标状态与 frozen requestKey
    const api = createMockApi()
    renderPage(<ProjectDetailPage projectId={projectId} onBack={vi.fn()} api={api} />)

    await screen.findByText('Design DB schema')
    // INIT 状态允许转移到 IN_PROGRESS 或 DONE
    const transitionBtn = screen.getByRole('button', { name: '→ IN_PROGRESS' })
    fireEvent.click(transitionBtn)

    await waitFor(() => {
      expect(api.transitionIssue).toHaveBeenCalledWith(
        'b0000000-0000-0000-0000-000000000001',
        expect.objectContaining({
          expectedVersion: '1',
          toState: 'IN_PROGRESS',
          requestKey: expect.any(String),
        }),
      )
    })
  })

  it('opens issue detail modal on card click', async () => {
    // 测试意图：验证点击卡片标题打开 Issue 详情弹窗
    const api = createMockApi()
    renderPage(<ProjectDetailPage projectId={projectId} onBack={vi.fn()} api={api} />)

    await screen.findByText('Design DB schema')
    fireEvent.click(screen.getByText('Design DB schema'))

    expect(await screen.findByLabelText('Issue #1 详情')).toBeInTheDocument()
  })

  it('handles reload on refresh button click and on invalidation broadcast', async () => {
    // 测试意图：验证手动点击刷新按钮和接收 SSE invalidation 触发重新获取快照
    const api = createMockApi()
    const { queryClient } = renderPage(<ProjectDetailPage projectId={projectId} onBack={vi.fn()} api={api} />)

    await screen.findByText('Awesome Platform')
    const refreshBtn = screen.getByLabelText('刷新项目 Snapshot')
    fireEvent.click(refreshBtn)

    await waitFor(() => {
      expect(api.getProjectSnapshot).toHaveBeenCalledTimes(2)
    })

    await act(async () => {
      await invalidateProjectQueries(queryClient)
    })
    await waitFor(() => {
      expect(api.getProjectSnapshot).toHaveBeenCalledTimes(3)
    })
  })
})
