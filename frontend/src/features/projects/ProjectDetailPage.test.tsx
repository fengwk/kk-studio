import { useEffect } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, useLocation, useNavigate } from 'react-router'
import { ProjectDetailPage } from './ProjectDetailPage'
import type { ProjectsApi } from './projects-api'
import type { IssueDetailDTO, ProjectSnapshotDTO } from './types'
import { invalidateProjectQueries } from './projects-invalidation'
import {
  clearPendingAction,
  loadPendingAction,
  storePendingAction,
  type PendingIssueAction,
} from './pending-action-sidecar'

vi.mock('@/shared/app-events', () => ({
  useApplicationEvents: () => ({
    subscribe: vi.fn().mockReturnValue(() => {}),
    connect: vi.fn(),
    disconnect: vi.fn(),
  }),
  ApplicationEventProvider: ({ children }: { children?: React.ReactNode }) => <>{children}</>,
}))

let capturedPaneProps: Record<string, unknown> | undefined

vi.mock('@/features/ai/runtime/AgentPane', () => ({
  AgentPane: (props: Record<string, unknown>) => {
    capturedPaneProps = props
    return (
      <div
        data-testid="agent-pane"
        data-owner={props.owner ? JSON.stringify(props.owner) : 'none'}
        data-has-instruction={String(props.onSubmitInstruction != null)}
        data-has-stop={String(props.onStop != null)}
      />
    )
  },
}))

interface NavController {
  navigate: ReturnType<typeof useNavigate>
  location: ReturnType<typeof useLocation>
}

let latestNav: NavController | undefined

function NavigationProbe() {
  const navigate = useNavigate()
  const location = useLocation()
  useEffect(() => {
    latestNav = { navigate, location }
  }, [navigate, location])
  return null
}

function renderPage(ui: React.ReactElement, client?: QueryClient, initialEntries: string[] = ['/']) {
  latestNav = undefined
  capturedPaneProps = undefined
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
        <NavigationProbe />
        {ui}
      </MemoryRouter>
    </QueryClientProvider>,
  )
  return { ...rendered, queryClient, getNav: () => latestNav! }
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
        currentOrLatestRun: null,
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
        currentOrLatestRun: {
          id: 'd0000000-0000-0000-0000-000000000001',
          issueId: 'b0000000-0000-0000-0000-000000000002',
          ordinal: '1',
          state: 'IN_PROGRESS',
          agentName: 'backend-dev',
          status: 'RUNNING',
          startedAt: '2026-09-14T00:30:00Z',
          endedAt: null,
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
    resolveUnknown: vi.fn().mockResolvedValue(mockSnapshot.issues[0].issue),
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
    const refreshBtn = screen.getByLabelText('刷新项目数据')
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

  it('mounts an owner-free bound-Thread AgentPane dock with container-only capabilities', async () => {
    // 测试意图：Issue 只是组织容器；合法 issue+thread 参数下挂载的 AgentPane 不伪造 owner、
    // 不接管发送与停止，只保留禁用创建 Session/分叉的容器能力，已有 Thread 的输入、Goal、预览与 Stop 全走通用交互。
    const api = createMockApi()

    renderPage(
      <ProjectDetailPage projectId={projectId} onBack={vi.fn()} api={api} />,
      undefined,
      [`/projects/${projectId}?issue=${mockSnapshot.issues[0].issue.id}&thread=th-valid-101`],
    )

    const pane = await screen.findByTestId('agent-pane')
    expect(pane).toHaveAttribute('data-owner', 'none')
    expect(pane).toHaveAttribute('data-has-instruction', 'false')
    expect(pane).toHaveAttribute('data-has-stop', 'false')

    await waitFor(() => expect(capturedPaneProps).toBeDefined())
    expect(capturedPaneProps?.paneId).toBe('project-issue-th-valid-101')
    expect(capturedPaneProps?.initialTarget).toEqual({ kind: 'BOUND_THREAD', threadId: 'th-valid-101' })
    expect(capturedPaneProps?.owner).toBeUndefined()
    expect(capturedPaneProps?.onSubmitInstruction).toBeUndefined()
    expect(capturedPaneProps?.onStop).toBeUndefined()
    // 容器能力只保留「不创建 Session / 不分叉」，不再有 Agent 切换与通用 Chat 门禁
    expect(capturedPaneProps?.capabilities).toEqual({ allowNewSession: false, allowBranching: false })

    // 宿主不再把输入劫持成 Issue 活动，也不再把 Stop 劫持成 stopIssue
    expect(api.appendIssueActivity).not.toHaveBeenCalled()
    expect(api.stopIssue).not.toHaveBeenCalled()
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
    expect(screen.queryByTestId('agent-pane')).not.toBeInTheDocument()
  })

  it('closes the AgentPane dock when clicking close button', async () => {
    // 测试意图：验证点击 Dock 头部关闭按钮后，清除 thread 路由参数并卸载 Agent 面板
    const api = createMockApi()

    renderPage(
      <ProjectDetailPage projectId={projectId} onBack={vi.fn()} api={api} />,
      undefined,
      [`/projects/${projectId}?issue=${mockSnapshot.issues[0].issue.id}&thread=th-valid-101`],
    )

    expect(await screen.findByTestId('agent-pane')).toBeInTheDocument()

    const closeBtn = screen.getByLabelText('关闭 Agent 视图')
    fireEvent.click(closeBtn)

    await waitFor(() => {
      expect(screen.queryByTestId('agent-pane')).not.toBeInTheDocument()
    })
  })

  it('prevents rapid duplicate clicks from dispatching concurrent requests for same issue (inflight protection)', async () => {
    // 测试意图：验证看板执行流转时，inflightIssuesRef 阻断快速连点，仅允许首个请求在途执行，避免并发冲撞
    let resolveTransition: () => void = () => {}
    const transitionMock = vi.fn().mockImplementation(() => {
      return new Promise<void>((resolve) => {
        resolveTransition = resolve
      })
    })

    const api = createMockApi({
      transitionIssue: transitionMock,
    })

    renderPage(<ProjectDetailPage projectId={projectId} onBack={vi.fn()} api={api} />)

    // 找到看板上 INIT 状态卡片的流转按钮（流转到 IN_PROGRESS）
    const transitionButtons = await screen.findAllByTitle('流转到 IN_PROGRESS')
    expect(transitionButtons.length).toBeGreaterThanOrEqual(1)
    const btn = transitionButtons[0]

    // 快速双击连点
    fireEvent.click(btn)
    fireEvent.click(btn)

    // transitionIssue 仅被调用一次，第二次点击被直接阻断
    expect(transitionMock).toHaveBeenCalledTimes(1)

    // 请求完成后释放 inflight 锁
    resolveTransition()
    await waitFor(() => {
      expect(transitionMock).toHaveBeenCalledTimes(1)
    })
  })

  it('blocks board action and displays error banner when storage throws QuotaExceededError on store', async () => {
    // 测试意图：验证看板写操作在侧车持久化抛异常时 fail-closed，拦截 API 调用（0 次调用），并在顶部呈现受控错误
    const transitionMock = vi.fn()
    const api = createMockApi({
      transitionIssue: transitionMock,
    })

    const setItemSpy = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('QuotaExceededError')
    })

    renderPage(<ProjectDetailPage projectId={projectId} onBack={vi.fn()} api={api} />)

    const transitionButtons = await screen.findAllByTitle('流转到 IN_PROGRESS')
    expect(transitionButtons.length).toBeGreaterThanOrEqual(1)
    fireEvent.click(transitionButtons[0])

    // API 0 调用
    expect(transitionMock).not.toHaveBeenCalled()

    // 页面呈现受控错误横幅
    expect(await screen.findByText(/无法保存操作记录，未发送请求/i)).toBeInTheDocument()

    setItemSpy.mockRestore()
  })

  it('keeps a board transition in the sidecar after an unknown network failure and replays the frozen request on remount', async () => {
    // 测试意图：看板动作与旧受控入口共用同一侧车执行器；网络未知失败必须保留完全相同的 requestKey/expectedVersion 供刷新后重试
    const targetIssue = mockSnapshot.issues[0].issue
    clearPendingAction(targetIssue.id)

    let callCount = 0
    const transitionMock = vi.fn().mockImplementation(() => {
      callCount++
      if (callCount === 1) {
        return Promise.reject(new Error('Network connection timeout'))
      }
      return Promise.resolve(targetIssue)
    })
    const api = createMockApi({ transitionIssue: transitionMock })

    const clickFirstTransition = async () => {
      const buttons = await screen.findAllByTitle('流转到 IN_PROGRESS')
      fireEvent.click(buttons[0])
    }

    const { unmount } = renderPage(<ProjectDetailPage projectId={projectId} onBack={vi.fn()} api={api} />)
    await clickFirstTransition()

    await waitFor(() => expect(transitionMock).toHaveBeenCalledTimes(1))
    const firstArgs = transitionMock.mock.calls[0]
    expect(firstArgs[0]).toBe(targetIssue.id)
    const firstPayload = firstArgs[1] as { requestKey: string; expectedVersion: string }

    // 侧车中应当持久化保留了该 VALID 动作
    const loaded = loadPendingAction(targetIssue.id)
    expect(loaded.type).toBe('VALID')
    if (loaded.type === 'VALID') {
      expect(loaded.action.kind).toBe('TRANSITION')
      expect(loaded.action.requestKey).toBe(firstPayload.requestKey)
      expect(loaded.action.expectedVersion).toBe(firstPayload.expectedVersion)
    }

    unmount()

    // 刷新 / 重新挂载后重试：必须复用冻结的 requestKey 与 expectedVersion
    renderPage(<ProjectDetailPage projectId={projectId} onBack={vi.fn()} api={api} />)
    await clickFirstTransition()

    await waitFor(() => expect(transitionMock).toHaveBeenCalledTimes(2))
    expect(transitionMock.mock.calls[1]).toEqual(transitionMock.mock.calls[0])
    await waitFor(() => expect(loadPendingAction(targetIssue.id)).toEqual({ type: 'NONE' }))

    clearPendingAction(targetIssue.id)
  })

  it('rejects a board action while another pending action exists and keeps the existing sidecar record', async () => {
    // 测试意图：同一 Issue 只允许一个未确认写操作；存在其他未决动作时拒绝新动作、0 API 调用且绝不覆盖侧车记录
    const targetIssue = mockSnapshot.issues[0].issue
    clearPendingAction(targetIssue.id)

    const existingAction: PendingIssueAction = {
      issueId: targetIssue.id,
      kind: 'RECOVER',
      requestKey: '11111111-2222-3333-4444-555555555555',
      expectedVersion: targetIssue.version,
      payload: {},
      createdAt: new Date().toISOString(),
      isUnknown: true,
    }
    storePendingAction(targetIssue.id, existingAction)

    const transitionMock = vi.fn()
    const api = createMockApi({ transitionIssue: transitionMock })

    renderPage(<ProjectDetailPage projectId={projectId} onBack={vi.fn()} api={api} />)

    const buttons = await screen.findAllByTitle('流转到 IN_PROGRESS')
    fireEvent.click(buttons[0])

    expect(transitionMock).not.toHaveBeenCalled()
    expect(await screen.findByText(/该 Issue 存在未确认结果的写操作，暂不能继续/i)).toBeInTheDocument()

    const loaded = loadPendingAction(targetIssue.id)
    expect(loaded.type).toBe('VALID')
    if (loaded.type === 'VALID') {
      expect(loaded.action.kind).toBe('RECOVER')
      expect(loaded.action.requestKey).toBe(existingAction.requestKey)
    }

    clearPendingAction(targetIssue.id)
  })

  it('navigates with MemoryRouter: Back removes issue parameter and closes detail modal without state residue', async () => {
    // 测试意图：验证单一 URL 事实源；点击卡片打开 Issue 详情后，浏览器真实 Back 使 URL 移除 issue 参数，弹窗彻底关闭而不是残留；Forward 重新打开
    const api = createMockApi()
    const { getNav } = renderPage(
      <ProjectDetailPage projectId={projectId} onBack={vi.fn()} api={api} />,
      undefined,
      [`/projects/${projectId}`],
    )

    await screen.findByText('Design DB schema')
    expect(screen.queryByLabelText('Issue #1 详情')).not.toBeInTheDocument()

    // 1. 点击卡片，打开详情弹窗
    fireEvent.click(screen.getByText('Design DB schema'))
    expect(await screen.findByLabelText('Issue #1 详情')).toBeInTheDocument()
    expect(getNav().location.search).toContain('issue=b0000000-0000-0000-0000-000000000001')

    // 2. 真实浏览器后退：URL 变为无 issue 参数，弹窗必须彻底关闭
    act(() => {
      getNav().navigate(-1)
    })
    await waitFor(() => {
      expect(screen.queryByLabelText('Issue #1 详情')).not.toBeInTheDocument()
      expect(getNav().location.search).not.toContain('issue=')
    })

    // 3. 真实浏览器前进：URL 恢复 issue 参数，弹窗重新打开
    act(() => {
      getNav().navigate(1)
    })
    expect(await screen.findByLabelText('Issue #1 详情')).toBeInTheDocument()
    expect(getNav().location.search).toContain('issue=b0000000-0000-0000-0000-000000000001')
  })

  it('navigates with MemoryRouter in dock mode: Back closes manual modal and Forward does not resurrect it', async () => {
    // 测试意图：验证 Dock 场景下点击“Issue 详情”打开 manual modal 后，浏览器真实 Back 导航关闭弹窗；Forward 重新前进时 dock 渲染但 manual modal 不复活
    const api = createMockApi()
    const targetIssueId = mockSnapshot.issues[0].issue.id
    const { getNav } = renderPage(
      <ProjectDetailPage projectId={projectId} onBack={vi.fn()} api={api} />,
      undefined,
      [`/projects/${projectId}`, `/projects/${projectId}?issue=${targetIssueId}&thread=th-valid-101`],
    )

    // 初始进入包含合法 thread，dock 挂载，弹窗默认未打开
    await screen.findByTestId('agent-pane')
    expect(screen.queryByLabelText('Issue #1 详情')).not.toBeInTheDocument()

    // 点击 Dock 头部按钮手动打开 Issue 详情
    fireEvent.click(screen.getByRole('button', { name: '查看完整 Issue 详情' }))
    expect(await screen.findByLabelText('Issue #1 详情')).toBeInTheDocument()

    // 真实浏览器后退：退回看板无参数页
    act(() => {
      getNav().navigate(-1)
    })
    await waitFor(() => {
      expect(screen.queryByTestId('agent-pane')).not.toBeInTheDocument()
      expect(screen.queryByLabelText('Issue #1 详情')).not.toBeInTheDocument()
    })

    // 真实浏览器前进：回到 Dock 页，Dock 存在但旧 manual modal 绝不复活
    act(() => {
      getNav().navigate(1)
    })
    await screen.findByTestId('agent-pane')
    expect(screen.queryByLabelText('Issue #1 详情')).not.toBeInTheDocument()
  })

  it('closes dock: removes thread and issue to return to board without implicit modal and retains other query filters', async () => {
    // 测试意图：验证关闭 Dock 默认清除 issue 与 thread 返回看板，避免隐式弹出详情弹窗，同时完整保留其他 query filters（如 filter=active）
    const api = createMockApi()
    const targetIssueId = mockSnapshot.issues[0].issue.id
    const { getNav } = renderPage(
      <ProjectDetailPage projectId={projectId} onBack={vi.fn()} api={api} />,
      undefined,
      [`/projects/${projectId}?filter=active&issue=${targetIssueId}&thread=th-valid-101`],
    )

    await screen.findByTestId('agent-pane')
    expect(screen.queryByLabelText('Issue #1 详情')).not.toBeInTheDocument()

    // 点击 Dock 头部关闭按钮
    fireEvent.click(screen.getByRole('button', { name: '关闭 Agent 视图' }))

    await waitFor(() => {
      // Dock 卸载
      expect(screen.queryByTestId('agent-pane')).not.toBeInTheDocument()
      // 绝对不隐式弹出 IssueDetailModal
      expect(screen.queryByLabelText('Issue #1 详情')).not.toBeInTheDocument()
      // URL 中 issue 和 thread 被清除，但 filter=active 得到完整保留
      const search = getNav().location.search
      expect(search).toContain('filter=active')
      expect(search).not.toContain('issue=')
      expect(search).not.toContain('thread=')
    })
  })

  it('opens thread from IssueDetailModal: updates URL with thread parameter and mounts dock', async () => {
    // 测试意图：验证从 Issue 详情中点击“打开 Agent 线程”时，handleOpenThread 将 thread 写入 URL 并展示受控 Dock
    const api = createMockApi()
    const targetIssue = mockSnapshot.issues[0].issue
    const { getNav } = renderPage(
      <ProjectDetailPage projectId={projectId} onBack={vi.fn()} api={api} />,
      undefined,
      [`/projects/${projectId}?issue=${targetIssue.id}`],
    )

    // 等待弹窗打开
    expect(await screen.findByLabelText('Issue #1 详情')).toBeInTheDocument()

    // 切换到 Agent 线程 Tab
    fireEvent.click(screen.getByRole('button', { name: /Agent 线程/ }))
    const openThreadBtn = await screen.findByTitle('打开此 Agent 线程视图')
    fireEvent.click(openThreadBtn)

    // 验证 URL 更新为包含 issue 和 thread，且挂载 AgentPane
    await waitFor(() => {
      expect(getNav().location.search).toContain(`issue=${targetIssue.id}`)
      expect(getNav().location.search).toContain('thread=th-valid-101')
      expect(screen.getByTestId('agent-pane')).toBeInTheDocument()
    })
  })

  it('closes issue detail modal in non-dock mode (clears issue) and dock mode (retains dock)', async () => {
    // 测试意图：验证 handleCloseIssueDetail 在普通模式下清空 URL issue 参数；在 Dock manual 模式下仅关闭弹窗并保留 Dock
    const api = createMockApi()
    const targetIssue = mockSnapshot.issues[0].issue
    const { getNav } = renderPage(
      <ProjectDetailPage projectId={projectId} onBack={vi.fn()} api={api} />,
      undefined,
      [`/projects/${projectId}?issue=${targetIssue.id}`],
    )

    // 1. 普通模式下关闭：URL 中 issue 被清理
    expect(await screen.findByLabelText('Issue #1 详情')).toBeInTheDocument()
    const closeBtns = screen.getAllByRole('button', { name: '关闭' })
    fireEvent.click(closeBtns[0])

    await waitFor(() => {
      expect(screen.queryByLabelText('Issue #1 详情')).not.toBeInTheDocument()
      expect(getNav().location.search).not.toContain('issue=')
    })

    // 2. Dock 模式下通过 manual modal 打开后再关闭：仅关弹窗，保留 Dock 与 URL
    act(() => {
      getNav().navigate(`/projects/${projectId}?issue=${targetIssue.id}&thread=th-valid-101`)
    })
    await screen.findByTestId('agent-pane')
    fireEvent.click(screen.getByRole('button', { name: '查看完整 Issue 详情' }))
    expect(await screen.findByLabelText('Issue #1 详情')).toBeInTheDocument()

    // 点击关闭弹窗
    const modalCloseBtns = screen.getAllByRole('button', { name: '关闭' })
    fireEvent.click(modalCloseBtns[0])

    await waitFor(() => {
      expect(screen.queryByLabelText('Issue #1 详情')).not.toBeInTheDocument()
      expect(screen.getByTestId('agent-pane')).toBeInTheDocument()
      expect(getNav().location.search).toContain('thread=th-valid-101')
    })
  })

  it('handles project archive toggle, header actions, and modal triggers', async () => {
    // 测试意图：验证头部归档/取消归档切换、编辑与删除弹窗打开与关闭，以及 onBack 点击
    const onBack = vi.fn()
    const api = createMockApi({
      archiveProject: vi.fn().mockResolvedValue({ ...mockSnapshot.project, archivedAt: '2026-09-30T00:00:00Z' }),
      unarchiveProject: vi.fn().mockResolvedValue({ ...mockSnapshot.project, archivedAt: null }),
    })
    renderPage(<ProjectDetailPage projectId={projectId} onBack={onBack} api={api} />)

    await screen.findByText('Awesome Platform')

    // 1. 点击返回按钮
    fireEvent.click(screen.getByRole('button', { name: '返回项目列表' }))
    expect(onBack).toHaveBeenCalledTimes(1)

    // 2. 点击归档按钮
    fireEvent.click(screen.getByRole('button', { name: '归档' }))
    await waitFor(() => {
      expect(api.archiveProject).toHaveBeenCalledWith(projectId, { expectedVersion: '2' })
    })

    // 3. 点击编辑按钮打开 EditProjectModal 并关闭
    fireEvent.click(screen.getByRole('button', { name: '编辑 / 工作流' }))
    expect(await screen.findByRole('dialog', { name: '编辑项目配置' })).toBeInTheDocument()
    const editCloseBtns = screen.getAllByRole('button', { name: '关闭' })
    fireEvent.click(editCloseBtns[editCloseBtns.length - 1])
    await waitFor(() => {
      expect(screen.queryByRole('dialog', { name: '编辑项目配置' })).not.toBeInTheDocument()
    })

    // 4. 点击删除按钮打开 DeleteProjectModal 并关闭
    fireEvent.click(screen.getByRole('button', { name: '删除' }))
    expect(await screen.findByRole('dialog', { name: '确认删除项目' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '取消' }))
    await waitFor(() => {
      expect(screen.queryByRole('dialog', { name: '确认删除项目' })).not.toBeInTheDocument()
    })
  })

  it('handles reload snapshot, create issue modal cancellation, and archive failure', async () => {
    // 测试意图：验证刷新 Snapshot、新建 Issue 弹窗取消以及归档异常分支
    const api = createMockApi({
      archiveProject: vi.fn().mockRejectedValue(new Error('Archive failed')),
    })

    renderPage(<ProjectDetailPage projectId={projectId} onBack={vi.fn()} api={api} />)
    await screen.findByText('Awesome Platform')

    // 1. 刷新项目数据
    fireEvent.click(screen.getByRole('button', { name: '刷新项目数据' }))
    await waitFor(() => {
      expect(api.getProjectSnapshot).toHaveBeenCalledTimes(2)
    })

    // 2. 归档异常分支
    fireEvent.click(screen.getByRole('button', { name: '归档' }))
    expect(await screen.findByText('Archive failed')).toBeInTheDocument()

    // 3. 点击新建 Issue 弹窗并关闭
    const createBtn = screen.getByRole('button', { name: '新建 Issue' })
    fireEvent.click(createBtn)
    expect(await screen.findByRole('dialog', { name: '新建 Issue' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '取消' }))
    await waitFor(() => {
      expect(screen.queryByRole('dialog', { name: '新建 Issue' })).not.toBeInTheDocument()
    })
  })

  it.each(['人工核查', '阻塞'])('opens the selected issue from the %s card action', async (action) => {
    // 两张卡片各自定位，快捷操作只能打开自身详情。
    const inProgressIssue = {
      ...mockSnapshot.issues[0].issue,
      state: 'IN_PROGRESS',
    }
    const unknownRunIssue = {
      ...mockSnapshot.issues[1].issue,
      id: 'iss-unknown',
      title: 'Unknown Run Issue',
      state: 'INIT',
    }
    const customSnapshot: ProjectSnapshotDTO = {
      ...mockSnapshot,
      issues: [
        { issue: inProgressIssue, currentOrLatestRun: null },
        {
          issue: unknownRunIssue,
          currentOrLatestRun: {
            id: 'run-unk',
            issueId: unknownRunIssue.id,
            ordinal: '1',
            state: 'INIT',
            status: 'UNKNOWN',
            agentName: 'Coder',
            startedAt: '2026-10-01T00:00:00Z',
            endedAt: null,
          },
        },
      ],
    }

    const api = createMockApi({
      getProjectSnapshot: vi.fn().mockResolvedValue(customSnapshot),
    })

    const { getNav } = renderPage(<ProjectDetailPage projectId={projectId} onBack={vi.fn()} api={api} />)
    await screen.findByText('Awesome Platform')

    const issue = action === '人工核查' ? unknownRunIssue : inProgressIssue
    const card = screen.getByRole('button', { name: `Issue #${issue.number} ${issue.title}` })
    fireEvent.click(within(card).getByRole('button', { name: action }))
    await waitFor(() => {
      expect(new URLSearchParams(getNav().location.search).get('issue')).toBe(issue.id)
    })
  })

  it('unarchives project when project is already archived', async () => {
    // 测试意图：验证已归档项目点击取消归档调用 unarchiveProject
    const archivedSnapshot = {
      ...mockSnapshot,
      project: {
        ...mockSnapshot.project,
        archivedAt: '2026-09-30T00:00:00Z',
      },
    }
    const api = createMockApi({
      getProjectSnapshot: vi.fn().mockResolvedValue(archivedSnapshot),
      unarchiveProject: vi.fn().mockResolvedValue({ ...archivedSnapshot.project, archivedAt: null }),
    })
    renderPage(<ProjectDetailPage projectId={projectId} onBack={vi.fn()} api={api} />)

    await screen.findByText('Awesome Platform')
    expect(screen.getByText('已归档')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '取消归档' }))
    await waitFor(() => {
      expect(api.unarchiveProject).toHaveBeenCalledWith(projectId, { expectedVersion: '2' })
    })
  })

  it('renders error banner and back button when snapshot is not found', async () => {
    // 测试意图：验证 snapshot 为空时展示错误横幅与返回列表按钮
    const onBack = vi.fn()
    const api = createMockApi({
      getProjectSnapshot: vi.fn().mockRejectedValue(new Error('Project not found')),
    })
    renderPage(<ProjectDetailPage projectId={projectId} onBack={onBack} api={api} />)

    expect(await screen.findByText('Project not found')).toBeInTheDocument()
    const backBtn = screen.getByRole('button', { name: '返回项目列表' })
    fireEvent.click(backBtn)
    expect(onBack).toHaveBeenCalled()
  })

  it('recovers blocked issues and reopens done issues using each card version', async () => {
    // 卡片动作携带自身版本和新的请求身份，避免复用其他卡片的 CAS 基线。
    const blockedIssue = {
      ...mockSnapshot.issues[0].issue,
      state: 'BLOCKED',
    }
    const doneIssue = {
      ...mockSnapshot.issues[1].issue,
      state: 'DONE',
    }
    const customSnapshot: ProjectSnapshotDTO = {
      ...mockSnapshot,
      issues: [
        { issue: blockedIssue, currentOrLatestRun: null },
        { issue: doneIssue, currentOrLatestRun: null },
      ],
    }

    const api = createMockApi({
      getProjectSnapshot: vi.fn().mockResolvedValue(customSnapshot),
      recoverIssue: vi.fn().mockResolvedValue(blockedIssue),
      reopenIssue: vi.fn().mockResolvedValue(doneIssue),
    })

    renderPage(<ProjectDetailPage projectId={projectId} onBack={vi.fn()} api={api} />)

    await screen.findByText('Awesome Platform')

    // 1. 点击“新建 Issue”打开 CreateIssueModal
    const createBtn = screen.getByRole('button', { name: '新建 Issue' })
    fireEvent.click(createBtn)
    expect(await screen.findByRole('dialog', { name: '新建 Issue' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '取消' }))

    // 2. 点击 BLOCKED 卡片上的恢复按钮
    const recoverBtn = screen.getByRole('button', { name: '恢复' })
    fireEvent.click(recoverBtn)
    await waitFor(() => {
      expect(api.recoverIssue).toHaveBeenCalledWith(blockedIssue.id, {
        expectedVersion: blockedIssue.version,
        requestKey: expect.any(String),
      })
    })

    // 3. 点击 DONE 卡片上的重新打开按钮
    const reopenBtn = screen.getByRole('button', { name: '重开' })
    fireEvent.click(reopenBtn)
    await waitFor(() => {
      expect(api.reopenIssue).toHaveBeenCalledWith(doneIssue.id, {
        expectedVersion: doneIssue.version,
        requestKey: expect.any(String),
      })
    })
  })
})
