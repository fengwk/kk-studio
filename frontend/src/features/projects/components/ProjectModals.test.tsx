import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { describe, expect, it, vi } from 'vitest'
import { CreateProjectModal } from './CreateProjectModal'
import { EditProjectModal } from './EditProjectModal'
import { agentService } from '@/shared/api/agent-service'
import type { ProjectDTO } from '../types'

vi.mock('@/shared/api/agent-service', () => ({
  agentService: {
    listAgents: vi.fn(),
  },
}))

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
  id: 'proj-1',
  title: 'Existing Project',
  description: 'Project desc',
  coordinatorAgentName: 'agent-alpha',
  nextIssueNumber: '1',
  version: '1',
  archivedAt: null,
  createdAt: '2026-09-14T00:00:00Z',
  updatedAt: '2026-09-14T00:00:00Z',
}

describe('CreateProjectModal Coordinator Agent Selection', () => {
  // 测试意图：验证创建项目弹窗中 Coordinator Agent 为下拉选择，加载中展示占位并禁用提交
  it('displays loading state and disables submission while agents are loading', () => {
    vi.mocked(agentService.listAgents).mockReturnValue(new Promise(() => {}))

    renderWithClient(
      <CreateProjectModal
        isOpen={true}
        onClose={vi.fn()}
        onSuccess={vi.fn()}
      />,
    )

    expect(screen.getByText('正在加载 Agent 列表...')).toBeInTheDocument()
    const submitBtn = screen.getByRole('button', { name: '创建项目' })
    expect(submitBtn).toBeDisabled()
  })

  // 测试意图：验证当加载 Agent 列表失败时展示错误提示，阻止创建项目
  it('displays error message and disables submission when agent query fails', async () => {
    vi.mocked(agentService.listAgents).mockRejectedValue(new Error('Network error loading agents'))

    renderWithClient(
      <CreateProjectModal
        isOpen={true}
        onClose={vi.fn()}
        onSuccess={vi.fn()}
      />,
    )

    expect(await screen.findByText('加载 Agent 列表失败，请稍后重试')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '创建项目' })).toBeDisabled()
  })

  // 测试意图：验证当无可用 Agent 时展示空提示并禁用提交
  it('displays empty state and prevents submit when no agents exist', async () => {
    vi.mocked(agentService.listAgents).mockResolvedValue({ results: [], totalCount: 0, pageNumber: 1, pageSize: 100 })

    renderWithClient(
      <CreateProjectModal
        isOpen={true}
        onClose={vi.fn()}
        onSuccess={vi.fn()}
      />,
    )

    expect(await screen.findByText('当前无可用 Agent，请先在 Agent 控制台创建')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '创建项目' })).toBeDisabled()
  })

  // 测试意图：验证用户只能从下拉选项中选择真实 Agent 并成功提交项目
  it('selects valid agent from dropdown and submits form successfully', async () => {
    const user = userEvent.setup()
    const mockApi = {
      createProject: vi.fn().mockResolvedValue(mockProject),
      listProjects: vi.fn(),
      getProject: vi.fn(),
      updateProject: vi.fn(),
      deleteProject: vi.fn(),
      archiveProject: vi.fn(),
      unarchiveProject: vi.fn(),
      sendProjectCommand: vi.fn(),
      getProjectSnapshot: vi.fn(),
      createIssue: vi.fn(),
      getIssue: vi.fn(),
      updateIssue: vi.fn(),
      changeIssueStatus: vi.fn(),
      addIssueDependency: vi.fn(),
      removeIssueDependency: vi.fn(),
      appendIssueInput: vi.fn(),
      reviewIssue: vi.fn(),
      cancelIssue: vi.fn(),
      retryIssue: vi.fn(),
      archiveIssue: vi.fn(),
      unarchiveIssue: vi.fn(),
    }
    const onSuccess = vi.fn()
    vi.mocked(agentService.listAgents).mockResolvedValue({
      results: [{ name: 'agent-one' }, { name: 'agent-two' }],
      totalCount: 2,
      pageNumber: 1,
      pageSize: 100,
    })

    renderWithClient(
      <CreateProjectModal
        isOpen={true}
        onClose={vi.fn()}
        onSuccess={onSuccess}
        api={mockApi}
      />,
    )

    await user.type(screen.getByLabelText(/项目名称/i), 'New Awesome Project')

    // 打开下拉选择 agent-one
    const selectTrigger = await screen.findByRole('button', { name: /Coordinator Agent 名称/i })
    await user.click(selectTrigger)
    const optionOne = await screen.findByRole('option', { name: 'agent-one' })
    await user.click(optionOne)

    const submitBtn = screen.getByRole('button', { name: '创建项目' })
    expect(submitBtn).not.toBeDisabled()
    await user.click(submitBtn)

    expect(mockApi.createProject).toHaveBeenCalledWith({
      title: 'New Awesome Project',
      description: null,
      coordinatorAgentName: 'agent-one',
    })
    expect(onSuccess).toHaveBeenCalledWith(mockProject)
  })
})

describe('EditProjectModal Defensive Orphan Handling', () => {
  // 测试意图：验证当已有项目引用的 Coordinator Agent 不在可用列表中时，防御性展示为 disabled (不可用)，且禁止提交直到切换为合法 Agent
  it('renders orphan coordinator as disabled (不可用) and blocks submit until valid agent selected', async () => {
    const user = userEvent.setup()
    const mockApi = {
      updateProject: vi.fn().mockResolvedValue(mockProject),
      listProjects: vi.fn(),
      createProject: vi.fn(),
      getProject: vi.fn(),
      deleteProject: vi.fn(),
      archiveProject: vi.fn(),
      unarchiveProject: vi.fn(),
      sendProjectCommand: vi.fn(),
      getProjectSnapshot: vi.fn(),
      createIssue: vi.fn(),
      getIssue: vi.fn(),
      updateIssue: vi.fn(),
      changeIssueStatus: vi.fn(),
      addIssueDependency: vi.fn(),
      removeIssueDependency: vi.fn(),
      appendIssueInput: vi.fn(),
      reviewIssue: vi.fn(),
      cancelIssue: vi.fn(),
      retryIssue: vi.fn(),
      archiveIssue: vi.fn(),
      unarchiveIssue: vi.fn(),
    }
    // agent-alpha 不在此列表中（孤儿状态）
    vi.mocked(agentService.listAgents).mockResolvedValue({
      results: [{ name: 'agent-replacement' }],
      totalCount: 1,
      pageNumber: 1,
      pageSize: 100,
    })

    renderWithClient(
      <EditProjectModal
        isOpen={true}
        project={mockProject}
        onClose={vi.fn()}
        onSuccess={vi.fn()}
        api={mockApi}
      />,
    )

    // 验证孤儿展示文案与提示
    expect(await screen.findByText('agent-alpha (不可用)')).toBeInTheDocument()
    expect(screen.getByText('当前 Coordinator Agent 不可用，请重新选择有效的 Agent')).toBeInTheDocument()

    // 提交按钮因 coordinator 不可用而处于禁用态
    const saveBtn = screen.getByRole('button', { name: '保存修改' })
    expect(saveBtn).toBeDisabled()

    // 打开下拉选择有效的 agent-replacement
    const selectTrigger = screen.getByRole('button', { name: /Coordinator Agent 名称/i })
    await user.click(selectTrigger)
    const replacementOption = await screen.findByRole('option', { name: 'agent-replacement' })
    await user.click(replacementOption)

    // 此时表单变为合法，提交按钮解除禁用并成功提交
    await waitFor(() => expect(saveBtn).not.toBeDisabled())
    await user.click(saveBtn)

    expect(mockApi.updateProject).toHaveBeenCalledWith(
      mockProject.id,
      expect.objectContaining({ coordinatorAgentName: 'agent-replacement' }),
    )
  })
})
