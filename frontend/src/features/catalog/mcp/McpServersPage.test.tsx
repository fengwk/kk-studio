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
    url: 'https://mcp.example.invalid/mcp',
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
    // 与 Provider 卡同一套时长格式：整秒值按秒显示，不再是 45000 毫秒。
    expect(screen.getByText('45 秒')).toBeInTheDocument()
    expect(screen.getByText('2')).toBeInTheDocument()
    expect(screen.getByText('已启用')).toBeInTheDocument()

    // 每条元信息都是独立行，共用卡片标签列；不存在脱离该列的成对行。
    const card = screen.getByText('fs_prod').closest('article')
    expect(card?.querySelectorAll('.resource-card-pairs')).toHaveLength(0)
    expect(
      Array.from(card?.querySelectorAll('.resource-card-meta-row .resource-card-meta-label') ?? []).map(
        (label) => label.textContent,
      ),
    ).toEqual(['状态', '工具数量', '版本', '超时', '启用状态', '更新时间'])

    // 列表项只有安全元数据：URL 与 headers 不进入卡片
    expect(screen.queryByText(/http:\/\//)).not.toBeInTheDocument()
    expect(screen.queryByText(/https:\/\//)).not.toBeInTheDocument()
    expect(screen.queryByText(/Authorization/)).not.toBeInTheDocument()

    expect(screen.getByLabelText('编辑 MCP 服务 fs_prod')).toBeInTheDocument()
    expect(screen.getByLabelText('删除 MCP 服务 fs_prod')).toBeInTheDocument()
  })

  // 列表读取失败走共享页壳：按 HTTP 分类出本地化文案，并就地给出重试。
  it('keeps the list failure inside the shared console frame with a retry', async () => {
    const user = userEvent.setup()
    vi.mocked(mcpServerService.listServers).mockRejectedValueOnce(new ApiError('boom', 500))

    renderPage()

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('服务暂时异常，请稍后重试')
    expect(screen.queryByText('boom')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '新建 MCP 服务' })).not.toBeInTheDocument()

    vi.mocked(mcpServerService.listServers).mockResolvedValueOnce([summary({ name: 'recovered' })])
    await user.click(screen.getByRole('button', { name: '重试' }))
    expect(await screen.findByText('recovered')).toBeInTheDocument()
  })

  it('filters the server list from the page search', async () => {
    const user = userEvent.setup()
    vi.mocked(mcpServerService.listServers).mockResolvedValue([
      summary({ name: 'filesystem' }),
      summary({ name: 'github_tools' }),
    ])

    renderPage()

    expect(await screen.findByText('github_tools')).toBeInTheDocument()

    // 搜索与其它 AI 子页同位同组件：只收窄资源卡，新建卡保留为空结果的唯一沉点。
    await user.type(screen.getByPlaceholderText('搜索资源...'), 'github')

    await waitFor(() => {
      expect(screen.queryByText('filesystem')).not.toBeInTheDocument()
    })
    expect(screen.getByText('github_tools')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '新建 MCP 服务' })).toBeInTheDocument()
  })

  it('creates a server with headers submitted as one secret', async () => {
    const user = userEvent.setup()
    vi.mocked(mcpServerService.createServer).mockResolvedValue(detail({ name: 'new_srv' }))

    renderPage()

    await user.click(await screen.findByRole('button', { name: '新建 MCP 服务' }))
    const modal = screen.getByRole('dialog', { name: '新建 MCP 服务' })

    await user.type(screen.getByPlaceholderText('例如 filesystem'), 'new_srv')
    await user.type(screen.getByPlaceholderText('https://mcp.example.invalid/mcp'), 'https://mcp.example.invalid/mcp')
    fireEvent.change(screen.getByRole('textbox', { name: /JSON 请求头/ }), {
      target: { value: '{"Authorization": "Bearer secret"}' },
    })
    await user.click(within(modal).getByRole('button', { name: '创建' }))

    await waitFor(() => {
      expect(mcpServerService.createServer).toHaveBeenCalledWith({
        name: 'new_srv',
        url: 'https://mcp.example.invalid/mcp',
        headers: { operation: 'SET', value: '{"Authorization":"Bearer secret"}' },
        enabled: true,
        timeoutMillis: '60000',
      })
    })
  })

  it('omits headers on create when none are provided', async () => {
    const user = userEvent.setup()
    vi.mocked(mcpServerService.createServer).mockResolvedValue(detail({ name: 'no_headers' }))

    renderPage()

    await user.click(await screen.findByRole('button', { name: '新建 MCP 服务' }))
    const modal = screen.getByRole('dialog', { name: '新建 MCP 服务' })

    await user.type(screen.getByPlaceholderText('例如 filesystem'), 'no_headers')
    await user.type(screen.getByPlaceholderText('https://mcp.example.invalid/mcp'), 'https://mcp.example.invalid/mcp')
    await user.click(within(modal).getByRole('button', { name: '创建' }))

    await waitFor(() => {
      expect(mcpServerService.createServer).toHaveBeenCalledWith({
        name: 'no_headers',
        url: 'https://mcp.example.invalid/mcp',
        enabled: true,
        timeoutMillis: '60000',
      })
    })
  })

  it('shows the localized validation error in the create dialog', async () => {
    const user = userEvent.setup()

    renderPage()

    await user.click(await screen.findByRole('button', { name: '新建 MCP 服务' }))
    const modal = screen.getByRole('dialog', { name: '新建 MCP 服务' })
    await user.type(screen.getByPlaceholderText('例如 filesystem'), 'Bad_Name')
    await user.type(screen.getByPlaceholderText('https://mcp.example.invalid/mcp'), 'https://mcp.example.invalid/mcp')
    await user.click(within(modal).getByRole('button', { name: '创建' }))

    // 校验错误是用户可见文案：必须随界面语言走，而不是固定英文。
    expect(screen.getByRole('alert')).toHaveTextContent('服务名称需以小写字母开头')
    expect(mcpServerService.createServer).not.toHaveBeenCalled()
  })

  it('labels the create timeout with its unit and shows no validation message initially', async () => {
    const user = userEvent.setup()

    renderPage()

    await user.click(await screen.findByRole('button', { name: '新建 MCP 服务' }))
    const modal = screen.getByRole('dialog', { name: '新建 MCP 服务' })

    const timeoutField = within(modal).getByLabelText('超时（毫秒）')
    expect(timeoutField).toHaveValue('60000')

    expect(within(modal).queryByRole('alert')).not.toBeInTheDocument()
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

    await user.click(await screen.findByRole('button', { name: '新建 MCP 服务' }))
    const modal = screen.getByRole('dialog', { name: '新建 MCP 服务' })
    await user.type(screen.getByPlaceholderText('例如 filesystem'), 'conflict_srv')
    await user.type(screen.getByPlaceholderText('https://mcp.example.invalid/mcp'), 'https://mcp.example.invalid/mcp')
    await user.click(within(modal).getByRole('button', { name: '创建' }))

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
    const modal = await screen.findByRole('dialog', { name: '编辑 MCP 服务：srv_edit' })
    expect(screen.getByDisplayValue('srv_edit')).toBeDisabled()

    const urlInput = screen.getByDisplayValue('https://example.com/api')
    // headers 已保存，但从不回读：输入框为空，提示留空保持
    const headersArea = screen.getByRole('textbox', { name: /JSON 请求头/ })
    expect(headersArea).toHaveValue('')
    fireEvent.focus(within(modal).getByRole('button', { name: /帮助：.*请求头/ }))
    expect(screen.getByText('留空保持已保存的请求头。')).toBeInTheDocument()

    await user.clear(urlInput)
    await user.type(urlInput, 'https://example.com/api-v2')
    await user.click(within(modal).getByRole('button', { name: '保存修改' }))

    await waitFor(() => {
      expect(mcpServerService.updateServer).toHaveBeenCalledWith('srv_edit', {
        expectedVersion: '3',
        url: 'https://example.com/api-v2',
        headers: { operation: 'KEEP' },
        enabled: true,
        timeoutMillis: '30000',
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
    const modal = await screen.findByRole('dialog', { name: '编辑 MCP 服务：srv_hdr' })

    fireEvent.change(screen.getByRole('textbox', { name: /JSON 请求头/ }), {
      target: { value: '{"X-Api-Key": "key_123"}' },
    })
    await user.click(within(modal).getByRole('button', { name: '保存修改' }))

    await waitFor(() => {
      expect(mcpServerService.updateServer).toHaveBeenCalledWith('srv_hdr', {
        expectedVersion: '1',
        url: 'https://mcp.example.invalid/mcp',
        headers: { operation: 'SET', value: '{"X-Api-Key":"key_123"}' },
        enabled: true,
        timeoutMillis: '30000',
      })
    })

    await user.click(await screen.findByLabelText('编辑 MCP 服务 srv_hdr'))
    const secondModal = await screen.findByRole('dialog', { name: '编辑 MCP 服务：srv_hdr' })
    await user.click(within(secondModal).getByLabelText('清除已保存的请求头'))
    await user.click(within(secondModal).getByRole('button', { name: '保存修改' }))

    await waitFor(() => {
      expect(mcpServerService.updateServer).toHaveBeenLastCalledWith('srv_hdr', {
        expectedVersion: '1',
        url: 'https://mcp.example.invalid/mcp',
        headers: { operation: 'CLEAR' },
        enabled: true,
        timeoutMillis: '30000',
      })
    })
  })

  it('blocks discover while the form has unsaved changes and discovers when clean', async () => {
    const user = userEvent.setup()
    vi.mocked(mcpServerService.listServers).mockResolvedValue([summary({ name: 'srv_disc', version: '2' })])
    vi.mocked(mcpServerService.getServer).mockResolvedValue(
      detail({ name: 'srv_disc', url: 'https://mcp.example.invalid/mcp', version: '2' }),
    )
    vi.mocked(mcpServerService.discoverServer).mockResolvedValue(
      detail({ name: 'srv_disc', version: '2', discoveryStatus: 'AVAILABLE', toolCount: 4 }),
    )

    renderPage()

    await user.click(await screen.findByLabelText('编辑 MCP 服务 srv_disc'))
    const modal = await screen.findByRole('dialog', { name: '编辑 MCP 服务：srv_disc' })
    const urlInput = screen.getByDisplayValue('https://mcp.example.invalid/mcp')
    const discoverBtn = within(modal).getByRole('button', { name: '发现工具' })

    await user.type(urlInput, '-modified')
    await user.click(discoverBtn)

    expect(screen.getByRole('alert')).toHaveTextContent('存在未保存的配置更改，请先保存再发现工具。')
    expect(mcpServerService.discoverServer).not.toHaveBeenCalled()

    await user.clear(urlInput)
    await user.type(urlInput, 'https://mcp.example.invalid/mcp')
    await user.click(discoverBtn)

    await waitFor(() => {
      expect(mcpServerService.discoverServer).toHaveBeenCalledWith('srv_disc', '2')
    })
  })

  it('compares the submitted timeout instead of the raw input when blocking discover', async () => {
    const user = userEvent.setup()
    vi.mocked(mcpServerService.listServers).mockResolvedValue([summary({ name: 'srv_timeout', version: '2' })])
    vi.mocked(mcpServerService.getServer).mockResolvedValue(
      detail({ name: 'srv_timeout', timeoutMillis: '60000', version: '2' }),
    )

    renderPage()

    await user.click(await screen.findByLabelText('编辑 MCP 服务 srv_timeout'))
    const modal = await screen.findByRole('dialog', { name: '编辑 MCP 服务：srv_timeout' })
    // 清空超时后提交会回退为默认 60000，与已保存值一致，不应被当作未保存的变更。
    await user.clear(within(modal).getByLabelText('超时（毫秒）'))
    await user.click(within(modal).getByRole('button', { name: '发现工具' }))

    await waitFor(() => {
      expect(mcpServerService.discoverServer).toHaveBeenCalledWith('srv_timeout', '2')
    })

    vi.mocked(mcpServerService.discoverServer).mockClear()
    await user.click(await screen.findByLabelText('编辑 MCP 服务 srv_timeout'))
    const reopened = await screen.findByRole('dialog', { name: '编辑 MCP 服务：srv_timeout' })
    // 真正不同的超时仍然是未保存的变更，必须阻断发现工具。
    await user.clear(within(reopened).getByLabelText('超时（毫秒）'))
    await user.type(within(reopened).getByLabelText('超时（毫秒）'), '45000')
    await user.click(within(reopened).getByRole('button', { name: '发现工具' }))

    expect(screen.getByRole('alert')).toHaveTextContent('存在未保存的配置更改，请先保存再发现工具。')
    expect(mcpServerService.discoverServer).not.toHaveBeenCalled()
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

  // 后台读取失败时，已加载的列表仍可用；重试成功后撤下错误提示。
  it('localizes background refetch errors without hiding cached servers', async () => {
    const user = userEvent.setup()
    vi.mocked(mcpServerService.listServers).mockResolvedValue([summary({ name: 'cached-server' })])
    const { queryClient } = renderPage()
    expect(await screen.findByText('cached-server')).toBeInTheDocument()

    vi.mocked(mcpServerService.listServers).mockRejectedValueOnce(
      new ApiError('Failed to fetch', undefined, 'network_error'),
    )
    await act(async () => {
      await queryClient.refetchQueries()
    })

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('网络异常，请稍后重试')
    expect(screen.getByText('cached-server')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '重试' }))
    await waitFor(() => expect(alert).not.toBeInTheDocument())
  })
})
