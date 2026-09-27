import { describe, expect, it, vi } from 'vitest'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router'
import { ProjectDetailPage } from './ProjectDetailPage'
import type { ProjectsApi } from './projects-api'
import type { IssueDetailDTO, ProjectSnapshotDTO } from './types'
import { invalidateProjectQueries } from './projects-invalidation'
import { queryKeys } from '@/shared/lib/query-keys'

vi.mock('@/shared/app-events', () => ({
  useApplicationEvents: () => ({
    subscribe: vi.fn().mockReturnValue(() => {}),
    connect: vi.fn(),
    disconnect: vi.fn(),
  }),
  ApplicationEventProvider: ({ children }: { children?: React.ReactNode }) => <>{children}</>,
}))

vi.mock('@/features/ai/runtime/AgentPane', () => ({
  AgentPane: ({
    owner,
    capabilities,
    onSubmitInstruction,
    onStop,
  }: {
    owner: unknown
    capabilities?: {
      allowSwitchAgent?: boolean
      allowBranching?: boolean
      allowGenericChat?: boolean
    }
    onSubmitInstruction?: (text: string) => Promise<void> | void
    onStop?: () => Promise<void> | void
  }) => (
    <div data-testid="controlled-agent-pane" data-owner={JSON.stringify(owner)}>
      <span data-testid="capabilities-switch-agent">{String(capabilities?.allowSwitchAgent)}</span>
      <span data-testid="capabilities-branching">{String(capabilities?.allowBranching)}</span>
      <span data-testid="capabilities-chat">{String(capabilities?.allowGenericChat)}</span>
      <button data-testid="pane-submit-instruction" onClick={() => void Promise.resolve(onSubmitInstruction?.('Please investigate test')).catch(() => {})}>
        Send Instruction
      </button>
      <button data-testid="pane-submit-instruction-alt" onClick={() => void Promise.resolve(onSubmitInstruction?.('Different instruction content')).catch(() => {})}>
        Send Alt Instruction
      </button>
      <button data-testid="pane-stop-action" onClick={() => void onStop?.()}>
        Stop Run
      </button>
    </div>
  ),
}))

function renderPage(ui: React.ReactElement, client?: QueryClient, initialEntries: string[] = ['/']) {
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
      <MemoryRouter initialEntries={initialEntries}>
        {ui}
      </MemoryRouter>
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
    agentThreads: [
      {
        issueId: mockSnapshot.issues[0].issue.id,
        agentName: 'architect',
        threadId: 'th-valid-101',
      },
    ],
    nextActivityCursor: null,
    currentRun: null,
    latestRun: null,
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

  it('mounts controlled AgentPane dock when issue and thread query parameters are valid and forwards instruction and stop actions', async () => {
    // 测试意图：验证携带合法 issue 与 thread 参数时成功挂载受控 AgentPane，输入转化为 INSTRUCTION 类型的 Issue 活动，停止操作触发 stopIssue
    const api = createMockApi({
      appendIssueActivity: vi.fn().mockResolvedValue({
        id: 'act-1',
        issueId: mockSnapshot.issues[0].issue.id,
        actorType: 'HUMAN',
        actorId: 'user-1',
        kind: 'INSTRUCTION',
        body: 'Please investigate test',
        createdAt: '2026-09-27T00:00:00Z',
      }),
      stopIssue: vi.fn().mockResolvedValue(mockSnapshot.issues[0].issue),
    })

    renderPage(
      <ProjectDetailPage projectId={projectId} onBack={vi.fn()} api={api} />,
      undefined,
      [`/projects/${projectId}?issue=${mockSnapshot.issues[0].issue.id}&thread=th-valid-101`],
    )

    // 等待受控 AgentPane 成功挂载
    const pane = await screen.findByTestId('controlled-agent-pane')
    expect(pane).toBeInTheDocument()

    // 验证受控门禁配置（不可切换 Agent、不可分叉、不可通用 Chat）
    expect(screen.getByTestId('capabilities-switch-agent')).toHaveTextContent('false')
    expect(screen.getByTestId('capabilities-branching')).toHaveTextContent('false')
    expect(screen.getByTestId('capabilities-chat')).toHaveTextContent('false')

    // 触发发送指令活动
    fireEvent.click(screen.getByTestId('pane-submit-instruction'))
    await waitFor(() => {
      expect(api.appendIssueActivity).toHaveBeenCalledWith(
        mockSnapshot.issues[0].issue.id,
        expect.objectContaining({
          kind: 'INSTRUCTION',
          body: 'Please investigate test',
          expectedVersion: mockSnapshot.issues[0].issue.version,
        }),
      )
    })

    // 触发受控停止
    fireEvent.click(screen.getByTestId('pane-stop-action'))
    await waitFor(() => {
      expect(api.stopIssue).toHaveBeenCalledWith(
        mockSnapshot.issues[0].issue.id,
        expect.objectContaining({
          expectedVersion: mockSnapshot.issues[0].issue.version,
          detail: '用户在 Agent 视图中终止执行',
        }),
      )
    })
  })

  it('strictly blocks and displays rejection banner when query thread does not belong to issue', async () => {
    // 测试意图：验证当 query 中的 thread 不属于该 Issue 的 agentThreads 或 runs 时，严格阻断并展示拒绝告警横幅，绝不挂载 AgentPane
    const api = createMockApi()

    renderPage(
      <ProjectDetailPage projectId={projectId} onBack={vi.fn()} api={api} />,
      undefined,
      [`/projects/${projectId}?issue=${mockSnapshot.issues[0].issue.id}&thread=th-illegal-999`],
    )

    expect(
      await screen.findByText('目标 Thread 不属于该 Issue 绑定的 Agent 线程或 Run 记录，已拒绝接入'),
    ).toBeInTheDocument()
    expect(screen.queryByTestId('controlled-agent-pane')).not.toBeInTheDocument()
  })

  it('closes controlled AgentPane dock when clicking close button', async () => {
    // 测试意图：验证点击受控 Dock 头部关闭按钮后，清除 thread 路由参数并卸载 Agent 面板
    const api = createMockApi()

    renderPage(
      <ProjectDetailPage projectId={projectId} onBack={vi.fn()} api={api} />,
      undefined,
      [`/projects/${projectId}?issue=${mockSnapshot.issues[0].issue.id}&thread=th-valid-101`],
    )

    expect(await screen.findByTestId('controlled-agent-pane')).toBeInTheDocument()

    const closeBtn = screen.getByLabelText('关闭 Agent 视图')
    fireEvent.click(closeBtn)

    await waitFor(() => {
      expect(screen.queryByTestId('controlled-agent-pane')).not.toBeInTheDocument()
    })
  })

  it('freezes attempt payload and reuses requestKey on network retry, but generates new requestKey for edited body', async () => {
    // 测试意图：验证指令活动在网络失败重试相同 payload 时沿用相同 requestKey，一旦编辑修改内容则视为新 attempt 生成不同 requestKey
    let callCount = 0
    const appendMock = vi.fn().mockImplementation(() => {
      callCount++
      if (callCount === 1) {
        return Promise.reject(new Error('Network offline'))
      }
      return Promise.resolve({
        id: `act-${callCount}`,
        issueId: mockSnapshot.issues[0].issue.id,
        actorType: 'HUMAN',
        actorId: 'user-1',
        kind: 'INSTRUCTION',
        body: 'Please investigate test',
        createdAt: '2026-09-27T00:00:00Z',
      })
    })

    const api = createMockApi({
      appendIssueActivity: appendMock,
    })

    const { queryClient } = renderPage(
      <ProjectDetailPage projectId={projectId} onBack={vi.fn()} api={api} />,
      undefined,
      [`/projects/${projectId}?issue=${mockSnapshot.issues[0].issue.id}&thread=th-valid-101`],
    )

    await screen.findByTestId('controlled-agent-pane')

    // 第一次提交：失败
    fireEvent.click(screen.getByTestId('pane-submit-instruction'))
    await waitFor(() => expect(appendMock).toHaveBeenCalledTimes(1))
    const firstRequestKey = appendMock.mock.calls[0][1].requestKey

    // 服务端已提交但响应丢失；失效事件先带来新版本，重试仍必须携带原始完整请求。
    const detailKey = queryKeys.projects.issue(projectId, mockSnapshot.issues[0].issue.id)
    await act(async () => {
      queryClient.setQueryData<IssueDetailDTO>(detailKey, (detail) => detail && ({
        ...detail,
        issue: { ...detail.issue, version: '2' },
      }))
    })

    // 第二次提交（相同内容重试）：必须沿用 firstRequestKey
    fireEvent.click(screen.getByTestId('pane-submit-instruction'))
    await waitFor(() => expect(appendMock).toHaveBeenCalledTimes(2))
    const retryRequestKey = appendMock.mock.calls[1][1].requestKey
    expect(retryRequestKey).toBe(firstRequestKey)
    expect(appendMock.mock.calls[1]).toEqual(appendMock.mock.calls[0])

    // 第三次提交（编辑为不同内容）：必须生成不同的全新 requestKey
    fireEvent.click(screen.getByTestId('pane-submit-instruction-alt'))
    await waitFor(() => expect(appendMock).toHaveBeenCalledTimes(3))
    const editedRequestKey = appendMock.mock.calls[2][1].requestKey
    expect(editedRequestKey).not.toBe(firstRequestKey)
  })
})
