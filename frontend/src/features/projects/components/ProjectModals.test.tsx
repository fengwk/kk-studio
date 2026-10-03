import { fireEvent, render, screen, waitFor } from '@testing-library/react'
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

  it('blocks concurrent submission: defer basic -> attempt yolo does not fire, after basic resolves yolo uses new version', async () => {
    // 测试意图：验证单一 activeSubmission 与 ref 互斥；当 basic 保存处于 pending 时，尝试提交 yolo 被严格拦截不发请求；basic resolve 后 yolo 才能提交且使用推进后的新版本
    const user = userEvent.setup()
    let resolveBasic: (value: ProjectDTO) => void = () => {}
    const basicPromise = new Promise<ProjectDTO>((resolve) => {
      resolveBasic = resolve
    })
    const projectV2 = { ...mockProject, title: 'New Basic Title', version: '2' }
    const projectV3 = { ...projectV2, yoloEnabled: false, version: '3' }

    const mockApi = {
      updateProject: vi.fn().mockImplementation(() => basicPromise),
      updateYolo: vi.fn().mockResolvedValue(projectV3),
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

    // 1. 用户修改 Basic 并提交，请求处于 pending 状态
    const titleInput = screen.getByLabelText(/项目名称/i)
    await user.clear(titleInput)
    await user.type(titleInput, 'New Basic Title')

    const basicSubmitBtn = screen.getByRole('button', { name: '保存基础信息' })
    await user.click(basicSubmitBtn)
    expect(mockApi.updateProject).toHaveBeenCalledTimes(1)
    expect(screen.getByRole('button', { name: '保存中...' })).toBeInTheDocument()

    // 2. 在 Basic pending 期间，尝试提交 YOLO，验证被互斥拦截，不发送请求
    const yoloSubmitBtn = screen.getByRole('button', { name: '保存 YOLO 模式' })
    expect(yoloSubmitBtn).toBeDisabled()
    await user.click(yoloSubmitBtn)
    expect(mockApi.updateYolo).not.toHaveBeenCalled()

    // 3. Resolve Basic 保存请求
    resolveBasic(projectV2)
    await waitFor(() => {
      expect(screen.getByText('基础信息保存成功')).toBeInTheDocument()
      expect(screen.getByText('2')).toBeInTheDocument()
    })
    expect(onSuccess).toHaveBeenCalledWith(projectV2)

    // 4. Basic 完成后，互斥锁释放，提交 YOLO，此时使用新版本 2
    await user.click(screen.getByRole('checkbox'))
    await user.click(screen.getByRole('button', { name: '保存 YOLO 模式' }))

    await waitFor(() => {
      expect(mockApi.updateYolo).toHaveBeenCalledWith(mockProject.id, {
        expectedVersion: '2',
        yoloEnabled: false,
      })
      expect(screen.getByText('3')).toBeInTheDocument()
      expect(screen.getByText('YOLO 模式保存成功')).toBeInTheDocument()
    })
  })

  it('blocks concurrent submission: defer basic -> attempt workflow does not fire, after basic resolves workflow uses new version', async () => {
    // 测试意图：验证 Basic 提交在途时阻止 Workflow 提交；Basic 成功推进版本后 Workflow 使用最新版本
    const user = userEvent.setup()
    let resolveBasic: (value: ProjectDTO) => void = () => {}
    const basicPromise = new Promise<ProjectDTO>((resolve) => {
      resolveBasic = resolve
    })
    const projectV2 = { ...mockProject, title: 'New Basic Title', version: '2' }
    const projectV3 = { ...projectV2, version: '3' }

    const mockApi = {
      updateProject: vi.fn().mockImplementation(() => basicPromise),
      updateWorkflow: vi.fn().mockResolvedValue(projectV3),
      getProject: vi.fn(),
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

    // 1. 发起 Basic 保存
    await user.click(screen.getByRole('button', { name: '保存基础信息' }))
    expect(mockApi.updateProject).toHaveBeenCalledTimes(1)

    // 2. 切换 Tab 到工作流并尝试点击保存，按钮处于 disabled，不发起提交
    await user.click(screen.getByRole('button', { name: '工作流 JSON 配置' }))
    const workflowBtn = screen.getByRole('button', { name: '保存工作流' })
    expect(workflowBtn).toBeDisabled()
    await user.click(workflowBtn)
    expect(mockApi.updateWorkflow).not.toHaveBeenCalled()

    // 3. Resolve Basic 保存
    resolveBasic(projectV2)
    await waitFor(() => {
      expect(screen.getByText('2')).toBeInTheDocument()
    })

    // 4. 再次保存工作流，验证 expectedVersion 使用最新的 '2'
    expect(workflowBtn).not.toBeDisabled()
    await user.click(workflowBtn)

    await waitFor(() => {
      expect(mockApi.updateWorkflow).toHaveBeenCalledWith(
        mockProject.id,
        expect.objectContaining({
          expectedVersion: '2',
        }),
      )
      expect(screen.getByText('3')).toBeInTheDocument()
    })
  })

  it('drops stale responses when modal closes and reopens, preventing invalid onSuccess calls and state mutation', async () => {
    // 测试意图：验证组件卸载守卫；正在保存时关闭弹窗并重新打开，迟到的旧 Promise resolve 不触发新实例的 onSuccess，也不篡改新实例状态
    const user = userEvent.setup()
    let resolveBasic: (value: ProjectDTO) => void = () => {}
    const basicPromise = new Promise<ProjectDTO>((resolve) => {
      resolveBasic = resolve
    })
    const projectV2 = { ...mockProject, title: 'Late Resolved Title', version: '2' }
    const mockApi = {
      updateProject: vi.fn().mockImplementation(() => basicPromise),
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

    // 1. 提交 basic 保存，处于 pending
    await user.click(screen.getByRole('button', { name: '保存基础信息' }))
    expect(mockApi.updateProject).toHaveBeenCalledTimes(1)

    // 2. 关闭模态框
    rerender(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <EditProjectModal
          isOpen={false}
          project={mockProject}
          onClose={vi.fn()}
          onSuccess={onSuccess}
          api={mockApi}
        />
      </QueryClientProvider>,
    )

    // 3. 重新打开模态框（全新 mount，版本为 1）
    rerender(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <EditProjectModal
          isOpen={true}
          project={mockProject}
          onClose={vi.fn()}
          onSuccess={onSuccess}
          api={mockApi}
        />
      </QueryClientProvider>,
    )
    expect(screen.getByText('1')).toBeInTheDocument()

    // 4. 旧的 basicPromise 此时 resolve
    resolveBasic(projectV2)

    await new Promise((r) => setTimeout(r, 20))
    expect(onSuccess).not.toHaveBeenCalled()
    expect(screen.getByText('1')).toBeInTheDocument()
    expect(screen.queryByText('基础信息保存成功')).not.toBeInTheDocument()
  })

  it('drops stale responses on project switch so previous project resolution does not mutate new project modal', async () => {
    // 测试意图：验证切换项目时旧项目的未完成保存响应被丢弃，不污染新项目的表单与版本
    const user = userEvent.setup()
    let resolveProjectA: (value: ProjectDTO) => void = () => {}
    const projectAPromise = new Promise<ProjectDTO>((resolve) => {
      resolveProjectA = resolve
    })
    const projectA = { ...mockProject, id: 'proj-a', title: 'Project A', version: '1' }
    const projectB = { ...mockProject, id: 'proj-b', title: 'Project B', version: '10' }
    const mockApi = {
      updateProject: vi.fn().mockImplementation(() => projectAPromise),
    } as unknown as ProjectsApi
    const onSuccess = vi.fn()

    const { rerender } = renderWithClient(
      <EditProjectModal
        isOpen={true}
        project={projectA}
        onClose={vi.fn()}
        onSuccess={onSuccess}
        api={mockApi}
      />,
    )

    // 1. 发起 Project A 的保存
    await user.click(screen.getByRole('button', { name: '保存基础信息' }))

    // 2. 外部切换为 Project B（key 改变触发重构）
    rerender(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <EditProjectModal
          isOpen={true}
          project={projectB}
          onClose={vi.fn()}
          onSuccess={onSuccess}
          api={mockApi}
        />
      </QueryClientProvider>,
    )
    expect(screen.getByText('10')).toBeInTheDocument()
    expect((screen.getByLabelText(/项目名称/i) as HTMLInputElement).value).toBe('Project B')

    // 3. Project A 的响应返回
    resolveProjectA({ ...projectA, title: 'Updated Project A', version: '2' })
    await new Promise((r) => setTimeout(r, 20))

    // 4. 验证新表单不受污染
    expect(onSuccess).not.toHaveBeenCalled()
    expect(screen.getByText('10')).toBeInTheDocument()
    expect((screen.getByLabelText(/项目名称/i) as HTMLInputElement).value).toBe('Project B')
  })

  it('does not silently rebase dirty drafts or advance version when parent passes new project prop without user submission', async () => {
    // 测试意图：验证模态表单以打开时的快照为基线；外部父组件传入新的 project prop 不会静默覆盖用户正在编辑的草稿，也不自动推进版本，确保 CAS 安全性
    const user = userEvent.setup()
    const mockApi = {
      updateProject: vi.fn(),
      getProject: vi.fn(),
    } as unknown as ProjectsApi

    const { rerender } = renderWithClient(
      <EditProjectModal
        isOpen={true}
        project={mockProject}
        onClose={vi.fn()}
        onSuccess={vi.fn()}
        api={mockApi}
      />,
    )

    // 1. 用户编辑了草稿
    const titleInput = screen.getByLabelText(/项目名称/i) as HTMLInputElement
    await user.clear(titleInput)
    await user.type(titleInput, 'User Unsaved Draft')

    // 2. 父组件因后台外部事件更新传下新 project prop（版本为 5，远程 title 为 'Remote Changed Title'）
    const remoteProject: ProjectDTO = {
      ...mockProject,
      title: 'Remote Changed Title',
      version: '5',
    }
    rerender(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <EditProjectModal
          isOpen={true}
          project={remoteProject}
          onClose={vi.fn()}
          onSuccess={vi.fn()}
          api={mockApi}
        />
      </QueryClientProvider>,
    )

    // 3. 验证用户的草稿依然完好保留，期望版本保持为打开时的 1，不静默覆盖远端
    expect(titleInput.value).toBe('User Unsaved Draft')
    expect(screen.getByText('1')).toBeInTheDocument()
  })

  it('validates empty title and displays validation error', async () => {
    // 测试意图：验证基础信息保存时标题为空被拦截并展示错误提示
    const user = userEvent.setup()
    const mockApi = { updateProject: vi.fn() } as unknown as ProjectsApi

    renderWithClient(
      <EditProjectModal
        isOpen={true}
        project={mockProject}
        onClose={vi.fn()}
        onSuccess={vi.fn()}
        api={mockApi}
      />,
    )

    const titleInput = screen.getByLabelText(/项目名称/i)
    await user.clear(titleInput)
    await user.click(screen.getByRole('button', { name: '保存基础信息' }))

    expect(await screen.findByText('项目名称不能为空')).toBeInTheDocument()
    expect(mockApi.updateProject).not.toHaveBeenCalled()
  })

  it('handles json formatting error and workflow validation failure', async () => {
    // 测试意图：验证工作流 JSON 格式化非法文本时展示错误提示，缺少必要状态时提交被拦截
    const user = userEvent.setup()
    const mockApi = { updateWorkflow: vi.fn() } as unknown as ProjectsApi

    renderWithClient(
      <EditProjectModal
        isOpen={true}
        project={mockProject}
        onClose={vi.fn()}
        onSuccess={vi.fn()}
        api={mockApi}
      />,
    )

    await user.click(screen.getByRole('button', { name: '工作流 JSON 配置' }))
    const textarea = screen.getByLabelText(/工作流配置/i)

    // 输入非法 JSON 并格式化
    fireEvent.change(textarea, { target: { value: '{ invalid json' } })
    await user.click(screen.getByRole('button', { name: /格式化 JSON/i }))
    expect(await screen.findByText(/JSON 格式错误/i)).toBeInTheDocument()

    // 输入重复状态编码的 JSON 并保存
    fireEvent.change(textarea, {
      target: {
        value: JSON.stringify({
          states: [
            { state: 'INIT', name: '初始1' },
            { state: 'INIT', name: '初始2' },
            { state: 'BLOCKED', name: '阻塞' },
            { state: 'DONE', name: '完成' },
          ],
        }),
      },
    })
    await user.click(screen.getByRole('button', { name: '保存工作流' }))
    expect(await screen.findByText('工作流状态编码不可重复')).toBeInTheDocument()

    // 切换回 basic 标签页并编辑 description
    await user.click(screen.getByRole('button', { name: '基础信息' }))
    const descTextarea = screen.getByLabelText(/项目描述/i)
    fireEvent.change(descTextarea, { target: { value: 'Updated description' } })
    expect(descTextarea).toHaveValue('Updated description')
  })

  it('handles reload error and workflow 409 conflict error', async () => {
    // 测试意图：验证刷新版本接口报错时呈现错误提示，工作流保存 409 冲突时呈现冲突提示
    const user = userEvent.setup()
    const conflictError = new ApiError('Conflict on workflow', 409)
    const mockApi = {
      getProject: vi.fn().mockRejectedValue(new Error('Reload network failure')),
      updateWorkflow: vi.fn().mockRejectedValue(conflictError),
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

    // 1. 刷新版本报错
    await user.click(screen.getByRole('button', { name: '刷新版本' }))
    expect(await screen.findByText('Reload network failure')).toBeInTheDocument()

    // 2. 工作流 409 冲突
    await user.click(screen.getByRole('button', { name: '工作流 JSON 配置' }))
    await user.click(screen.getByRole('button', { name: '保存工作流' }))
    expect(await screen.findByText(/工作流配置更新冲突 \(409\)/i)).toBeInTheDocument()
  })

  it('enforces mutual exclusion between reload and save actions in both directions', async () => {
    // 测试意图：验证 reload 与保存操作双向互斥：reload 在途时禁止发起保存；保存发起在途时禁止触发 reload
    const user = userEvent.setup()
    let resolveReload: (value: ProjectDTO) => void = () => {}
    let resolveBasic: (value: ProjectDTO) => void = () => {}

    const mockApi = {
      getProject: vi.fn().mockImplementation(() => new Promise((resolve) => { resolveReload = resolve })),
      updateProject: vi.fn().mockImplementation(() => new Promise((resolve) => { resolveBasic = resolve })),
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

    // 1. 发起 reload，在途期间保存按钮 disabled 且点击被拦截
    await user.click(screen.getByRole('button', { name: '刷新版本' }))
    expect(mockApi.getProject).toHaveBeenCalledTimes(1)
    const saveBtn = screen.getByRole('button', { name: '保存基础信息' })
    expect(saveBtn).toBeDisabled()
    await user.click(saveBtn)
    expect(mockApi.updateProject).not.toHaveBeenCalled()

    // 完成 reload
    resolveReload({ ...mockProject, version: '2' })
    await waitFor(() => {
      expect(screen.getByText('2')).toBeInTheDocument()
      expect(saveBtn).not.toBeDisabled()
    })

    // 2. 发起保存，在途期间刷新版本按钮 disabled 且点击被拦截
    await user.click(saveBtn)
    expect(mockApi.updateProject).toHaveBeenCalledTimes(1)
    const reloadBtn = screen.getByRole('button', { name: '刷新版本' })
    expect(reloadBtn).toBeDisabled()
    await user.click(reloadBtn)
    expect(mockApi.getProject).toHaveBeenCalledTimes(1)

    resolveBasic({ ...mockProject, version: '3' })
    await waitFor(() => {
      expect(screen.getByText('3')).toBeInTheDocument()
    })
  })

  it('drops late rejection when modal closes and reopens, preventing error pollution in new modal instance', async () => {
    // 测试意图：验证在途请求失败且遇到关闭重开时，迟到的 reject 被抛弃，不污染新打开的弹窗界面
    const user = userEvent.setup()
    let rejectBasic: (reason: unknown) => void = () => {}
    const basicPromise = new Promise<ProjectDTO>((_, reject) => {
      rejectBasic = reject
    })
    const mockApi = {
      updateProject: vi.fn().mockImplementation(() => basicPromise),
    } as unknown as ProjectsApi

    const { rerender } = renderWithClient(
      <EditProjectModal
        isOpen={true}
        project={mockProject}
        onClose={vi.fn()}
        onSuccess={vi.fn()}
        api={mockApi}
      />,
    )

    // 发起保存后立即关闭
    await user.click(screen.getByRole('button', { name: '保存基础信息' }))
    expect(mockApi.updateProject).toHaveBeenCalledTimes(1)

    rerender(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <EditProjectModal
          isOpen={false}
          project={mockProject}
          onClose={vi.fn()}
          onSuccess={vi.fn()}
          api={mockApi}
        />
      </QueryClientProvider>,
    )

    // 重新打开弹窗
    rerender(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <EditProjectModal
          isOpen={true}
          project={mockProject}
          onClose={vi.fn()}
          onSuccess={vi.fn()}
          api={mockApi}
        />
      </QueryClientProvider>,
    )

    // 迟到的旧请求 reject
    rejectBasic(new Error('Delayed server error'))
    await new Promise((r) => setTimeout(r, 20))

    // 验证新弹窗干净无错误横幅
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.queryByText('Delayed server error')).not.toBeInTheDocument()
  })

  it('does not rollback expectedVersion when reload returns a lower version than current', async () => {
    // 测试意图：验证当服务端因副本延迟返回较旧版本时，期望版本单调递增，不向后回退
    const user = userEvent.setup()
    const mockApi = {
      getProject: vi.fn().mockResolvedValue({ ...mockProject, version: '1' }),
      updateProject: vi.fn().mockResolvedValue({ ...mockProject, version: '5' }),
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

    // 1. 保存成功将当前版本提升至 5
    await user.click(screen.getByRole('button', { name: '保存基础信息' }))
    await waitFor(() => {
      expect(screen.getByText('5')).toBeInTheDocument()
    })

    // 2. 服务端返回旧版本 1，验证期望版本保持 5，不发生回退
    await user.click(screen.getByRole('button', { name: '刷新版本' }))
    await waitFor(() => {
      expect(mockApi.getProject).toHaveBeenCalledTimes(1)
    })
    expect(screen.getByText('5')).toBeInTheDocument()
  })

  it('keeps dirty draft and does not advance expectedVersion upon 409 conflict', async () => {
    // 测试意图：验证 409 冲突时不推进 expectedVersion 且保留用户草稿，供用户核对后显式决定
    const user = userEvent.setup()
    const conflictError = new ApiError('Conflict 409', 409)
    const mockApi = {
      updateProject: vi.fn().mockRejectedValue(conflictError),
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
    await user.type(titleInput, 'Conflict Draft')
    await user.click(screen.getByRole('button', { name: '保存基础信息' }))

    await waitFor(() => {
      expect(screen.getByText(/409 冲突/i)).toBeInTheDocument()
    })
    // 版本仍为 1，草稿仍为 'Conflict Draft'
    expect(screen.getByText('1')).toBeInTheDocument()
    expect(titleInput.value).toBe('Conflict Draft')
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
