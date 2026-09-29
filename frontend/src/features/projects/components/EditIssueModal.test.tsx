import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ApiError } from '@/shared/api/client'
import { EditIssueModal } from './EditIssueModal'
import type { ProjectsApi } from '../projects-api'
import type { IssueDTO } from '../types'

function renderWithClient(ui: React.ReactElement) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: {
        retry: false,
      },
    },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      {ui}
    </QueryClientProvider>,
  )
}

describe('EditIssueModal', () => {
  const mockIssue: IssueDTO = {
    id: 'b0000000-0000-0000-0000-000000000001',
    projectId: 'a0000000-0000-0000-0000-000000000001',
    number: '10',
    title: 'Original Title',
    description: 'Original description',
    state: 'INIT',
    version: '5',
    archivedAt: null,
    createdAt: '2026-09-14T00:00:00Z',
    updatedAt: '2026-09-14T00:00:00Z',
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
    getIssue: vi.fn().mockResolvedValue({
      issue: mockIssue,
      activities: [],
      stageBudgets: [],
      runs: [],
      agentThreads: [],
      evidences: [],
    }),
    updateIssue: vi.fn().mockResolvedValue(mockIssue),
    transitionIssue: vi.fn(),
    blockIssue: vi.fn(),
    recoverIssue: vi.fn(),
    reopenIssue: vi.fn(),
    resolveUnknown: vi.fn(),
    pauseIssue: vi.fn(),
    stopIssue: vi.fn(),
    resetStageBudget: vi.fn(),
    appendIssueActivity: vi.fn(),
    addIssueEvidence: vi.fn(),
    deleteIssue: vi.fn(),
    archiveIssue: vi.fn(),
    unarchiveIssue: vi.fn(),
    ...overrides,
  })

  it('renders issue form fields populated with current issue data', () => {
    // 测试意图：验证打开弹窗时正确加载 Issue 当前标题与规格描述
    const api = createMockApi()
    renderWithClient(
      <EditIssueModal
        isOpen={true}
        issue={mockIssue}
        onClose={vi.fn()}
        onSuccess={vi.fn()}
        api={api}
      />,
    )

    expect(screen.getByText('编辑 Issue #10')).toBeInTheDocument()
    expect((screen.getByLabelText(/标题/i) as HTMLInputElement).value).toBe('Original Title')
    expect((screen.getByLabelText(/描述/i) as HTMLTextAreaElement).value).toBe(
      'Original description',
    )
  })

  it('submits updated issue data with correct parameters', async () => {
    // 测试意图：验证用户修改标题与需求描述后，正确调用 updateIssue API 传递 expectedVersion
    const api = createMockApi()
    const onSuccess = vi.fn()
    const onClose = vi.fn()

    renderWithClient(
      <EditIssueModal
        isOpen={true}
        issue={mockIssue}
        onClose={onClose}
        onSuccess={onSuccess}
        api={api}
      />,
    )

    const titleInput = screen.getByLabelText(/标题/i)
    fireEvent.change(titleInput, { target: { value: 'Updated Title' } })

    const descInput = screen.getByLabelText(/描述/i)
    fireEvent.change(descInput, { target: { value: 'Updated requirements' } })

    const submitBtn = screen.getByRole('button', { name: '保存修改' })
    fireEvent.click(submitBtn)

    await waitFor(() => {
      expect(api.updateIssue).toHaveBeenCalledWith(mockIssue.id, {
        expectedVersion: '5',
        title: 'Updated Title',
        description: 'Updated requirements',
      })
      expect(onSuccess).toHaveBeenCalledWith(mockIssue)
      expect(onClose).toHaveBeenCalled()
    })
  })

  it('preserves user input and offers refresh on 409 conflict', async () => {
    // 测试意图：核心场景——当提交发生 409 Conflict 时，用户输入的标题与描述不得丢失，可刷新版本号继续重试
    const conflictError = new ApiError('Conflict', 409)
    const freshIssue = { ...mockIssue, version: '6' }
    const api = createMockApi({
      updateIssue: vi.fn().mockRejectedValueOnce(conflictError).mockResolvedValueOnce(freshIssue),
      getIssue: vi.fn().mockResolvedValue({
        issue: freshIssue,
        activities: [],
        stageBudgets: [],
        runs: [],
        agentThreads: [],
        evidences: [],
      }),
    })

    renderWithClient(
      <EditIssueModal
        isOpen={true}
        issue={mockIssue}
        onClose={vi.fn()}
        onSuccess={vi.fn()}
        api={api}
      />,
    )

    const titleInput = screen.getByLabelText(/标题/i) as HTMLInputElement
    fireEvent.change(titleInput, { target: { value: 'Draft Modified Title' } })

    const submitBtn = screen.getByRole('button', { name: '保存修改' })
    fireEvent.click(submitBtn)

    // 验证出现 409 冲突提示
    expect(await screen.findByText(/409 冲突/i)).toBeInTheDocument()
    // 草稿仍然保留
    expect(titleInput.value).toBe('Draft Modified Title')

    // 点击刷新版本
    const refreshBtn = screen.getByRole('button', { name: '刷新版本' })
    fireEvent.click(refreshBtn)

    await waitFor(() => {
      expect(screen.getByText('6')).toBeInTheDocument()
      expect(titleInput.value).toBe('Draft Modified Title')
    })
  })
})
