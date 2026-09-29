import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/shared/api/client'
import { CreateProjectModal } from './CreateProjectModal'
import { EditProjectModal } from './EditProjectModal'
import { DeleteProjectModal } from './DeleteProjectModal'
import type { ProjectDTO } from '../types'
import type { ProjectsApi } from '../projects-api'

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
    } as unknown as ProjectsApi
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
    const mockApi = { createProject: vi.fn() } as unknown as ProjectsApi

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
  it('switches to workflow tab, formats json, and submits workflow updates independently', async () => {
    // 测试意图：验证进入工作流 JSON 编辑器标签，可格式化 JSON 并严格调用独立的 updateWorkflow 保存，成功触发 onSuccess
    const user = userEvent.setup()
    const mockApi = {
      updateProject: vi.fn().mockResolvedValue({ ...mockProject, version: '2' }),
      updateWorkflow: vi.fn().mockResolvedValue({ ...mockProject, version: '2' }),
      getProject: vi.fn(),
    } as unknown as ProjectsApi
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

    // 点击独立的工作流保存按钮
    await user.click(screen.getByRole('button', { name: '保存工作流' }))
    await waitFor(() => {
      expect(mockApi.updateWorkflow).toHaveBeenCalledWith(
        mockProject.id,
        expect.objectContaining({
          expectedVersion: '1',
          workflow: expect.objectContaining({
            states: expect.arrayContaining([
              expect.objectContaining({ state: 'INIT', name: '待开始' }),
              expect.objectContaining({ state: 'BLOCKED', name: '业务阻塞' }),
              expect.objectContaining({ state: 'DONE', name: '完成' }),
            ]),
          }),
        }),
      )
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
    } as unknown as ProjectsApi

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

    await user.click(screen.getByRole('button', { name: '保存基础信息' }))

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

  it('independently saves YOLO mode and updates authoritative version for subsequent actions', async () => {
    // 测试意图：验证 YOLO 模式拥有独立保存表单与按钮，CAS 成功后立即更新模态框权威版本，后续基础配置保存继承新版本
    const user = userEvent.setup()
    const projectAfterYolo = { ...mockProject, yoloEnabled: false, version: '2' }
    const projectAfterBasic = { ...projectAfterYolo, title: 'Updated Title', version: '3' }
    const mockApi = {
      updateYolo: vi.fn().mockResolvedValue(projectAfterYolo),
      updateProject: vi.fn().mockResolvedValue(projectAfterBasic),
    } as unknown as ProjectsApi
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

    // 更改 YOLO 模式并点击保存 YOLO 模式
    const yoloCheckbox = screen.getByRole('checkbox')
    await user.click(yoloCheckbox)
    await user.click(screen.getByRole('button', { name: '保存 YOLO 模式' }))

    await waitFor(() => {
      expect(mockApi.updateYolo).toHaveBeenCalledWith(mockProject.id, {
        expectedVersion: '1',
        yoloEnabled: false,
      })
      expect(screen.getByText('2')).toBeInTheDocument() // 期望版本推进到 2
      expect(screen.getByText('YOLO 模式保存成功')).toBeInTheDocument()
    })

    // 接着在同一弹窗中保存基础信息，验证其使用已更新的权威版本 2 进行 CAS
    const titleInput = screen.getByLabelText(/项目名称/i)
    await user.clear(titleInput)
    await user.type(titleInput, 'Updated Title')
    await user.click(screen.getByRole('button', { name: '保存基础信息' }))

    await waitFor(() => {
      expect(mockApi.updateProject).toHaveBeenCalledWith(mockProject.id, {
        expectedVersion: '2',
        title: 'Updated Title',
        description: mockProject.description,
      })
      expect(screen.getByText('3')).toBeInTheDocument() // 期望版本推进到 3
      expect(screen.getByText('基础信息保存成功')).toBeInTheDocument()
    })
  })

  it('does not claim composite atomic failure when one subform fails after another succeeds', async () => {
    // 测试意图：验证当基础信息更新成功（版本变为 2）后，YOLO 模式保存抛出网络错误，只在 YOLO 区域提示错误，绝不声称基础配置保存也失败
    const user = userEvent.setup()
    const projectAfterBasic = { ...mockProject, title: 'Valid New Title', version: '2' }
    const mockApi = {
      updateProject: vi.fn().mockResolvedValue(projectAfterBasic),
      updateYolo: vi.fn().mockRejectedValue(new Error('YOLO Network timeout')),
    } as unknown as ProjectsApi
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

    // 1. 保存基础信息成功
    const titleInput = screen.getByLabelText(/项目名称/i)
    await user.clear(titleInput)
    await user.type(titleInput, 'Valid New Title')
    await user.click(screen.getByRole('button', { name: '保存基础信息' }))

    await waitFor(() => {
      expect(screen.getByText('基础信息保存成功')).toBeInTheDocument()
      expect(screen.getByText('2')).toBeInTheDocument() // 权威版本更新为 2
    })

    // 2. 保存 YOLO 模式失败
    await user.click(screen.getByRole('button', { name: '保存 YOLO 模式' }))

    await waitFor(() => {
      expect(screen.getByText('YOLO Network timeout')).toBeInTheDocument()
      // 基础信息仍然显示成功，不被篡改为原子全部失败
      expect(screen.getByText('基础信息保存成功')).toBeInTheDocument()
      // 权威版本依然停留在成功后的 2
      expect(screen.getByText('2')).toBeInTheDocument()
    })
  })

  it('preserves dirty uncommitted drafts when parent updates project prop following a subform save (I08)', async () => {
    // 测试意图：验证当父组件响应子表单保存并回传更新后的 project prop（版本推进）时，未提交的脏草稿字段不被重置覆盖，且后续保存自动继承新版本
    const user = userEvent.setup()
    const projectV2: ProjectDTO = {
      ...mockProject,
      yoloEnabled: false,
      version: '2',
    }
    const projectV3: ProjectDTO = {
      ...projectV2,
      title: 'Draft Custom Title',
      version: '3',
    }
    const mockApi = {
      updateYolo: vi.fn().mockResolvedValue(projectV2),
      updateProject: vi.fn().mockResolvedValue(projectV3),
      getProject: vi.fn(),
    } as unknown as ProjectsApi
    const onSuccess = vi.fn()

    const { rerender } = renderWithClient(
      <EditProjectModal
        isOpen={true}
        project={mockProject}
        onClose={vi.fn()}
        onSuccess={onSuccess}
        api={mockApi}
      />,
    )

    // 1. 用户在 Basic 表单输入未提交的草稿
    const titleInput = screen.getByLabelText(/项目名称/i) as HTMLInputElement
    await user.clear(titleInput)
    await user.type(titleInput, 'Draft Custom Title')
    expect(titleInput.value).toBe('Draft Custom Title')

    // 2. 用户在 YOLO 区域独立保存 YOLO
    const yoloCheckbox = screen.getByRole('checkbox')
    await user.click(yoloCheckbox)
    await user.click(screen.getByRole('button', { name: '保存 YOLO 模式' }))

    await waitFor(() => {
      expect(mockApi.updateYolo).toHaveBeenCalledWith(mockProject.id, {
        expectedVersion: '1',
        yoloEnabled: false,
      })
      expect(onSuccess).toHaveBeenCalledWith(projectV2)
    })

    // 3. 模拟父组件根据 onSuccess 回调更新 project prop 为 projectV2
    rerender(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <EditProjectModal
          isOpen={true}
          project={projectV2}
          onClose={vi.fn()}
          onSuccess={onSuccess}
          api={mockApi}
        />
      </QueryClientProvider>,
    )

    // 4. 验证：权威版本更新为 2，未提交的 Basic 草稿依然完好保留！
    expect(screen.getByText('2')).toBeInTheDocument()
    expect(titleInput.value).toBe('Draft Custom Title')

    // 5. 点击保存基础信息，验证其使用从父组件同步来的权威版本 2 进行 CAS
    await user.click(screen.getByRole('button', { name: '保存基础信息' }))
    await waitFor(() => {
      expect(mockApi.updateProject).toHaveBeenCalledWith(mockProject.id, {
        expectedVersion: '2',
        title: 'Draft Custom Title',
        description: mockProject.description,
      })
      expect(screen.getByText('3')).toBeInTheDocument()
    })
  })
})

describe('DeleteProjectModal', () => {
  it('calls deleteProject with expectedVersion upon confirmation', async () => {
    // 测试意图：验证确认删除项目时向 deleteProject 传递 projectId 和 expectedVersion
    const user = userEvent.setup()
    const mockApi = {
      deleteProject: vi.fn().mockResolvedValue(undefined),
    } as unknown as ProjectsApi
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
