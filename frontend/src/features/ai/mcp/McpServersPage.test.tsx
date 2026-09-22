import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { McpServersPage } from '@/features/ai/mcp/McpServersPage'
import { mcpServerService } from '@/shared/api/mcp-server-service'
import type { McpServerConfigDTO, McpServerDTO } from '@/shared/api/contracts/ai-mcp'
import { ApiError } from '@/shared/api/client'

vi.mock('@/shared/api/mcp-server-service', () => ({
  mcpServerService: {
    pageServers: vi.fn(),
    getServer: vi.fn(),
    getServerConfig: vi.fn(),
    createServer: vi.fn(),
    updateServer: vi.fn(),
    discoverServer: vi.fn(),
    deleteServer: vi.fn(),
  },
}))

vi.mock('@/features/ai/extensions/AiNavigation', () => ({
  AiNavigation: () => null,
}))

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  const view = render(
    <QueryClientProvider client={queryClient}>
      <McpServersPage />
    </QueryClientProvider>,
  )
  return { queryClient, view }
}

function mockServer(overrides: Partial<McpServerDTO> = {}): McpServerDTO {
  return {
    name: 'filesystem',
    enabled: true,
    timeoutMillis: 30000,
    discoveryStatus: 'AVAILABLE',
    toolCount: 3,
    version: '1',
    createTime: '2026-07-20T00:00:00.000Z',
    updateTime: '2026-07-20T00:00:00.000Z',
    ...overrides,
  }
}

describe('McpServersPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(mcpServerService.pageServers).mockResolvedValue({
      pageNumber: 1,
      pageSize: 100,
      totalCount: 0,
      results: [],
    })
  })

  it('renders server card metadata and actions without leaking URL or headers in list', async () => {
    vi.mocked(mcpServerService.pageServers).mockResolvedValue({
      pageNumber: 1,
      pageSize: 100,
      totalCount: 1,
      results: [
        mockServer({
          name: 'fs_prod',
          enabled: true,
          timeoutMillis: 45000,
          discoveryStatus: 'AVAILABLE',
          toolCount: 5,
          version: '2',
        }),
      ],
    })

    renderPage()

    expect(await screen.findByText('fs_prod')).toBeInTheDocument()
    expect(screen.getByText('Streamable HTTP')).toBeInTheDocument()
    expect(screen.getByText('可用')).toBeInTheDocument()
    expect(screen.getByText('5')).toBeInTheDocument()
    expect(screen.getByText('45000ms')).toBeInTheDocument()
    expect(screen.getByText('2')).toBeInTheDocument()
    expect(screen.getByText('已启用')).toBeInTheDocument()

    // 确保卡片上无 URL 或 headers
    expect(screen.queryByText(/http:\/\//)).not.toBeInTheDocument()
    expect(screen.queryByText(/https:\/\//)).not.toBeInTheDocument()
    expect(screen.queryByText(/Authorization/)).not.toBeInTheDocument()

    // 操作按钮 aria-label 包含 server.name
    expect(screen.getByLabelText('编辑 MCP 服务 fs_prod')).toBeInTheDocument()
    expect(screen.getByLabelText('删除 MCP 服务 fs_prod')).toBeInTheDocument()
  })

  it('handles loading and error states for server list', async () => {
    vi.mocked(mcpServerService.pageServers).mockRejectedValue(new Error('Network offline'))
    renderPage()

    expect(await screen.findByText('Network offline')).toBeInTheDocument()
  })

  it('opens create modal, validates inputs, and submits new HTTP MCP server', async () => {
    const user = userEvent.setup()
    vi.mocked(mcpServerService.createServer).mockResolvedValue(mockServer({ name: 'github' }))

    renderPage()

    await user.click(await screen.findByRole('button', { name: '创建 MCP 服务' }))

    const modal = screen.getByRole('dialog', { name: '创建 MCP 服务' })
    expect(modal).toBeInTheDocument()

    const nameInput = screen.getByPlaceholderText('e.g. filesystem')
    const urlInput = screen.getByPlaceholderText('https://example.com/mcp')
    const headersInput = screen.getByLabelText(/自定义请求头/)
    const submitBtn = within(modal).getByRole('button', { name: '确认' })

    // 校验：无效 name
    await user.clear(nameInput)
    await user.type(nameInput, 'INVALID-NAME!')
    await user.type(urlInput, 'https://example.com/mcp')
    await user.click(submitBtn)
    expect(screen.getByRole('alert')).toHaveTextContent(/Name must start with lowercase letter/)

    // 校验：无效 URL
    await user.clear(nameInput)
    await user.type(nameInput, 'github')
    await user.clear(urlInput)
    await user.type(urlInput, 'ftp://invalid-scheme.com')
    await user.click(submitBtn)
    expect(screen.getByRole('alert')).toHaveTextContent(/URL scheme must be http or https/)

    // 校验：无效 headers JSON
    await user.clear(urlInput)
    await user.type(urlInput, 'https://example.com/mcp')
    fireEvent.change(headersInput, { target: { value: '{ not valid json }' } })
    await user.click(submitBtn)
    expect(screen.getByRole('alert')).toHaveTextContent(/Headers must be valid JSON/)

    // 校验：非字符串属性值 headers
    fireEvent.change(headersInput, { target: { value: '{"auth": 123}' } })
    await user.click(submitBtn)
    expect(screen.getByRole('alert')).toHaveTextContent(/Header value for "auth" must be a string/)

    // 输入有效值并提交，header 属性值必须保留空格
    fireEvent.change(headersInput, {
      target: { value: '{"Authorization": " Bearer my_token "}' },
    })

    await user.click(submitBtn)

    await waitFor(() => {
      expect(mcpServerService.createServer).toHaveBeenCalledWith({
        name: 'github',
        url: 'https://example.com/mcp',
        headers: { Authorization: ' Bearer my_token ' },
        enabled: true,
        timeoutMillis: 60000,
      })
      expect(screen.queryByRole('dialog', { name: '创建 MCP 服务' })).not.toBeInTheDocument()
    })
  })

  it('handles create conflict presentation', async () => {
    const user = userEvent.setup()
    vi.mocked(mcpServerService.createServer).mockRejectedValue(
      new ApiError('Resource exists', 409, 'CONFLICT', {
        errorType: 'OUTDATED_SNAPSHOT',
        message: 'Conflict',
        expectedVersion: '0',
        currentVersion: '1',
      }),
    )

    renderPage()

    await user.click(await screen.findByRole('button', { name: '创建 MCP 服务' }))
    const modal = screen.getByRole('dialog', { name: '创建 MCP 服务' })
    const nameInput = screen.getByPlaceholderText('e.g. filesystem')
    const urlInput = screen.getByPlaceholderText('https://example.com/mcp')

    await user.type(nameInput, 'conflict_srv')
    await user.type(urlInput, 'https://example.com/mcp')
    await user.click(within(modal).getByRole('button', { name: '确认' }))

    // 冲突弹窗出现
    expect(await screen.findByText('持久状态已变化')).toBeInTheDocument()
  })

  it('opens edit modal, fetches config, displays immutable name, and submits full replacement PUT', async () => {
    const user = userEvent.setup()
    const serverInstance = mockServer({
      name: 'srv_edit',
      version: '3',
      enabled: true,
      timeoutMillis: 30000,
    })

    vi.mocked(mcpServerService.pageServers).mockResolvedValue({
      pageNumber: 1,
      pageSize: 100,
      totalCount: 1,
      results: [serverInstance],
    })

    const configResponse: McpServerConfigDTO = {
      name: 'srv_edit',
      version: '3',
      url: 'https://example.com/api',
      headers: { Authorization: 'Bearer token-abc' },
      enabled: true,
      timeoutMillis: 30000,
    }
    vi.mocked(mcpServerService.getServerConfig).mockResolvedValue(configResponse)
    vi.mocked(mcpServerService.updateServer).mockResolvedValue(mockServer({ name: 'srv_edit', version: '4' }))

    renderPage()

    await user.click(await screen.findByLabelText('编辑 MCP 服务 srv_edit'))

    // 验证调用了 getServerConfig(name)
    expect(mcpServerService.getServerConfig).toHaveBeenCalledWith('srv_edit')

    const modal = await screen.findByRole('dialog', { name: '编辑 MCP 服务 · srv_edit' })
    expect(modal).toBeInTheDocument()

    // 验证 Name 为只读展示
    const nameInput = screen.getByDisplayValue('srv_edit')
    expect(nameInput).toBeDisabled()

    // 验证 URL 和 headers 回填
    const urlInput = screen.getByDisplayValue('https://example.com/api')
    expect(urlInput).toBeInTheDocument()

    // 编辑 URL、headers、enabled 和 timeout
    await user.clear(urlInput)
    await user.type(urlInput, 'https://example.com/api-v2')

    const headersArea = screen.getByLabelText(/自定义请求头/)
    fireEvent.change(headersArea, {
      target: { value: '{"X-Api-Key": "key_123"}' },
    })

    // 提交更新
    await user.click(within(modal).getByRole('button', { name: '确认' }))

    await waitFor(() => {
      expect(mcpServerService.updateServer).toHaveBeenCalledWith('srv_edit', {
        expectedVersion: '3',
        url: 'https://example.com/api-v2',
        headers: { 'X-Api-Key': 'key_123' },
        enabled: true,
        timeoutMillis: 30000,
      })
      expect(screen.queryByRole('dialog', { name: '编辑 MCP 服务 · srv_edit' })).not.toBeInTheDocument()
    })
  })

  it('blocks discover if unsaved changes exist in edit form, and discovers tools when clean', async () => {
    const user = userEvent.setup()
    const serverInstance = mockServer({ name: 'srv_disc', version: '2' })
    vi.mocked(mcpServerService.pageServers).mockResolvedValue({
      pageNumber: 1,
      pageSize: 100,
      totalCount: 1,
      results: [serverInstance],
    })

    vi.mocked(mcpServerService.getServerConfig).mockResolvedValue({
      name: 'srv_disc',
      version: '2',
      url: 'https://example.com/mcp',
      headers: { Authorization: 'Bearer tok' },
      enabled: true,
      timeoutMillis: 60000,
    })
    vi.mocked(mcpServerService.discoverServer).mockResolvedValue(
      mockServer({ name: 'srv_disc', version: '2', discoveryStatus: 'AVAILABLE', toolCount: 4 }),
    )

    renderPage()

    await user.click(await screen.findByLabelText('编辑 MCP 服务 srv_disc'))
    const modal = await screen.findByRole('dialog', { name: '编辑 MCP 服务 · srv_disc' })

    const urlInput = screen.getByDisplayValue('https://example.com/mcp')
    const discoverBtn = within(modal).getByRole('button', { name: '发现工具' })

    // 修改 URL 但未保存，点击发现应被拦截
    await user.type(urlInput, '-modified')
    await user.click(discoverBtn)

    expect(screen.getByRole('alert')).toHaveTextContent('存在未保存的配置更改，请先保存再发现工具。')
    expect(mcpServerService.discoverServer).not.toHaveBeenCalled()

    // 恢复 URL，使表单与 loadedConfig 一致
    await user.clear(urlInput)
    await user.type(urlInput, 'https://example.com/mcp')

    // 再次点击发现，应发起 discoverServer
    await user.click(discoverBtn)

    await waitFor(() => {
      expect(mcpServerService.discoverServer).toHaveBeenCalledWith('srv_disc', '2')
      expect(screen.queryByRole('dialog', { name: '编辑 MCP 服务 · srv_disc' })).not.toBeInTheDocument()
    })
  })

  it('opens delete modal and calls deleteServer with name and expectedVersion', async () => {
    const user = userEvent.setup()
    const serverInstance = mockServer({ name: 'srv_del', version: '5' })
    vi.mocked(mcpServerService.pageServers).mockResolvedValue({
      pageNumber: 1,
      pageSize: 100,
      totalCount: 1,
      results: [serverInstance],
    })
    vi.mocked(mcpServerService.deleteServer).mockResolvedValue(undefined)

    renderPage()

    await user.click(await screen.findByLabelText('删除 MCP 服务 srv_del'))

    expect(screen.getByText('确认删除 MCP 服务「srv_del」？')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '删除 MCP 服务' }))

    await waitFor(() => {
      expect(mcpServerService.deleteServer).toHaveBeenCalledWith('srv_del', '5')
      expect(screen.queryByText('确认删除 MCP 服务「srv_del」？')).not.toBeInTheDocument()
    })
  })

  it('renders retry button when getServerConfig fails', async () => {
    const user = userEvent.setup()
    const serverInstance = mockServer({ name: 'srv_err' })
    vi.mocked(mcpServerService.pageServers).mockResolvedValue({
      pageNumber: 1,
      pageSize: 100,
      totalCount: 1,
      results: [serverInstance],
    })
    vi.mocked(mcpServerService.getServerConfig).mockRejectedValueOnce(new Error('Failed to load config'))

    renderPage()

    await user.click(await screen.findByLabelText('编辑 MCP 服务 srv_err'))

    expect(await screen.findByText('Failed to load config')).toBeInTheDocument()
    const retryBtn = screen.getByRole('button', { name: '重试' })
    expect(retryBtn).toBeInTheDocument()

    vi.mocked(mcpServerService.getServerConfig).mockResolvedValueOnce({
      name: 'srv_err',
      version: '1',
      url: 'https://example.com/ok',
      headers: {},
      enabled: true,
      timeoutMillis: 60000,
    })

    await user.click(retryBtn)
    expect(await screen.findByDisplayValue('https://example.com/ok')).toBeInTheDocument()
  })
})
