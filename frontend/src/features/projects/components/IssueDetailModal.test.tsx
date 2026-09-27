import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { IssueDetailModal } from './IssueDetailModal'
import type { ProjectsApi } from '../projects-api'
import type { IssueDetailDTO } from '../types'
import type { StorageService } from '@/shared/api/storage-service'
import type { StorageUploadDTO, StorageUploadResultDTO } from '@/shared/api/contracts/storage'

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
    resolveUnknownIssue: vi.fn().mockResolvedValue(mockActiveIssueDetail.issue),
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

    expect(screen.getByRole('heading', { name: /解除 UNKNOWN 门禁/i })).toBeInTheDocument()
    const verificationInput = screen.getByPlaceholderText(/说明已核实的内容与外部状态一致性保证/i)
    fireEvent.change(verificationInput, { target: { value: 'Manually verified as safe to continue.' } })

    fireEvent.click(screen.getByRole('button', { name: '确认解除门禁' }))

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

    const { container } = renderModal(
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
    const fileInput = container.querySelector('input[type="file"]') as HTMLInputElement
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
})
