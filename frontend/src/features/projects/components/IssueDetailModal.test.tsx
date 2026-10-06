import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { IssueDetailModal } from './IssueDetailModal'
import type { ProjectsApi } from '../projects-api'
import type { IssueDetailDTO } from '../types'
import type { StorageService } from '@/shared/api/storage-service'
import type { StorageUploadDTO, StorageUploadResultDTO } from '@/shared/api/contracts/storage'
import { ApiError } from '@/shared/api/client'
import {
  loadPendingAction,
  pendingActionStorageKey,
  storePendingAction,
} from '../pending-action-sidecar'

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
  beforeEach(() => {
    window.localStorage.clear()
    vi.clearAllMocks()
  })

  const projectId = 'proj-00000000-0000-0000-0000-000000000001'
  const issueId = 'issue-00000000-0000-0000-0000-000000000001'

  const mockActiveIssueDetail: IssueDetailDTO = {
    issue: {
      id: issueId,
      projectId,
      number: '12',
      title: 'Implement OAuth2 login',
      description: 'Support GitHub and Google OAuth2 providers',
      state: 'IN_PROGRESS',
      paused: false,
      version: '3',
      archivedAt: null,
      createdAt: '2026-09-20T10:00:00Z',
      updatedAt: '2026-09-20T11:00:00Z',
    },
    activities: [
      {
        id: 'act-1',
        issueId,
        sequence: '1',
        actorType: 'HUMAN',
        actorName: 'Lead Architect',
        kind: 'INSTRUCTION',
        body: 'Use standard authorization code flow with PKCE.',
        createdAt: '2026-09-20T10:05:00Z',
      },
    ],
    agentThreads: [
      {
        id: 'thread-item-1',
        issueId,
        agentName: 'auth-developer',
        threadId: 'thread-auth-dev-uuid',
        createdAt: '2026-09-20T10:01:00Z',
      },
    ],
    runs: [
      {
        id: 'run-1',
        issueId,
        ordinal: '1',
        state: 'IN_PROGRESS',
        agentName: 'auth-developer',
        status: 'RUNNING',
        startEntryId: 'entry-001',
        endEntryId: null,
        remainingExecutionMs: '120000',
        createdAt: '2026-09-20T10:01:00Z',
        updatedAt: '2026-09-20T10:02:00Z',
      },
    ],
    stageBudgets: [
      {
        issueId,
        state: 'IN_PROGRESS',
        maxRuns: '5',
        usedRuns: '1',
        remainingRuns: '4',
        budgetAfterOrdinal: '0',
      },
    ],
    evidences: [
      {
        id: 'ev-1',
        issueId,
        storageKey: 'evidence/oauth2-design.png',
        mimeType: 'image/png',
        sizeBytes: '204800',
        description: 'Architecture diagram',
        createdAt: '2026-09-20T10:15:00Z',
      },
    ],
  }

  const createMockApi = (overrides: Partial<ProjectsApi> = {}): ProjectsApi => ({
    listProjects: vi.fn(),
    createProject: vi.fn(),
    getProject: vi.fn(),
    updateProject: vi.fn(),
    updateWorkflow: vi.fn(),
    deleteProject: vi.fn(),
    archiveProject: vi.fn(),
    unarchiveProject: vi.fn(),
    getProjectSnapshot: vi.fn(),
    createIssue: vi.fn(),
    getIssue: vi.fn().mockResolvedValue(mockActiveIssueDetail),
    updateIssue: vi.fn().mockResolvedValue(mockActiveIssueDetail.issue),
    transitionIssue: vi.fn().mockResolvedValue(mockActiveIssueDetail.issue),
    blockIssue: vi.fn().mockResolvedValue(mockActiveIssueDetail.issue),
    recoverIssue: vi.fn().mockResolvedValue(mockActiveIssueDetail.issue),
    reopenIssue: vi.fn().mockResolvedValue(mockActiveIssueDetail.issue),
    resolveUnknown: vi.fn().mockResolvedValue(mockActiveIssueDetail.issue),
    pauseIssue: vi.fn().mockResolvedValue(mockActiveIssueDetail.issue),
    stopIssue: vi.fn().mockResolvedValue(mockActiveIssueDetail.issue),
    resetStageBudget: vi.fn().mockResolvedValue(mockActiveIssueDetail.issue),
    appendIssueActivity: vi.fn().mockResolvedValue(mockActiveIssueDetail.activities[0]),
    addIssueEvidence: vi.fn().mockResolvedValue(mockActiveIssueDetail.evidences[0]),
    deleteIssue: vi.fn().mockResolvedValue(undefined),
    archiveIssue: vi.fn().mockResolvedValue(mockActiveIssueDetail.issue),
    unarchiveIssue: vi.fn().mockResolvedValue(mockActiveIssueDetail.issue),
    ...overrides,
  })

  const mockUploadDTO: StorageUploadDTO = {
    uploadId: 'mock-upload-id',
    storageKey: 'mock-storage-key',
    token: 'mock-token',
    maxSizeBytes: 10485760,
    expiresAt: '2026-09-20T12:00:00Z',
  }

  const createMockStorageService = (): StorageService => ({
    reserveUpload: vi.fn().mockResolvedValue(mockUploadDTO),
    uploadFile: vi.fn().mockResolvedValue(undefined),
    completeUpload: vi.fn().mockResolvedValue(mockUploadDTO as unknown as StorageUploadResultDTO),
    deleteUpload: vi.fn().mockResolvedValue(undefined),
    getBlobDownloadUrl: vi.fn().mockResolvedValue({ url: 'https://storage.example.com/download/blob-1', expiresAt: '2026-09-27T00:00:00Z', sizeBytes: 1024, mediaType: 'image/png' }),
    getBlobPreviewUrl: vi.fn().mockResolvedValue({ url: 'https://storage.example.com/preview/blob-1', expiresAt: '2026-09-27T00:00:00Z' }),
  })

  it('renders issue detail header, tabs, and natural state token', async () => {
    // 测试意图：验证加载展示 Issue 标题、状态 natural token、版本号以及 Tab 分组
    const api = createMockApi()
    renderModal(
      <IssueDetailModal
        isOpen={true}
        issueId={issueId}
        onClose={vi.fn()}
        onIssueUpdated={vi.fn()}
        api={api}
        storageService={createMockStorageService()}
      />,
    )

    expect(await screen.findByLabelText('Issue #12 详情')).toBeInTheDocument()
    expect(screen.getAllByText('IN_PROGRESS').length).toBeGreaterThanOrEqual(1)
    expect(screen.getByText('期望版本号:')).toBeInTheDocument()
    expect(screen.getByText('3')).toBeInTheDocument()
    expect(screen.getByText('需求事实')).toBeInTheDocument()
    expect(screen.getByText(/活动时间线/)).toBeInTheDocument()
    expect(screen.getByText(/Run 报告/)).toBeInTheDocument()
    expect(screen.getByText(/阶段预算/)).toBeInTheDocument()
    expect(screen.getByText(/Agent 线程/)).toBeInTheDocument()
  })

  it('posts COMMENT activity with frozen requestKey', async () => {
    // 测试意图：验证在活动时间线发布 COMMENT，调用 appendIssueActivity 传递 kind=COMMENT 和 requestKey
    const api = createMockApi()
    renderModal(
      <IssueDetailModal
        isOpen={true}
        issueId={issueId}
        onClose={vi.fn()}
        onIssueUpdated={vi.fn()}
        api={api}
        storageService={createMockStorageService()}
      />,
    )

    await screen.findByLabelText('Issue #12 详情')
    // 切换到活动时间线
    fireEvent.click(screen.getByRole('button', { name: /活动时间线/i }))

    const textarea = screen.getByPlaceholderText(/添加一条讨论或事实备注/i)
    fireEvent.change(textarea, { target: { value: 'This looks solid.' } })

    const postBtn = screen.getByRole('button', { name: '发表评论' })
    fireEvent.click(postBtn)

    await waitFor(() => {
      expect(api.appendIssueActivity).toHaveBeenCalledWith(
        issueId,
        expect.objectContaining({
          expectedVersion: '3',
          kind: 'COMMENT',
          body: 'This looks solid.',
          requestKey: expect.any(String),
        }),
      )
    })
  })

  it('posts INSTRUCTION activity when switching segmented toggle', async () => {
    // 测试意图：验证在活动时间线切换为 INSTRUCTION 类型后，正确发送人类指令
    const api = createMockApi()
    renderModal(
      <IssueDetailModal
        isOpen={true}
        issueId={issueId}
        onClose={vi.fn()}
        onIssueUpdated={vi.fn()}
        api={api}
        storageService={createMockStorageService()}
      />,
    )

    await screen.findByLabelText('Issue #12 详情')
    fireEvent.click(screen.getByRole('button', { name: /活动时间线/i }))

    // 切换为 下达指令
    fireEvent.click(screen.getByRole('button', { name: /下达指令/i }))

    const textarea = screen.getByPlaceholderText(/输入指令内容要求当前 Agent 遵循/i)
    fireEvent.change(textarea, { target: { value: 'Please switch to JWT.' } })

    const postBtn = screen.getByRole('button', { name: '派发指令' })
    fireEvent.click(postBtn)

    await waitFor(() => {
      expect(api.appendIssueActivity).toHaveBeenCalledWith(
        issueId,
        expect.objectContaining({
          expectedVersion: '3',
          kind: 'INSTRUCTION',
          body: 'Please switch to JWT.',
          requestKey: expect.any(String),
        }),
      )
    })
  })

  it('opens reset budget dialog and resets stage budget with state and maxRuns', async () => {
    // 测试意图：验证阶段预算面板可以打开重置对话框，并调用 resetStageBudget API
    const api = createMockApi()
    renderModal(
      <IssueDetailModal
        isOpen={true}
        issueId={issueId}
        onClose={vi.fn()}
        onIssueUpdated={vi.fn()}
        api={api}
        storageService={createMockStorageService()}
      />,
    )

    await screen.findByLabelText('Issue #12 详情')
    fireEvent.click(screen.getByRole('button', { name: /阶段预算/i }))

    // budgetAfterOrdinal 是排除边界，不是已用次数；新额度只计入此序号之后的 Run。
    expect(screen.getByText('额度从此序号后起算:').parentElement).toHaveTextContent('#0')

    // 点击重置阶段预算
    fireEvent.click(screen.getByRole('button', { name: '重置阶段预算' }))

    expect(screen.getByRole('heading', { name: '重置阶段预算' })).toBeInTheDocument()

    // 填写最大预算
    const maxRunsInput = screen.getByLabelText(/最大 Run 额度/i)
    fireEvent.change(maxRunsInput, { target: { value: '10' } })

    // 提交重置
    const confirmBtn = screen.getByRole('button', { name: '确认重置' })
    fireEvent.click(confirmBtn)

    await waitFor(() => {
      expect(api.resetStageBudget).toHaveBeenCalledWith(
        issueId,
        expect.objectContaining({
          expectedVersion: '3',
          state: 'IN_PROGRESS',
          maxRuns: 10,
          requestKey: expect.any(String),
        }),
      )
    })
  })

  it('opens block issue dialog and submits blockReason', async () => {
    // 测试意图：验证业务阻塞操作弹窗并向 blockIssue 传递 reason 与 frozen requestKey
    const api = createMockApi()
    renderModal(
      <IssueDetailModal
        isOpen={true}
        issueId={issueId}
        onClose={vi.fn()}
        onIssueUpdated={vi.fn()}
        api={api}
        storageService={createMockStorageService()}
      />,
    )

    await screen.findByLabelText('Issue #12 详情')
    fireEvent.click(screen.getByRole('button', { name: '业务阻塞' }))

    expect(screen.getByRole('heading', { name: /标记业务阻塞/i })).toBeInTheDocument()
    const reasonInput = screen.getByPlaceholderText(/说明导致 Issue 无法继续执行的外部原因/i)
    fireEvent.change(reasonInput, { target: { value: 'Waiting for upstream API key' } })

    fireEvent.click(screen.getByRole('button', { name: '确认阻塞' }))

    await waitFor(() => {
      expect(api.blockIssue).toHaveBeenCalledWith(
        issueId,
        expect.objectContaining({
          expectedVersion: '3',
          reason: 'Waiting for upstream API key',
          requestKey: expect.any(String),
        }),
      )
    })
  })

  it('opens stop issue dialog and submits stop action', async () => {
    // 测试意图：验证人工停止当前阶段执行操作弹窗并向 stopIssue 传递 detail 与 requestKey
    const api = createMockApi()
    renderModal(
      <IssueDetailModal
        isOpen={true}
        issueId={issueId}
        onClose={vi.fn()}
        onIssueUpdated={vi.fn()}
        api={api}
        storageService={createMockStorageService()}
      />,
    )

    await screen.findByLabelText('Issue #12 详情')
    fireEvent.click(screen.getByRole('button', { name: '终止 (Stop)' }))

    expect(screen.getByRole('heading', { name: /终止当前运行/i })).toBeInTheDocument()
    const detailInput = screen.getByPlaceholderText(/说明人工中止执行的原因/i)
    fireEvent.change(detailInput, { target: { value: 'Emergency cancellation' } })

    fireEvent.click(screen.getByRole('button', { name: '确认终止' }))

    await waitFor(() => {
      expect(api.stopIssue).toHaveBeenCalledWith(
        issueId,
        expect.objectContaining({
          expectedVersion: '3',
          detail: 'Emergency cancellation',
          requestKey: expect.any(String),
        }),
      )
    })
  })

  it('resolves UNKNOWN issue state via human verification dialog', async () => {
    // 测试意图：当 Issue 状态为 UNKNOWN 时，验证打开 resolve-unknown 人工裁决对话框并正确提交裁决说明
    const unknownIssueDetail: IssueDetailDTO = {
      ...mockActiveIssueDetail,
      issue: {
        ...mockActiveIssueDetail.issue,
        state: 'UNKNOWN',
      },
    }
    const api = createMockApi({
      getIssue: vi.fn().mockResolvedValue(unknownIssueDetail),
    })

    renderModal(
      <IssueDetailModal
        isOpen={true}
        issueId={issueId}
        onClose={vi.fn()}
        onIssueUpdated={vi.fn()}
        api={api}
        storageService={createMockStorageService()}
      />,
    )

    const resolveBtn = await screen.findByRole('button', { name: '人工核查' })
    fireEvent.click(resolveBtn)

    expect(screen.getByRole('heading', { name: /解除 UNKNOWN（人工核查）/i })).toBeInTheDocument()
    const verificationInput = screen.getByPlaceholderText(/说明已核实的内容与外部状态一致性保证/i)
    fireEvent.change(verificationInput, { target: { value: 'Manually verified as safe to continue.' } })

    fireEvent.click(screen.getByRole('button', { name: '确认解除 UNKNOWN' }))

    await waitFor(() => {
      expect(api.resolveUnknown).toHaveBeenCalledWith(
        issueId,
        expect.objectContaining({
          expectedVersion: '3',
          verification: 'Manually verified as safe to continue.',
          requestKey: expect.any(String),
        }),
      )
    })
  })

  it('deletes issue with expectedVersion', async () => {
    // 测试意图：验证删除 Issue 操作弹窗并向 deleteIssue 传递 expectedVersion
    const api = createMockApi()
    const onClose = vi.fn()
    const onIssueUpdated = vi.fn()

    renderModal(
      <IssueDetailModal
        isOpen={true}
        issueId={issueId}
        onClose={onClose}
        onIssueUpdated={onIssueUpdated}
        api={api}
        storageService={createMockStorageService()}
      />,
    )

    await screen.findByLabelText('Issue #12 详情')
    fireEvent.click(screen.getByRole('button', { name: '删除 Issue' }))

    expect(screen.getByText('删除 Issue #12')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '确认删除' }))

    await waitFor(() => {
      expect(api.deleteIssue).toHaveBeenCalledWith(issueId, '3')
      expect(onClose).toHaveBeenCalled()
      expect(onIssueUpdated).toHaveBeenCalled()
    })
  })

  it('authorizes and downloads evidence file via storageService instead of non-existent static path', async () => {
    // 测试意图：验证公开证据 tab 下上传的交付物通过 storageService 授权获取真实下载 URL，不指向无效的 /api/resources/{blobId}
    const api = createMockApi({
      addIssueEvidence: vi.fn().mockResolvedValue({
        issueId,
        blobId: 'blob-real-123',
        name: 'delivered-spec.pdf',
        actorAgentName: 'pm',
        uri: '',
        runId: null,
        createdAt: '2026-09-27T10:00:00Z',
      }),
    })
    const storageService = createMockStorageService()

    renderModal(
      <IssueDetailModal
        isOpen={true}
        issueId={issueId}
        onClose={vi.fn()}
        api={api}
        storageService={storageService}
        hashFile={async () => '0'.repeat(64)}
      />,
    )

    await screen.findByLabelText('Issue #12 详情')
    const evidenceTab = screen.getByText(/公开证据/)
    fireEvent.click(evidenceTab)

    // 通过隐藏的文件 input 模拟上传交付物
    // Dialog 通过 portal 挂到 body，不受 render 容器范围限制。
    const fileInput = document.querySelector('input[type="file"]') as HTMLInputElement
    const file = new File(['mock content'], 'delivered-spec.pdf', { type: 'application/pdf' })
    fireEvent.change(fileInput, { target: { files: [file] } })

    expect(await screen.findByText('delivered-spec.pdf')).toBeInTheDocument()
    await waitFor(() => {
      expect(storageService.getBlobDownloadUrl).toHaveBeenCalledWith('blob-real-123')
    })

    const downloadLink = screen.getByTitle('预览或下载')
    expect(downloadLink).toHaveAttribute('href', 'https://storage.example.com/download/blob-1')
    expect(downloadLink).not.toHaveAttribute('href', '/api/resources/blob-real-123')
  })

  it('triggers onOpenThread callback when clicking open button in agent threads tab', async () => {
    // 测试意图：验证在 Agent 线程 tab 点击“打开”按钮能够触发 onOpenThread 回调并传递对应的 threadId
    const onOpenThread = vi.fn()
    const api = createMockApi({
      getIssue: vi.fn().mockResolvedValue({
        ...mockActiveIssueDetail,
        agentThreads: [
          {
            issueId,
            agentName: 'architect',
            threadId: 'th-arch-001',
          },
        ],
      }),
    })

    renderModal(
      <IssueDetailModal
        isOpen={true}
        issueId={issueId}
        onClose={vi.fn()}
        onOpenThread={onOpenThread}
        api={api}
        storageService={createMockStorageService()}
      />,
    )

    await screen.findByLabelText('Issue #12 详情')
    const threadsTab = screen.getByText(/Agent 线程/)
    fireEvent.click(threadsTab)

    expect(await screen.findByText('architect')).toBeInTheDocument()
    expect(screen.getByText('th-arch-001')).toBeInTheDocument()

    const openBtn = screen.getByTitle('打开此 Agent 线程视图')
    fireEvent.click(openBtn)

    expect(onOpenThread).toHaveBeenCalledWith('th-arch-001')
  })

  const mockWorkflow = {
    states: [
      { state: 'IN_PROGRESS', next: ['REVIEW'] },
    ],
  }

  it('persists transition request to sidecar and displays unknown banner on network failure; supports exact retry (I07)', async () => {
    // 测试意图：网络错误或 500 时，流转操作必须持久化至 sidecar 并显示未知状态横幅，按钮被禁用，支持原样精确重试
    let callCount = 0
    let recordedRequestKey = ''
    const api = createMockApi({
      transitionIssue: vi.fn().mockImplementation((_id, req) => {
        callCount++
        recordedRequestKey = req.requestKey
        if (callCount === 1) {
          return Promise.reject(new Error('Network offline or Gateway Timeout'))
        }
        return Promise.resolve({
          ...mockActiveIssueDetail.issue,
          state: 'REVIEW',
          version: '4',
        })
      }),
    })

    renderModal(
      <IssueDetailModal
        isOpen={true}
        issueId={issueId}
        workflow={mockWorkflow}
        onClose={vi.fn()}
        onIssueUpdated={vi.fn()}
        api={api}
        storageService={createMockStorageService()}
      />,
    )

    await screen.findByLabelText('Issue #12 详情')
    const transitionBtn = screen.getByRole('button', { name: /流转至 REVIEW/i })
    fireEvent.click(transitionBtn)

    // 应该出现未知状态横幅，显示操作类型与基准版本
    const warningTitle = await screen.findByText(/检测到未确认结果的写操作/i)
    expect(warningTitle).toBeInTheDocument()
    const banner = warningTitle.closest('[role="alert"]')!
    expect(banner).toHaveTextContent('TRANSITION')
    expect(banner).toHaveTextContent('3')

    // 验证 localStorage 中保存了未决操作
    const sidecar = loadPendingAction(issueId)
    expect(sidecar.type).toBe('VALID')
    if (sidecar.type === 'VALID') {
      expect(sidecar.action.kind).toBe('TRANSITION')
      expect(sidecar.action.requestKey).toBe(recordedRequestKey)
      expect(sidecar.action.expectedVersion).toBe('3')
    }

    // 此时流转按钮应该被禁用，禁止开启新身份请求
    expect(transitionBtn).toBeDisabled()

    // 点击重试原操作
    const retryBtn = screen.getByRole('button', { name: '重试原操作' })
    fireEvent.click(retryBtn)

    // 验证第二次调用使用的是完全相同的 requestKey 与 expectedVersion
    await waitFor(() => {
      expect(callCount).toBe(2)
      expect(api.transitionIssue).toHaveBeenLastCalledWith(
        issueId,
        expect.objectContaining({
          expectedVersion: '3',
          toState: 'REVIEW',
          requestKey: recordedRequestKey,
        }),
      )
    })

    // 成功后，未知状态横幅消失，sidecar 清除
    await waitFor(() => {
      expect(screen.queryByText(/检测到未确认结果的写操作/i)).toBeNull()
      expect(loadPendingAction(issueId)).toEqual({ type: 'NONE' })
    })
  })

  it('cleans sidecar on 409 conflict without entering unknown banner (I07)', async () => {
    // 测试意图：409 Conflict 表示服务端已明确拒绝，必须清理 sidecar，不展示未决警告
    const api = createMockApi({
      transitionIssue: vi.fn().mockRejectedValue(new ApiError('Version conflict', 409)),
    })

    renderModal(
      <IssueDetailModal
        isOpen={true}
        issueId={issueId}
        workflow={mockWorkflow}
        onClose={vi.fn()}
        onIssueUpdated={vi.fn()}
        api={api}
        storageService={createMockStorageService()}
      />,
    )

    await screen.findByLabelText('Issue #12 详情')
    const transitionBtn = screen.getByRole('button', { name: /流转至 REVIEW/i })
    fireEvent.click(transitionBtn)

    // 出现冲突提示，但没有未知状态横幅
    expect(await screen.findByText(/版本冲突/i)).toBeInTheDocument()
    expect(screen.queryByText(/检测到未确认结果的写操作/i)).toBeNull()
    expect(loadPendingAction(issueId)).toEqual({ type: 'NONE' })
  })

  it('allows explicit discard of unknown action with confirmation warning (I07)', async () => {
    // 测试意图：对于从 storage 恢复的未知操作，用户点击放弃时弹出显式警告，确认后清除 sidecar
    storePendingAction(issueId, {
      issueId,
      kind: 'STOP',
      expectedVersion: '3',
      payload: { detail: 'abandoned task' },
      requestKey: 'req-unknown-stop-1',
      createdAt: new Date().toISOString(),
      isUnknown: true,
    })

    renderModal(
      <IssueDetailModal
        isOpen={true}
        issueId={issueId}
        onClose={vi.fn()}
        onIssueUpdated={vi.fn()}
        api={createMockApi()}
        storageService={createMockStorageService()}
      />,
    )

    await screen.findByLabelText('Issue #12 详情')
    const banner = screen.getByRole('alert')
    expect(banner).toHaveTextContent(/检测到未确认结果的写操作/i)
    expect(banner).toHaveTextContent('STOP')

    // 点击放弃未决操作
    const discardBtn = screen.getByRole('button', { name: '放弃未决操作' })
    fireEvent.click(discardBtn)

    // 应该出现二次确认警示
    expect(screen.getByText(/此操作可能已在服务端执行。放弃后将不再跟踪原请求/i)).toBeInTheDocument()
    const confirmDiscardBtn = screen.getByRole('button', { name: '确认放弃' })
    fireEvent.click(confirmDiscardBtn)

    // 警告消失，sidecar 清除
    await waitFor(() => {
      expect(screen.queryByText(/检测到未确认结果的写操作/i)).toBeNull()
      expect(loadPendingAction(issueId)).toEqual({ type: 'NONE' })
    })
  })

  it('blocks API calls and displays error when storage throws QuotaExceededError on store', async () => {
    // 测试意图：持久化侧车抛出异常时 fail-closed，阻止网络请求并呈现受控错误横幅，保证 0 API 发送
    const api = createMockApi({
      transitionIssue: vi.fn(),
    })

    const setItemSpy = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('QuotaExceededError')
    })

    renderModal(
      <IssueDetailModal
        isOpen={true}
        issueId={issueId}
        workflow={mockWorkflow}
        onClose={vi.fn()}
        onIssueUpdated={vi.fn()}
        api={api}
        storageService={createMockStorageService()}
      />,
    )

    await screen.findByLabelText('Issue #12 详情')
    const transitionBtn = screen.getByRole('button', { name: /流转至 REVIEW/i })
    fireEvent.click(transitionBtn)

    // 提示错误且 API 零调用
    expect(await screen.findByText(/无法保存操作记录，未发送请求/i)).toBeInTheDocument()
    expect(api.transitionIssue).not.toHaveBeenCalled()

    setItemSpy.mockRestore()
  })

  it('presents blocked UI for corrupt storage record and unlocks when discarded', async () => {
    // 测试意图：检测到损坏的未决记录时渲染锁定警告，禁用写操作，支持人工放弃并解锁
    window.localStorage.setItem(pendingActionStorageKey(issueId), 'corrupted non-json content')

    renderModal(
      <IssueDetailModal
        isOpen={true}
        issueId={issueId}
        workflow={mockWorkflow}
        onClose={vi.fn()}
        onIssueUpdated={vi.fn()}
        api={createMockApi()}
        storageService={createMockStorageService()}
      />,
    )

    await screen.findByLabelText('Issue #12 详情')
    const banner = screen.getByRole('alert')
    expect(banner).toHaveTextContent(/检测到损坏的本地未决操作记录/i)

    // 所有写操作按钮被锁定
    const transitionBtn = screen.getByRole('button', { name: /流转至 REVIEW/i })
    expect(transitionBtn).toBeDisabled()

    // 点击放弃损坏记录并解锁
    const discardCorruptBtn = screen.getByRole('button', { name: '放弃损坏记录并解锁' })
    fireEvent.click(discardCorruptBtn)

    // 锁定解除，按钮恢复可用
    await waitFor(() => {
      expect(screen.queryByText(/检测到损坏的本地未决操作记录/i)).toBeNull()
      expect(transitionBtn).not.toBeDisabled()
    })
  })

  it('safely catches storage read exceptions on load and blocks write UI without crashing', async () => {
    // 测试意图：读取本地存储抛出异常时不发生 render crash，呈现受控 blocked UI，且按钮被禁用
    const getItemSpy = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('Storage Access Denied (SecurityError)')
    })

    renderModal(
      <IssueDetailModal
        isOpen={true}
        issueId={issueId}
        workflow={mockWorkflow}
        onClose={vi.fn()}
        onIssueUpdated={vi.fn()}
        api={createMockApi()}
        storageService={createMockStorageService()}
      />,
    )

    await screen.findByLabelText('Issue #12 详情')
    const banner = screen.getByRole('alert')
    expect(banner).toHaveTextContent(/本地存储异常，已锁定该 Issue 的写操作/i)

    const transitionBtn = screen.getByRole('button', { name: /流转至 REVIEW/i })
    expect(transitionBtn).toBeDisabled()

    getItemSpy.mockRestore()
  })

  it('rotates requestKey when payload changes in draft inputs, preventing payload switching under same key (I07)', async () => {
    // 测试意图：每次草稿输入修改后，下一次提交的 requestKey 必须重新生成，杜绝同一 requestKey 下静默更换 payload
    const keys: string[] = []
    const api = createMockApi({
      blockIssue: vi.fn().mockImplementation((_id, req) => {
        keys.push(req.requestKey)
        return Promise.resolve(mockActiveIssueDetail.issue)
      }),
    })

    renderModal(
      <IssueDetailModal
        isOpen={true}
        issueId={issueId}
        onClose={vi.fn()}
        onIssueUpdated={vi.fn()}
        api={api}
        storageService={createMockStorageService()}
      />,
    )

    await screen.findByLabelText('Issue #12 详情')
    fireEvent.click(screen.getByRole('button', { name: '业务阻塞' }))

    const reasonInput = screen.getByPlaceholderText(/说明导致 Issue 无法继续执行的外部原因/i)
    fireEvent.change(reasonInput, { target: { value: 'Reason A' } })
    fireEvent.click(screen.getByRole('button', { name: '确认阻塞' }))

    await waitFor(() => {
      expect(keys.length).toBe(1)
    })

    // 等待阻塞弹窗完成并关闭
    await waitFor(() => {
      expect(screen.queryByRole('heading', { name: /标记业务阻塞/i })).toBeNull()
    })

    // 再次打开阻塞模态框，修改输入
    fireEvent.click(screen.getByRole('button', { name: '业务阻塞' }))
    const reasonInput2 = await screen.findByPlaceholderText(/说明导致 Issue 无法继续执行的外部原因/i)
    fireEvent.change(reasonInput2, { target: { value: 'Reason B (modified)' } })
    fireEvent.click(screen.getByRole('button', { name: '确认阻塞' }))

    await waitFor(() => {
      expect(keys.length).toBe(2)
      expect(keys[0]).not.toBe(keys[1])
    })
  })

  it('draft inputs do not leak across modal close and reopen (I09)', async () => {
    // 测试意图：在 IssueDetailModal 中输入未提交草稿，关闭弹窗后再打开，草稿输入被重置清理
    const api = createMockApi()
    const { rerender } = renderModal(
      <IssueDetailModal
        key="open-1"
        isOpen={true}
        issueId={issueId}
        onClose={vi.fn()}
        onIssueUpdated={vi.fn()}
        api={api}
        storageService={createMockStorageService()}
      />,
    )

    await screen.findByLabelText('Issue #12 详情')
    fireEvent.click(screen.getByRole('button', { name: /活动时间线/i }))

    const textarea = screen.getByPlaceholderText(/添加一条讨论或事实备注/i) as HTMLTextAreaElement
    fireEvent.change(textarea, { target: { value: 'Temporary unsubmitted draft' } })
    expect(textarea.value).toBe('Temporary unsubmitted draft')

    // 关闭弹窗
    rerender(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <IssueDetailModal
          key="closed"
          isOpen={false}
          issueId={issueId}
          onClose={vi.fn()}
          onIssueUpdated={vi.fn()}
          api={api}
          storageService={createMockStorageService()}
        />
      </QueryClientProvider>,
    )

    // 重新打开弹窗（如 ProjectDetailPage 中 key={effectiveIssueId ?? 'none'} 重新挂载）
    rerender(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <IssueDetailModal
          key="open-2"
          isOpen={true}
          issueId={issueId}
          onClose={vi.fn()}
          onIssueUpdated={vi.fn()}
          api={api}
          storageService={createMockStorageService()}
        />
      </QueryClientProvider>,
    )

    await screen.findByLabelText('Issue #12 详情')
    fireEvent.click(screen.getByRole('button', { name: /活动时间线/i }))
    const reopenedTextarea = screen.getByPlaceholderText(/添加一条讨论或事实备注/i) as HTMLTextAreaElement
    expect(reopenedTextarea.value).toBe('')
  })

  it('when pending unknown action exists, editing spec, save spec and delete issue are blocked with 0 API calls', async () => {
    // 测试意图：验证侧车中存在未确认结果的 UNKNOWN 写操作时，
    // isWriteBlocked 门禁生效：删除 Issue 按钮与 Spec 编辑/保存按钮均 disabled，
    // 且 handler 门禁严格拦截，api.updateIssue 与 api.deleteIssue 调用次数严格为 0。
    const api = createMockApi()
    storePendingAction(issueId, {
      issueId,
      kind: 'TRANSITION',
      requestKey: 'pending-req-key-1',
      expectedVersion: '3',
      payload: { toState: 'DONE' },
      createdAt: new Date().toISOString(),
      isUnknown: true,
    })

    renderModal(
      <IssueDetailModal
        isOpen={true}
        issueId={issueId}
        onClose={vi.fn()}
        onIssueUpdated={vi.fn()}
        api={api}
        storageService={createMockStorageService()}
      />,
    )

    await screen.findByLabelText('Issue #12 详情')
    // 验证 UNKNOWN 警告横幅出现
    await screen.findByText(/检测到未确认结果的写操作/i)

    // 1. 验证顶部删除 Issue 按钮被 disabled 禁用
    const deleteBtn = screen.getByRole('button', { name: '删除 Issue' })
    expect(deleteBtn).toBeDisabled()

    // 2. 验证 Spec 区域编辑需求按钮被 disabled 禁用
    const editSpecBtn = screen.getByRole('button', { name: /编辑需求/i })
    expect(editSpecBtn).toBeDisabled()

    // 关键断言：写 API 绝对未被调用
    expect(api.updateIssue).toHaveBeenCalledTimes(0)
    expect(api.deleteIssue).toHaveBeenCalledTimes(0)
  })

  it('when editing spec, manual reload does not silently update specVersion, and subsequent save retains original expectedVersion', async () => {
    // 测试意图：验证用户在编辑 Spec 期间，普通刷新（handleReloadFreshData）绝对不能自动静默推进 specVersion（绝不自动无提示 rebase），
    // 再次保存时仍严格保留原有 expectedVersion 发送 CAS（或由冲突拦截）；
    // 只有用户显式点击“可能覆盖远端最新修改，确认用最新版本重试保留的草稿”确认按钮后，才推进 version 并保存。
    const api = createMockApi()
    let currentVersion = '3'
    api.getIssue = vi.fn().mockImplementation(async () => ({
      ...mockActiveIssueDetail,
      issue: {
        ...mockActiveIssueDetail.issue,
        version: currentVersion,
      },
    }))

    // 真实 CAS 校验：服务端若 version 不匹配则返回 409
    api.updateIssue = vi.fn().mockImplementation(async (_id, req) => {
      if (req.expectedVersion !== currentVersion) {
        throw new ApiError('Conflict: expectedVersion mismatch', 409)
      }
      return {
        ...mockActiveIssueDetail.issue,
        version: String(Number(currentVersion) + 1),
        title: req.title,
      }
    })

    renderModal(
      <IssueDetailModal
        isOpen={true}
        issueId={issueId}
        onClose={vi.fn()}
        onIssueUpdated={vi.fn()}
        api={api}
        storageService={createMockStorageService()}
      />,
    )

    await screen.findByLabelText('Issue #12 详情')

    // 1. 进入 Spec 编辑状态
    fireEvent.click(screen.getByRole('button', { name: /编辑需求/i }))
    const titleInput = screen.getByDisplayValue('Implement OAuth2 login')
    fireEvent.change(titleInput, { target: { value: 'Implement OAuth2 login with PKCE updated' } })

    // 2. 此时服务端版本更新推进至 '4'（模拟并发写入或其他端修改）
    currentVersion = '4'

    // 3. 用户点击右上角“刷新数据”按钮（普通刷新）
    fireEvent.click(screen.getByRole('button', { name: '刷新数据' }))

    // 4. 验证出现冲突/风险提示横幅，提示可能覆盖远端修改
    await screen.findByText(/服务端版本已更新为 v4/i)

    // 5. 用户此时直接点击“保存更改”表单提交
    fireEvent.click(screen.getByRole('button', { name: '保存更改' }))

    await waitFor(() => {
      expect(api.updateIssue).toHaveBeenCalledTimes(1)
    })
    // 核心断言：调用的 expectedVersion 依然是原有的 '3'，绝不被普通刷新静默替换成 '4'！
    expect(api.updateIssue).toHaveBeenLastCalledWith(
      issueId,
      expect.objectContaining({
        expectedVersion: '3',
        title: 'Implement OAuth2 login with PKCE updated',
      }),
    )

    // 6. 由于服务端已是 '4'，第 1 次 updateIssue 409 拦截，草稿保留，用户显式点击确认按钮
    const explicitConfirmBtn = await screen.findByRole('button', {
      name: /可能覆盖远端最新修改，确认用最新版本重试保留的草稿/i,
    })
    fireEvent.click(explicitConfirmBtn)

    // 7. 用户再次点击“保存更改”
    fireEvent.click(screen.getByRole('button', { name: '保存更改' }))

    await waitFor(() => {
      expect(api.updateIssue).toHaveBeenCalledTimes(2)
    })
    // 核心断言：显式确认后，才推进使用最新版本 '4' 提交保存成功！
    expect(api.updateIssue).toHaveBeenLastCalledWith(
      issueId,
      expect.objectContaining({
        expectedVersion: '4',
        title: 'Implement OAuth2 login with PKCE updated',
      }),
    )
  })
})
