import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { McpServersPage } from '@/features/ai/mcp/McpServersPage'
import { mcpServerService } from '@/shared/api/mcp-server-service'
import type { McpServerDTO } from '@/shared/api/contracts/ai-mcp'

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
  it('renders configured MCP servers with status and details', async () => {
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

    const editBtn = await screen.findByRole('button', { name: '编辑 MCP 服务' })
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

  it('refreshes tools on an MCP server', async () => {
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

    const refreshBtn = await screen.findByRole('button', { name: '刷新工具' })
    await user.click(refreshBtn)

    expect(mcpServerService.refreshServer).toHaveBeenCalledWith('srv-1', '1')
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

    const deleteBtn = await screen.findByRole('button', { name: '删除 MCP 服务' })
    await user.click(deleteBtn)

    const modal = await screen.findByRole('alertdialog', { name: '删除 MCP 服务' })
    const confirmBtn = within(modal).getByRole('button', { name: '删除 MCP 服务' })
    await user.click(confirmBtn)

    expect(mcpServerService.deleteServer).toHaveBeenCalledWith('srv-1', '1')
  })
})
