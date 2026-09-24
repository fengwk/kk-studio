import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { IssueDetailModal } from './IssueDetailModal'
import type { ProjectsApi } from '../projects-api'
import type { IssueDetailDTO, IssueEvidenceDTO } from '../types'
import type { StorageService } from '@/shared/api/storage-service'
import type { StorageUploadDTO } from '@/shared/api/contracts/storage'

vi.mock('@/shared/api/agent-service', () => ({
  agentService: {
    listAgents: vi.fn().mockResolvedValue({
      results: [
        { name: 'auth-developer' },
        { name: 'security-reviewer' },
        { name: 'next-agent' },
      ],
      totalCount: 3,
      pageNumber: 1,
      pageSize: 50,
    }),
  },
}))

function renderModal(ui: React.ReactElement, client?: QueryClient) {
  const queryClient = client ?? new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  const rendered = render(
    <QueryClientProvider client={queryClient}>
      {ui}
    </QueryClientProvider>,
  )
  return { ...rendered, queryClient }
}

describe('IssueDetailModal', () => {
  const projectId = 'proj-00000000-0000-0000-0000-000000000001'
  const issueId = 'issue-00000000-0000-0000-0000-000000000001'

  const mockActiveIssueDetail: IssueDetailDTO = {
    issue: {
      id: issueId,
      projectId,
      number: '12',
      title: 'Implement OAuth2 login',
      description: 'Support GitHub and Google OAuth2 providers',
      status: 'IN_PROGRESS',
      assigneeAgentName: 'auth-developer',
      reviewerAgentName: 'security-reviewer',
      version: '3',
      archivedAt: null,
      createdAt: '2026-09-20T10:00:00Z',
      updatedAt: '2026-09-20T11:00:00Z',
    },
    blocked: false,
    dependencies: [],
    activities: [
      {
        id: 'act-1',
        issueId,
        sequence: '1',
        actorType: 'HUMAN',
        actorName: 'Lead Architect',
        kind: 'INSTRUCTION',
        body: 'Use standard authorization code flow with PKCE.',
        targetRole: 'EXECUTOR',
        createdAt: '2026-09-20T10:05:00Z',
      },
    ],
    sessions: [
      {
        id: 'sess-item-1',
        issueId,
        agentName: 'auth-developer',
        role: 'EXECUTOR',
        sessionId: 'session-auth-dev-uuid',
        branchId: 'branch-auth-dev-uuid',
        createdAt: '2026-09-20T10:01:00Z',
      },
      {
        id: 'sess-item-2',
        issueId,
        agentName: 'security-reviewer',
        role: 'REVIEWER',
        sessionId: 'session-sec-rev-uuid',
        branchId: 'branch-sec-rev-uuid',
        createdAt: '2026-09-20T10:02:00Z',
      },
    ],
    runs: [
      {
        id: 'run-1',
        issueId,
        ordinal: '1',
        role: 'EXECUTOR',
        actorType: 'AGENT',
        agentName: 'auth-developer',
        submissionRunId: null,
        status: 'RUNNING',
        outcome: null,
        waitingReason: null,
        createdAt: '2026-09-20T10:10:00Z',
        completedAt: null,
      },
    ],
    currentRun: {
      id: 'run-1',
      issueId,
      ordinal: '1',
      role: 'EXECUTOR',
      actorType: 'AGENT',
      agentName: 'auth-developer',
      submissionRunId: null,
      status: 'RUNNING',
      outcome: null,
      waitingReason: null,
      createdAt: '2026-09-20T10:10:00Z',
      completedAt: null,
    },
    latestRun: null,
    evidence: [],
  }

  const mockBlockedIssueDetail: IssueDetailDTO = {
    ...mockActiveIssueDetail,
    issue: {
      ...mockActiveIssueDetail.issue,
      status: 'BLOCKED',
      version: '4',
    },
    blocked: true,
  }

  const createMockApi = (detail: IssueDetailDTO, overrides: Partial<ProjectsApi> = {}): ProjectsApi => ({
    listProjects: vi.fn(),
    createProject: vi.fn(),
    getProject: vi.fn(),
    updateProject: vi.fn(),
    deleteProject: vi.fn(),
    archiveProject: vi.fn(),
    unarchiveProject: vi.fn(),
    getProjectSnapshot: vi.fn(),
    createIssue: vi.fn(),
    getIssue: vi.fn().mockResolvedValue(detail),
    listActivities: vi.fn().mockResolvedValue([]),
    updateIssue: vi.fn().mockResolvedValue(detail.issue),
    changeIssueStatus: vi.fn().mockResolvedValue(detail.issue),
    blockIssue: vi.fn().mockResolvedValue(undefined),
    recoverIssue: vi.fn().mockResolvedValue(undefined),
    addIssueDependency: vi.fn().mockResolvedValue({
      issueId: detail.issue.id,
      dependsOnIssueId: 'dep-1',
      projectId,
      createdAt: '2026-09-20T00:00:00Z',
    }),
    removeIssueDependency: vi.fn().mockResolvedValue(undefined),
    appendIssueActivity: vi.fn().mockImplementation((_id, req) =>
      Promise.resolve({
        id: 'act-2',
        issueId: detail.issue.id,
        sequence: '2',
        actorType: 'HUMAN',
        actorName: 'Operator',
        kind: 'COMMENT',
        body: req.body,
        targetRole: req.targetRole ?? null,
        createdAt: '2026-09-20T11:30:00Z',
      }),
    ),
    reviewIssue: vi.fn().mockResolvedValue(undefined),
    cancelIssue: vi.fn().mockResolvedValue({ ...detail.issue, status: 'CANCELED' }),
    retryIssue: vi.fn().mockResolvedValue(detail.activities[0]),
    archiveIssue: vi.fn().mockResolvedValue({ ...detail.issue, archivedAt: 'now' }),
    unarchiveIssue: vi.fn().mockResolvedValue({ ...detail.issue, archivedAt: null }),
    addIssueEvidence: vi.fn().mockResolvedValue({
      issueId: detail.issue.id,
      blobId: 'blob-default',
      uri: 'kkstudio:/resources/blob-default',
      origin: 'HUMAN',
      name: 'evidence.png',
      runId: null,
      publishedAt: '2026-09-20T12:00:00Z',
    }),
    ...overrides,
  })

  it('blocks an active issue with expectedVersion and required reason', async () => {
    // 测试意图：验证非终态且未阻塞的 Issue 可触发人工阻塞，校验必填理由并调用 blockIssue 接口
    const onUpdated = vi.fn()
    const api = createMockApi(mockActiveIssueDetail)

    renderModal(
      <IssueDetailModal
        isOpen={true}
        issueId={issueId}
        projectId={projectId}
        onClose={vi.fn()}
        onUpdated={onUpdated}
        api={api}
      />,
    )

    await waitFor(() => {
      expect(screen.getByText(/Implement OAuth2 login/i)).toBeInTheDocument()
      expect(screen.getByRole('button', { name: /阻塞 Issue/i })).toBeInTheDocument()
    })

    // Click "阻塞 Issue" button
    fireEvent.click(screen.getByRole('button', { name: /阻塞 Issue/i }))

    expect(screen.getByText('显式阻塞 Issue')).toBeInTheDocument()

    // Submit button should be disabled without reason
    const confirmBlockBtn = screen.getByRole('button', { name: '确认阻塞' })
    expect(confirmBlockBtn).toBeDisabled()

    // Enter block reason
    const reasonInput = screen.getByPlaceholderText(/详细说明业务障碍或待决问题/i)
    fireEvent.change(reasonInput, { target: { value: 'Waiting for legal compliance on third-party OAuth' } })
    expect(confirmBlockBtn).not.toBeDisabled()

    fireEvent.click(confirmBlockBtn)

    await waitFor(() => {
      expect(api.blockIssue).toHaveBeenCalledWith(issueId, {
        expectedVersion: '3',
        reason: 'Waiting for legal compliance on third-party OAuth',
      })
      expect(onUpdated).toHaveBeenCalled()
    })
  })

  it('recovers a blocked issue to TODO with optional comment', async () => {
    // 测试意图：验证处于阻塞态的 Issue 提供恢复按钮，默认恢复至 TODO 并向 recoverIssue 提交 expectedVersion 与 comment
    const onUpdated = vi.fn()
    const api = createMockApi(mockBlockedIssueDetail)

    renderModal(
      <IssueDetailModal
        isOpen={true}
        issueId={issueId}
        projectId={projectId}
        onClose={vi.fn()}
        onUpdated={onUpdated}
        api={api}
      />,
    )

    await waitFor(() => {
      expect(screen.getByText(/Implement OAuth2 login/i)).toBeInTheDocument()
      expect(screen.getAllByRole('button', { name: /恢复 Issue/i }).length).toBeGreaterThanOrEqual(1)
    })

    fireEvent.click(screen.getAllByRole('button', { name: /恢复 Issue/i })[0])

    expect(screen.getByText('人工恢复 Issue')).toBeInTheDocument()

    // Add optional comment
    const commentInput = screen.getByPlaceholderText(/说明恢复原因或指导意见/i)
    fireEvent.change(commentInput, { target: { value: 'Legal compliance approved, unblocked to resume.' } })

    const confirmRecoverBtn = screen.getByRole('button', { name: '确认恢复' })
    fireEvent.click(confirmRecoverBtn)

    await waitFor(() => {
      expect(api.recoverIssue).toHaveBeenCalledWith(issueId, {
        expectedVersion: '4',
        toBacklog: false,
        comment: 'Legal compliance approved, unblocked to resume.',
      })
      expect(onUpdated).toHaveBeenCalled()
    })
  })

  it('recovers a blocked issue to BACKLOG when selected', async () => {
    // 测试意图：验证恢复 Issue 时可选择恢复至 BACKLOG，后端接口参数 toBacklog 为 true
    const onUpdated = vi.fn()
    const api = createMockApi(mockBlockedIssueDetail)

    renderModal(
      <IssueDetailModal
        isOpen={true}
        issueId={issueId}
        projectId={projectId}
        onClose={vi.fn()}
        onUpdated={onUpdated}
        api={api}
      />,
    )

    await waitFor(() => {
      expect(screen.getAllByRole('button', { name: /恢复 Issue/i }).length).toBeGreaterThanOrEqual(1)
    })

    fireEvent.click(screen.getAllByRole('button', { name: /恢复 Issue/i })[0])

    // Select toBacklog radio
    const backlogRadio = screen.getByRole('radio', { name: /恢复至 BACKLOG/i })
    fireEvent.click(backlogRadio)

    fireEvent.click(screen.getByRole('button', { name: '确认恢复' }))

    await waitFor(() => {
      expect(api.recoverIssue).toHaveBeenCalledWith(issueId, {
        expectedVersion: '4',
        toBacklog: true,
        comment: null,
      })
      expect(onUpdated).toHaveBeenCalled()
    })
  })

  it('renders Agent sessions list with independent roles, branches, and session IDs', async () => {
    // 测试意图：验证 Issue 规格页面展示独立的 per-Agent sessions 归属信息，包含角色、分支及 Session ID
    const api = createMockApi(mockActiveIssueDetail)

    renderModal(
      <IssueDetailModal
        isOpen={true}
        issueId={issueId}
        projectId={projectId}
        onClose={vi.fn()}
        onUpdated={vi.fn()}
        api={api}
      />,
    )

    await waitFor(() => {
      expect(screen.getByText('Agent 稳定归属与工作分支 (Sessions)')).toBeInTheDocument()
    })

    // EXECUTOR session
    expect(screen.getByText('执行角色: auth-developer')).toBeInTheDocument()
    expect(screen.getByText('session-auth-dev-uuid')).toBeInTheDocument()
    expect(screen.getByText(/branch-a/i)).toBeInTheDocument()

    // REVIEWER session
    expect(screen.getByText('审查角色: security-reviewer')).toBeInTheDocument()
    expect(screen.getByText('session-sec-rev-uuid')).toBeInTheDocument()
  })

  it('appends role-targeted activity (@EXECUTOR / @REVIEWER) to activity stream', async () => {
    // 测试意图：验证活动流标签页支持选择目标职责（targetRole），提交后触发 appendIssueActivity 并在列表中呈现
    const api = createMockApi(mockActiveIssueDetail)

    renderModal(
      <IssueDetailModal
        isOpen={true}
        issueId={issueId}
        projectId={projectId}
        onClose={vi.fn()}
        onUpdated={vi.fn()}
        api={api}
      />,
    )

    await waitFor(() => {
      expect(screen.getByText(/Implement OAuth2 login/i)).toBeInTheDocument()
    })

    // Navigate to activities tab
    fireEvent.click(screen.getByRole('button', { name: /^活动流/ }))

    await waitFor(() => {
      expect(screen.getByText('Use standard authorization code flow with PKCE.')).toBeInTheDocument()
      expect(screen.getAllByText(/@EXECUTOR/).length).toBeGreaterThanOrEqual(1)
    })

    // Target @REVIEWER
    const targetSelect = screen.getByLabelText('目标职责')
    fireEvent.change(targetSelect, { target: { value: 'REVIEWER' } })

    // Input activity content
    const contentInput = screen.getByLabelText('活动内容')
    fireEvent.change(contentInput, { target: { value: 'Please inspect CSRF state parameter validation closely.' } })

    // Submit
    fireEvent.click(screen.getByRole('button', { name: '追加活动' }))

    await waitFor(() => {
      expect(api.appendIssueActivity).toHaveBeenCalledWith(issueId, {
        body: 'Please inspect CSRF state parameter validation closely.',
        targetRole: 'REVIEWER',
        idempotencyKey: expect.any(String),
      })
      expect(screen.getByText('Please inspect CSRF state parameter validation closely.')).toBeInTheDocument()
    })
  })

  it('renders controlled Agent selection in spec editing form (~line 737) and preserves stale assignments', async () => {
    // 测试意图：验证 IssueDetailModal 规格编辑模式下，执行 Agent 与审查 Agent 均为受控原生 select，透明保留陈旧名称且仅能选择有效 catalog agent 或空白
    const staleDetail: IssueDetailDTO = {
      ...mockActiveIssueDetail,
      issue: {
        ...mockActiveIssueDetail.issue,
        assigneeAgentName: 'stale-executor-agent',
        reviewerAgentName: 'stale-reviewer-agent',
      },
    }

    const api = createMockApi(staleDetail, {
      updateIssue: vi.fn().mockResolvedValue({
        ...staleDetail.issue,
        assigneeAgentName: 'next-agent',
        reviewerAgentName: null,
      }),
    })

    renderModal(
      <IssueDetailModal
        isOpen={true}
        issueId={issueId}
        projectId={projectId}
        onClose={vi.fn()}
        onUpdated={vi.fn()}
        api={api}
      />,
    )

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /Implement OAuth2 login/i })).toBeInTheDocument()
    })

    // 进入规格编辑模式
    const editSpecBtn = screen.getByRole('button', { name: /编辑规格/i })
    fireEvent.click(editSpecBtn)

    const assigneeSelect = screen.getByLabelText(/执行 Agent/i) as HTMLSelectElement
    const reviewerSelect = screen.getByLabelText(/审查 Agent/i) as HTMLSelectElement

    expect(assigneeSelect.tagName.toLowerCase()).toBe('select')
    expect(reviewerSelect.tagName.toLowerCase()).toBe('select')

    // 初始值透明保留历史已分配名称
    expect(assigneeSelect.value).toBe('stale-executor-agent')
    expect(reviewerSelect.value).toBe('stale-reviewer-agent')

    // 验证下拉包含陈旧名称、catalog 名称及空白项
    await waitFor(() => {
      const assigneeOptionValues = Array.from(assigneeSelect.options).map((o) => o.value)
      expect(assigneeOptionValues).toContain('stale-executor-agent')
      expect(assigneeOptionValues).toContain('next-agent')
      expect(assigneeOptionValues).toContain('auth-developer')
      expect(assigneeOptionValues).toContain('')
    })

    // 更改选择：执行者选择已知 next-agent，审查者置空为人工审核
    fireEvent.change(assigneeSelect, { target: { value: 'next-agent' } })
    fireEvent.change(reviewerSelect, { target: { value: '' } })

    // 保存修改
    const saveBtn = screen.getByRole('button', { name: '保存修改' })
    fireEvent.click(saveBtn)

    await waitFor(() => {
      expect(api.updateIssue).toHaveBeenCalledWith(issueId, {
        expectedVersion: staleDetail.issue.version,
        title: staleDetail.issue.title,
        description: staleDetail.issue.description,
        assigneeAgentName: 'next-agent',
        reviewerAgentName: null,
      })
    })
  })

  const createMockStorageService = (
    overrides: Partial<StorageService> = {},
  ): StorageService => ({
    reserveUpload: vi.fn().mockResolvedValue({
      id: 'upload-id-1',
      state: 'PENDING',
      sha256: 'abc123sha256',
      mediaType: 'image/png',
      sizeBytes: 1024,
      presignedPut: {
        url: 'https://upload.example.com/put',
        headers: {},
        expiresAt: '2026-09-20T12:00:00Z',
      },
    }),
    completeUpload: vi.fn().mockResolvedValue({
      id: 'upload-id-1',
      state: 'READY',
      sha256: 'abc123sha256',
      mediaType: 'image/png',
      sizeBytes: 1024,
      presignedPut: null,
    }),
    uploadFile: vi.fn().mockResolvedValue(undefined),
    deleteUpload: vi.fn().mockResolvedValue(undefined),
    getBlobDownloadUrl: vi.fn().mockResolvedValue({
      url: 'https://download.example.com/file.png',
      mediaType: 'image/png',
      sizeBytes: 1024,
      expiresAt: '2026-09-20T13:00:00Z',
    }),
    getBlobPreviewUrl: vi.fn().mockResolvedValue({
      url: 'https://preview.example.com/file.png',
      mediaType: 'image/png',
      sizeBytes: 1024,
      expiresAt: '2026-09-20T13:00:00Z',
    }),
    ...overrides,
  })

  it('displays evidence list with origin badges and URI, fetching download URL only on click', async () => {
    // 测试意图：验证证据列表正常展示来源徽章、名称与显式 URI，且仅在用户点击时按需换取原件下载地址
    const clickSpy = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    const evidenceList: IssueEvidenceDTO[] = [
      {
        issueId,
        blobId: 'blob-human-1',
        uri: 'kkstudio:/resources/blob-human-1',
        origin: 'HUMAN',
        name: 'test-evidence.png',
        runId: null,
        publishedAt: '2026-09-20T11:00:00Z',
      },
      {
        issueId,
        blobId: 'blob-exec-2',
        uri: 'kkstudio:/resources/blob-exec-2',
        origin: 'EXECUTOR',
        name: null,
        runId: 'run-exec-12345678-abcd',
        publishedAt: '2026-09-20T11:05:00Z',
      },
    ]

    const detailWithEvidence: IssueDetailDTO = {
      ...mockActiveIssueDetail,
      evidence: evidenceList,
    }

    const api = createMockApi(detailWithEvidence)
    const storageService = createMockStorageService()

    renderModal(
      <IssueDetailModal
        isOpen={true}
        issueId={issueId}
        projectId={projectId}
        onClose={vi.fn()}
        onUpdated={vi.fn()}
        api={api}
        storageService={storageService}
      />,
    )

    await waitFor(() => {
      expect(screen.getByText('已发布证据 (2)')).toBeInTheDocument()
    })

    // 切换到已发布证据标签页
    fireEvent.click(screen.getByText('已发布证据 (2)'))

    // 验证展示来源与名称
    expect(screen.getByText('人工上传')).toBeInTheDocument()
    expect(screen.getByText('test-evidence.png')).toBeInTheDocument()
    expect(screen.getByText('kkstudio:/resources/blob-human-1')).toBeInTheDocument()

    expect(screen.getByText('执行者交付')).toBeInTheDocument()
    expect(screen.getAllByText('kkstudio:/resources/blob-exec-2')).toHaveLength(2)
    expect(screen.getByText(/关联 Run: #run-exec/)).toBeInTheDocument()

    // 验证尚未调用下载 URL
    expect(storageService.getBlobDownloadUrl).not.toHaveBeenCalled()

    // 点击第一项下载原件
    const downloadBtns = screen.getAllByRole('button', { name: /下载/i })
    fireEvent.click(downloadBtns[0])

    await waitFor(() => {
      expect(storageService.getBlobDownloadUrl).toHaveBeenCalledWith('blob-human-1')
      expect(clickSpy).toHaveBeenCalled()
    })

    clickSpy.mockRestore()
  })

  it('filters out evidence from unrelated issues (no cross-issue display)', async () => {
    // 测试意图：隔离安全校验，确保绝不展示非当前 Issue 的证据项
    const evidenceList: IssueEvidenceDTO[] = [
      {
        issueId,
        blobId: 'blob-current',
        uri: 'kkstudio:/resources/blob-current',
        origin: 'HUMAN',
        name: 'current-issue-evidence.png',
        runId: null,
        publishedAt: '2026-09-20T11:00:00Z',
      },
      {
        issueId: 'unrelated-issue-999',
        blobId: 'blob-foreign',
        uri: 'kkstudio:/resources/blob-foreign',
        origin: 'HUMAN',
        name: 'leaked-foreign-evidence.png',
        runId: null,
        publishedAt: '2026-09-20T11:00:00Z',
      },
    ]

    const detailWithForeignEvidence: IssueDetailDTO = {
      ...mockActiveIssueDetail,
      evidence: evidenceList,
    }

    const api = createMockApi(detailWithForeignEvidence)
    const storageService = createMockStorageService()

    renderModal(
      <IssueDetailModal
        isOpen={true}
        issueId={issueId}
        projectId={projectId}
        onClose={vi.fn()}
        onUpdated={vi.fn()}
        api={api}
        storageService={storageService}
      />,
    )

    await waitFor(() => {
      expect(screen.getByText(/已发布证据/)).toBeInTheDocument()
    })

    fireEvent.click(screen.getByRole('button', { name: /已发布证据/ }))

    expect(screen.getByText('current-issue-evidence.png')).toBeInTheDocument()
    expect(screen.queryByText('leaked-foreign-evidence.png')).not.toBeInTheDocument()
  })

  it('uploads human evidence via storage pipeline and calls addIssueEvidence', async () => {
    // 测试意图：验证人工上传证据流程完整执行预留、直传、完成并调用 addIssueEvidence 发布
    const onUpdated = vi.fn()
    const api = createMockApi(mockActiveIssueDetail)
    const storageService = createMockStorageService()
    const mockHasher = vi.fn().mockResolvedValue('hash-123456')

    renderModal(
      <IssueDetailModal
        isOpen={true}
        issueId={issueId}
        projectId={projectId}
        onClose={vi.fn()}
        onUpdated={onUpdated}
        api={api}
        storageService={storageService}
        hashFile={mockHasher}
      />,
    )

    await waitFor(() => {
      expect(screen.getByText(/规格与状态/)).toBeInTheDocument()
    })

    fireEvent.click(screen.getByRole('button', { name: /已发布证据/ }))

    // 暂无证据提示
    expect(screen.getByText(/暂无已发布证据/)).toBeInTheDocument()

    // 查找上传 input 并触发文件选择
    const file = new File(['evidence-bytes'], 'manual-spec.pdf', { type: 'application/pdf' })
    const fileInput = document.querySelector('input[type="file"]') as HTMLInputElement
    expect(fileInput).toBeTruthy()

    fireEvent.change(fileInput, { target: { files: [file] } })

    await waitFor(() => {
      expect(mockHasher).toHaveBeenCalledWith(file)
      expect(storageService.reserveUpload).toHaveBeenCalledWith({
        filename: 'manual-spec.pdf',
        mediaType: 'application/pdf',
        sizeBytes: file.size,
        sha256: 'hash-123456',
      })
      expect(storageService.uploadFile).toHaveBeenCalled()
      expect(storageService.completeUpload).toHaveBeenCalledWith('upload-id-1')
      expect(api.addIssueEvidence).toHaveBeenCalledWith(issueId, { uploadId: 'upload-id-1' })
      expect(api.getIssue).toHaveBeenCalledTimes(2) // 初始 + refetch
      expect(onUpdated).toHaveBeenCalled()
    })
  })

  it('shows error banner when download URL fetch fails and allows dismissal', async () => {
    // 测试意图：验证下载地址换取失败时展示错误提示，且用户可手动关闭
    const evidenceList: IssueEvidenceDTO[] = [
      {
        issueId,
        blobId: 'blob-err-1',
        uri: 'kkstudio:/resources/blob-err-1',
        origin: 'HUMAN',
        name: 'failed.png',
        runId: null,
        publishedAt: '2026-09-20T11:00:00Z',
      },
    ]

    const api = createMockApi({
      ...mockActiveIssueDetail,
      evidence: evidenceList,
    })
    const storageService = createMockStorageService({
      getBlobDownloadUrl: vi.fn().mockRejectedValue(new Error('未获取到有效下载权限')),
    })

    renderModal(
      <IssueDetailModal
        isOpen={true}
        issueId={issueId}
        projectId={projectId}
        onClose={vi.fn()}
        onUpdated={vi.fn()}
        api={api}
        storageService={storageService}
      />,
    )

    await waitFor(() => {
      expect(screen.getByText(/已发布证据/)).toBeInTheDocument()
    })

    fireEvent.click(screen.getByRole('button', { name: /已发布证据/ }))

    const downloadBtn = screen.getByRole('button', { name: /下载/i })
    fireEvent.click(downloadBtn)

    await waitFor(() => {
      expect(screen.getByText('未获取到有效下载权限')).toBeInTheDocument()
    })

    // 关闭错误提示
    const closeErrorBtn = screen.getByRole('button', { name: '关闭下载错误提示' })
    fireEvent.click(closeErrorBtn)

    await waitFor(() => {
      expect(screen.queryByText('未获取到有效下载权限')).not.toBeInTheDocument()
    })
  })

  it('stale protection: cancels or discards completion if issueId changes before upload completes', async () => {
    // 测试意图：验证当异步上传尚未完成时，若用户切换到其它 Issue，丢弃旧 Issue 的 addIssueEvidence 调用并释放已预留资源
    let resolveReserve: (val: StorageUploadDTO) => void
    const reservePromise = new Promise<StorageUploadDTO>((resolve) => {
      resolveReserve = resolve
    })

    const onUpdated = vi.fn()
    const api = createMockApi(mockActiveIssueDetail)
    const storageService = createMockStorageService({
      reserveUpload: vi.fn().mockImplementation(() => reservePromise),
    })

    const { rerender } = renderModal(
      <IssueDetailModal
        isOpen={true}
        issueId={issueId}
        projectId={projectId}
        onClose={vi.fn()}
        onUpdated={onUpdated}
        api={api}
        storageService={storageService}
        hashFile={vi.fn().mockResolvedValue('hash-123')}
      />,
    )

    await waitFor(() => {
      expect(screen.getByText(/已发布证据/)).toBeInTheDocument()
    })
    fireEvent.click(screen.getByRole('button', { name: /已发布证据/ }))

    const file = new File(['evidence'], 'evidence.png', { type: 'image/png' })
    const fileInput = document.querySelector('input[type="file"]') as HTMLInputElement
    fireEvent.change(fileInput, { target: { files: [file] } })

    // 等待预留已发起（哈希已完成，但 reserveUpload 挂起）
    await waitFor(() => {
      expect(storageService.reserveUpload).toHaveBeenCalled()
    })

    // 在异步 reserve 仍在挂起时，修改 props 将 issueId 切换到 issue-2
    const nextIssueId = 'issue-00000000-0000-0000-0000-000000000002'
    rerender(
      <QueryClientProvider
        client={
          new QueryClient({
            defaultOptions: { queries: { retry: false } },
          })
        }
      >
        <IssueDetailModal
          isOpen={true}
          issueId={nextIssueId}
          projectId={projectId}
          onClose={vi.fn()}
          onUpdated={onUpdated}
          api={api}
          storageService={storageService}
        />
      </QueryClientProvider>,
    )

    // 现在 resolve 旧的 reservePromise
    resolveReserve!({
      id: 'stale-upload-id',
      state: 'PENDING',
      sha256: 'hash-123',
      mediaType: 'image/png',
      sizeBytes: 8,
      presignedPut: {
        url: 'https://upload.example.com',
        headers: {},
        expiresAt: '2026-09-20T12:00:00Z',
      },
    })

    // 等待 microtask 执行完毕
    await new Promise((r) => setTimeout(r, 50))

    // 验证：绝不向旧的 issueId 发送 addIssueEvidence，并对已预留的上传句柄进行释放 (deleteUpload)
    expect(api.addIssueEvidence).not.toHaveBeenCalled()
    expect(storageService.deleteUpload).toHaveBeenCalledWith('stale-upload-id')
  })
})
