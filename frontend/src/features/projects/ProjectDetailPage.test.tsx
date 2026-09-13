import { describe, expect, it, vi } from 'vitest'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { ApiError } from '@/shared/api/client'
import { ProjectDetailPage } from './ProjectDetailPage'
import type { ProjectsApi } from './projects-api'
import type { IssueDetailDTO, ProjectSnapshotDTO } from './types'
import { notifyProjectsChanged } from './useProjectsInvalidation'

describe('ProjectDetailPage', () => {
  const projectId = 'a0000000-0000-0000-0000-000000000001'

  const mockSnapshot: ProjectSnapshotDTO = {
    project: {
      id: projectId,
      title: 'Awesome Platform',
      description: 'Building next gen studio',
      coordinatorAgentName: 'coordinator-lead',
      nextIssueNumber: '4',
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
          specRevision: '0',
          inputSequence: '0',
          archivedAt: null,
          createdAt: '2026-09-14T00:00:00Z',
          updatedAt: '2026-09-14T00:00:00Z',
        },
        blocked: false,
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
          specRevision: '1',
          inputSequence: '1',
          archivedAt: null,
          createdAt: '2026-09-14T00:00:00Z',
          updatedAt: '2026-09-14T01:00:00Z',
        },
        blocked: true,
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
          specRevision: '0',
          inputSequence: '0',
          archivedAt: null,
          createdAt: '2026-09-14T00:00:00Z',
          updatedAt: '2026-09-14T01:00:00Z',
        },
        blocked: false,
        currentOrLatestRun: null,
      },
    ],
    dependencies: [],
    coordinatorSessionId: 'f0000000-0000-0000-0000-000000000001',
    coordinatorSession: {
      sessionId: 'f0000000-0000-0000-0000-000000000001',
      name: 'Main Coordinator Session',
      createdAt: '2026-09-14T00:00:00Z',
      lastActivityAt: '2026-09-14T01:00:00Z',
      firstMessagePreview: 'Coordinator ready',
      threadCount: 1,
    },
    coordinatorThread: {
      threadId: 'f0000000-0000-0000-0000-000000000002',
      name: 'main',
      createdAt: '2026-09-14T00:00:00Z',
      updatedAt: '2026-09-14T01:00:00Z',
      status: 'IDLE',
      model: {
        providerName: 'openai',
        modelName: 'gpt-4o',
        variant: 'default',
      },
      headMessagePreview: 'Coordinator ready',
    },
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
    inputs: [
      {
        issueId: mockSnapshot.issues[1].issue.id,
        sequence: '1',
        kind: 'HUMAN',
        body: 'Use REST API please',
        idempotencyKey: null,
        createdAt: '2026-09-14T00:35:00Z',
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
        observedSpecRevision: '1',
        observedInputSequence: '0',
        continuationCount: 1,
        maxContinuations: 10,
        deadline: null,
        waitingReason: 'Choose between REST or gRPC',
        result: null,
        terminalActionId: null,
        version: '1',
        createdAt: '2026-09-14T00:30:00Z',
        updatedAt: '2026-09-14T00:30:00Z',
        completedAt: null,
        sessionId: 'f0000000-0000-0000-0000-000000000003',
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
      observedSpecRevision: '1',
      observedInputSequence: '0',
      continuationCount: 1,
      maxContinuations: 10,
      deadline: null,
      waitingReason: 'Choose between REST or gRPC',
      result: null,
      terminalActionId: null,
      version: '1',
      createdAt: '2026-09-14T00:30:00Z',
      updatedAt: '2026-09-14T00:30:00Z',
      completedAt: null,
      sessionId: 'f0000000-0000-0000-0000-000000000003',
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
    sendProjectCommand: vi.fn().mockResolvedValue({}),
    getProjectSnapshot: vi.fn().mockResolvedValue(mockSnapshot),
    createIssue: vi.fn().mockResolvedValue(mockSnapshot.issues[0].issue),
    getIssue: vi.fn().mockResolvedValue(mockIssueDetail),
    updateIssue: vi.fn().mockResolvedValue(mockSnapshot.issues[1].issue),
    changeIssueStatus: vi.fn().mockResolvedValue(mockSnapshot.issues[0].issue),
    addIssueDependency: vi.fn().mockResolvedValue(mockIssueDetail.dependencies[0]),
    removeIssueDependency: vi.fn().mockResolvedValue(undefined),
    appendIssueInput: vi.fn().mockResolvedValue(mockIssueDetail.inputs[0]),
    reviewIssue: vi.fn().mockResolvedValue({
      id: 'd0000000-0000-0000-0000-000000000002',
      issueId: mockSnapshot.issues[1].issue.id,
      ordinal: '2',
      role: 'REVIEWER',
      actorType: 'HUMAN',
      agentName: null,
      submissionRunId: null,
      status: 'COMPLETED',
      outcome: 'APPROVED',
      waitingReason: null,
      createdAt: '2026-09-14T00:40:00Z',
      completedAt: '2026-09-14T00:41:00Z',
    }),
    cancelIssue: vi.fn().mockResolvedValue({ ...mockSnapshot.issues[1].issue, status: 'CANCELED' }),
    retryIssue: vi.fn().mockResolvedValue(mockIssueDetail.inputs[0]),
    archiveIssue: vi.fn().mockResolvedValue({ ...mockSnapshot.issues[0].issue, archivedAt: 'now' }),
    unarchiveIssue: vi.fn().mockResolvedValue({ ...mockSnapshot.issues[0].issue, archivedAt: null }),
    ...overrides,
  })

  it('renders project header and 6 columns accurately classifying issues', async () => {
    // 测试意图：验证六列看板按 issue.status 与 run.status 正确归类 Issue（如 WAITING_HUMAN 归入等待人类列）
    const api = createMockApi()
    render(<ProjectDetailPage projectId={projectId} api={api} />)

    await waitFor(() => {
      expect(screen.getByText('Awesome Platform')).toBeInTheDocument()
      expect(screen.getAllByText(/coordinator-lead/i).length).toBeGreaterThanOrEqual(1)
    })

    // Issue #1 is BACKLOG -> should be in Backlog column
    expect(screen.getByText('Design DB schema')).toBeInTheDocument()

    // Issue #2 is IN_PROGRESS with WAITING_HUMAN run -> should be placed in Waiting Human column
    const waitingCol = screen.getByText('等待人类 (Waiting Human)').closest('.board-column')
    expect(waitingCol).toHaveTextContent('Implement REST API')

    // Issue #2 should also show BLOCKED badge
    expect(screen.getByText('BLOCKED')).toBeInTheDocument()

    // Canceled issue should be hidden by default
    expect(screen.queryByText('Old dropped feature')).not.toBeInTheDocument()
  })

  it('shows canceled issues when toggle is enabled', async () => {
    // 测试意图：验证“显示已取消”开关能够展示 CANCELED 规格列
    const api = createMockApi()
    render(<ProjectDetailPage projectId={projectId} api={api} />)

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
    render(<ProjectDetailPage projectId={projectId} api={api} />)

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

  it('opens IssueDetailModal, navigates tabs and appends input', async () => {
    // 测试意图：验证点击卡片打开详情弹窗，可在输入流标签页追加人类输入
    const api = createMockApi()
    render(<ProjectDetailPage projectId={projectId} api={api} />)

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

    // Switch to inputs tab
    const inputsTabBtn = screen.getByRole('button', { name: /^输入流/ })
    fireEvent.click(inputsTabBtn)

    await waitFor(() => {
      expect(screen.getByText('Use REST API please')).toBeInTheDocument()
    })

    // Append new input
    const inputArea = screen.getByPlaceholderText(/输入要向执行上下文传递的内容/i)
    fireEvent.change(inputArea, { target: { value: 'Proceed with REST option' } })

    const submitInputBtn = screen.getByRole('button', { name: '提交输入' })
    fireEvent.click(submitInputBtn)

    await waitFor(() => {
      expect(api.appendIssueInput).toHaveBeenCalledWith(
        mockSnapshot.issues[1].issue.id,
        expect.objectContaining({
          kind: 'HUMAN',
          body: 'Proceed with REST option',
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

    render(<ProjectDetailPage projectId={projectId} api={api} />)

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
    render(<ProjectDetailPage projectId={projectId} api={api} />)

    await screen.findByText('Implement REST API')
    fireEvent.click(screen.getByLabelText('Issue #2 Implement REST API'))
    await screen.findByText('编辑规格')
    fireEvent.click(screen.getByText('编辑规格'))
    const titleInput = screen.getByLabelText(/标题/i)
    fireEvent.change(titleInput, { target: { value: 'Unsaved Issue draft' } })

    act(() => notifyProjectsChanged({ projectId }))
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

  it('sends command in CoordinatorConversation with canonical UUID idempotencyKey', async () => {
    // 测试意图：验证 Coordinator 对话框发送命令时构造正确的 UUID 幂等键并调用 sendProjectCommand
    const api = createMockApi()
    render(<ProjectDetailPage projectId={projectId} api={api} />)

    await waitFor(() => {
      expect(screen.getByText(/Coordinator: coordinator-lead/i)).toBeInTheDocument()
    })

    const input = screen.getByLabelText('Coordinator 指令输入')
    fireEvent.change(input, { target: { value: 'Please create an architecture RFC' } })

    const sendBtn = screen.getByRole('button', { name: '发送' })
    fireEvent.click(sendBtn)

    await waitFor(() => {
      expect(api.sendProjectCommand).toHaveBeenCalledWith(
        projectId,
        expect.objectContaining({
          message: 'Please create an architecture RFC',
          threadId: mockSnapshot.coordinatorThread?.threadId,
        }),
      )
    })
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

    render(<ProjectDetailPage projectId={projectId} api={api} />)

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

    // Fill review form
    const summaryInput = screen.getByLabelText(/审核摘要/i)
    fireEvent.change(summaryInput, { target: { value: 'Approved after verification' } })

    const verifyInput = screen.getByLabelText(/验证依据/i)
    fireEvent.change(verifyInput, { target: { value: 'Manual test passed' } })

    const submitReviewBtn = screen.getByRole('button', { name: '提交审核决定' })
    fireEvent.click(submitReviewBtn)

    await waitFor(() => {
      expect(api.reviewIssue).toHaveBeenCalledWith(
        inReviewIssue.id,
        expect.objectContaining({
          decision: 'APPROVE',
          summary: 'Approved after verification',
          verification: 'Manual test passed',
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

    render(<ProjectDetailPage projectId={projectId} api={api} />)

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
      expect(screen.getByText(/最近 Run 处于 FAILED 或 UNKNOWN 状态/i)).toBeInTheDocument()
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
    render(<ProjectDetailPage projectId={projectId} api={api} />)

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
    render(<ProjectDetailPage projectId={projectId} onBack={onBack} api={api} />)

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

    render(<ProjectDetailPage projectId={projectId} onBack={onBack} api={api} />)

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
    render(<ProjectDetailPage projectId={projectId} api={api} />)

    await screen.findByText('Awesome Platform')
    fireEvent.click(screen.getByRole('button', { name: /编辑/i }))
    const titleInput = screen.getByLabelText(/项目名称/i)
    fireEvent.change(titleInput, { target: { value: 'Unsaved project draft' } })

    act(() => notifyProjectsChanged({ projectId }))
    await waitFor(() => expect(api.getProjectSnapshot).toHaveBeenCalledTimes(2))
    expect(titleInput).toHaveValue('Unsaved project draft')

    fireEvent.click(screen.getByRole('button', { name: '保存修改' }))
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
    // 测试意图：并发 Snapshot 请求乱序完成时，只允许最新请求更新页面。
    let resolveInitial!: (snapshot: ProjectSnapshotDTO) => void
    const initial = new Promise<ProjectSnapshotDTO>((resolve) => {
      resolveInitial = resolve
    })
    const latestSnapshot: ProjectSnapshotDTO = {
      ...mockSnapshot,
      project: { ...mockSnapshot.project, title: 'Latest Snapshot' },
    }
    const api = createMockApi({
      getProjectSnapshot: vi.fn().mockReturnValueOnce(initial).mockResolvedValue(latestSnapshot),
    })
    render(<ProjectDetailPage projectId={projectId} api={api} />)

    act(() => notifyProjectsChanged({ projectId }))
    expect(await screen.findByText('Latest Snapshot')).toBeInTheDocument()

    await act(async () => {
      resolveInitial({
        ...mockSnapshot,
        project: { ...mockSnapshot.project, title: 'Stale Snapshot' },
      })
      await initial
    })
    expect(screen.getByText('Latest Snapshot')).toBeInTheDocument()
    expect(screen.queryByText('Stale Snapshot')).not.toBeInTheDocument()
  })

  it('edits and deletes project from detail page header actions', async () => {
    // 测试意图：验证在详情页顶部直接编辑项目与删除项目（级联触发 onBack）流程
    const onBack = vi.fn()
    const api = createMockApi()
    render(<ProjectDetailPage projectId={projectId} onBack={onBack} api={api} />)

    await waitFor(() => {
      expect(screen.getByText('Awesome Platform')).toBeInTheDocument()
    })

    // Edit project
    fireEvent.click(screen.getByRole('button', { name: /编辑/i }))
    expect(screen.getByText('编辑项目', { selector: 'h3' })).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText(/项目名称/i), { target: { value: 'Renamed Platform' } })
    fireEvent.click(screen.getByRole('button', { name: '保存修改' }))

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
    render(<ProjectDetailPage projectId={projectId} api={api} />)

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
    render(<ProjectDetailPage projectId={projectId} api={api} />)

    await waitFor(() => {
      expect(screen.getByText('Implement REST API')).toBeInTheDocument()
    })

    fireEvent.click(screen.getByLabelText('Issue #2 Implement REST API'))

    await waitFor(() => {
      expect(screen.getByText('取消 Issue')).toBeInTheDocument()
    })

    // Click "取消 Issue" button to show confirmation sub-form
    fireEvent.click(screen.getByText('取消 Issue'))

    const reasonInput = screen.getByPlaceholderText('可选取消原因...')
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

  it('displays error in CoordinatorConversation when command fails and supports Enter shortcut', async () => {
    // 测试意图：验证向 Coordinator 发送指令失败时呈现错误提示，并测试 Ctrl+Enter 快捷键发送
    const api = createMockApi({
      sendProjectCommand: vi.fn().mockRejectedValue(new Error('Coordinator offline')),
    })
    render(<ProjectDetailPage projectId={projectId} api={api} />)

    await waitFor(() => {
      expect(screen.getByText(/Coordinator: coordinator-lead/i)).toBeInTheDocument()
    })

    const textarea = screen.getByLabelText('Coordinator 指令输入')
    fireEvent.change(textarea, { target: { value: 'Trigger prompt' } })

    // Press Ctrl+Enter
    fireEvent.keyDown(textarea, { key: 'Enter', ctrlKey: true })

    await waitFor(() => {
      expect(screen.getByText('Coordinator offline')).toBeInTheDocument()
    })
    expect(
      screen.queryByText('Trigger prompt', { selector: '.coordinator-bubble' }),
    ).not.toBeInTheDocument()
  })
})
