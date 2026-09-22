import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { ApiError } from '@/shared/api/client'
import { EditIssueModal } from './EditIssueModal'
import type { ProjectsApi } from '../projects-api'
import type { IssueDTO, IssueDetailDTO } from '../types'

describe('EditIssueModal', () => {
  const mockIssue: IssueDTO = {
    id: 'b0000000-0000-0000-0000-000000000001',
    projectId: 'a0000000-0000-0000-0000-000000000001',
    number: '10',
    title: 'Original Title',
    description: 'Original description',
    status: 'TODO',
    assigneeAgentName: 'agent-1',
    reviewerAgentName: 'agent-2',
    version: '5',
    specRevision: '1',
    inputSequence: '1',
    archivedAt: null,
    createdAt: '2026-09-14T00:00:00Z',
    updatedAt: '2026-09-14T00:00:00Z',
  }

  const createMockApi = (overrides: Partial<ProjectsApi> = {}): ProjectsApi => ({
    listProjects: vi.fn(),
    createProject: vi.fn(),
    getProject: vi.fn(),
    updateProject: vi.fn(),
    deleteProject: vi.fn(),
    archiveProject: vi.fn(),
    unarchiveProject: vi.fn(),
    getProjectSnapshot: vi.fn(),
    createIssue: vi.fn(),
    getIssue: vi.fn(),
    updateIssue: vi.fn().mockResolvedValue(mockIssue),
    changeIssueStatus: vi.fn(),
    addIssueDependency: vi.fn(),
    removeIssueDependency: vi.fn(),
    appendIssueInput: vi.fn(),
    reviewIssue: vi.fn(),
    cancelIssue: vi.fn(),
    retryIssue: vi.fn(),
    archiveIssue: vi.fn(),
    unarchiveIssue: vi.fn(),
    ...overrides,
  })

  it('renders issue form fields populated with current issue data', () => {
    // 测试意图：验证打开弹窗时正确加载 Issue 当前标题、规格描述、Assignee 和 Reviewer
    const api = createMockApi()
    render(
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
    expect((screen.getByLabelText(/规格与详细要求/i) as HTMLTextAreaElement).value).toBe(
      'Original description',
    )
    expect((screen.getByLabelText(/Assignee Agent/i) as HTMLInputElement).value).toBe('agent-1')
    expect((screen.getByLabelText(/Reviewer Agent/i) as HTMLInputElement).value).toBe('agent-2')
  })

  it('submits updated issue data successfully and invokes onSuccess', async () => {
    // 测试意图：验证修改字段后提交表单，调用 updateIssue API 传递 expectedVersion 并触发 onSuccess 回调
    const onSuccess = vi.fn()
    const onClose = vi.fn()
    const updatedIssue: IssueDTO = {
      ...mockIssue,
      title: 'Updated Title',
      description: 'New spec text',
      assigneeAgentName: 'agent-new',
      reviewerAgentName: null,
      version: '6',
    }
    const api = createMockApi({
      updateIssue: vi.fn().mockResolvedValue(updatedIssue),
    })

    render(
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

    const descInput = screen.getByLabelText(/规格与详细要求/i)
    fireEvent.change(descInput, { target: { value: 'New spec text' } })

    const assigneeInput = screen.getByLabelText(/Assignee Agent/i)
    fireEvent.change(assigneeInput, { target: { value: 'agent-new' } })

    const reviewerInput = screen.getByLabelText(/Reviewer Agent/i)
    fireEvent.change(reviewerInput, { target: { value: '' } })

    const saveBtn = screen.getByRole('button', { name: '保存修改' })
    fireEvent.click(saveBtn)

    await waitFor(() => {
      expect(api.updateIssue).toHaveBeenCalledWith(mockIssue.id, {
        expectedVersion: '5',
        title: 'Updated Title',
        description: 'New spec text',
        assigneeAgentName: 'agent-new',
        reviewerAgentName: null,
      })
      expect(onSuccess).toHaveBeenCalledWith(updatedIssue)
      expect(onClose).toHaveBeenCalled()
    })
  })

  it('handles 409 CAS conflict by preserving draft and allowing version reload', async () => {
    // 测试意图：验证 409 乐观锁冲突发生时表单不丢失草稿，点击同步版本号可拉取最新 version 并再次保存成功
    const conflictError = new ApiError('Conflict', 409, 'PROJECT_VERSION_CONFLICT')
    const freshDetail: IssueDetailDTO = {
      issue: {
        ...mockIssue,
        version: '7',
        title: 'Server Title',
      },
      blocked: false,
      dependencies: [],
      inputs: [],
      runs: [],
      currentRun: null,
      latestRun: null,
    }

    const api = createMockApi({
      updateIssue: vi
        .fn()
        .mockRejectedValueOnce(conflictError)
        .mockResolvedValueOnce({ ...freshDetail.issue, title: 'Preserved Draft' }),
      getIssue: vi.fn().mockResolvedValue(freshDetail),
    })

    render(
      <EditIssueModal
        isOpen={true}
        issue={mockIssue}
        onClose={vi.fn()}
        onSuccess={vi.fn()}
        api={api}
      />,
    )

    const titleInput = screen.getByLabelText(/标题/i)
    fireEvent.change(titleInput, { target: { value: 'Preserved Draft' } })

    fireEvent.click(screen.getByRole('button', { name: '保存修改' }))

    await waitFor(() => {
      expect(screen.getByText(/版本冲突/i)).toBeInTheDocument()
    })

    // 草稿内容保留
    expect((screen.getByLabelText(/标题/i) as HTMLInputElement).value).toBe('Preserved Draft')

    // 点击同步最新版本号
    const syncBtn = screen.getByRole('button', { name: /同步最新版本号并重试/i })
    fireEvent.click(syncBtn)

    await waitFor(() => {
      expect(api.getIssue).toHaveBeenCalledWith(mockIssue.id)
    })

    // 再次提交，使用最新 version '7'
    fireEvent.click(screen.getByRole('button', { name: '保存修改' }))

    await waitFor(() => {
      expect(api.updateIssue).toHaveBeenCalledWith(mockIssue.id, {
        expectedVersion: '7',
        title: 'Preserved Draft',
        description: mockIssue.description,
        assigneeAgentName: mockIssue.assigneeAgentName,
        reviewerAgentName: mockIssue.reviewerAgentName,
      })
    })
  })

  it('allows discarding draft and resetting to server content on conflict', async () => {
    // 测试意图：验证冲突发生时用户可选择放弃草稿并重置为服务端最新内容
    const conflictError = new ApiError('Conflict', 409, 'PROJECT_VERSION_CONFLICT')
    const freshDetail: IssueDetailDTO = {
      issue: {
        ...mockIssue,
        version: '8',
        title: 'Server Authoritative Title',
        description: 'Server spec',
        assigneeAgentName: 'new-agent',
        reviewerAgentName: 'human-review',
      },
      blocked: false,
      dependencies: [],
      inputs: [],
      runs: [],
      currentRun: null,
      latestRun: null,
    }

    const api = createMockApi({
      updateIssue: vi.fn().mockRejectedValueOnce(conflictError),
      getIssue: vi.fn().mockResolvedValue(freshDetail),
    })

    render(
      <EditIssueModal
        isOpen={true}
        issue={mockIssue}
        onClose={vi.fn()}
        onSuccess={vi.fn()}
        api={api}
      />,
    )

    fireEvent.change(screen.getByLabelText(/标题/i), { target: { value: 'Draft to discard' } })
    fireEvent.click(screen.getByRole('button', { name: '保存修改' }))

    await waitFor(() => {
      expect(screen.getByText(/版本冲突/i)).toBeInTheDocument()
    })

    // 点击放弃草稿重置
    const discardBtn = screen.getByRole('button', { name: '放弃草稿重置' })
    fireEvent.click(discardBtn)

    await waitFor(() => {
      expect(api.getIssue).toHaveBeenCalledWith(mockIssue.id)
      expect((screen.getByLabelText(/标题/i) as HTMLInputElement).value).toBe(
        'Server Authoritative Title',
      )
    })
  })

  it('closes on Escape key press and backdrop click', () => {
    // 测试意图：验证 Escape 快捷键和点击遮罩触发 onClose
    const onClose = vi.fn()
    const { container } = render(
      <EditIssueModal
        isOpen={true}
        issue={mockIssue}
        onClose={onClose}
        onSuccess={vi.fn()}
        api={createMockApi()}
      />,
    )

    fireEvent.keyDown(window, { key: 'Escape' })
    expect(onClose).toHaveBeenCalledTimes(1)

    const backdrop = container.querySelector('.modal-backdrop')
    expect(backdrop).toBeInTheDocument()
    fireEvent.click(backdrop!)
    expect(onClose).toHaveBeenCalledTimes(2)
  })
})
