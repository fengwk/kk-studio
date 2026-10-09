import { cloneElement, type ReactElement } from 'react'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/shared/api/client'
import { agentService } from '@/shared/api/agent-service'
import { queryKeys } from '@/shared/lib/query-keys'
import { CreateProjectModal } from './CreateProjectModal'
import { EditProjectModal, type EditProjectModalProps } from './EditProjectModal'
import { DeleteProjectModal } from './DeleteProjectModal'
import type { ProjectDTO, ProjectIssueSnapshotDTO } from '../types'
import type { ProjectsApi } from '../projects-api'

// Agent 目录与环境列表由共享查询提供；这里固定为可预期的目录内容，
// 以便验证「目录中存在的 Agent」与「仅存于草稿中的历史 Agent」两条路径。
vi.mock('@/shared/api/agent-service', () => ({
  agentService: {
    listAgents: vi.fn().mockResolvedValue({
      results: [{ name: 'architect' }, { name: 'backend-dev' }],
      totalCount: 2,
    }),
  },
}))

vi.mock('@/shared/api/environment-service', () => ({
  environmentService: {
    listEnvironments: vi.fn().mockResolvedValue([{ name: 'macos' }, { name: 'ubuntu' }]),
    getEnvironmentUpdate: vi.fn(),
    startEnvironmentUpdate: vi.fn(),
  },
}))

function client() {
  return new QueryClient({ defaultOptions: { queries: { retry: false } } })
}

function renderWithClient(ui: React.ReactElement) {
  return render(<QueryClientProvider client={client()}>{withSnapshot(ui)}</QueryClientProvider>)
}

/** 子表单单测提供权威引用快照；异步读取测试直接渲染未注入快照的弹窗。 */
function withSnapshot(ui: ReactElement) {
  if (ui.type !== EditProjectModal) return ui
  const modal = ui as ReactElement<EditProjectModalProps>
  const project = modal.props.project
  return cloneElement(modal, {
    snapshot: modal.props.snapshot ?? (project
      ? { project, issues: [], referencedStateCodes: [] }
      : undefined),
  })
}

function rerenderWithClient(
  rerender: ReturnType<typeof render>['rerender'],
  ui: React.ReactElement,
) {
  rerender(<QueryClientProvider client={client()}>{withSnapshot(ui)}</QueryClientProvider>)
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

/** 多 next 图 + 停用阶段 + Agent 阶段，用于结构化往返与引用约束。 */
const workflowProject: ProjectDTO = {
  ...mockProject,
  workflow: {
    states: [
      { state: 'INIT', name: '待开始', next: ['WORK', 'REVIEW'] },
      {
        state: 'WORK',
        name: '处理中',
        agent: 'backend-dev',
        environment: 'ubuntu',
        instructions: '实现并自测',
        maxRuns: '3',
        next: ['REVIEW', 'DONE'],
      },
      { state: 'REVIEW', name: '评审', enabled: false, next: ['DONE'] },
      { state: 'BLOCKED', name: '业务阻塞' },
      { state: 'DONE', name: '完成' },
    ],
  },
}

function issueSnapshot(state: string, status: string | null = null): ProjectIssueSnapshotDTO {
  return {
    issue: { state, blockedFromState: null },
    currentOrLatestRun: status ? { status } : null,
  } as unknown as ProjectIssueSnapshotDTO
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
  it.each(['empty', 'loading', 'failed'] as const)(
    'keeps incomplete Agent mode with a %s catalog and saves only after explicit configuration',
    async (catalogState) => {
      // 空/读取中/失败目录不撤销用户模式选择；真实查询刷新后仍须显式选 Agent 与额度。
      const user = userEvent.setup()
      const emptyPage = { results: [], totalCount: 0, pageNumber: 1, pageSize: 50 }
      let resolveCatalog!: (value: typeof emptyPage) => void
      if (catalogState === 'loading') {
        vi.mocked(agentService.listAgents).mockImplementationOnce(
          () => new Promise((resolve) => { resolveCatalog = resolve }),
        )
      } else if (catalogState === 'failed') {
        vi.mocked(agentService.listAgents).mockRejectedValueOnce(new Error('Catalog unavailable'))
      } else {
        vi.mocked(agentService.listAgents).mockResolvedValueOnce(emptyPage)
      }
      const project = {
        ...workflowProject,
        workflow: { states: workflowProject.workflow.states.map((state) =>
          state.state === 'WORK'
            ? { ...state, agent: null, environment: null, maxRuns: null }
            : state,
        ) },
      }
      const api = {
        updateWorkflow: vi.fn().mockResolvedValue({ ...project, version: '2' }),
      } as unknown as ProjectsApi
      const queryClient = client()
      render(<QueryClientProvider client={queryClient}>
        <EditProjectModal isOpen initialTab="workflow" project={project}
          snapshot={{ project, issues: [], referencedStateCodes: [] }}
          api={api} onClose={vi.fn()} onSuccess={vi.fn()} />
      </QueryClientProvider>)
      const catalogKey = [...queryKeys.agents.list, 'all-names']
      if (catalogState !== 'loading') {
        await waitFor(() => expect(queryClient.getQueryData(catalogKey)).toEqual([]))
      }
      await user.click(screen.getByRole('button', { name: /2处理中/ }))
      await user.click(screen.getByRole('button', { name: '执行方式' }))
      await user.click(screen.getByRole('option', { name: 'Agent 执行' }))
      expect(screen.getByRole('button', { name: '执行方式' })).toHaveTextContent('Agent 执行')
      const agentField = screen.getByRole('button', { name: 'Agent', exact: true })
      expect(agentField).toHaveAttribute('aria-required', 'true')
      expect(agentField).toHaveAttribute('aria-invalid', 'true')
      expect(within(screen.getByRole('button', { name: /2处理中/ })).getByText('Agent')).toBeInTheDocument()
      if (catalogState === 'loading') {
        expect(agentField).toBeDisabled()
      }
      await user.click(screen.getByRole('button', { name: '保存工作流' }))
      expect(await screen.findByText('Agent 阶段「WORK」必须选择 Agent')).toBeInTheDocument()
      expect(api.updateWorkflow).not.toHaveBeenCalled()

      if (catalogState === 'loading') {
        await act(async () => { resolveCatalog(emptyPage) })
        await waitFor(() => expect(agentField).toBeEnabled())
      }
      await act(async () => { await queryClient.invalidateQueries({ queryKey: catalogKey, exact: true }) })
      expect(screen.getByRole('button', { name: '执行方式' })).toHaveTextContent('Agent 执行')
      expect(agentField).toHaveAttribute('aria-invalid', 'true')
      await user.click(agentField)
      await user.click(screen.getByRole('option', { name: 'backend-dev' }))
      expect(agentField).not.toHaveAttribute('aria-invalid')
      await user.type(screen.getByRole('textbox', { name: 'Run 额度（maxRuns）' }), '3')
      await user.click(screen.getByRole('button', { name: '保存工作流' }))
      await waitFor(() => expect(api.updateWorkflow).toHaveBeenCalledTimes(1))
      expect(api.updateWorkflow).toHaveBeenCalledWith(project.id, expect.objectContaining({
        expectedVersion: '1',
        workflow: { states: expect.arrayContaining([
          expect.objectContaining({ state: 'WORK', agent: 'backend-dev', maxRuns: '3' }),
        ]) },
      }))
    },
  )

  it('uses initialTab only on mount and applies the requested tab again on reopen', async () => {
    // initialTab 不是受控页签：打开期间 prop 变化不覆盖用户选择，重开才重新初始化。
    const user = userEvent.setup()
    const props = { project: mockProject, onClose: vi.fn(), onSuccess: vi.fn() }
    const { rerender } = renderWithClient(
      <EditProjectModal {...props} isOpen initialTab="workflow" />,
    )
    expect(screen.getByRole('tab', { name: '工作流' })).toHaveAttribute('aria-selected', 'true')
    await user.click(screen.getByRole('tab', { name: '基础信息' }))
    await user.clear(screen.getByLabelText(/项目名称/))
    await user.type(screen.getByLabelText(/项目名称/), 'Local Project')
    rerenderWithClient(rerender, <EditProjectModal {...props} isOpen initialTab="basic" />)
    rerenderWithClient(rerender, <EditProjectModal {...props} isOpen initialTab="workflow" />)
    expect(screen.getByRole('tab', { name: '基础信息' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByLabelText(/项目名称/)).toHaveValue('Local Project')

    rerenderWithClient(rerender, <EditProjectModal {...props} isOpen={false} />)
    rerenderWithClient(rerender, <EditProjectModal {...props} isOpen />)
    expect(screen.getByRole('tab', { name: '基础信息' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByLabelText(/项目名称/)).toHaveValue(mockProject.title)
  })

  it('protects archived-only and blocked-from references even with no visible issues', async () => {
    const user = userEvent.setup()
    const project = {
      ...mockProject,
      workflow: { states: [
        ...mockProject.workflow.states,
        { state: 'OLD', name: '归档来源', enabled: false, next: [] },
        { state: 'SOURCE', name: '阻塞来源', enabled: false, next: [] },
      ] },
    }
    renderWithClient(
      <EditProjectModal isOpen project={project}
        snapshot={{ project, issues: [], referencedStateCodes: ['OLD', 'SOURCE'] }}
        onClose={vi.fn()} onSuccess={vi.fn()} />,
    )
    await user.click(screen.getByRole('tab', { name: '工作流' }))
    await user.click(screen.getByRole('button', { name: /4归档来源/ }))
    expect(screen.getByRole('button', { name: '删除 归档来源' })).toBeDisabled()
    expect(screen.getByLabelText(/状态标识/)).toBeDisabled()
    await user.click(screen.getByRole('button', { name: /5阻塞来源/ }))
    expect(screen.getByRole('button', { name: '删除 阻塞来源' })).toBeDisabled()
    expect(screen.getByLabelText(/状态标识/)).toBeDisabled()
  })

  it('locks delete, rename and save until list-entry references have actually loaded', async () => {
    const user = userEvent.setup()
    const project = { ...mockProject, workflow: { states: [
      ...mockProject.workflow.states,
      { state: 'OLD', name: 'Old', enabled: false, next: [] },
    ] } }
    let resolveSnapshot!: (value: { project: ProjectDTO; issues: []; referencedStateCodes: string[] }) => void
    const api = {
      getProjectSnapshot: vi.fn().mockReturnValue(new Promise((resolve) => { resolveSnapshot = resolve })),
      updateWorkflow: vi.fn(),
    } as unknown as ProjectsApi
    render(<QueryClientProvider client={client()}>
      <EditProjectModal isOpen project={project} api={api} onClose={vi.fn()} onSuccess={vi.fn()} />
    </QueryClientProvider>)
    await user.click(screen.getByRole('tab', { name: '工作流' }))
    await user.click(screen.getByRole('button', { name: /4Old/ }))
    expect(screen.getByRole('button', { name: '删除 Old' })).toBeDisabled()
    expect(screen.getByLabelText(/状态标识/)).toBeDisabled()
    expect(screen.getByRole('button', { name: '保存工作流' })).toBeDisabled()
    expect(api.updateWorkflow).not.toHaveBeenCalled()
    resolveSnapshot({ project, issues: [], referencedStateCodes: [] })
    await waitFor(() => expect(screen.getByRole('button', { name: '删除 Old' })).toBeEnabled())
    expect(screen.getByLabelText(/状态标识/)).toBeEnabled()
    expect(screen.getByRole('button', { name: '保存工作流' })).toBeEnabled()
  })

  it('loads list-entry constraints only when workflow opens, fails closed and retries without replacing the draft', async () => {
    const user = userEvent.setup()
    const api = {
      getProjectSnapshot: vi.fn()
        .mockRejectedValueOnce(new Error('Reference read failed'))
        .mockResolvedValue({ project: { ...mockProject, version: '9' }, issues: [], referencedStateCodes: [] }),
      updateWorkflow: vi.fn().mockResolvedValue({ ...mockProject, version: '2' }),
    } as unknown as ProjectsApi
    render(<QueryClientProvider client={client()}>
      <EditProjectModal isOpen project={mockProject} api={api} onClose={vi.fn()} onSuccess={vi.fn()} />
    </QueryClientProvider>)
    expect(api.getProjectSnapshot).not.toHaveBeenCalled()
    await user.click(screen.getByRole('tab', { name: '工作流' }))
    expect(await screen.findByText('Reference read failed')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '保存工作流' })).toBeDisabled()
    await user.clear(screen.getByLabelText(/显示名称/))
    await user.type(screen.getByLabelText(/显示名称/), 'Local Init')
    await user.click(screen.getByRole('button', { name: '重试' }))
    await waitFor(() => expect(screen.getByRole('button', { name: '保存工作流' })).toBeEnabled())
    expect(screen.getByLabelText(/显示名称/)).toHaveValue('Local Init')
    await user.click(screen.getByRole('button', { name: '保存工作流' }))
    expect(api.updateWorkflow).toHaveBeenCalledWith(mockProject.id, expect.objectContaining({
      expectedVersion: '1',
    }))
  })

  it('revalidates pushed references without replacing the workflow draft or frozen CAS', async () => {
    const user = userEvent.setup()
    const project = { ...mockProject, workflow: { states: [
      ...mockProject.workflow.states,
      { state: 'OLD', name: 'Old', enabled: false, next: [] },
    ] } }
    const api = { updateWorkflow: vi.fn() } as unknown as ProjectsApi
    const props = { isOpen: true, project, api, onClose: vi.fn(), onSuccess: vi.fn() }
    const { rerender } = renderWithClient(<EditProjectModal {...props}
      snapshot={{ project, issues: [], referencedStateCodes: [] }} />)
    await user.click(screen.getByRole('tab', { name: '工作流' }))
    await user.click(screen.getByRole('button', { name: '删除 Old' }))
    rerenderWithClient(rerender, <EditProjectModal {...props}
      snapshot={{ project: { ...project, version: '9' }, issues: [], referencedStateCodes: ['OLD'] }} />)
    await user.click(screen.getByRole('button', { name: '保存工作流' }))
    expect(api.updateWorkflow).not.toHaveBeenCalled()
    expect(screen.getByRole('alert')).toHaveTextContent('OLD')
    expect(screen.queryByRole('button', { name: '删除 Old' })).not.toBeInTheDocument()
  })

  it('saves basic info with the version frozen at open time', async () => {
    // 测试意图：基础信息保存严格使用打开时冻结的 expectedVersion，成功后提示并回调
    const user = userEvent.setup()
    const mockApi = {
      updateProject: vi.fn().mockResolvedValue({ ...mockProject, version: '2' }),
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

    await user.click(screen.getByRole('button', { name: '保存基础信息' }))

    await waitFor(() => {
      expect(mockApi.updateProject).toHaveBeenCalledWith(mockProject.id, {
        expectedVersion: '1',
        title: 'Existing Project',
        description: 'Project desc',
      })
    })
    expect(await screen.findByText('基础信息保存成功')).toBeInTheDocument()
    expect(onSuccess).toHaveBeenCalled()
  })

  it('validates empty title and displays validation error', async () => {
    // 测试意图：基础信息保存时标题为空被拦截并展示错误提示
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

    await user.clear(screen.getByLabelText(/项目名称/i))
    await user.click(screen.getByRole('button', { name: '保存基础信息' }))

    expect(await screen.findByText('项目名称不能为空')).toBeInTheDocument()
    expect(mockApi.updateProject).not.toHaveBeenCalled()
  })

  it('keeps the dirty draft and the open-time CAS baseline when the parent pushes a new project prop', async () => {
    // 测试意图：外部推送新 project prop（后台失效广播）不得静默改写草稿，也不得自动推进 expectedVersion
    const user = userEvent.setup()
    const mockApi = { updateProject: vi.fn() } as unknown as ProjectsApi

    const { rerender } = renderWithClient(
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
    await user.type(titleInput, 'User Unsaved Draft')

    rerenderWithClient(
      rerender,
      <EditProjectModal
        isOpen={true}
        project={{ ...mockProject, title: 'Remote Changed Title', version: '5' }}
        onClose={vi.fn()}
        onSuccess={vi.fn()}
        api={mockApi}
      />,
    )

    expect(titleInput.value).toBe('User Unsaved Draft')
    await user.click(screen.getByRole('button', { name: '保存基础信息' }))
    await waitFor(() => {
      expect(mockApi.updateProject).toHaveBeenCalledWith(mockProject.id, {
        expectedVersion: '1',
        title: 'User Unsaved Draft',
        description: 'Project desc',
      })
    })
  })

  it('inherits the version advanced by an earlier subform save for the next save', async () => {
    // 测试意图：YOLO 子表单保存成功推进版本后，基础信息保存继承新的权威版本
    const user = userEvent.setup()
    const projectV2 = { ...mockProject, yoloEnabled: false, version: '2' }
    const mockApi = {
      updateYolo: vi.fn().mockResolvedValue(projectV2),
      updateProject: vi.fn().mockResolvedValue({ ...projectV2, version: '3' }),
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

    await user.click(screen.getByRole('checkbox'))
    await user.click(screen.getByRole('button', { name: '保存 YOLO 模式' }))
    await waitFor(() => {
      expect(mockApi.updateYolo).toHaveBeenCalledWith(mockProject.id, {
        expectedVersion: '1',
        yoloEnabled: false,
      })
    })

    await user.click(screen.getByRole('button', { name: '保存基础信息' }))
    await waitFor(() => {
      expect(mockApi.updateProject).toHaveBeenCalledWith(mockProject.id, {
        expectedVersion: '2',
        title: 'Existing Project',
        description: 'Project desc',
      })
    })
  })

  it('keeps independent subform results when a later subform fails with a definite error', async () => {
    // 测试意图：基础信息成功后 YOLO 返回明确错误（4xx），只在 YOLO 区域提示，不改写成功结果与版本
    const user = userEvent.setup()
    const projectV2 = { ...mockProject, version: '2' }
    const mockApi = {
      updateProject: vi.fn().mockResolvedValue(projectV2),
      updateYolo: vi.fn().mockRejectedValue(new ApiError('YOLO 参数非法', 400)),
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

    await user.click(screen.getByRole('button', { name: '保存基础信息' }))
    expect(await screen.findByText('基础信息保存成功')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '保存 YOLO 模式' }))
    expect(await screen.findByText('YOLO 参数非法')).toBeInTheDocument()
    expect(screen.getByText('基础信息保存成功')).toBeInTheDocument()

    // 明确失败不推进版本：再次保存基础信息仍使用成功后的 2，而不是 3
    await user.click(screen.getByRole('button', { name: '保存基础信息' }))
    await waitFor(() => {
      expect(mockApi.updateProject).toHaveBeenLastCalledWith(mockProject.id, {
        expectedVersion: '2',
        title: 'Existing Project',
        description: 'Project desc',
      })
    })
  })

  it('treats an unknown transport outcome as needing explicit recovery', async () => {
    // 测试意图：网络结果未知时保留草稿并给出显式恢复入口，而不是普通错误提示
    const user = userEvent.setup()
    const mockApi = {
      updateProject: vi.fn().mockRejectedValue(new ApiError('Network Error')),
      getProject: vi.fn().mockResolvedValue({ ...mockProject, version: '4' }),
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
    await user.type(titleInput, 'Offline Draft')
    await user.click(screen.getByRole('button', { name: '保存基础信息' }))

    expect(await screen.findByText(/基础信息未确认写入结果/)).toBeInTheDocument()
    expect(titleInput.value).toBe('Offline Draft')
    expect(screen.queryByText('Network Error')).not.toBeInTheDocument()
  })

  it('keeps the draft on 409 conflict and retries with the version loaded on demand', async () => {
    // 测试意图：409 冲突时草稿与版本基线冻结，只有显式「加载最新并保留草稿」才推进 expectedVersion
    const user = userEvent.setup()
    const mockApi = {
      updateProject: vi
        .fn()
        .mockRejectedValueOnce(new ApiError('Conflict occurred', 409))
        .mockResolvedValue({ ...mockProject, version: '6' }),
      getProject: vi
        .fn()
        .mockResolvedValue({ ...mockProject, title: 'Fresh Title', version: '5' }),
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

    expect(await screen.findByText(/基础信息未确认写入结果/)).toBeInTheDocument()
    expect(titleInput.value).toBe('Draft New Title')

    await user.click(screen.getByRole('button', { name: '加载最新并保留草稿' }))
    await waitFor(() => {
      expect(mockApi.getProject).toHaveBeenCalledWith(mockProject.id)
    })
    // 保留草稿：不套用最新数据里的标题
    expect(titleInput.value).toBe('Draft New Title')

    await user.click(screen.getByRole('button', { name: '保存基础信息' }))
    await waitFor(() => {
      expect(mockApi.updateProject).toHaveBeenLastCalledWith(mockProject.id, {
        expectedVersion: '5',
        title: 'Draft New Title',
        description: 'Project desc',
      })
    })
  })

  it('discards the workflow draft and adopts the latest snapshot on demand after a 409', async () => {
    // 测试意图：工作流 409 后选择放弃草稿，则工作流编辑器重置为最新权威数据并使用其版本提交
    const user = userEvent.setup()
    const freshWorkflow = {
      states: [
        { state: 'INIT', name: '待开始', next: ['DONE'] },
        { state: 'BLOCKED', name: '业务阻塞' },
        { state: 'DONE', name: '完成' },
      ],
    }
    const mockApi = {
      updateWorkflow: vi
        .fn()
        .mockRejectedValueOnce(new ApiError('Conflict on workflow', 409))
        .mockResolvedValue({ ...workflowProject, version: '8' }),
      getProject: vi
        .fn()
        .mockResolvedValue({ ...workflowProject, workflow: freshWorkflow, version: '7' }),
    } as unknown as ProjectsApi

    renderWithClient(
      <EditProjectModal
        isOpen={true}
        project={workflowProject}
        onClose={vi.fn()}
        onSuccess={vi.fn()}
        api={mockApi}
      />,
    )

    await user.click(screen.getByRole('tab', { name: '工作流' }))
    expect(screen.getAllByRole('listitem')).toHaveLength(5)

    await user.click(screen.getByRole('button', { name: '保存工作流' }))
    expect(await screen.findByText(/工作流配置未确认写入结果/)).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '放弃草稿并加载最新' }))
    await waitFor(() => {
      expect(screen.getAllByRole('listitem')).toHaveLength(3)
    })
    expect(screen.queryByRole('button', { name: /^\d+ 评审/ })).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '保存工作流' }))
    await waitFor(() => {
      expect(mockApi.updateWorkflow).toHaveBeenLastCalledWith(workflowProject.id, {
        expectedVersion: '7',
        workflow: {
          states: [
            { state: 'INIT', name: '待开始', enabled: true, next: ['DONE'] },
            { state: 'BLOCKED', name: '业务阻塞', enabled: true, next: [] },
            { state: 'DONE', name: '完成', enabled: true, next: [] },
          ],
        },
      })
    })
  })

  it('mutually excludes concurrent submissions across scopes', async () => {
    // 测试意图：单一在途提交互斥；基础信息在途时其它作用域的保存按钮禁用且不发请求
    const user = userEvent.setup()
    let resolveBasic: (value: ProjectDTO) => void = () => {}
    const mockApi = {
      updateProject: vi.fn().mockImplementation(
        () =>
          new Promise<ProjectDTO>((resolve) => {
            resolveBasic = resolve
          }),
      ),
      updateYolo: vi.fn(),
      updateWorkflow: vi.fn(),
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

    await user.click(screen.getByRole('button', { name: '保存基础信息' }))
    expect(mockApi.updateProject).toHaveBeenCalledTimes(1)
    expect(screen.getByRole('button', { name: '保存中...' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '保存 YOLO 模式' })).toBeDisabled()

    await user.click(screen.getByRole('tab', { name: '工作流' }))
    const workflowBtn = screen.getByRole('button', { name: '保存工作流' })
    expect(workflowBtn).toBeDisabled()
    await user.click(workflowBtn)
    expect(mockApi.updateWorkflow).not.toHaveBeenCalled()

    resolveBasic({ ...mockProject, version: '2' })
    await waitFor(() => {
      expect(onSuccess).toHaveBeenCalledWith({ ...mockProject, version: '2' })
    })
    expect(mockApi.updateYolo).not.toHaveBeenCalled()
  })

  it('drops stale responses when the modal closes and reopens', async () => {
    // 测试意图：卸载守卫——关闭重开后迟到的旧响应不触发新实例的 onSuccess，也不写入新实例状态
    const user = userEvent.setup()
    let resolveBasic: (value: ProjectDTO) => void = () => {}
    const mockApi = {
      updateProject: vi.fn().mockImplementation(
        () =>
          new Promise<ProjectDTO>((resolve) => {
            resolveBasic = resolve
          }),
      ),
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

    await user.click(screen.getByRole('button', { name: '保存基础信息' }))
    expect(mockApi.updateProject).toHaveBeenCalledTimes(1)

    rerenderWithClient(
      rerender,
      <EditProjectModal
        isOpen={false}
        project={mockProject}
        onClose={vi.fn()}
        onSuccess={onSuccess}
        api={mockApi}
      />,
    )
    rerenderWithClient(
      rerender,
      <EditProjectModal
        isOpen={true}
        project={mockProject}
        onClose={vi.fn()}
        onSuccess={onSuccess}
        api={mockApi}
      />,
    )

    resolveBasic({ ...mockProject, title: 'Late Resolved Title', version: '2' })
    await new Promise((resolve) => setTimeout(resolve, 20))

    expect(onSuccess).not.toHaveBeenCalled()
    expect(screen.queryByText('基础信息保存成功')).not.toBeInTheDocument()
  })

  it('drops a late rejection when the modal closes and reopens', async () => {
    // 测试意图：关闭重开后迟到的旧失败不污染新弹窗的错误区域
    const user = userEvent.setup()
    let rejectBasic: (reason: unknown) => void = () => {}
    const mockApi = {
      updateProject: vi.fn().mockImplementation(
        () =>
          new Promise<ProjectDTO>((_, reject) => {
            rejectBasic = reject
          }),
      ),
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

    await user.click(screen.getByRole('button', { name: '保存基础信息' }))
    expect(mockApi.updateProject).toHaveBeenCalledTimes(1)

    rerenderWithClient(
      rerender,
      <EditProjectModal
        isOpen={false}
        project={mockProject}
        onClose={vi.fn()}
        onSuccess={vi.fn()}
        api={mockApi}
      />,
    )
    rerenderWithClient(
      rerender,
      <EditProjectModal
        isOpen={true}
        project={mockProject}
        onClose={vi.fn()}
        onSuccess={vi.fn()}
        api={mockApi}
      />,
    )

    rejectBasic(new Error('Delayed server error'))
    await new Promise((resolve) => setTimeout(resolve, 20))

    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.queryByText('Delayed server error')).not.toBeInTheDocument()
  })

  it('round-trips the structured workflow, preserving multi-next, disabled stages and Agent fields', async () => {
    // 测试意图：工作流以结构化编辑器整体保存，多 next、停用阶段与 Agent 字段原样往返
    const user = userEvent.setup()
    const mockApi = {
      updateWorkflow: vi.fn().mockResolvedValue({ ...workflowProject, version: '2' }),
    } as unknown as ProjectsApi
    const onSuccess = vi.fn()

    renderWithClient(
      <EditProjectModal
        isOpen={true}
        project={workflowProject}
        onClose={vi.fn()}
        onSuccess={onSuccess}
        api={mockApi}
      />,
    )

    await user.click(screen.getByRole('tab', { name: '工作流' }))
    const list = screen.getByRole('list', { name: '工作流阶段' })
    expect(within(list).getAllByRole('listitem')).toHaveLength(5)

    await user.click(screen.getByRole('button', { name: '保存工作流' }))

    await waitFor(() => {
      expect(mockApi.updateWorkflow).toHaveBeenCalledWith(workflowProject.id, {
        expectedVersion: '1',
        workflow: {
          states: [
            { state: 'INIT', name: '待开始', enabled: true, next: ['WORK', 'REVIEW'] },
            {
              state: 'WORK',
              name: '处理中',
              enabled: true,
              next: ['REVIEW', 'DONE'],
              agent: 'backend-dev',
              environment: 'ubuntu',
              instructions: '实现并自测',
              maxRuns: '3',
            },
            { state: 'REVIEW', name: '评审', enabled: false, next: ['DONE'] },
            { state: 'BLOCKED', name: '业务阻塞', enabled: true, next: [] },
            { state: 'DONE', name: '完成', enabled: true, next: [] },
          ],
        },
      })
    })
    expect(await screen.findByText('工作流配置保存成功')).toBeInTheDocument()
    expect(onSuccess).toHaveBeenCalled()
  })

  it('blocks an invalid workflow draft before calling the API', async () => {
    // 测试意图：缺少 INIT → DONE 正常路径的草稿被前端拦截，不发起请求
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

    await user.click(screen.getByRole('tab', { name: '工作流' }))
    // INIT 默认选中：取消唯一的后续阶段，工作流不再存在到 DONE 的正常路径
    await user.click(screen.getByRole('checkbox', { name: '完成（DONE）' }))

    await user.click(screen.getByRole('button', { name: '保存工作流' }))

    expect(
      await screen.findByText('工作流必须提供从 INIT 到 DONE 的正常路径'),
    ).toBeInTheDocument()
    expect(mockApi.updateWorkflow).not.toHaveBeenCalled()
  })

  it('cannot delete a stage still referenced by issues or transition edges', async () => {
    // 测试意图：被 Issue 引用的阶段与保留状态不可删除，未被引用的阶段仍可删除
    const user = userEvent.setup()
    const mockApi = { updateWorkflow: vi.fn() } as unknown as ProjectsApi

    renderWithClient(
      <EditProjectModal
        isOpen={true}
        project={workflowProject}
        snapshot={{
          project: workflowProject,
          issues: [],
          referencedStateCodes: ['REVIEW'],
        }}
        onClose={vi.fn()}
        onSuccess={vi.fn()}
        api={mockApi}
      />,
    )

    await user.click(screen.getByRole('tab', { name: '工作流' }))

    expect(screen.getByRole('button', { name: '删除 评审' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '删除 待开始' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '删除 处理中' })).toBeDisabled() // 被 INIT 的转移边引用
  })

  it('locks workflow editing while the project has an active run', async () => {
    // 测试意图：存在活动 Run 时禁止改写工作流，并给出原因
    const user = userEvent.setup()

    renderWithClient(
      <EditProjectModal
        isOpen={true}
        project={workflowProject}
        snapshot={{
          project: workflowProject,
          issues: [issueSnapshot('WORK', 'RUNNING')],
          referencedStateCodes: ['WORK'],
        }}
        onClose={vi.fn()}
        onSuccess={vi.fn()}
        api={{ updateWorkflow: vi.fn() } as unknown as ProjectsApi}
      />,
    )

    await user.click(screen.getByRole('tab', { name: '工作流' }))

    expect(
      screen.getByText('项目存在活动 Run，运行结束并结算后才能修改工作流'),
    ).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '保存工作流' })).toBeDisabled()
  })

  it('locks workflow editing for an archived project', async () => {
    // 测试意图：归档项目在取消归档前禁止改写工作流
    const user = userEvent.setup()

    renderWithClient(
      <EditProjectModal
        isOpen={true}
        project={{ ...workflowProject, archivedAt: '2026-09-20T00:00:00Z' }}
        onClose={vi.fn()}
        onSuccess={vi.fn()}
        api={{ updateWorkflow: vi.fn() } as unknown as ProjectsApi}
      />,
    )

    await user.click(screen.getByRole('tab', { name: '工作流' }))

    expect(screen.getByText('项目已归档，取消归档后才能修改工作流')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '保存工作流' })).toBeDisabled()
  })

  it('keeps a configured Agent that is missing from the catalog', async () => {
    // 测试意图：目录中不存在的已配置 Agent 被透明保留并提示，不静默替换
    const user = userEvent.setup()

    renderWithClient(
      <EditProjectModal
        isOpen={true}
        project={{
          ...workflowProject,
          workflow: {
            states: workflowProject.workflow.states.map((state) =>
              state.state === 'WORK' ? { ...state, agent: 'legacy-agent' } : state,
            ),
          },
        }}
        onClose={vi.fn()}
        onSuccess={vi.fn()}
        api={{ updateWorkflow: vi.fn() } as unknown as ProjectsApi}
      />,
    )

    await user.click(screen.getByRole('tab', { name: '工作流' }))

    expect(
      await screen.findByText(/已配置的 Agent 当前不在目录中，已保留原名称：legacy-agent/),
    ).toBeInTheDocument()
  })
})

describe('DeleteProjectModal', () => {
  it('calls deleteProject with expectedVersion upon confirmation', async () => {
    // 测试意图：确认删除项目时向 deleteProject 传递 projectId 和 expectedVersion
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
