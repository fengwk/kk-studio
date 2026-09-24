import { describe, expect, it, vi } from 'vitest'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ApiError } from '@/shared/api/client'
import { ProjectsPage } from './ProjectsPage'
import type { ProjectsApi } from './projects-api'
import type { ProjectDTO } from './types'
import { invalidateProjectQueries } from './projects-invalidation'

function renderProjectsPage(ui: React.ReactElement, client?: QueryClient) {
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

describe('ProjectsPage', () => {
  const mockProjects: ProjectDTO[] = [
    {
      id: 'a0000000-0000-0000-0000-000000000001',
      title: 'Alpha Project',
      description: 'First project for testing',
      yoloEnabled: true,
      maxReviewRejections: '3',
      nextIssueNumber: '5',
      version: '1',
      archivedAt: null,
      createdAt: '2026-09-14T00:00:00Z',
      updatedAt: '2026-09-14T00:00:00Z',
    },
    {
      id: 'a0000000-0000-0000-0000-000000000002',
      title: 'Beta Project',
      description: 'Second project',
      yoloEnabled: false,
      maxReviewRejections: '1',
      nextIssueNumber: '1',
      version: '0',
      archivedAt: '2026-09-14T01:00:00Z',
      createdAt: '2026-09-14T00:00:00Z',
      updatedAt: '2026-09-14T01:00:00Z',
    },
  ]

  const createMockApi = (overrides: Partial<ProjectsApi> = {}): ProjectsApi => ({
    listProjects: vi.fn().mockResolvedValue(mockProjects),
    createProject: vi.fn().mockResolvedValue(mockProjects[0]),
    getProject: vi.fn().mockResolvedValue(mockProjects[0]),
    updateProject: vi.fn().mockResolvedValue(mockProjects[0]),
    deleteProject: vi.fn().mockResolvedValue(undefined),
    archiveProject: vi.fn().mockResolvedValue({ ...mockProjects[0], archivedAt: 'now' }),
    unarchiveProject: vi.fn().mockResolvedValue({ ...mockProjects[1], archivedAt: null }),
    getProjectSnapshot: vi.fn().mockResolvedValue({
      project: mockProjects[0],
      issues: [],
      dependencies: [],
    }),
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
    ...overrides,
  })

  it('renders project list and filters by search query', async () => {
    // 测试意图：验证项目列表正确加载，并支持通过搜索框客户端过滤
    const api = createMockApi()
    renderProjectsPage(<ProjectsPage api={api} />)

    await waitFor(() => {
      expect(screen.getByText('Alpha Project')).toBeInTheDocument()
      expect(screen.getByText('Beta Project')).toBeInTheDocument()
    })

    const searchInput = screen.getByLabelText('搜索项目')
    fireEvent.change(searchInput, { target: { value: 'Alpha' } })

    expect(screen.getByText('Alpha Project')).toBeInTheDocument()
    expect(screen.queryByText('Beta Project')).not.toBeInTheDocument()
  })

  it('toggles includeArchived checkbox to query archived projects', async () => {
    // 测试意图：验证勾选“显示已归档”时，向后端传递 includeArchived=true
    const api = createMockApi()
    renderProjectsPage(<ProjectsPage api={api} />)

    const checkbox = screen.getByLabelText('显示已归档')
    fireEvent.click(checkbox)

    await waitFor(() => {
      expect(api.listProjects).toHaveBeenCalledWith(true)
    })
  })

  it('creates a new project and triggers onSelectProject', async () => {
    // 测试意图：验证新建项目弹窗提交成功后触发刷新并进入新项目（携带 yoloEnabled 与 maxReviewRejections）
    const onSelectProject = vi.fn()
    const newProj: ProjectDTO = {
      id: 'a0000000-0000-0000-0000-000000000003',
      title: 'Gamma Project',
      description: 'Brand new project',
      yoloEnabled: true,
      maxReviewRejections: '3',
      nextIssueNumber: '1',
      version: '0',
      archivedAt: null,
      createdAt: '2026-09-14T00:00:00Z',
      updatedAt: '2026-09-14T00:00:00Z',
    }
    const api = createMockApi({
      createProject: vi.fn().mockResolvedValue(newProj),
    })

    renderProjectsPage(<ProjectsPage api={api} onSelectProject={onSelectProject} />)

    await waitFor(() => {
      expect(screen.getByText('Alpha Project')).toBeInTheDocument()
    })

    // Click "新建项目" card
    const createBtn = screen.getByRole('button', { name: '新建项目' })
    fireEvent.click(createBtn)

    // Fill form
    const titleInput = screen.getByLabelText(/项目名称/i)
    fireEvent.change(titleInput, { target: { value: 'Gamma Project' } })

    const descInput = screen.getByLabelText(/项目描述/i)
    fireEvent.change(descInput, { target: { value: 'Brand new project' } })

    const submitBtn = screen.getByRole('button', { name: '创建项目' })
    fireEvent.click(submitBtn)

    await waitFor(() => {
      expect(api.createProject).toHaveBeenCalledWith({
        title: 'Gamma Project',
        description: 'Brand new project',
        yoloEnabled: true,
        maxReviewRejections: 3,
      })
      expect(onSelectProject).toHaveBeenCalledWith(newProj.id)
    })
  })

  it('handles CAS conflict on EditProjectModal by preserving draft and allowing reload', async () => {
    // 测试意图：验证编辑项目发生 409 CAS 冲突时，表单保留用户草稿并提供重新加载功能
    const conflictError = new ApiError('Version conflict', 409, 'PROJECT_VERSION_CONFLICT')
    const freshProject: ProjectDTO = {
      ...mockProjects[0],
      version: '2',
      title: 'Server Updated Title',
    }

    const api = createMockApi({
      updateProject: vi.fn().mockRejectedValueOnce(conflictError).mockResolvedValueOnce({
        ...freshProject,
        title: 'My Custom Draft',
        version: '3',
      }),
      getProject: vi.fn().mockResolvedValue(freshProject),
    })

    renderProjectsPage(<ProjectsPage api={api} />)

    await waitFor(() => {
      expect(screen.getByText('Alpha Project')).toBeInTheDocument()
    })

    // Click Edit button on Alpha Project
    const editBtn = screen.getByLabelText('编辑项目 Alpha Project')
    fireEvent.click(editBtn)

    // Edit title
    const titleInput = screen.getByLabelText(/项目名称/i)
    fireEvent.change(titleInput, { target: { value: 'My Custom Draft' } })

    // Submit
    const saveBtn = screen.getByRole('button', { name: '保存更改' })
    await waitFor(() => {
      expect(saveBtn).not.toBeDisabled()
    })
    fireEvent.click(saveBtn)

    // CAS conflict banner should appear
    await waitFor(() => {
      expect(screen.getByText(/版本冲突/i)).toBeInTheDocument()
    })

    // User draft remains in form
    expect((screen.getByLabelText(/项目名称/i) as HTMLInputElement).value).toBe(
      'My Custom Draft',
    )

    // Click "保留草稿并重新加载最新版本号"
    const reloadBtn = screen.getByRole('button', { name: /保留草稿并重新加载最新版本号/i })
    fireEvent.click(reloadBtn)

    await waitFor(() => {
      expect(api.getProject).toHaveBeenCalledWith(mockProjects[0].id)
    })

    // Submit again with updated expectedVersion
    await waitFor(() => {
      expect(screen.getByRole('button', { name: '保存更改' })).not.toBeDisabled()
    })
    fireEvent.click(screen.getByRole('button', { name: '保存更改' }))

    await waitFor(() => {
      expect(api.updateProject).toHaveBeenCalledWith(mockProjects[0].id, {
        expectedVersion: '2',
        title: 'My Custom Draft',
        description: mockProjects[0].description,
        yoloEnabled: true,
        maxReviewRejections: 3,
      })
    })
  })

  it('deletes project after user confirmation', async () => {
    // 测试意图：验证删除项目弹窗在用户确认后携带 expectedVersion 执行删除
    const api = createMockApi()
    renderProjectsPage(<ProjectsPage api={api} />)

    await waitFor(() => {
      expect(screen.getByText('Alpha Project')).toBeInTheDocument()
    })

    const deleteBtn = screen.getByLabelText('删除项目 Alpha Project')
    fireEvent.click(deleteBtn)

    expect(screen.getByText(/确认删除项目/i)).toBeInTheDocument()

    const confirmBtn = screen.getByRole('button', { name: '确认删除' })
    fireEvent.click(confirmBtn)

    await waitFor(() => {
      expect(api.deleteProject).toHaveBeenCalledWith(
        mockProjects[0].id,
        mockProjects[0].version,
      )
    })
  })

  it('renders empty state when projects list is empty and allows creating first project', async () => {
    // 测试意图：验证项目列表为空时网格首张卡片即为唯一的新建项目入口，不展示多余的通用空态文本块
    const api = createMockApi({
      listProjects: vi.fn().mockResolvedValue([]),
    })
    renderProjectsPage(<ProjectsPage api={api} />)

    await waitFor(() => {
      const createBtns = screen.getAllByRole('button', { name: '新建项目' })
      expect(createBtns).toHaveLength(1)
      expect(createBtns[0]).toHaveClass('create-card')
    })

    expect(screen.queryByText('暂无项目')).not.toBeInTheDocument()
    expect(screen.queryByText('暂无匹配项目')).not.toBeInTheDocument()

    const createBtn = screen.getByRole('button', { name: '新建项目' })
    fireEvent.click(createBtn)

    expect(screen.getByText('新建项目', { selector: 'h3' })).toBeInTheDocument()
  })

  it('renders no-results state when nonblank search yields no matching projects with no duplicate create button', async () => {
    // 测试意图：验证非空搜索无匹配项时展示有用的无结果提示，且不包含重复的创建按钮
    const api = createMockApi()
    renderProjectsPage(<ProjectsPage api={api} />)

    await waitFor(() => {
      expect(screen.getByText('Alpha Project')).toBeInTheDocument()
    })

    const searchInput = screen.getByPlaceholderText('搜索项目名称或描述...')
    fireEvent.change(searchInput, { target: { value: 'nonexistent-query-123' } })

    expect(await screen.findByText('暂无匹配项目')).toBeInTheDocument()
    expect(screen.getByText('没有找到符合搜索条件的项目')).toBeInTheDocument()

    // 验证整个页面仍只有首张 CreateCard 创建入口，无状态块内重复创建按钮
    const createButtons = screen.getAllByRole('button', { name: '新建项目' })
    expect(createButtons).toHaveLength(1)
    expect(createButtons[0]).toHaveClass('create-card')
  })

  it('renders error banner when listing projects fails', async () => {
    // 测试意图：验证当后端 listProjects 接口返回错误时，展示错误提示横条
    const api = createMockApi({
      listProjects: vi.fn().mockRejectedValue(new Error('Network error loading projects')),
    })
    renderProjectsPage(<ProjectsPage api={api} />)

    await waitFor(() => {
      expect(screen.getByText('Network error loading projects')).toBeInTheDocument()
    })
  })

  it('toggles project archive status via card action button', async () => {
    // 测试意图：验证在项目卡片上点击归档与取消归档按钮，分别调用 archiveProject 和 unarchiveProject 接口
    const api = createMockApi()
    renderProjectsPage(<ProjectsPage api={api} />)

    await waitFor(() => {
      expect(screen.getByText('Alpha Project')).toBeInTheDocument()
    })

    // Alpha Project is unarchived -> archive button
    const archiveBtn = screen.getByLabelText('归档项目 Alpha Project')
    fireEvent.click(archiveBtn)

    await waitFor(() => {
      expect(api.archiveProject).toHaveBeenCalledWith(mockProjects[0].id, {
        expectedVersion: mockProjects[0].version,
      })
    })

    // Beta Project is archived -> unarchive button
    const unarchiveBtn = screen.getByLabelText('取消归档项目 Beta Project')
    fireEvent.click(unarchiveBtn)

    await waitFor(() => {
      expect(api.unarchiveProject).toHaveBeenCalledWith(mockProjects[1].id, {
        expectedVersion: mockProjects[1].version,
      })
    })
  })

  it('triggers onSelectProject when clicking project title or enter project button', async () => {
    // 测试意图：验证点击项目卡片标题或“进入项目”主按钮触发 onSelectProject 回调
    const onSelectProject = vi.fn()
    const api = createMockApi()
    renderProjectsPage(<ProjectsPage api={api} onSelectProject={onSelectProject} />)

    await waitFor(() => {
      expect(screen.getByText('Alpha Project')).toBeInTheDocument()
    })

    // Click title
    fireEvent.click(screen.getByText('Alpha Project'))
    expect(onSelectProject).toHaveBeenCalledWith(mockProjects[0].id)

    // Click "进入项目" button
    const enterBtns = screen.getAllByRole('button', { name: /进入项目/i })
    fireEvent.click(enterBtns[0])
    expect(onSelectProject).toHaveBeenCalledWith(mockProjects[0].id)
  })

  it('reloads project list on refresh button click and on invalidation broadcast', async () => {
    // 测试意图：验证点击标题旁的刷新按钮以及全局发布项目变更通知时，能够触发重新加载列表
    const api = createMockApi()
    const { queryClient } = renderProjectsPage(<ProjectsPage api={api} />)

    await waitFor(() => {
      expect(screen.getByText('Alpha Project')).toBeInTheDocument()
    })

    const refreshBtn = screen.getByLabelText('刷新项目列表')
    fireEvent.click(refreshBtn)

    await waitFor(() => {
      expect(api.listProjects).toHaveBeenCalledTimes(2)
    })

    await act(async () => {
      await invalidateProjectQueries(queryClient)
    })
    await waitFor(() => {
      expect(api.listProjects).toHaveBeenCalledTimes(3)
    })
  })

  it('ignores a stale project list response after a newer invalidation reload', async () => {
    // 测试意图：验证 invalidation reload 之后，最新权威数据正确更新替换旧数据
    const initialProjects = [{ ...mockProjects[0], title: 'Initial Project' }]
    const latestProjects = [{ ...mockProjects[0], title: 'Latest Project' }]
    const api = createMockApi({
      listProjects: vi.fn()
        .mockResolvedValueOnce(initialProjects)
        .mockResolvedValue(latestProjects),
    })
    const { queryClient } = renderProjectsPage(<ProjectsPage api={api} />)

    expect(await screen.findByText('Initial Project')).toBeInTheDocument()

    await act(async () => {
      await invalidateProjectQueries(queryClient)
    })
    expect(await screen.findByText('Latest Project')).toBeInTheDocument()
    expect(screen.queryByText('Initial Project')).not.toBeInTheDocument()
  })

  it('enforces UI consistency with single CreateCard as first item, info-card inheritance, and modern checkbox', async () => {
    // 测试意图：验证 UI 一致性规范，包括全局唯一张创建卡、项目卡片继承 info-card 以及已归档过滤使用现代 Checkbox
    const api = createMockApi()
    renderProjectsPage(<ProjectsPage api={api} />)

    await waitFor(() => {
      expect(screen.getByText('Alpha Project')).toBeInTheDocument()
    })

    const createButtons = screen.getAllByRole('button', { name: '新建项目' })
    expect(createButtons).toHaveLength(1)
    expect(createButtons[0]).toHaveClass('create-card')

    const projectCards = screen.getAllByRole('article')
    expect(projectCards).toHaveLength(2)
    for (const card of projectCards) {
      expect(card).toHaveClass('info-card')
      expect(card).toHaveClass('project-card')
    }

    const archiveCheckbox = screen.getByRole('checkbox', { name: '显示已归档' })
    expect(archiveCheckbox).toBeInTheDocument()
    expect(archiveCheckbox).toHaveClass('ui-checkbox-input')
  })
})
