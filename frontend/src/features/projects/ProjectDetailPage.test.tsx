import { describe, expect, it, vi } from 'vitest'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ApiError } from '@/shared/api/client'
import { ProjectDetailPage } from './ProjectDetailPage'
import type { ProjectsApi } from './projects-api'
import type { IssueDetailDTO, ProjectSnapshotDTO } from './types'
import { invalidateProjectQueries } from './projects-invalidation'

vi.mock('@/shared/api/agent-service', () => ({
  agentService: {
    listAgents: vi.fn().mockResolvedValue({
      results: [
        { name: 'backend-dev' },
        { name: 'db-specialist' },
        { name: 'reviewer-agent' },
      ],
      totalCount: 3,
      pageNumber: 1,
      pageSize: 50,
    }),
  },
}))

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
      yoloEnabled: true,
      maxReviewRejections: '3',
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
          status: 'BACKLOG',
          assigneeAgentName: null,
          reviewerAgentName: null,
          version: '1',
          archivedAt: null,
          createdAt: '2026-09-14T00:00:00Z',
          updatedAt: '2026-09-14T00:00:00Z',
        },
        blocked: false,
        reviewRejectionCount: '0',
        currentOrLatestRun: null,
      },
      {
        issue: {
          id: 'b0000000-0000-0000-0000-000000000002',
          projectId,
          number: '2',
          title: 'Implement REST API',
          description: 'Controller endpoints',
          status: 'IN_PROGRESS',
          assigneeAgentName: 'backend-dev',
          reviewerAgentName: null,
          version: '2',
          archivedAt: null,
          createdAt: '2026-09-14T00:00:00Z',
          updatedAt: '2026-09-14T01:00:00Z',
        },
        blocked: true,
        reviewRejectionCount: '1',
        currentOrLatestRun: {
          id: 'd0000000-0000-0000-0000-000000000001',
          issueId: 'b0000000-0000-0000-0000-000000000002',
          ordinal: '1',
          role: 'EXECUTOR',
          actorType: 'AGENT',
          agentName: 'backend-dev',
          submissionRunId: null,
          status: 'WAITING_HUMAN',
          outcome: null,
          waitingReason: 'Choose between REST or gRPC',
          createdAt: '2026-09-14T00:30:00Z',
          completedAt: null,
        },
      },
      {
        issue: {
          id: 'b0000000-0000-0000-0000-000000000003',
          projectId,
          number: '3',
          title: 'Old dropped feature',
          description: 'No longer needed',
          status: 'CANCELED',
          assigneeAgentName: null,
          reviewerAgentName: null,
          version: '1',
          archivedAt: null,
          createdAt: '2026-09-14T00:00:00Z',
          updatedAt: '2026-09-14T01:00:00Z',
        },
        blocked: false,
        reviewRejectionCount: '0',
        currentOrLatestRun: null,
      },
      {
        issue: {
          id: 'b0000000-0000-0000-0000-000000000004',
          projectId,
          number: '4',
          title: 'Blocked upstream task',
          description: 'Waiting on external service',
          status: 'BLOCKED',
          assigneeAgentName: null,
          reviewerAgentName: null,
          version: '1',
          archivedAt: null,
          createdAt: '2026-09-14T00:00:00Z',
          updatedAt: '2026-09-14T01:00:00Z',
        },
        blocked: true,
        reviewRejectionCount: '2',
        currentOrLatestRun: null,
      },
    ],
    dependencies: [],
  }

  const mockIssueDetail: IssueDetailDTO = {
    issue: mockSnapshot.issues[1].issue,
    blocked: true,
    dependencies: [
      {
        issueId: mockSnapshot.issues[1].issue.id,
        dependsOnIssueId: mockSnapshot.issues[0].issue.id,
        projectId,
        createdAt: '2026-09-14T00:00:00Z',
      },
    ],
    activities: [
      {
        id: 'act-001',
        issueId: mockSnapshot.issues[1].issue.id,
        sequence: '1',
        actorType: 'HUMAN',
        actorName: 'Operator',
        kind: 'COMMENT',
        body: 'Use REST API please',
        targetRole: 'EXECUTOR',
        createdAt: '2026-09-14T00:35:00Z',
      },
    ],
    sessions: [
      {
        id: 'sess-item-01',
        issueId: mockSnapshot.issues[1].issue.id,
        agentName: 'backend-dev',
        role: 'EXECUTOR',
        sessionId: 'sess-dev-01',
        branchId: 'branch-dev-01',
        createdAt: '2026-09-14T00:30:00Z',
      },
    ],
    runs: [
      {
        id: 'd0000000-0000-0000-0000-000000000001',
        issueId: mockSnapshot.issues[1].issue.id,
        ordinal: '1',
        role: 'EXECUTOR',
        actorType: 'AGENT',
        agentName: 'backend-dev',
        submissionRunId: null,
        status: 'WAITING_HUMAN',
        outcome: null,
        waitingReason: 'Choose between REST or gRPC',
        createdAt: '2026-09-14T00:30:00Z',
        completedAt: null,
      },
    ],
    currentRun: {
      id: 'd0000000-0000-0000-0000-000000000001',
      issueId: mockSnapshot.issues[1].issue.id,
      ordinal: '1',
      role: 'EXECUTOR',
      actorType: 'AGENT',
      agentName: 'backend-dev',
      submissionRunId: null,
      status: 'WAITING_HUMAN',
      outcome: null,
      waitingReason: 'Choose between REST or gRPC',
      createdAt: '2026-09-14T00:30:00Z',
      completedAt: null,
    },
    latestRun: null,
  }

  const createMockApi = (overrides: Partial<ProjectsApi> = {}): ProjectsApi => ({
    listProjects: vi.fn().mockResolvedValue([mockSnapshot.project]),
    createProject: vi.fn().mockResolvedValue(mockSnapshot.project),
    getProject: vi.fn().mockResolvedValue(mockSnapshot.project),
    updateProject: vi.fn().mockResolvedValue(mockSnapshot.project),
    deleteProject: vi.fn().mockResolvedValue(undefined),
    archiveProject: vi.fn().mockResolvedValue(mockSnapshot.project),
    unarchiveProject: vi.fn().mockResolvedValue(mockSnapshot.project),
    getProjectSnapshot: vi.fn().mockResolvedValue(mockSnapshot),
    createIssue: vi.fn().mockResolvedValue(mockSnapshot.issues[0].issue),
    getIssue: vi.fn().mockResolvedValue(mockIssueDetail),
    listActivities: vi.fn().mockResolvedValue([]),
    updateIssue: vi.fn().mockResolvedValue(mockSnapshot.issues[1].issue),
    changeIssueStatus: vi.fn().mockResolvedValue(mockSnapshot.issues[0].issue),
    blockIssue: vi.fn().mockResolvedValue(undefined),
    recoverIssue: vi.fn().mockResolvedValue(undefined),
    addIssueDependency: vi.fn().mockResolvedValue(mockIssueDetail.dependencies[0]),
    removeIssueDependency: vi.fn().mockResolvedValue(undefined),
    appendIssueActivity: vi.fn().mockImplementation((_issueId, req) =>
      Promise.resolve({
        id: 'act-002',
        issueId: mockSnapshot.issues[1].issue.id,
        sequence: '2',
        actorType: 'HUMAN',
        actorName: 'Operator',
        kind: 'COMMENT',
        body: req.body,
        targetRole: req.targetRole ?? null,
        createdAt: '2026-09-14T00:36:00Z',
      }),
    ),
    reviewIssue: vi.fn().mockResolvedValue(undefined),
    cancelIssue: vi.fn().mockResolvedValue({ ...mockSnapshot.issues[1].issue, status: 'CANCELED' }),
    retryIssue: vi.fn().mockResolvedValue(mockIssueDetail.activities[0]),
    archiveIssue: vi.fn().mockResolvedValue({ ...mockSnapshot.issues[0].issue, archivedAt: 'now' }),
    unarchiveIssue: vi.fn().mockResolvedValue({ ...mockSnapshot.issues[0].issue, archivedAt: null }),
    ...overrides,
  })

  it('renders project header and 7 status columns accurately classifying issues', async () => {
    // 测试意图：验证项目详情页展示 YOLO 状态及打回上限，看板按 7 种状态与 run.status 正确归类 Issue
    const api = createMockApi()
    renderPage(<ProjectDetailPage projectId={projectId} api={api} />)

    await waitFor(() => {
      expect(screen.getByText('Awesome Platform')).toBeInTheDocument()
      expect(screen.getByText('YOLO:')).toBeInTheDocument()
      expect(screen.getByText('开启')).toBeInTheDocument()
      expect(screen.getByText('最大打回:')).toBeInTheDocument()
    })

    // Issue #1 is BACKLOG -> should be in Backlog column
    expect(screen.getByText('Design DB schema')).toBeInTheDocument()

    // Issue #2 is IN_PROGRESS with WAITING_HUMAN run -> should be placed in Waiting Human column
    const waitingCol = screen.getByText('等待人类 (Waiting Human)').closest('.board-column')
    expect(waitingCol).toHaveTextContent('Implement REST API')

    // Issue #4 is BLOCKED status -> should be in Blocked column
    const blockedCol = screen.getByText('已阻塞 (Blocked)').closest('.board-column')
    expect(blockedCol).toHaveTextContent('Blocked upstream task')

    // Issue #2 should also show BLOCKED badge because blocked=true
    expect(screen.getAllByText('BLOCKED').length).toBeGreaterThanOrEqual(1)

    // BLOCKED cards should show review rejection count against project maxReviewRejections (3)
    // Issue #4: reviewRejectionCount = 2 -> '2 / 3'
    // Issue #2: reviewRejectionCount = 1 -> '1 / 3'
    expect(screen.getByText('2 / 3')).toBeInTheDocument()
    expect(screen.getByText('1 / 3')).toBeInTheDocument()

    // Canceled issue should be hidden by default
    expect(screen.queryByText('Old dropped feature')).not.toBeInTheDocument()
  })

  it('shows canceled issues when toggle is enabled', async () => {
    // 测试意图：验证“显示已取消”开关能够展示 CANCELED 规格列
    const api = createMockApi()
    renderPage(<ProjectDetailPage projectId={projectId} api={api} />)

    await waitFor(() => {
      expect(screen.getByText('Awesome Platform')).toBeInTheDocument()
    })

    const toggle = screen.getByLabelText(/显示已取消/i)
    fireEvent.click(toggle)

    await waitFor(() => {
      expect(screen.getByText('已取消 (Canceled)')).toBeInTheDocument()
      expect(screen.getByText('Old dropped feature')).toBeInTheDocument()
    })
  })

  it('changes issue status from board quick actions', async () => {
    // 测试意图：验证看板快捷按钮触发 changeIssueStatus API 投递合法状态流转
    const api = createMockApi()
    renderPage(<ProjectDetailPage projectId={projectId} api={api} />)

    await waitFor(() => {
      expect(screen.getByText('Design DB schema')).toBeInTheDocument()
    })

    // Issue #1 is BACKLOG, has "→ TODO" quick action
    const toTodoBtn = screen.getByRole('button', { name: '→ TODO' })
    fireEvent.click(toTodoBtn)

    await waitFor(() => {
      expect(api.changeIssueStatus).toHaveBeenCalledWith(
        mockSnapshot.issues[0].issue.id,
        {
          expectedVersion: mockSnapshot.issues[0].issue.version,
          status: 'TODO',
        },
      )
    })
  })

  it('opens IssueDetailModal, navigates tabs and appends activity', async () => {
    // 测试意图：验证点击卡片打开详情弹窗，可在活动流标签页追加指定角色的活动
    const api = createMockApi()
    renderPage(<ProjectDetailPage projectId={projectId} api={api} />)

    await waitFor(() => {
      expect(screen.getByText('Implement REST API')).toBeInTheDocument()
    })

    // Click issue card
    const card = screen.getByLabelText('Issue #2 Implement REST API')
    fireEvent.click(card)

    await waitFor(() => {
      expect(screen.getByText(/Issue #2: Implement REST API/i)).toBeInTheDocument()
      expect(screen.getByText('规格与状态')).toBeInTheDocument()
    })

    // Switch to activities tab
    const activitiesTabBtn = screen.getByRole('button', { name: /^活动流/ })
    fireEvent.click(activitiesTabBtn)

    await waitFor(() => {
      expect(screen.getByText('Use REST API please')).toBeInTheDocument()
    })

    // Set target role
    const targetRoleSelect = screen.getByLabelText('目标职责')
    fireEvent.change(targetRoleSelect, { target: { value: 'EXECUTOR' } })

    // Append new activity
    const activityInput = screen.getByLabelText('活动内容')
    fireEvent.change(activityInput, { target: { value: 'Proceed with REST option' } })

    const submitActivityBtn = screen.getByRole('button', { name: '追加活动' })
    fireEvent.click(submitActivityBtn)

    await waitFor(() => {
      expect(api.appendIssueActivity).toHaveBeenCalledWith(
        mockSnapshot.issues[1].issue.id,
        expect.objectContaining({
          body: 'Proceed with REST option',
          targetRole: 'EXECUTOR',
        }),
      )
    })
  })

  it('handles CAS conflict in IssueDetailModal spec editor', async () => {
    // 测试意图：验证在 IssueDetailModal 中编辑规格触发 409 CAS 冲突时，保留用户草稿并提供同步按钮
    const conflictError = new ApiError('Version conflict', 409, 'PROJECT_VERSION_CONFLICT')
    const freshDetail: IssueDetailDTO = {
      ...mockIssueDetail,
      issue: {
        ...mockIssueDetail.issue,
        version: '3',
      },
    }

    const api = createMockApi({
      updateIssue: vi.fn().mockRejectedValueOnce(conflictError).mockResolvedValueOnce({
        ...freshDetail.issue,
        title: 'New Spec Draft',
      }),
      getIssue: vi.fn().mockResolvedValue(freshDetail),
    })

    renderPage(<ProjectDetailPage projectId={projectId} api={api} />)

    await waitFor(() => {
      expect(screen.getByText('Implement REST API')).toBeInTheDocument()
    })

    fireEvent.click(screen.getByLabelText('Issue #2 Implement REST API'))

    await waitFor(() => {
      expect(screen.getByText('编辑规格')).toBeInTheDocument()
    })

    // Click "编辑规格"
    fireEvent.click(screen.getByText('编辑规格'))

    // Edit title
    const titleInput = screen.getByLabelText(/标题/i)
    fireEvent.change(titleInput, { target: { value: 'New Spec Draft' } })

    // Save
    fireEvent.click(screen.getByRole('button', { name: '保存修改' }))

    await waitFor(() => {
      expect(screen.getByText(/版本冲突/i)).toBeInTheDocument()
    })

    // Draft preserved
    expect((screen.getByLabelText(/标题/i) as HTMLInputElement).value).toBe('New Spec Draft')

    // Click "同步最新版本号"
    fireEvent.click(screen.getByRole('button', { name: /同步最新版本号/i }))

    await waitFor(() => {
      expect(api.getIssue).toHaveBeenCalled()
    })

    // Resubmit
    fireEvent.click(screen.getByRole('button', { name: '保存修改' }))

    await waitFor(() => {
      expect(api.updateIssue).toHaveBeenCalledWith(
        mockIssueDetail.issue.id,
        expect.objectContaining({
          expectedVersion: '3',
          title: 'New Spec Draft',
        }),
      )
    })
  })

  it('preserves an Issue spec draft while project invalidation refreshes its detail', async () => {
    // 测试意图：跨节点 Issue 失效应刷新详情，但编辑中的规格草稿继续使用原 CAS 基线。
    const refreshedDetail: IssueDetailDTO = {
      ...mockIssueDetail,
      issue: {
        ...mockIssueDetail.issue,
        title: 'Server-side issue title',
        version: '3',
      },
    }
    const api = createMockApi({
      getIssue: vi.fn()
        .mockResolvedValueOnce(mockIssueDetail)
        .mockResolvedValue(refreshedDetail),
      updateIssue: vi.fn().mockResolvedValue(mockIssueDetail.issue),
    })
    const { queryClient } = renderPage(<ProjectDetailPage projectId={projectId} api={api} />)

    await screen.findByText('Implement REST API')
    fireEvent.click(screen.getByLabelText('Issue #2 Implement REST API'))
    await screen.findByText('编辑规格')
    fireEvent.click(screen.getByText('编辑规格'))
    const titleInput = screen.getByLabelText(/标题/i)
    fireEvent.change(titleInput, { target: { value: 'Unsaved Issue draft' } })

    await act(async () => {
      await invalidateProjectQueries(queryClient, { projectId })
    })
    await waitFor(() => expect(api.getIssue).toHaveBeenCalledTimes(2))
    expect(titleInput).toHaveValue('Unsaved Issue draft')

    fireEvent.click(screen.getByRole('button', { name: '保存修改' }))
    await waitFor(() => {
      expect(api.updateIssue).toHaveBeenCalledWith(
        mockIssueDetail.issue.id,
        expect.objectContaining({
          expectedVersion: mockIssueDetail.issue.version,
          title: 'Unsaved Issue draft',
        }),
      )
    })
  })

  it('does not refetch an active issue query when targeted invalidation belongs to a different project', async () => {
    // 测试意图：验证其他 project 的精准失效不会导致当前项目已打开的 Issue 重新请求
    const api = createMockApi({
      getIssue: vi.fn().mockResolvedValue(mockIssueDetail),
    })
    const { queryClient } = renderPage(<ProjectDetailPage projectId={projectId} api={api} />)

    await screen.findByText('Implement REST API')
    fireEvent.click(screen.getByLabelText('Issue #2 Implement REST API'))
    await screen.findByText('编辑规格')
    expect(api.getIssue).toHaveBeenCalledTimes(1)

    // 发起针对另一个 project 的精准失效
    await act(async () => {
      await invalidateProjectQueries(queryClient, { projectId: 'different-project-id' })
    })

    // 当前项目的 getIssue 绝不应被重新调用
    expect(api.getIssue).toHaveBeenCalledTimes(1)
  })

  it('performs human review when issue is IN_REVIEW and reviewerAgentName is null', async () => {
    // 测试意图：验证处于 IN_REVIEW 且 reviewerAgentName 为空时，人类可提交 APPROVE 或 REQUEST_CHANGES 审核决定
    const inReviewIssue = {
      ...mockSnapshot.issues[1].issue,
      status: 'IN_REVIEW' as const,
      reviewerAgentName: null,
    }
    const reviewDetail: IssueDetailDTO = {
      ...mockIssueDetail,
      issue: inReviewIssue,
      currentRun: {
        ...mockIssueDetail.currentRun!,
        status: 'RUNNING',
      },
    }

    const api = createMockApi({
      getIssue: vi.fn().mockResolvedValue(reviewDetail),
    })

    renderPage(<ProjectDetailPage projectId={projectId} api={api} />)

    await waitFor(() => {
      expect(screen.getByText('Implement REST API')).toBeInTheDocument()
    })

    fireEvent.click(screen.getByLabelText('Issue #2 Implement REST API'))

    await waitFor(() => {
      expect(screen.getByText(/Issue #2/)).toBeInTheDocument()
    })

    // Switch to runs & review tab
    const runsTab = screen.getByRole('button', { name: /^执行与审核/ })
    fireEvent.click(runsTab)

    await waitFor(() => {
      expect(screen.getByText(/人工审核/i)).toBeInTheDocument()
    })

    // Fill review reason
    const reasonInput = screen.getByLabelText(/审核理由 \/ 反馈说明/i)
    fireEvent.change(reasonInput, { target: { value: 'Approved after verification' } })

    const submitReviewBtn = screen.getByRole('button', { name: '提交审核决定' })
    fireEvent.click(submitReviewBtn)

    await waitFor(() => {
      expect(api.reviewIssue).toHaveBeenCalledWith(
        inReviewIssue.id,
        expect.objectContaining({
          decision: 'APPROVE',
          reason: 'Approved after verification',
        }),
      )
    })
  })

  it('retries run when run is in FAILED or UNKNOWN state', async () => {
    // 测试意图：验证当前或最近 Run 失败时，详情页提供重试按钮并触发 retryIssue 接口
    const failedDetail: IssueDetailDTO = {
      ...mockIssueDetail,
      currentRun: {
        ...mockIssueDetail.currentRun!,
        status: 'FAILED',
      },
    }

    const api = createMockApi({
      getIssue: vi.fn().mockResolvedValue(failedDetail),
    })

    renderPage(<ProjectDetailPage projectId={projectId} api={api} />)

    await waitFor(() => {
      expect(screen.getByText('Implement REST API')).toBeInTheDocument()
    })

    fireEvent.click(screen.getByLabelText('Issue #2 Implement REST API'))

    await waitFor(() => {
      expect(screen.getByText(/Issue #2/)).toBeInTheDocument()
    })

    // Switch to runs tab
    const runsTab = screen.getByRole('button', { name: /^执行与审核/ })
    fireEvent.click(runsTab)

    await waitFor(() => {
      expect(screen.getByText(/Run 执行失败或处于未知状态/i)).toBeInTheDocument()
    })

    const retryBtn = screen.getByRole('button', { name: /重试 Run/i })
    fireEvent.click(retryBtn)

    await waitFor(() => {
      expect(api.retryIssue).toHaveBeenCalledWith(
        failedDetail.issue.id,
        expect.objectContaining({
          idempotencyKey: expect.any(String),
        }),
      )
    })
  })

  it('creates a new issue from the board toolbar button', async () => {
    // 测试意图：验证从看板工具栏点击“新建 Issue”打开弹窗，输入标题和参数后正确创建 Issue 并刷新看板
    const api = createMockApi()
    renderPage(<ProjectDetailPage projectId={projectId} api={api} />)

    await waitFor(() => {
      expect(screen.getByText('Awesome Platform')).toBeInTheDocument()
    })

    // Click "新建 Issue"
    const createIssueBtn = screen.getByRole('button', { name: '新建 Issue' })
    fireEvent.click(createIssueBtn)

    await waitFor(() => {
      expect(screen.getByText('新建 Issue', { selector: 'h3' })).toBeInTheDocument()
    })

    // Fill form
    fireEvent.change(screen.getByLabelText(/标题/i), { target: { value: 'Refactor DB queries' } })
    fireEvent.change(screen.getByLabelText(/规格与详细要求/i), { target: { value: 'Optimize JOINs' } })
    fireEvent.change(screen.getByLabelText(/初始状态/i), { target: { value: 'TODO' } })
    fireEvent.change(screen.getByLabelText(/Assignee Agent/i), { target: { value: 'db-specialist' } })

    // Submit
    fireEvent.click(screen.getByRole('button', { name: '创建 Issue' }))

    await waitFor(() => {
      expect(api.createIssue).toHaveBeenCalledWith(projectId, {
        title: 'Refactor DB queries',
        description: 'Optimize JOINs',
        initialStatus: 'TODO',
        assigneeAgentName: 'db-specialist',
        reviewerAgentName: null,
      })
      expect(api.getProjectSnapshot).toHaveBeenCalledTimes(2)
    })
  })

  it('navigates back when clicking back button in header', async () => {
    // 测试意图：验证点击顶部返回箭头按钮触发 onBack 回调
    const onBack = vi.fn()
    const api = createMockApi()
    renderPage(<ProjectDetailPage projectId={projectId} onBack={onBack} api={api} />)

    await waitFor(() => {
      expect(screen.getByText('Awesome Platform')).toBeInTheDocument()
    })

    const backBtn = screen.getByLabelText('返回项目列表')
    fireEvent.click(backBtn)

    expect(onBack).toHaveBeenCalledTimes(1)
  })

  it('renders error banner when loading project snapshot fails and allows returning back', async () => {
    // 测试意图：验证项目 Snapshot 获取失败时展示错误提示，并可点击返回按钮
    const onBack = vi.fn()
    const api = createMockApi({
      getProjectSnapshot: vi.fn().mockRejectedValue(new Error('Snapshot failed to load')),
    })

    renderPage(<ProjectDetailPage projectId={projectId} onBack={onBack} api={api} />)

    await waitFor(() => {
      expect(screen.getByText('Snapshot failed to load')).toBeInTheDocument()
    })

    const backBtn = screen.getByRole('button', { name: /返回项目列表/i })
    fireEvent.click(backBtn)
    expect(onBack).toHaveBeenCalledTimes(1)
  })

  it('preserves the project edit draft when an invalidation refreshes the snapshot', async () => {
    // 测试意图：后台变更只刷新权威页面快照，不得重置正在编辑的项目草稿或偷换 CAS 基线。
    const refreshedSnapshot: ProjectSnapshotDTO = {
      ...mockSnapshot,
      project: {
        ...mockSnapshot.project,
        title: 'Server-side title',
        version: '3',
      },
    }
    const api = createMockApi({
      getProjectSnapshot: vi.fn()
        .mockResolvedValueOnce(mockSnapshot)
        .mockResolvedValue(refreshedSnapshot),
    })
    const { queryClient } = renderPage(<ProjectDetailPage projectId={projectId} api={api} />)

    await screen.findByText('Awesome Platform')
    fireEvent.click(screen.getByRole('button', { name: /编辑/i }))
    const titleInput = screen.getByLabelText(/项目名称/i)
    fireEvent.change(titleInput, { target: { value: 'Unsaved project draft' } })

    await act(async () => {
      await invalidateProjectQueries(queryClient, { projectId })
    })
    await waitFor(() => expect(api.getProjectSnapshot).toHaveBeenCalledTimes(2))
    expect(titleInput).toHaveValue('Unsaved project draft')

    fireEvent.click(screen.getByRole('button', { name: '保存更改' }))
    await waitFor(() => {
      expect(api.updateProject).toHaveBeenCalledWith(
        projectId,
        expect.objectContaining({
          expectedVersion: mockSnapshot.project.version,
          title: 'Unsaved project draft',
        }),
      )
    })
  })

  it('ignores a stale snapshot response after a newer invalidation reload', async () => {
    // 测试意图：验证 invalidation reload 之后，最新权威数据正确更新替换旧数据
    const initialSnapshot: ProjectSnapshotDTO = {
      ...mockSnapshot,
      project: { ...mockSnapshot.project, title: 'Initial Snapshot' },
    }
    const latestSnapshot: ProjectSnapshotDTO = {
      ...mockSnapshot,
      project: { ...mockSnapshot.project, title: 'Latest Snapshot' },
    }
    const api = createMockApi({
      getProjectSnapshot: vi.fn()
        .mockResolvedValueOnce(initialSnapshot)
        .mockResolvedValue(latestSnapshot),
    })
    const { queryClient } = renderPage(<ProjectDetailPage projectId={projectId} api={api} />)

    expect(await screen.findByText('Initial Snapshot')).toBeInTheDocument()

    await act(async () => {
      await invalidateProjectQueries(queryClient, { projectId })
    })
    expect(await screen.findByText('Latest Snapshot')).toBeInTheDocument()
  })

  it('edits and deletes project from detail page header actions', async () => {
    // 测试意图：验证在详情页顶部直接编辑项目与删除项目（级联触发 onBack）流程
    const onBack = vi.fn()
    const api = createMockApi()
    renderPage(<ProjectDetailPage projectId={projectId} onBack={onBack} api={api} />)

    await waitFor(() => {
      expect(screen.getByText('Awesome Platform')).toBeInTheDocument()
    })

    // Edit project
    fireEvent.click(screen.getByRole('button', { name: /编辑/i }))

    expect(screen.getByText('编辑项目', { selector: 'h3' })).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText(/项目名称/i), { target: { value: 'Renamed Platform' } })
    await waitFor(() => {
      expect(screen.getByRole('button', { name: '保存更改' })).not.toBeDisabled()
    })
    fireEvent.click(screen.getByRole('button', { name: '保存更改' }))

    await waitFor(() => {
      expect(api.updateProject).toHaveBeenCalledWith(
        projectId,
        expect.objectContaining({ title: 'Renamed Platform' }),
      )
    })

    // Delete project
    fireEvent.click(screen.getByRole('button', { name: /删除/i }))
    expect(screen.getByText('确认删除项目', { selector: 'h3' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '确认删除' }))

    await waitFor(() => {
      expect(api.deleteProject).toHaveBeenCalledWith(projectId, mockSnapshot.project.version)
      expect(onBack).toHaveBeenCalledTimes(1)
    })
  })

  it('manages issue dependencies inside IssueDetailModal (add and remove)', async () => {
    // 测试意图：验证在 Issue 详情弹窗的“依赖关系”标签页中添加与删除依赖项
    const api = createMockApi()
    renderPage(<ProjectDetailPage projectId={projectId} api={api} />)

    await waitFor(() => {
      expect(screen.getByText('Implement REST API')).toBeInTheDocument()
    })

    // Open Issue #2
    fireEvent.click(screen.getByLabelText('Issue #2 Implement REST API'))

    await waitFor(() => {
      expect(screen.getByText(/Issue #2/)).toBeInTheDocument()
    })

    // Switch to dependencies tab
    const depsTab = screen.getByRole('button', { name: /^依赖关系/ })
    fireEvent.click(depsTab)

    await waitFor(() => {
      expect(screen.getByText('依赖的 Issue')).toBeInTheDocument()
    })

    // Remove existing dependency
    const removeBtn = screen.getByTitle('移除此依赖')
    fireEvent.click(removeBtn)

    await waitFor(() => {
      expect(api.removeIssueDependency).toHaveBeenCalledWith(
        mockSnapshot.issues[1].issue.id,
        mockSnapshot.issues[0].issue.id,
        mockSnapshot.issues[1].issue.version,
      )
    })

    // Add new dependency from candidates dropdown
    const selectDep = screen.getByLabelText('选择要依赖的 Issue')
    fireEvent.change(selectDep, { target: { value: mockSnapshot.issues[2].issue.id } })

    const addDepBtn = screen.getByRole('button', { name: /添加依赖/i })
    fireEvent.click(addDepBtn)

    await waitFor(() => {
      expect(api.addIssueDependency).toHaveBeenCalledWith(mockSnapshot.issues[1].issue.id, {
        expectedVersion: mockSnapshot.issues[1].issue.version,
        dependsOnIssueId: mockSnapshot.issues[2].issue.id,
      })
    })
  })

  it('cancels an issue from IssueDetailModal with optional reason', async () => {
    // 测试意图：验证在详情弹窗中触发取消 Issue 流程，输入原因并调用 cancelIssue
    const api = createMockApi()
    renderPage(<ProjectDetailPage projectId={projectId} api={api} />)

    await waitFor(() => {
      expect(screen.getByText('Implement REST API')).toBeInTheDocument()
    })

    fireEvent.click(screen.getByLabelText('Issue #2 Implement REST API'))

    await waitFor(() => {
      expect(screen.getByText('取消 Issue')).toBeInTheDocument()
    })

    // Click "取消 Issue" button to show confirmation sub-form
    fireEvent.click(screen.getByText('取消 Issue'))

    const reasonInput = screen.getByLabelText('取消原因')
    fireEvent.change(reasonInput, { target: { value: 'Merged into another epic' } })

    const confirmCancelBtn = screen.getByRole('button', { name: '确认取消' })
    fireEvent.click(confirmCancelBtn)

    await waitFor(() => {
      expect(api.cancelIssue).toHaveBeenCalledWith(mockSnapshot.issues[1].issue.id, {
        expectedVersion: mockSnapshot.issues[1].issue.version,
        reason: 'Merged into another epic',
      })
    })
  })

  it('renders zero/reset rejection count on BLOCKED cards and updates when project threshold changes', async () => {
    // 测试意图：验证 BLOCKED 卡片在打回数为 0 时展示重置后的 "0 / <threshold>"，并在项目阈值变更时正确更新分母
    const customSnapshot: ProjectSnapshotDTO = {
      ...mockSnapshot,
      project: {
        ...mockSnapshot.project,
        maxReviewRejections: '5',
      },
      issues: [
        {
          issue: {
            ...mockSnapshot.issues[3].issue,
            id: 'b0000000-0000-0000-0000-000000000099',
            number: '99',
            title: 'Reset blocked issue',
          },
          blocked: true,
          reviewRejectionCount: '0',
          currentOrLatestRun: null,
        },
      ],
    }

    const api = createMockApi({
      getProjectSnapshot: vi.fn().mockResolvedValue(customSnapshot),
    })

    renderPage(<ProjectDetailPage projectId={projectId} api={api} />)

    await waitFor(() => {
      expect(screen.getByText('Reset blocked issue')).toBeInTheDocument()
    })

    // 验证重置计数 0 及新项目阈值 5
    expect(screen.getByText('0 / 5')).toBeInTheDocument()
  })

  it('provides controlled agent selection in CreateIssueModal with catalog options and blank', async () => {
    // 测试意图：验证新建 Issue 弹窗中 Assignee 与 Reviewer 为受控原生 select，仅含 catalog 中的 Agent 与空白项
    const api = createMockApi()
    renderPage(<ProjectDetailPage projectId={projectId} api={api} />)

    await waitFor(() => {
      expect(screen.getByText('Awesome Platform')).toBeInTheDocument()
    })

    fireEvent.click(screen.getByRole('button', { name: '新建 Issue' }))

    await waitFor(() => {
      expect(screen.getByText('新建 Issue', { selector: 'h3' })).toBeInTheDocument()
    })

    const assigneeSelect = screen.getByLabelText(/Assignee Agent/i) as HTMLSelectElement
    const reviewerSelect = screen.getByLabelText(/Reviewer Agent/i) as HTMLSelectElement

    // 检查标签类型为 select，而非 input text
    expect(assigneeSelect.tagName.toLowerCase()).toBe('select')
    expect(reviewerSelect.tagName.toLowerCase()).toBe('select')

    // 验证选项包含空白选项与 catalog 中的 agent
    await waitFor(() => {
      const assigneeOptionValues = Array.from(assigneeSelect.options).map((o) => o.value)
      expect(assigneeOptionValues).toContain('')
      expect(assigneeOptionValues).toContain('backend-dev')
      expect(assigneeOptionValues).toContain('db-specialist')
      expect(assigneeOptionValues).toContain('reviewer-agent')
    })

    // 选择已知 agent 与空白选项
    fireEvent.change(assigneeSelect, { target: { value: 'backend-dev' } })
    expect(assigneeSelect.value).toBe('backend-dev')

    fireEvent.change(reviewerSelect, { target: { value: 'reviewer-agent' } })
    expect(reviewerSelect.value).toBe('reviewer-agent')

    fireEvent.change(reviewerSelect, { target: { value: '' } })
    expect(reviewerSelect.value).toBe('')
  })
})
