import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { McpServersPage } from '@/features/catalog/mcp/McpServersPage'
import { mcpServerService } from '@/features/catalog/mcp-server-service'
import { ApiError } from '@/shared/api/client'
import type {
  McpServerDTO,
  McpServerSummaryDTO,
} from '@/shared/api/generated/contracts'

vi.mock('@/features/catalog/mcp-server-service', () => ({
  headersUpdate: vi.fn((headers: Record<string, string> | null, clear: boolean) => {
    if (headers && Object.keys(headers).length > 0) {
      return { operation: 'SET', value: JSON.stringify(headers) }
    }
    return { operation: clear ? 'CLEAR' : 'KEEP' }
  }),
  mcpServerService: {
    listServers: vi.fn(),
    getServer: vi.fn(),
    createServer: vi.fn(),
    updateServer: vi.fn(),
    discoverServer: vi.fn(),
    deleteServer: vi.fn(),
  },
}))

vi.mock('@/app/ai-console/AiNavigation', () => ({
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

const SEEDED_AT = 1784505600000

function summary(overrides: Partial<McpServerSummaryDTO> = {}): McpServerSummaryDTO {
  return {
    name: 'filesystem',
    enabled: true,
    timeoutMillis: '30000',
    discoveryStatus: 'AVAILABLE',
    toolCount: 3,
    version: '1',
    createdAt: SEEDED_AT,
    updatedAt: SEEDED_AT,
    ...overrides,
  }
}

function detail(overrides: Partial<McpServerDTO> = {}): McpServerDTO {
  return {
    name: 'filesystem',
    url: 'https://example.com/mcp',
    enabled: true,
    timeoutMillis: '30000',
    discoveryStatus: 'AVAILABLE',
    headers: { configured: false },
    toolCount: 0,
    version: '1',
    createdAt: SEEDED_AT,
    updatedAt: SEEDED_AT,
    ...overrides,
  }
}

describe('McpServersPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(mcpServerService.listServers).mockResolvedValue([])
    vi.mocked(mcpServerService.getServer).mockResolvedValue(detail())
    vi.mocked(mcpServerService.deleteServer).mockResolvedValue(undefined)
  })

  it('renders server summary metadata and actions without URL or headers', async () => {
    vi.mocked(mcpServerService.listServers).mockResolvedValue([
      summary({
        name: 'fs_prod',
        enabled: true,
        timeoutMillis: '45000',
        discoveryStatus: 'AVAILABLE',
        toolCount: 5,
        version: '2',
      }),
    ])

    renderPage()

    expect(await screen.findByText('fs_prod')).toBeInTheDocument()
    expect(screen.getByText('Streamable HTTP')).toBeInTheDocument()
    expect(screen.getByText('可用')).toBeInTheDocument()
    expect(screen.getByText('5')).toBeInTheDocument()
    expect(screen.getByText('45000ms')).toBeInTheDocument()
    expect(screen.getByText('2')).toBeInTheDocument()
    expect(screen.getByText('已启用')).toBeInTheDocument()

    // 列表项只有安全元数据：URL 与 headers 不进入卡片
    expect(screen.queryByText(/http:\/\//)).not.toBeInTheDocument()
    expect(screen.queryByText(/https:\/\//)).not.toBeInTheDocument()
    expect(screen.queryByText(/Authorization/)).not.toBeInTheDocument()

    expect(screen.getByLabelText('编辑 MCP 服务 fs_prod')).toBeInTheDocument()
    expect(screen.getByLabelText('删除 MCP 服务 fs_prod')).toBeInTheDocument()
  })

  it('handles loading and error states for the server list', async () => {
    vi.mocked(mcpServerService.listServers).mockRejectedValue(new Error('Network offline'))
    renderPage()

    expect(await screen.findByText('Network offline')).toBeInTheDocument()
  })

  it('creates a server with headers submitted as one secret', async () => {
    const user = userEvent.setup()
    vi.mocked(mcpServerService.createServer).mockResolvedValue(detail({ name: 'new_srv' }))

    renderPage()

    await user.click(await screen.findByRole('button', { name: '创建 MCP 服务' }))
    const modal = screen.getByRole('dialog', { name: '创建 MCP 服务' })

    await user.type(screen.getByPlaceholderText('e.g. filesystem'), 'new_srv')
    await user.type(screen.getByPlaceholderText('https://example.com/mcp'), 'https://example.com/mcp')
    fireEvent.change(screen.getByLabelText(/JSON 请求头/), {
      target: { value: '{"Authorization": "Bearer secret"}' },
    })
    await user.click(within(modal).getByRole('button', { name: '确认' }))

    await waitFor(() => {
      expect(mcpServerService.createServer).toHaveBeenCalledWith({
        name: 'new_srv',
        url: 'https://example.com/mcp',
        headers: { operation: 'SET', value: '{"Authorization":"Bearer secret"}' },
        enabled: true,
        timeoutMillis: 60000,
      })
    })
  })

  it('omits headers on create when none are provided', async () => {
    const user = userEvent.setup()
    vi.mocked(mcpServerService.createServer).mockResolvedValue(detail({ name: 'no_headers' }))

    renderPage()

    await user.click(await screen.findByRole('button', { name: '创建 MCP 服务' }))
    const modal = screen.getByRole('dialog', { name: '创建 MCP 服务' })

    await user.type(screen.getByPlaceholderText('e.g. filesystem'), 'no_headers')
    await user.type(screen.getByPlaceholderText('https://example.com/mcp'), 'https://example.com/mcp')
    await user.click(within(modal).getByRole('button', { name: '确认' }))

    await waitFor(() => {
      expect(mcpServerService.createServer).toHaveBeenCalledWith({
        name: 'no_headers',
        url: 'https://example.com/mcp',
        enabled: true,
        timeoutMillis: 60000,
      })
    })
  })

  it('shows a conflict presenter when create conflicts', async () => {
    const user = userEvent.setup()
    vi.mocked(mcpServerService.createServer).mockRejectedValue(
      new ApiError('Conflict', 409, 'duplicate', {
        name: 'conflict_srv',
        currentVersion: '1',
      }),
    )

    renderPage()

    await user.click(await screen.findByRole('button', { name: '创建 MCP 服务' }))
    const modal = screen.getByRole('dialog', { name: '创建 MCP 服务' })
    await user.type(screen.getByPlaceholderText('e.g. filesystem'), 'conflict_srv')
    await user.type(screen.getByPlaceholderText('https://example.com/mcp'), 'https://example.com/mcp')
    await user.click(within(modal).getByRole('button', { name: '确认' }))

    expect(await screen.findByText('数据已发生变化')).toBeInTheDocument()
  })

  it('edits from the detail projection and keeps stored headers unless replaced or cleared', async () => {
    const user = userEvent.setup()
    vi.mocked(mcpServerService.listServers).mockResolvedValue([summary({ name: 'srv_edit', version: '3' })])
    vi.mocked(mcpServerService.getServer).mockResolvedValue(
      detail({
        name: 'srv_edit',
        url: 'https://example.com/api',
        version: '3',
        headers: { configured: true },
      }),
    )
    vi.mocked(mcpServerService.updateServer).mockResolvedValue(detail({ name: 'srv_edit', version: '4' }))

    renderPage()

    await user.click(await screen.findByLabelText('编辑 MCP 服务 srv_edit'))

    expect(mcpServerService.getServer).toHaveBeenCalledWith('srv_edit')
    const modal = await screen.findByRole('dialog', { name: '编辑 MCP 服务 · srv_edit' })
    expect(screen.getByDisplayValue('srv_edit')).toBeDisabled()

    const urlInput = screen.getByDisplayValue('https://example.com/api')
    // headers 已保存，但从不回读：输入框为空，提示留空保持
    const headersArea = screen.getByLabelText(/JSON 请求头/)
    expect(headersArea).toHaveValue('')
    expect(within(modal).getByText('留空保持已保存的请求头。')).toBeInTheDocument()

    await user.clear(urlInput)
    await user.type(urlInput, 'https://example.com/api-v2')
    await user.click(within(modal).getByRole('button', { name: '确认' }))

    await waitFor(() => {
      expect(mcpServerService.updateServer).toHaveBeenCalledWith('srv_edit', {
        expectedVersion: '3',
        url: 'https://example.com/api-v2',
        headers: { operation: 'KEEP' },
        enabled: true,
        timeoutMillis: 30000,
      })
    })
  })

  it('replaces headers with a SET secret and clears them with CLEAR', async () => {
    const user = userEvent.setup()
    vi.mocked(mcpServerService.listServers).mockResolvedValue([summary({ name: 'srv_hdr', version: '1' })])
    vi.mocked(mcpServerService.getServer).mockResolvedValue(
      detail({ name: 'srv_hdr', version: '1', headers: { configured: true } }),
    )
    vi.mocked(mcpServerService.updateServer).mockResolvedValue(detail({ name: 'srv_hdr', version: '2' }))

    renderPage()

    await user.click(await screen.findByLabelText('编辑 MCP 服务 srv_hdr'))
    const modal = await screen.findByRole('dialog', { name: '编辑 MCP 服务 · srv_hdr' })

    fireEvent.change(screen.getByLabelText(/JSON 请求头/), {
      target: { value: '{"X-Api-Key": "key_123"}' },
    })
    await user.click(within(modal).getByRole('button', { name: '确认' }))

    await waitFor(() => {
      expect(mcpServerService.updateServer).toHaveBeenCalledWith('srv_hdr', {
        expectedVersion: '1',
        url: 'https://example.com/mcp',
        headers: { operation: 'SET', value: '{"X-Api-Key":"key_123"}' },
        enabled: true,
        timeoutMillis: 30000,
      })
    })

    await user.click(await screen.findByLabelText('编辑 MCP 服务 srv_hdr'))
    const secondModal = await screen.findByRole('dialog', { name: '编辑 MCP 服务 · srv_hdr' })
    await user.click(within(secondModal).getByLabelText('清除已保存的请求头'))
    await user.click(within(secondModal).getByRole('button', { name: '确认' }))

    await waitFor(() => {
      expect(mcpServerService.updateServer).toHaveBeenLastCalledWith('srv_hdr', {
        expectedVersion: '1',
        url: 'https://example.com/mcp',
        headers: { operation: 'CLEAR' },
        enabled: true,
        timeoutMillis: 30000,
      })
    })
  })

  it('blocks discover while the form has unsaved changes and discovers when clean', async () => {
    const user = userEvent.setup()
    vi.mocked(mcpServerService.listServers).mockResolvedValue([summary({ name: 'srv_disc', version: '2' })])
    vi.mocked(mcpServerService.getServer).mockResolvedValue(
      detail({ name: 'srv_disc', url: 'https://example.com/mcp', version: '2' }),
    )
    vi.mocked(mcpServerService.discoverServer).mockResolvedValue(
      detail({ name: 'srv_disc', version: '2', discoveryStatus: 'AVAILABLE', toolCount: 4 }),
    )

    renderPage()

    await user.click(await screen.findByLabelText('编辑 MCP 服务 srv_disc'))
    const modal = await screen.findByRole('dialog', { name: '编辑 MCP 服务 · srv_disc' })
    const urlInput = screen.getByDisplayValue('https://example.com/mcp')
    const discoverBtn = within(modal).getByRole('button', { name: '发现工具' })

    await user.type(urlInput, '-modified')
    await user.click(discoverBtn)

    expect(screen.getByRole('alert')).toHaveTextContent('存在未保存的配置更改，请先保存再发现工具。')
    expect(mcpServerService.discoverServer).not.toHaveBeenCalled()

    await user.clear(urlInput)
    await user.type(urlInput, 'https://example.com/mcp')
    await user.click(discoverBtn)

    await waitFor(() => {
      expect(mcpServerService.discoverServer).toHaveBeenCalledWith('srv_disc', '2')
    })
  })

  it('deletes with the displayed version', async () => {
    const user = userEvent.setup()
    vi.mocked(mcpServerService.listServers).mockResolvedValue([summary({ name: 'srv_del', version: '5' })])

    renderPage()

    await user.click(await screen.findByLabelText('删除 MCP 服务 srv_del'))
    expect(screen.getByText('确认删除 MCP 服务「srv_del」？')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '删除 MCP 服务' }))

    await waitFor(() => {
      expect(mcpServerService.deleteServer).toHaveBeenCalledWith('srv_del', '5')
    })
  })

  it('offers retry when the detail projection cannot be loaded', async () => {
    const user = userEvent.setup()
    vi.mocked(mcpServerService.listServers).mockResolvedValue([summary({ name: 'srv_err' })])
    vi.mocked(mcpServerService.getServer).mockRejectedValueOnce(new Error('Failed to load detail'))

    renderPage()

    await user.click(await screen.findByLabelText('编辑 MCP 服务 srv_err'))

    expect(await screen.findByText('Failed to load detail')).toBeInTheDocument()
    vi.mocked(mcpServerService.getServer).mockResolvedValueOnce(
      detail({ name: 'srv_err', url: 'https://example.com/ok' }),
    )
    await user.click(screen.getByRole('button', { name: '重试' }))
    expect(await screen.findByDisplayValue('https://example.com/ok')).toBeInTheDocument()
  })

  it('refreshes cached servers on background refetch errors', async () => {
    vi.mocked(mcpServerService.listServers).mockResolvedValueOnce([summary({ name: 'cached-server' })])
    const { queryClient } = renderPage()
    expect(await screen.findByText('cached-server')).toBeInTheDocument()

    vi.mocked(mcpServerService.listServers).mockRejectedValueOnce(new Error('background mcp error'))
    await act(async () => {
      await queryClient.refetchQueries()
    })

    expect(await screen.findByText('background mcp error')).toBeInTheDocument()
    expect(screen.getByText('cached-server')).toBeInTheDocument()
    expect(screen.getByText('创建 MCP 服务')).toBeInTheDocument()
  })
})
