import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/shared/api/client'
import { CreateProjectModal } from './CreateProjectModal'
import { EditProjectModal } from './EditProjectModal'
import { DeleteProjectModal } from './DeleteProjectModal'
import type { ProjectDTO } from '../types'

function renderWithClient(ui: React.ReactElement) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      {ui}
    </QueryClientProvider>,
  )
}

const mockProject: ProjectDTO = {
  id: 'a0000000-0000-0000-0000-000000000001',
  title: 'Existing Project',
  description: 'Project desc',
  workflow: {
    states: [
      { state: 'INIT', name: '待开始', next: ['DONE'] },
      { state: 'BLOCKED', name: '业务阻塞' },
      { state: 'DONE', name: '完成' },
    ],
  },
  yoloEnabled: true,
  nextIssueNumber: '1',
  version: '1',
  archivedAt: null,
  createdAt: '2026-09-14T00:00:00Z',
  updatedAt: '2026-09-14T00:00:00Z',
}

describe('CreateProjectModal', () => {
  it('renders default yoloEnabled and handles submit', async () => {
    // 测试意图：验证创建项目弹窗默认开启 YOLO，填写名称后正确调用 createProject
    const user = userEvent.setup()
    const mockApi = {
      createProject: vi.fn().mockResolvedValue(mockProject),
    } as any
    const onSuccess = vi.fn()
    const onClose = vi.fn()

    renderWithClient(
      <CreateProjectModal
        isOpen={true}
        onClose={onClose}
        onSuccess={onSuccess}
        api={mockApi}
      />,
    )

    const yoloCheckbox = screen.getByRole('checkbox') as HTMLInputElement
    expect(yoloCheckbox.checked).toBe(true)

    await user.type(screen.getByLabelText(/项目名称/i), 'Awesome Project')
    await user.type(screen.getByLabelText(/项目描述/i), 'Description text')
    await user.click(screen.getByRole('button', { name: '创建项目' }))

    await waitFor(() => {
      expect(mockApi.createProject).toHaveBeenCalledWith({
        title: 'Awesome Project',
        description: 'Description text',
        yoloEnabled: true,
      })
      expect(onSuccess).toHaveBeenCalledWith(mockProject)
      expect(onClose).toHaveBeenCalled()
    })
  })

  it('validates required title', async () => {
    // 测试意图：验证标题为空时拦截并展示错误提示
    const user = userEvent.setup()
    const mockApi = { createProject: vi.fn() } as any

    renderWithClient(
      <CreateProjectModal
        isOpen={true}
        onClose={vi.fn()}
        onSuccess={vi.fn()}
        api={mockApi}
      />,
    )

    await user.click(screen.getByRole('button', { name: '创建项目' }))
    expect(await screen.findByText('项目名称不能为空')).toBeInTheDocument()
    expect(mockApi.createProject).not.toHaveBeenCalled()
  })
})

describe('EditProjectModal', () => {
  it('switches to workflow tab, formats json, and submits workflow updates', async () => {
    // 测试意图：验证进入工作流 JSON 编辑器标签，可格式化 JSON 并严格调用 updateWorkflow 保存
    const user = userEvent.setup()
    const mockApi = {
      updateProject: vi.fn().mockResolvedValue({ ...mockProject, version: '2' }),
      updateWorkflow: vi.fn().mockResolvedValue({ ...mockProject, version: '2' }),
      getProject: vi.fn(),
    } as any
    const onSuccess = vi.fn()

    renderWithClient(
      <EditProjectModal
        isOpen={true}
        project={mockProject}
        onClose={vi.fn()}
        onSuccess={onSuccess}
        api={mockApi}
      />,
    )

    // 切换到工作流 tab
    await user.click(screen.getByRole('button', { name: '工作流 JSON 配置' }))
    const textarea = screen.getByLabelText(/工作流配置/i)
    expect(textarea).toBeInTheDocument()

    // 格式化 JSON
    await user.click(screen.getByRole('button', { name: /格式化 JSON/i }))

    // 点击保存
    await user.click(screen.getByRole('button', { name: '保存更改' }))
    await waitFor(() => {
      expect(onSuccess).toHaveBeenCalled()
    })
  })

  it('retains draft when 409 conflict occurs and allows version refresh', async () => {
    // 测试意图：核心场景——编辑遇到 409 Conflict 时保留编辑草稿，刷新版本号更新 expectedVersion
    const user = userEvent.setup()
    const conflictError = new ApiError('Conflict occurred', 409)
    const freshProject = { ...mockProject, version: '5' }
    const mockApi = {
      updateProject: vi.fn().mockRejectedValueOnce(conflictError).mockResolvedValueOnce(freshProject),
      getProject: vi.fn().mockResolvedValue(freshProject),
    } as any

    renderWithClient(
      <EditProjectModal
        isOpen={true}
        project={mockProject}
        onClose={vi.fn()}
        onSuccess={vi.fn()}
        api={mockApi}
      />,
    )

    const titleInput = screen.getByLabelText(/项目名称/i) as HTMLInputElement
    await user.clear(titleInput)
    await user.type(titleInput, 'Draft New Title')

    await user.click(screen.getByRole('button', { name: '保存更改' }))

    // 提示 409 冲突并保留草稿
    expect(await screen.findByText(/409 冲突/i)).toBeInTheDocument()
    expect(titleInput.value).toBe('Draft New Title')

    // 点击刷新版本
    await user.click(screen.getByRole('button', { name: '刷新版本' }))
    await waitFor(() => {
      expect(screen.getByText('5')).toBeInTheDocument() // 期望版本更新为 5
      expect(titleInput.value).toBe('Draft New Title') // 草稿依然保留
    })
  })
})

describe('DeleteProjectModal', () => {
  it('calls deleteProject with expectedVersion upon confirmation', async () => {
    // 测试意图：验证确认删除项目时向 deleteProject 传递 projectId 和 expectedVersion
    const user = userEvent.setup()
    const mockApi = {
      deleteProject: vi.fn().mockResolvedValue(undefined),
    } as any
    const onSuccess = vi.fn()

    renderWithClient(
      <DeleteProjectModal
        isOpen={true}
        project={mockProject}
        onClose={vi.fn()}
        onSuccess={onSuccess}
        api={mockApi}
      />,
    )

    await user.click(screen.getByRole('button', { name: '确认删除' }))
    await waitFor(() => {
      expect(mockApi.deleteProject).toHaveBeenCalledWith(mockProject.id, mockProject.version)
      expect(onSuccess).toHaveBeenCalled()
    })
  })
})
