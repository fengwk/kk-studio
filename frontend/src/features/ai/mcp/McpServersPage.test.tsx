import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { McpServersPage } from '@/features/ai/mcp/McpServersPage'
import { mcpServerService } from '@/shared/api/mcp-server-service'
import type { McpServerDTO } from '@/shared/api/contracts/ai-mcp'
import { ApiError } from '@/shared/api/client'

vi.mock('@/shared/api/mcp-server-service', () => ({
  mcpServerService: {
    pageServers: vi.fn(),
    createServer: vi.fn(),
    updateServer: vi.fn(),
    refreshServer: vi.fn(),
    deleteServer: vi.fn(),
  },
}))

vi.mock('@/platform/workbench/WorkbenchSlots', () => ({
  NavigationSlot: () => null,
}))

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const view = render(
    <QueryClientProvider client={queryClient}>
      <McpServersPage />
    </QueryClientProvider>,
  )
  return { queryClient, view }
}

function server(overrides: Partial<McpServerDTO>): McpServerDTO {
  return {
    id: 'srv-1',
    name: 'filesystem',
    url: 'http://localhost:8000/mcp',
    bearerTokenConfigured: true,
    timeoutMillis: 30000,
    version: '1',
    createTime: '2026-07-20T00:00:00.000Z',
    updateTime: '2026-07-20T00:00:00.000Z',
    ...overrides,
  }
}

describe('McpServersPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  // 验证渲染已配置 MCP 服务列表，包含配置状态、元数据，且卡片底部仅暴露通用编辑与删除动作，不暴露刷新动作
  it('renders configured MCP servers with status and details, with only edit and delete actions in footer', async () => {
    vi.mocked(mcpServerService.pageServers).mockResolvedValue({
      pageNumber: 1,
      pageSize: 100,
      totalCount: 2,
      results: [
        server({ id: 'srv-1', name: 'filesystem', bearerTokenConfigured: true }),
        server({ id: 'srv-2', name: 'github', bearerTokenConfigured: false, url: 'http://localhost:8001/mcp' }),
      ],
    })
    renderPage()

    expect(await screen.findByText('filesystem')).toBeInTheDocument()
    expect(screen.getByText('github')).toBeInTheDocument()
    expect(screen.getByText('已配置')).toBeInTheDocument()
    expect(screen.getByText('匿名访问')).toBeInTheDocument()

    const cards = screen.getAllByRole('article')
    expect(cards).toHaveLength(2)
    for (const card of cards) {
      const footerButtons = within(card).getAllByRole('button')
      expect(footerButtons).toHaveLength(2)
      expect(within(card).getByRole('button', { name: /编辑 MCP 服务/ })).toBeInTheDocument()
      expect(within(card).getByRole('button', { name: /删除 MCP 服务/ })).toBeInTheDocument()
      expect(within(card).queryByRole('button', { name: /刷新/ })).toBeNull()
    }
  })

  // 验证后端 Java Instant 以数值秒（JavaTime 默认）或数值毫秒到达时，均能正确格式化为相同的现代年份日期，绝不误解析为 1970
  it('formats numeric epoch seconds and milliseconds to the same modern date instead of 1970', async () => {
    vi.mocked(mcpServerService.pageServers).mockResolvedValue({
      pageNumber: 1,
      pageSize: 100,
      totalCount: 2,
      results: [
        server({
          id: 'srv-seconds',
          name: 'seconds_server',
          updateTime: 1750000000,
        }),
        server({
          id: 'srv-millis',
          name: 'millis_server',
          updateTime: 1750000000000,
        }),
      ],
    })
    renderPage()

    expect(await screen.findByText('seconds_server')).toBeInTheDocument()
    expect(screen.getByText('millis_server')).toBeInTheDocument()

    // 绝不能出现 1970 年
    expect(screen.queryByText(/1970/)).toBeNull()

    // 秒和毫秒表示应渲染完全一致的现代日期（2025 年）
    const formattedDates = screen.getAllByText(/2025/)
    expect(formattedDates.length).toBe(2)
    expect(formattedDates[0]!.textContent).toBe(formattedDates[1]!.textContent)
  })

  it('creates an MCP server', async () => {
    const user = userEvent.setup()
    vi.mocked(mcpServerService.pageServers).mockResolvedValue({
      pageNumber: 1,
      pageSize: 100,
      totalCount: 0,
      results: [],
    })
    vi.mocked(mcpServerService.createServer).mockResolvedValue(
      server({ id: 'srv-new', name: 'new_server', url: 'http://localhost:9000/mcp' }),
    )
    renderPage()

    const createBtn = await screen.findByRole('button', { name: '创建 MCP 服务' })
    await user.click(createBtn)

    await user.type(screen.getByRole('textbox', { name: /服务名称/ }), 'new_server')
    await user.type(screen.getByRole('textbox', { name: /Endpoint URL/ }), 'http://localhost:9000/mcp')
    await user.click(screen.getByRole('button', { name: '确认' }))

    expect(mcpServerService.createServer).toHaveBeenCalledWith({
      name: 'new_server',
      url: 'http://localhost:9000/mcp',
      bearerToken: null,
      timeoutMillis: 30000,
    })
  })

  it('edits an MCP server with token three-state options', async () => {
    const user = userEvent.setup()
    vi.mocked(mcpServerService.pageServers).mockResolvedValue({
      pageNumber: 1,
      pageSize: 100,
      totalCount: 1,
      results: [server({ id: 'srv-1', name: 'filesystem', version: '1' })],
    })
    vi.mocked(mcpServerService.updateServer).mockResolvedValue(
      server({ id: 'srv-1', name: 'filesystem', url: 'http://localhost:8080/mcp', version: '2' }),
    )
    renderPage()

    const editBtn = await screen.findByRole('button', { name: /编辑 MCP 服务/ })
    await user.click(editBtn)

    const urlInput = screen.getByRole('textbox', { name: /Endpoint URL/ })
    await user.clear(urlInput)
    await user.type(urlInput, 'http://localhost:8080/mcp')

    // 切换到清除 token
    const clearRadio = screen.getByRole('radio', { name: /清除 Token/ })
    await user.click(clearRadio)

    await user.click(screen.getByRole('button', { name: '确认' }))

    expect(mcpServerService.updateServer).toHaveBeenCalledWith('srv-1', {
      expectedVersion: '1',
      url: 'http://localhost:8080/mcp',
      timeoutMillis: 30000,
      bearerToken: '',
    })
  })

  // 验证从卡片编辑弹窗中调用「刷新工具」次级动作，成功后关闭过期编辑弹窗并刷新查询
  it('refreshes tools on an MCP server from edit dialog and closes modal on success', async () => {
    const user = userEvent.setup()
    vi.mocked(mcpServerService.pageServers).mockResolvedValue({
      pageNumber: 1,
      pageSize: 100,
      totalCount: 1,
      results: [server({ id: 'srv-1', name: 'filesystem', version: '1' })],
    })
    vi.mocked(mcpServerService.refreshServer).mockResolvedValue(
      server({ id: 'srv-1', name: 'filesystem', version: '2' }),
    )
    renderPage()

    // 验证卡片上不提供直接刷新入口
    const card = await screen.findByRole('article')
    expect(within(card).queryByRole('button', { name: /刷新/ })).toBeNull()

    // 打开编辑弹窗
    const editBtn = within(card).getByRole('button', { name: /编辑 MCP 服务/ })
    await user.click(editBtn)

    // 在编辑弹窗内部触发刷新工具次级动作
    const editDialog = await screen.findByRole('dialog', { name: /编辑 MCP 服务/ })
    const refreshBtn = within(editDialog).getByRole('button', { name: '刷新工具' })
    await user.click(refreshBtn)

    expect(mcpServerService.refreshServer).toHaveBeenCalledWith('srv-1', '1')
    // 刷新成功后关闭过期编辑弹窗
    await waitFor(() => {
      expect(screen.queryByRole('dialog', { name: /编辑 MCP 服务/ })).toBeNull()
    })
  })

  it('deletes an MCP server with confirmation modal', async () => {
    const user = userEvent.setup()
    vi.mocked(mcpServerService.pageServers).mockResolvedValue({
      pageNumber: 1,
      pageSize: 100,
      totalCount: 1,
      results: [server({ id: 'srv-1', name: 'filesystem', version: '1' })],
    })
    vi.mocked(mcpServerService.deleteServer).mockResolvedValue(undefined)
    renderPage()

    const deleteBtn = await screen.findByRole('button', { name: /删除 MCP 服务/ })
    await user.click(deleteBtn)

    const modal = await screen.findByRole('alertdialog', { name: '删除 MCP 服务' })
    const confirmBtn = within(modal).getByRole('button', { name: '删除 MCP 服务' })
    await user.click(confirmBtn)

    expect(mcpServerService.deleteServer).toHaveBeenCalledWith('srv-1', '1')
  })

  // 验证 MCP Server update 发生 409 冲突时通过 ConflictPresenter 展示，点击刷新后重置编辑弹窗并刷新列表，不自动重放
  it('handles 409 conflict on server update and resets stale edit modal on explicit refresh', async () => {
    const user = userEvent.setup()
    vi.mocked(mcpServerService.pageServers).mockResolvedValue({
      pageNumber: 1,
      pageSize: 100,
      totalCount: 1,
      results: [server({ id: 'srv-1', name: 'filesystem', version: '1' })],
    })
    vi.mocked(mcpServerService.updateServer).mockRejectedValue(
      new ApiError('冲突', 409, 'CONFLICT', { reason: 'version_conflict', detail: 'MCP Server version conflict' }),
    )
    renderPage()

    const editBtn = await screen.findByRole('button', { name: /编辑 MCP 服务/ })
    await user.click(editBtn)

    const urlInput = screen.getByRole('textbox', { name: /Endpoint URL/ })
    await user.clear(urlInput)
    await user.type(urlInput, 'http://localhost:8088/mcp')
    await user.click(screen.getByRole('button', { name: '确认' }))

    const conflictModal = await screen.findByRole('alertdialog', { name: '持久状态已变化' })
    expect(within(conflictModal).getByText(/version_conflict/)).toBeInTheDocument()
    expect(within(conflictModal).getByText('MCP Server version conflict')).toBeInTheDocument()
    expect(within(conflictModal).queryByRole('button', { name: '重试' })).toBeNull()

    const listCallsBefore = vi.mocked(mcpServerService.pageServers).mock.calls.length
    await user.click(within(conflictModal).getByRole('button', { name: '刷新' }))

    await waitFor(() => {
      expect(screen.queryByRole('alertdialog', { name: '持久状态已变化' })).toBeNull()
      expect(screen.queryByRole('dialog', { name: /编辑 MCP 服务/ })).toBeNull()
    })
    expect(vi.mocked(mcpServerService.pageServers).mock.calls.length).toBeGreaterThan(listCallsBefore)
    expect(mcpServerService.updateServer).toHaveBeenCalledTimes(1)
  })

  // 验证 MCP Server 工具刷新失败时在编辑弹窗内可见展示（不静默），并在 409 冲突时呈现 ConflictPresenter 并关闭编辑弹窗
  it('shows visible error in edit modal on refresh failure and handles 409 conflict refresh', async () => {
    const user = userEvent.setup()
    vi.mocked(mcpServerService.pageServers).mockResolvedValue({
      pageNumber: 1,
      pageSize: 100,
      totalCount: 1,
      results: [server({ id: 'srv-1', name: 'filesystem', version: '1' })],
    })
    vi.mocked(mcpServerService.refreshServer).mockRejectedValueOnce(
      new Error('refresh tool discover timeout'),
    )
    renderPage()

    // 打开编辑弹窗
    const editBtn = await screen.findByRole('button', { name: /编辑 MCP 服务/ })
    await user.click(editBtn)

    const editDialog = await screen.findByRole('dialog', { name: /编辑 MCP 服务/ })
    const refreshBtn = within(editDialog).getByRole('button', { name: '刷新工具' })
    await user.click(refreshBtn)

    // 非 409 失败在编辑弹窗内可见展示
    const alertMsg = await within(editDialog).findByRole('alert')
    expect(alertMsg).toHaveTextContent('refresh tool discover timeout')

    // 409 冲突展示 ConflictPresenter 并关闭编辑弹窗
    vi.mocked(mcpServerService.refreshServer).mockRejectedValueOnce(
      new ApiError('冲突', 409, 'CONFLICT', { reason: 'stale_version', detail: 'Server modified during discovery' }),
    )
    await user.click(refreshBtn)

    const conflictModal = await screen.findByRole('alertdialog', { name: '持久状态已变化' })
    expect(within(conflictModal).getByText(/stale_version/)).toBeInTheDocument()
    expect(screen.queryByRole('dialog', { name: /编辑 MCP 服务/ })).toBeNull()

    await user.click(within(conflictModal).getByRole('button', { name: '刷新' }))
    await waitFor(() => {
      expect(screen.queryByRole('alertdialog', { name: '持久状态已变化' })).toBeNull()
    })
  })

  // 验证 MCP Server 删除失败时在确认弹窗中展示错误，并在 409 冲突时呈现 ConflictPresenter
  it('shows visible error on server delete failure and handles 409 conflict refresh', async () => {
    const user = userEvent.setup()
    vi.mocked(mcpServerService.pageServers).mockResolvedValue({
      pageNumber: 1,
      pageSize: 100,
      totalCount: 1,
      results: [server({ id: 'srv-1', name: 'filesystem', version: '1' })],
    })
    vi.mocked(mcpServerService.deleteServer).mockRejectedValueOnce(
      new Error('server delete permission denied'),
    )
    renderPage()

    const deleteBtn = await screen.findByRole('button', { name: /删除 MCP 服务/ })
    await user.click(deleteBtn)

    const modal = await screen.findByRole('alertdialog', { name: '删除 MCP 服务' })
    const confirmBtn = within(modal).getByRole('button', { name: '删除 MCP 服务' })
    await user.click(confirmBtn)

    // 非 409 错误在弹窗中展示
    expect(await within(modal).findByRole('alert')).toHaveTextContent('server delete permission denied')

    // 409 冲突展示 ConflictPresenter 并关闭确认弹窗
    vi.mocked(mcpServerService.deleteServer).mockRejectedValueOnce(
      new ApiError('冲突', 409, 'CONFLICT', { reason: 'version_conflict', detail: 'Already deleted' }),
    )
    await user.click(confirmBtn)

    const conflictModal = await screen.findByRole('alertdialog', { name: '持久状态已变化' })
    expect(within(conflictModal).getByText(/version_conflict/)).toBeInTheDocument()
    expect(screen.queryByRole('alertdialog', { name: '删除 MCP 服务' })).toBeNull()

    await user.click(within(conflictModal).getByRole('button', { name: '刷新' }))
    await waitFor(() => {
      expect(screen.queryByRole('alertdialog', { name: '持久状态已变化' })).toBeNull()
    })
  })
})
