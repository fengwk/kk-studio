import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/shared/api/client'
import { CreateProjectModal } from './CreateProjectModal'
import { EditProjectModal } from './EditProjectModal'
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
  yoloEnabled: true,
  maxReviewRejections: '3',
  nextIssueNumber: '1',
  version: '1',
  archivedAt: null,
  createdAt: '2026-09-14T00:00:00Z',
  updatedAt: '2026-09-14T00:00:00Z',
}

describe('CreateProjectModal', () => {
  it('renders default yoloEnabled (true) and maxReviewRejections (3)', () => {
    // 测试意图：验证创建项目弹窗默认开启 YOLO 模式，并且最大审查打回次数默认置为 3
    renderWithClient(
      <CreateProjectModal
        isOpen={true}
        onClose={vi.fn()}
        onSuccess={vi.fn()}
      />,
    )

    const yoloCheckbox = screen.getByRole('checkbox', { name: /YOLO 模式/i }) as HTMLInputElement
    expect(yoloCheckbox.checked).toBe(true)

    const rejectionsInput = screen.getByRole('spinbutton', { name: /最大审查打回次数/i }) as HTMLInputElement
    expect(rejectionsInput.value).toBe('3')

    const submitBtn = screen.getByRole('button', { name: '创建项目' })
    expect(submitBtn).toBeDisabled() // title is empty
  })

  it('submits valid project payload with yoloEnabled and maxReviewRejections', async () => {
    // 测试意图：验证填写项目标题与自定义配置后正确调用 createProject API 并传递 yoloEnabled 与 maxReviewRejections
    const user = userEvent.setup()
    const mockApi = {
      createProject: vi.fn().mockResolvedValue(mockProject),
      listProjects: vi.fn(),
      getProject: vi.fn(),
      updateProject: vi.fn(),
      deleteProject: vi.fn(),
      archiveProject: vi.fn(),
      unarchiveProject: vi.fn(),
      getProjectSnapshot: vi.fn(),
      createIssue: vi.fn(),
      getIssue: vi.fn(),
      listActivities: vi.fn(),
      updateIssue: vi.fn(),
      changeIssueStatus: vi.fn(),
      blockIssue: vi.fn(),
      recoverIssue: vi.fn(),
      addIssueDependency: vi.fn(),
      removeIssueDependency: vi.fn(),
      appendIssueActivity: vi.fn(),
      reviewIssue: vi.fn(),
      cancelIssue: vi.fn(),
      retryIssue: vi.fn(),
      archiveIssue: vi.fn(),
      unarchiveIssue: vi.fn(),
    }
    const onSuccess = vi.fn()

    renderWithClient(
      <CreateProjectModal
        isOpen={true}
        onClose={vi.fn()}
        onSuccess={onSuccess}
        api={mockApi}
      />,
    )

    await user.type(screen.getByLabelText(/项目名称/i), 'Awesome Next-Gen Project')
    await user.clear(screen.getByRole('spinbutton', { name: /最大审查打回次数/i }))
    await user.type(screen.getByRole('spinbutton', { name: /最大审查打回次数/i }), '5')

    const submitBtn = screen.getByRole('button', { name: '创建项目' })
    expect(submitBtn).not.toBeDisabled()
    await user.click(submitBtn)

    expect(mockApi.createProject).toHaveBeenCalledWith({
      title: 'Awesome Next-Gen Project',
      description: null,
      yoloEnabled: true,
      maxReviewRejections: 5,
    })
    expect(onSuccess).toHaveBeenCalledWith(mockProject)
  })

  it('displays error banner when project creation fails', async () => {
    // 测试意图：验证创建项目失败时展示错误提示横幅
    const user = userEvent.setup()
    const mockApi = {
      createProject: vi.fn().mockRejectedValue(new Error('Project title already exists')),
      listProjects: vi.fn(),
      getProject: vi.fn(),
      updateProject: vi.fn(),
      deleteProject: vi.fn(),
      archiveProject: vi.fn(),
      unarchiveProject: vi.fn(),
      getProjectSnapshot: vi.fn(),
      createIssue: vi.fn(),
      getIssue: vi.fn(),
      listActivities: vi.fn(),
      updateIssue: vi.fn(),
      changeIssueStatus: vi.fn(),
      blockIssue: vi.fn(),
      recoverIssue: vi.fn(),
      addIssueDependency: vi.fn(),
      removeIssueDependency: vi.fn(),
      appendIssueActivity: vi.fn(),
      reviewIssue: vi.fn(),
      cancelIssue: vi.fn(),
      retryIssue: vi.fn(),
      archiveIssue: vi.fn(),
      unarchiveIssue: vi.fn(),
    }

    renderWithClient(
      <CreateProjectModal
        isOpen={true}
        onClose={vi.fn()}
        onSuccess={vi.fn()}
        api={mockApi}
      />,
    )

    await user.type(screen.getByLabelText(/项目名称/i), 'Duplicate Title')
    await user.click(screen.getByRole('button', { name: '创建项目' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Project title already exists')
  })
})

describe('EditProjectModal', () => {
  it('populates initial project values for yoloEnabled and maxReviewRejections', () => {
    // 测试意图：验证编辑项目弹窗正确回显项目的 yoloEnabled 与 maxReviewRejections 属性
    renderWithClient(
      <EditProjectModal
        isOpen={true}
        project={mockProject}
        onClose={vi.fn()}
        onSuccess={vi.fn()}
      />,
    )

    expect((screen.getByLabelText(/项目名称/i) as HTMLInputElement).value).toBe('Existing Project')
    expect((screen.getByRole('checkbox', { name: /YOLO 模式/i }) as HTMLInputElement).checked).toBe(true)
    expect((screen.getByRole('spinbutton', { name: /最大审查打回次数/i }) as HTMLInputElement).value).toBe('3')
  })

  it('submits updated project with modified settings', async () => {
    // 测试意图：验证编辑项目修改 YOLO 模式与打回阈值后，调用 updateProject API 并携带 expectedVersion
    const user = userEvent.setup()
    const updatedProject: ProjectDTO = {
      ...mockProject,
      yoloEnabled: false,
      maxReviewRejections: '2',
      version: '2',
    }
    const mockApi = {
      updateProject: vi.fn().mockResolvedValue(updatedProject),
      listProjects: vi.fn(),
      createProject: vi.fn(),
      getProject: vi.fn(),
      deleteProject: vi.fn(),
      archiveProject: vi.fn(),
      unarchiveProject: vi.fn(),
      getProjectSnapshot: vi.fn(),
      createIssue: vi.fn(),
      getIssue: vi.fn(),
      listActivities: vi.fn(),
      updateIssue: vi.fn(),
      changeIssueStatus: vi.fn(),
      blockIssue: vi.fn(),
      recoverIssue: vi.fn(),
      addIssueDependency: vi.fn(),
      removeIssueDependency: vi.fn(),
      appendIssueActivity: vi.fn(),
      reviewIssue: vi.fn(),
      cancelIssue: vi.fn(),
      retryIssue: vi.fn(),
      archiveIssue: vi.fn(),
      unarchiveIssue: vi.fn(),
    }
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

    // 切换 YOLO
    await user.click(screen.getByRole('checkbox', { name: /YOLO 模式/i }))
    // 修改打回阈值为 2
    await user.clear(screen.getByRole('spinbutton', { name: /最大审查打回次数/i }))
    await user.type(screen.getByRole('spinbutton', { name: /最大审查打回次数/i }), '2')

    await user.click(screen.getByRole('button', { name: '保存更改' }))

    expect(mockApi.updateProject).toHaveBeenCalledWith(mockProject.id, {
      expectedVersion: '1',
      title: 'Existing Project',
      description: 'Project desc',
      yoloEnabled: false,
      maxReviewRejections: 2,
    })
    expect(onSuccess).toHaveBeenCalledWith(updatedProject)
  })

  it('surfaces active-run server validation error in error banner', async () => {
    // 测试意图：验证当服务端抛出活动 Run 校验限制或业务错误时，错误信息正确渲染在错误横幅上
    const user = userEvent.setup()
    const mockApi = {
      updateProject: vi.fn().mockRejectedValue(new Error('Cannot modify project settings while active run is in progress')),
      listProjects: vi.fn(),
      createProject: vi.fn(),
      getProject: vi.fn(),
      deleteProject: vi.fn(),
      archiveProject: vi.fn(),
      unarchiveProject: vi.fn(),
      getProjectSnapshot: vi.fn(),
      createIssue: vi.fn(),
      getIssue: vi.fn(),
      listActivities: vi.fn(),
      updateIssue: vi.fn(),
      changeIssueStatus: vi.fn(),
      blockIssue: vi.fn(),
      recoverIssue: vi.fn(),
      addIssueDependency: vi.fn(),
      removeIssueDependency: vi.fn(),
      appendIssueActivity: vi.fn(),
      reviewIssue: vi.fn(),
      cancelIssue: vi.fn(),
      retryIssue: vi.fn(),
      archiveIssue: vi.fn(),
      unarchiveIssue: vi.fn(),
    }

    renderWithClient(
      <EditProjectModal
        isOpen={true}
        project={mockProject}
        onClose={vi.fn()}
        onSuccess={vi.fn()}
        api={mockApi}
      />,
    )

    await user.click(screen.getByRole('button', { name: '保存更改' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Cannot modify project settings while active run is in progress',
    )
  })

  it('surfaces CAS conflict banner on 409 conflict and supports reloading', async () => {
    // 测试意图：验证乐观锁版本冲突时展示冲突面板，并可通过重新加载同步最新版本
    const user = userEvent.setup()
    const conflictError = new ApiError('Version conflict', 409, 'VERSION_CONFLICT', {
      expectedVersion: '1',
      actualVersion: '2',
    })
    const reloadedProject: ProjectDTO = {
      ...mockProject,
      version: '2',
    }
    const mockApi = {
      updateProject: vi.fn().mockRejectedValue(conflictError),
      getProject: vi.fn().mockResolvedValue(reloadedProject),
      listProjects: vi.fn(),
      createProject: vi.fn(),
      deleteProject: vi.fn(),
      archiveProject: vi.fn(),
      unarchiveProject: vi.fn(),
      getProjectSnapshot: vi.fn(),
      createIssue: vi.fn(),
      getIssue: vi.fn(),
      listActivities: vi.fn(),
      updateIssue: vi.fn(),
      changeIssueStatus: vi.fn(),
      blockIssue: vi.fn(),
      recoverIssue: vi.fn(),
      addIssueDependency: vi.fn(),
      removeIssueDependency: vi.fn(),
      appendIssueActivity: vi.fn(),
      reviewIssue: vi.fn(),
      cancelIssue: vi.fn(),
      retryIssue: vi.fn(),
      archiveIssue: vi.fn(),
      unarchiveIssue: vi.fn(),
    }

    renderWithClient(
      <EditProjectModal
        isOpen={true}
        project={mockProject}
        onClose={vi.fn()}
        onSuccess={vi.fn()}
        api={mockApi}
      />,
    )

    await user.click(screen.getByRole('button', { name: '保存更改' }))

    expect(await screen.findByText(/版本冲突/i)).toBeInTheDocument()

    // 点击保留草稿重新加载
    await user.click(screen.getByRole('button', { name: /保留草稿并重新加载最新版本号/i }))
    await waitFor(() => {
      expect(mockApi.getProject).toHaveBeenCalledWith(mockProject.id)
      expect(screen.queryByText(/版本冲突/i)).not.toBeInTheDocument()
    })
  })
})
