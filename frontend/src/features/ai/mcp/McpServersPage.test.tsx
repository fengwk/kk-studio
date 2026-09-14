import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { McpServersPage } from '@/features/ai/mcp/McpServersPage'
import { mcpServerService } from '@/shared/api/mcp-server-service'
import { environmentService } from '@/shared/api/environment-service'
import type { McpServerConfigDTO, McpServerDTO } from '@/shared/api/contracts/ai-mcp'
import type { EnvironmentCardDTO } from '@/shared/api/contracts/ai-environment'
import { ApiError } from '@/shared/api/client'
import { createLocalConfigTemplate } from './mcp-config-json'

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

vi.mock('@/shared/api/environment-service', () => ({
  DEFAULT_OPERATION_LIMIT: 50,
  environmentService: {
    listEnvironments: vi.fn(),
  },
}))

vi.mock('@/platform/workbench/WorkbenchSlots', () => ({
  NavigationSlot: () => null,
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

function server(overrides: Partial<McpServerDTO> = {}): McpServerDTO {
  return {
    id: 'srv-1',
    name: 'filesystem',
    type: 'remote',
    environmentId: null,
    enabled: true,
    timeoutMillis: 30000,
    discoveryStatus: 'AVAILABLE',
    discoveredVersion: '1',
    toolCount: 3,
    version: '1',
    createTime: '2026-07-20T00:00:00.000Z',
    updateTime: '2026-07-20T00:00:00.000Z',
    ...overrides,
  }
}

const mockEnvironment: EnvironmentCardDTO = {
  id: '00000000-0000-4000-8000-000000000001',
  name: 'prod-linux-node',
  status: 'READY',
  ready: true,
  lastSeen: '2026-07-20T00:00:00.000Z',
  capabilities: [],
  rootPath: '/opt/studio',
  version: '1',
  createTime: '2026-07-20T00:00:00.000Z',
  updateTime: '2026-07-20T00:00:00.000Z',
}

describe('McpServersPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([mockEnvironment])
  })

  /**
   * 测试意图：验证 MCP 卡片仅渲染安全元数据（类型、环境名、状态、工具数、超时、版本、启用状态、更新时间），
   * 绝不暴露配置 URL、Bearer Token 或命令行参数，且卡片底部仅保留编辑与删除按钮。
   */
  it('renders safe metadata only on MCP cards and never exposes config URL or credentials', async () => {
    vi.mocked(mcpServerService.pageServers).mockResolvedValue({
      pageNumber: 1,
      pageSize: 100,
      totalCount: 2,
      results: [
        server({
          id: 'srv-remote',
          name: 'remote_server',
          type: 'remote',
          discoveryStatus: 'AVAILABLE',
          toolCount: 8,
          timeoutMillis: 45000,
          version: '3',
          discoveredVersion: '3',
        }),
        server({
          id: 'srv-local',
          name: 'local_server',
          type: 'local',
          environmentId: '00000000-0000-4000-8000-000000000001',
          discoveryStatus: 'UNVERIFIED',
          toolCount: 0,
          timeoutMillis: 60000,
          version: '1',
          discoveredVersion: null,
        }),
      ],
    })
    renderPage()

    expect(await screen.findByText('remote_server')).toBeInTheDocument()
    expect(screen.getByText('local_server')).toBeInTheDocument()

    // 验证安全元数据展示
    expect(screen.getByText('远程 (Remote)')).toBeInTheDocument()
    expect(screen.getByText('本地 (Local)')).toBeInTheDocument()
    expect(screen.getByText('可用')).toBeInTheDocument()
    expect(screen.getByText('未验证')).toBeInTheDocument()
    expect(screen.getByText('prod-linux-node')).toBeInTheDocument()
    expect(screen.getByText('45000ms')).toBeInTheDocument()
    expect(screen.getByText('60000ms')).toBeInTheDocument()
    expect(screen.getAllByText('启用状态')).toHaveLength(2)
    expect(screen.getAllByText('已启用')).toHaveLength(2)

    // 验证绝不包含 URL、bearerToken 或命令内容
    expect(screen.queryByText(/localhost/i)).toBeNull()
    expect(screen.queryByText(/bearer/i)).toBeNull()
    expect(screen.queryByText(/token/i)).toBeNull()

    // 验证卡片仅含编辑与删除按钮，卡片上不含直接刷新或发现按钮
    const cards = screen.getAllByRole('article')
    expect(cards).toHaveLength(2)
    for (const card of cards) {
      const footerButtons = within(card).getAllByRole('button')
      expect(footerButtons).toHaveLength(2)
      expect(within(card).getByRole('button', { name: /编辑 MCP 服务/ })).toBeInTheDocument()
      expect(within(card).getByRole('button', { name: /删除 MCP 服务/ })).toBeInTheDocument()
      expect(within(card).queryByRole('button', { name: /发现/ })).toBeNull()
      expect(within(card).queryByRole('button', { name: /刷新/ })).toBeNull()
    }
  })

  /**
   * 测试意图：验证后端数值秒与毫秒时间戳格式化为现代年份，不误解析为 1970。
   */
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
    expect(screen.queryByText(/1970/)).toBeNull()

    const formattedDates = screen.getAllByText(/2025/)
    expect(formattedDates.length).toBe(2)
    expect(formattedDates[0]!.textContent).toBe(formattedDates[1]!.textContent)
  })

  /**
   * 测试意图：验证创建 MCP 服务时呈现服务名称与单份权威配置 JSON 文本域，支持模板载入并提交创建。
   */
  it('creates an MCP server with name and authoritative JSON textarea', async () => {
    const user = userEvent.setup()
    vi.mocked(mcpServerService.pageServers).mockResolvedValue({
      pageNumber: 1,
      pageSize: 100,
      totalCount: 0,
      results: [],
    })
    vi.mocked(mcpServerService.createServer).mockResolvedValue(
      server({ id: 'srv-new', name: 'new_server' }),
    )
    renderPage()

    const createBtn = await screen.findByRole('button', { name: '创建 MCP 服务' })
    await user.click(createBtn)

    const dialog = await screen.findByRole('dialog', { name: '创建 MCP 服务' })
    expect(dialog).toBeInTheDocument()

    const nameInput = within(dialog).getByRole('textbox', { name: /服务名称/ })
    await user.type(nameInput, 'new_server')

    // 切换到 Local 模板
    const localTplBtn = within(dialog).getByRole('button', { name: 'Local 模板' })
    await user.click(localTplBtn)

    const textarea = within(dialog).getByRole('textbox', { name: /配置 JSON/ })
    expect((textarea as HTMLTextAreaElement).value).toContain('"type": "local"')
    expect((textarea as HTMLTextAreaElement).value).toContain(mockEnvironment.id)

    // 格式化按钮动作
    const formatBtn = within(dialog).getByRole('button', { name: '格式化' })
    await user.click(formatBtn)

    // 校验按钮动作
    const validateBtn = within(dialog).getByRole('button', { name: '校验' })
    await user.click(validateBtn)
    expect(within(dialog).getByText('配置格式校验通过')).toBeInTheDocument()

    // 确认提交
    const submitBtn = within(dialog).getByRole('button', { name: '确认' })
    await user.click(submitBtn)

    expect(mcpServerService.createServer).toHaveBeenCalledWith({
      name: 'new_server',
      configJson: expect.stringContaining('"type": "local"'),
    })
  })

  /**
   * 测试意图：验证打开编辑弹窗时直接发起 getServerConfig 读取完整配置且不使用 React Query 缓存，
   * 提交保存时以返回的配置版本作为 CAS expectedVersion。
   */
  it('edits an MCP server via direct getServerConfig and saves with loaded CAS version', async () => {
    const user = userEvent.setup()
    vi.mocked(mcpServerService.pageServers).mockResolvedValue({
      pageNumber: 1,
      pageSize: 100,
      totalCount: 1,
      results: [server({ id: 'srv-1', name: 'filesystem', version: '1' })],
    })
    vi.mocked(mcpServerService.getServerConfig).mockResolvedValue({
      id: 'srv-1',
      name: 'filesystem',
      version: '4', // 后端最新配置版本可能领先于列表版本
      configJson: '{"type":"remote","url":"https://example.com/mcp","enabled":true,"timeoutMillis":60000}',
    })
    vi.mocked(mcpServerService.updateServer).mockResolvedValue(
      server({ id: 'srv-1', name: 'filesystem', version: '5' }),
    )
    renderPage()

    const editBtn = await screen.findByRole('button', { name: /编辑 MCP 服务/ })
    await user.click(editBtn)

    // 验证显式直读配置 API 被调用
    expect(mcpServerService.getServerConfig).toHaveBeenCalledWith('srv-1')

    const dialog = await screen.findByRole('dialog', { name: /编辑 MCP 服务/ })
    const textarea = await within(dialog).findByRole('textbox', { name: /配置 JSON/ })
    expect((textarea as HTMLTextAreaElement).value).toContain('https://example.com/mcp')

    // 修改配置
    fireEvent.change(textarea, {
      target: {
        value:
          '{"type":"remote","url":"https://example.com/mcp-v2","enabled":true,"timeoutMillis":60000}',
      },
    })

    const confirmBtn = within(dialog).getByRole('button', { name: '确认' })
    await user.click(confirmBtn)

    // 验证更新请求使用的是配置直读响应的 version (4)，而非列表的过期版本 (1)
    expect(mcpServerService.updateServer).toHaveBeenCalledWith('srv-1', {
      configJson: '{"type":"remote","url":"https://example.com/mcp-v2","enabled":true,"timeoutMillis":60000}',
      expectedVersion: '4',
    })

    await waitFor(() => {
      expect(screen.queryByRole('dialog', { name: /编辑 MCP 服务/ })).toBeNull()
    })
  })

  /**
   * 测试意图：验证单调递增代际 token 解决针对同一 Server 的 ABA 竞态：
   * 打开同一 server 后关闭并立即重新打开，第一个请求的延迟响应绝不能覆写第二个请求的状态。
   */
  it('fences ABA race with generation token when closing and reopening the same server', async () => {
    const user = userEvent.setup()
    vi.mocked(mcpServerService.pageServers).mockResolvedValue({
      pageNumber: 1,
      pageSize: 100,
      totalCount: 1,
      results: [server({ id: 'srv-1', name: 'filesystem' })],
    })

    let resolveFirstConfig!: (value: McpServerConfigDTO) => void
    let resolveSecondConfig!: (value: McpServerConfigDTO) => void
    let callCount = 0

    vi.mocked(mcpServerService.getServerConfig).mockImplementation(() => {
      callCount++
      if (callCount === 1) {
        return new Promise((resolve) => {
          resolveFirstConfig = resolve
        })
      }
      return new Promise((resolve) => {
        resolveSecondConfig = resolve
      })
    })

    renderPage()

    const editBtn = await screen.findByRole('button', { name: /编辑 MCP 服务/ })
    // 第一次打开编辑（Generation 1）
    await user.click(editBtn)
    expect(await screen.findByRole('dialog', { name: /编辑 MCP 服务/ })).toBeInTheDocument()
    expect(screen.getByText('正在读取配置...')).toBeInTheDocument()

    // 关闭弹窗（Generation 失效）
    const closeBtn = screen.getByRole('button', { name: '关闭' })
    await user.click(closeBtn)
    expect(screen.queryByRole('dialog', { name: /编辑 MCP 服务/ })).toBeNull()

    // 重新打开同一 server 的编辑（Generation 2）
    await user.click(editBtn)
    expect(await screen.findByRole('dialog', { name: /编辑 MCP 服务/ })).toBeInTheDocument()
    expect(screen.getByText('正在读取配置...')).toBeInTheDocument()

    // 第一次请求的响应现在迟到返回
    resolveFirstConfig({
      id: 'srv-1',
      name: 'filesystem',
      version: '1',
      configJson: '{"type":"remote","url":"https://example.com/first"}',
    })

    // 验证代际已失效：第一次请求的响应被丢弃，弹窗仍然处于第二次请求的加载中状态，未被 /first 污染
    await new Promise((r) => setTimeout(r, 20))
    expect(screen.getByText('正在读取配置...')).toBeInTheDocument()
    expect(screen.queryByText(/https:\/\/example\.com\/first/)).toBeNull()

    // 第二次请求的响应返回
    resolveSecondConfig({
      id: 'srv-1',
      name: 'filesystem',
      version: '2',
      configJson: '{"type":"remote","url":"https://example.com/second"}',
    })

    // 验证代际匹配：第二次请求的响应正确生效展示
    const textarea = await screen.findByRole('textbox', { name: /配置 JSON/ })
    expect((textarea as HTMLTextAreaElement).value).toContain('https://example.com/second')

    // 再次验证：如果用户在请求未完成前关闭弹窗，后续到达的响应不会重新拉起弹窗
    const closeBtn2 = screen.getByRole('button', { name: '关闭' })
    await user.click(closeBtn2)
    expect(screen.queryByRole('dialog', { name: /编辑 MCP 服务/ })).toBeNull()
  })

  /**
   * 测试意图：验证当草稿为 Local JSON 时，关联环境下拉选单仅重写同一份 JSON 内的 environmentId，
   * 绝不额外附带发送第二个绑定字段。
   */
  it('rewrites environmentId in JSON text when selecting environment for local draft', async () => {
    const user = userEvent.setup()
    const targetEnvId = '00000000-0000-4000-8000-000000000099'
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([
      mockEnvironment,
      {
        ...mockEnvironment,
        id: targetEnvId,
        name: 'staging-cluster',
      },
    ])
    vi.mocked(mcpServerService.pageServers).mockResolvedValue({
      pageNumber: 1,
      pageSize: 100,
      totalCount: 0,
      results: [],
    })
    vi.mocked(mcpServerService.createServer).mockResolvedValue(
      server({ id: 'srv-local-new', name: 'local_server' }),
    )

    renderPage()
    const createBtn = await screen.findByRole('button', { name: '创建 MCP 服务' })
    await user.click(createBtn)

    const dialog = await screen.findByRole('dialog', { name: '创建 MCP 服务' })
    await user.type(within(dialog).getByRole('textbox', { name: /服务名称/ }), 'local_server')

    // 载入 Local 模板
    await user.click(within(dialog).getByRole('button', { name: 'Local 模板' }))

    // 验证环境下拉选单可见
    const envSelect = within(dialog).getByRole('combobox', { name: /关联环境/ })
    expect(envSelect).toBeInTheDocument()

    // 切换选中的环境
    await user.selectOptions(envSelect, targetEnvId)

    // 验证文本域中 environmentId 被同步回写
    const textarea = within(dialog).getByRole('textbox', { name: /配置 JSON/ })
    expect((textarea as HTMLTextAreaElement).value).toContain(targetEnvId)

    // 提交创建，验证载荷中仅包含 name 和 configJson，绝无额外的独立 environmentId 绑定字段
    await user.click(within(dialog).getByRole('button', { name: '确认' }))
    expect(mcpServerService.createServer).toHaveBeenCalledWith({
      name: 'local_server',
      configJson: expect.stringContaining(targetEnvId),
    })
  })

  /**
   * 测试意图：验证当存在未保存的语义配置修改时，点击「发现工具」被主动拦截并展示操作提示，绝不发出请求。
   */
  it('prevents tool discovery when unsaved semantic configuration changes exist', async () => {
    const user = userEvent.setup()
    vi.mocked(mcpServerService.pageServers).mockResolvedValue({
      pageNumber: 1,
      pageSize: 100,
      totalCount: 1,
      results: [server({ id: 'srv-1', name: 'filesystem', version: '2' })],
    })
    vi.mocked(mcpServerService.getServerConfig).mockResolvedValue({
      id: 'srv-1',
      name: 'filesystem',
      version: '2',
      configJson: '{"type":"remote","url":"https://example.com/mcp"}',
    })

    renderPage()
    const editBtn = await screen.findByRole('button', { name: /编辑 MCP 服务/ })
    await user.click(editBtn)

    const dialog = await screen.findByRole('dialog', { name: /编辑 MCP 服务/ })
    const textarea = await within(dialog).findByRole('textbox', { name: /配置 JSON/ })

    // 修改了 URL（语义变更）
    fireEvent.change(textarea, {
      target: { value: '{"type":"remote","url":"https://example.com/changed"}' },
    })

    const discoverBtn = within(dialog).getByRole('button', { name: '发现工具' })
    await user.click(discoverBtn)

    // 验证拦截提示呈现，且绝未调用 discoverServer
    expect(
      within(dialog).getByText('存在未保存的配置更改，请先保存再发现工具。'),
    ).toBeInTheDocument()
    expect(mcpServerService.discoverServer).not.toHaveBeenCalled()
  })

  /**
   * 测试意图：验证纯空白/换行格式化差异被判定为语义等价，允许直接触发发现。
   */
  it('allows tool discovery when changes are whitespace-only formatting differences', async () => {
    const user = userEvent.setup()
    vi.mocked(mcpServerService.pageServers).mockResolvedValue({
      pageNumber: 1,
      pageSize: 100,
      totalCount: 1,
      results: [server({ id: 'srv-1', name: 'filesystem', version: '2' })],
    })
    vi.mocked(mcpServerService.getServerConfig).mockResolvedValue({
      id: 'srv-1',
      name: 'filesystem',
      version: '2',
      configJson: '{"type":"remote","url":"https://example.com/mcp"}',
    })
    vi.mocked(mcpServerService.discoverServer).mockResolvedValue({
      server: server({ id: 'srv-1', version: '2', discoveryStatus: 'AVAILABLE' }),
      operation: null,
    })

    renderPage()
    const editBtn = await screen.findByRole('button', { name: /编辑 MCP 服务/ })
    await user.click(editBtn)

    const dialog = await screen.findByRole('dialog', { name: /编辑 MCP 服务/ })
    const textarea = await within(dialog).findByRole('textbox', { name: /配置 JSON/ })

    // 格式化为换行多行（无语义变化）
    fireEvent.change(textarea, {
      target: {
        value: '{\n  "type": "remote",\n  "url": "https://example.com/mcp"\n}',
      },
    })

    const discoverBtn = within(dialog).getByRole('button', { name: '发现工具' })
    await user.click(discoverBtn)

    expect(mcpServerService.discoverServer).toHaveBeenCalledWith('srv-1', '2')
    await waitFor(() => {
      expect(screen.queryByRole('dialog', { name: /编辑 MCP 服务/ })).toBeNull()
    })
  })

  /**
   * 测试意图：验证针对 Local MCP Server 发现时返回异步 EnvironmentOperationDTO，并使操作缓存失效。
   */
  it('discovers tools on a local MCP server returning an asynchronous EnvironmentOperation', async () => {
    const user = userEvent.setup()
    vi.mocked(mcpServerService.pageServers).mockResolvedValue({
      pageNumber: 1,
      pageSize: 100,
      totalCount: 1,
      results: [
        server({
          id: 'srv-local',
          name: 'local_daemon',
          type: 'local',
          environmentId: '00000000-0000-4000-8000-000000000001',
          version: '3',
        }),
      ],
    })
    vi.mocked(mcpServerService.getServerConfig).mockResolvedValue({
      id: 'srv-local',
      name: 'local_daemon',
      version: '3',
      configJson: createLocalConfigTemplate('00000000-0000-4000-8000-000000000001'),
    })
    vi.mocked(mcpServerService.discoverServer).mockResolvedValue({
      server: server({
        id: 'srv-local',
        name: 'local_daemon',
        type: 'local',
        environmentId: '00000000-0000-4000-8000-000000000001',
        version: '3',
        discoveryStatus: 'UNVERIFIED',
      }),
      operation: {
        id: 'op-mcp-1',
        environmentId: '00000000-0000-4000-8000-000000000001',
        resourceType: 'MCP_SERVER',
        resourceId: 'srv-local',
        operationType: 'MCP_SERVER_DISCOVER',
        status: 'PENDING',
        resourceVersion: '3',
        parameterSummary: { timeoutMillis: 60000 },
        deadlineAt: '2026-07-20T01:00:00.000Z',
        startedAt: null,
        finishedAt: null,
        resultSummary: null,
        failureCode: null,
        failureMessage: null,
        createdAt: '2026-07-20T00:00:00.000Z',
        updatedAt: '2026-07-20T00:00:00.000Z',
      },
    })

    renderPage()
    const editBtn = await screen.findByRole('button', { name: /编辑 MCP 服务/ })
    await user.click(editBtn)

    const dialog = await screen.findByRole('dialog', { name: /编辑 MCP 服务/ })
    const discoverBtn = await within(dialog).findByRole('button', { name: '发现工具' })
    await user.click(discoverBtn)

    expect(mcpServerService.discoverServer).toHaveBeenCalledWith('srv-local', '3')
    await waitFor(() => {
      expect(screen.queryByRole('dialog', { name: /编辑 MCP 服务/ })).toBeNull()
    })
  })

  /**
   * 测试意图：验证 MCP Server update 发生 409 冲突时通过 ConflictPresenter 展示，点击刷新后重置并刷新列表。
   */
  it('handles 409 conflict on server update and resets stale edit modal on explicit refresh', async () => {
    const user = userEvent.setup()
    vi.mocked(mcpServerService.pageServers).mockResolvedValue({
      pageNumber: 1,
      pageSize: 100,
      totalCount: 1,
      results: [server({ id: 'srv-1', name: 'filesystem', version: '1' })],
    })
    vi.mocked(mcpServerService.getServerConfig).mockResolvedValue({
      id: 'srv-1',
      name: 'filesystem',
      version: '1',
      configJson: '{"type":"remote","url":"https://example.com/mcp"}',
    })
    vi.mocked(mcpServerService.updateServer).mockRejectedValue(
      new ApiError('冲突', 409, 'CONFLICT', {
        reason: 'version_conflict',
        detail: 'MCP Server version conflict',
      }),
    )
    renderPage()

    const editBtn = await screen.findByRole('button', { name: /编辑 MCP 服务/ })
    await user.click(editBtn)

    const dialog = await screen.findByRole('dialog', { name: /编辑 MCP 服务/ })
    await within(dialog).findByRole('textbox', { name: /配置 JSON/ })
    await user.click(within(dialog).getByRole('button', { name: '确认' }))

    const conflictModal = await screen.findByRole('alertdialog', { name: '持久状态已变化' })
    expect(within(conflictModal).getByText(/version_conflict/)).toBeInTheDocument()
    expect(within(conflictModal).getByText('MCP Server version conflict')).toBeInTheDocument()

    const listCallsBefore = vi.mocked(mcpServerService.pageServers).mock.calls.length
    await user.click(within(conflictModal).getByRole('button', { name: '刷新' }))

    await waitFor(() => {
      expect(screen.queryByRole('alertdialog', { name: '持久状态已变化' })).toBeNull()
      expect(screen.queryByRole('dialog', { name: /编辑 MCP 服务/ })).toBeNull()
    })
    expect(vi.mocked(mcpServerService.pageServers).mock.calls.length).toBeGreaterThan(listCallsBefore)
  })

  /**
   * 测试意图：验证 MCP Server 发现失败时在编辑弹窗内可见展示，并在 409 冲突时呈现 ConflictPresenter。
   */
  it('shows visible error in edit modal on discover failure and handles 409 conflict discover', async () => {
    const user = userEvent.setup()
    vi.mocked(mcpServerService.pageServers).mockResolvedValue({
      pageNumber: 1,
      pageSize: 100,
      totalCount: 1,
      results: [server({ id: 'srv-1', name: 'filesystem', version: '1' })],
    })
    vi.mocked(mcpServerService.getServerConfig).mockResolvedValue({
      id: 'srv-1',
      name: 'filesystem',
      version: '1',
      configJson: '{"type":"remote","url":"https://example.com/mcp"}',
    })
    vi.mocked(mcpServerService.discoverServer).mockRejectedValueOnce(
      new Error('discover tools timeout'),
    )
    renderPage()

    const editBtn = await screen.findByRole('button', { name: /编辑 MCP 服务/ })
    await user.click(editBtn)

    const editDialog = await screen.findByRole('dialog', { name: /编辑 MCP 服务/ })
    const discoverBtn = await within(editDialog).findByRole('button', { name: '发现工具' })
    await user.click(discoverBtn)

    // 非 409 失败在编辑弹窗内可见展示
    const alertMsg = await within(editDialog).findByRole('alert')
    expect(alertMsg).toHaveTextContent('discover tools timeout')

    // 409 冲突展示 ConflictPresenter 并关闭编辑弹窗
    vi.mocked(mcpServerService.discoverServer).mockRejectedValueOnce(
      new ApiError('冲突', 409, 'CONFLICT', {
        reason: 'stale_version',
        detail: 'Server modified during discovery',
      }),
    )
    await user.click(discoverBtn)

    const conflictModal = await screen.findByRole('alertdialog', { name: '持久状态已变化' })
    expect(within(conflictModal).getByText(/stale_version/)).toBeInTheDocument()
    expect(screen.queryByRole('dialog', { name: /编辑 MCP 服务/ })).toBeNull()

    await user.click(within(conflictModal).getByRole('button', { name: '刷新' }))
    await waitFor(() => {
      expect(screen.queryByRole('alertdialog', { name: '持久状态已变化' })).toBeNull()
    })
  })

  /**
   * 测试意图：验证删除 MCP Server 确认弹窗与 409 冲突处理。
   */
  it('deletes an MCP server with confirmation modal and handles 409 conflict', async () => {
    const user = userEvent.setup()
    vi.mocked(mcpServerService.pageServers).mockResolvedValue({
      pageNumber: 1,
      pageSize: 100,
      totalCount: 1,
      results: [server({ id: 'srv-1', name: 'filesystem', version: '1' })],
    })
    vi.mocked(mcpServerService.deleteServer).mockResolvedValueOnce(undefined)
    renderPage()

    const deleteBtn = await screen.findByRole('button', { name: /删除 MCP 服务/ })
    await user.click(deleteBtn)

    const modal = await screen.findByRole('alertdialog', { name: '删除 MCP 服务' })
    const confirmBtn = within(modal).getByRole('button', { name: '删除 MCP 服务' })
    await user.click(confirmBtn)

    expect(mcpServerService.deleteServer).toHaveBeenCalledWith('srv-1', '1')

    // 验证 409 冲突展示 ConflictPresenter
    vi.mocked(mcpServerService.deleteServer).mockRejectedValueOnce(
      new ApiError('冲突', 409, 'CONFLICT', {
        reason: 'version_conflict',
        detail: 'Already deleted',
      }),
    )
    await user.click(deleteBtn)
    const modal2 = await screen.findByRole('alertdialog', { name: '删除 MCP 服务' })
    await user.click(within(modal2).getByRole('button', { name: '删除 MCP 服务' }))

    const conflictModal = await screen.findByRole('alertdialog', { name: '持久状态已变化' })
    expect(within(conflictModal).getByText(/version_conflict/)).toBeInTheDocument()
    expect(screen.queryByRole('alertdialog', { name: '删除 MCP 服务' })).toBeNull()

    await user.click(within(conflictModal).getByRole('button', { name: '刷新' }))
    await waitFor(() => {
      expect(screen.queryByRole('alertdialog', { name: '持久状态已变化' })).toBeNull()
    })
  })

  /**
   * 测试意图：验证在 update 或 discover 异步请求进行中时，所有 JSON 编辑修改控件（文本域、模板按钮、环境下拉、格式化、校验）
   * 均处于 disabled 禁用状态，防止用户在请求期间输入的修改被请求完成静默丢弃。
   */
  it('disables all JSON editor mutating controls while update or discover is pending', async () => {
    const user = userEvent.setup()
    vi.mocked(mcpServerService.pageServers).mockResolvedValue({
      pageNumber: 1,
      pageSize: 100,
      totalCount: 1,
      results: [
        server({
          id: 'srv-1',
          name: 'filesystem',
          type: 'local',
          environmentId: mockEnvironment.id,
          version: '1',
        }),
      ],
    })
    vi.mocked(mcpServerService.getServerConfig).mockResolvedValue({
      id: 'srv-1',
      name: 'filesystem',
      version: '1',
      configJson: createLocalConfigTemplate(mockEnvironment.id),
    })

    let resolveUpdate!: () => void
    vi.mocked(mcpServerService.updateServer).mockReturnValue(
      new Promise((resolve) => {
        resolveUpdate = resolve
      }),
    )

    renderPage()
    const editBtn = await screen.findByRole('button', { name: /编辑 MCP 服务/ })
    await user.click(editBtn)

    const dialog = await screen.findByRole('dialog', { name: /编辑 MCP 服务/ })
    const textarea = await within(dialog).findByRole('textbox', { name: /配置 JSON/ })
    const remoteTplBtn = within(dialog).getByRole('button', { name: 'Remote 模板' })
    const localTplBtn = within(dialog).getByRole('button', { name: 'Local 模板' })
    const formatBtn = within(dialog).getByRole('button', { name: '格式化' })
    const validateBtn = within(dialog).getByRole('button', { name: '校验' })
    const envSelect = within(dialog).getByRole('combobox', { name: /关联环境/ })

    // 初始状态下控件均可用
    expect(textarea).not.toBeDisabled()
    expect(remoteTplBtn).not.toBeDisabled()
    expect(localTplBtn).not.toBeDisabled()
    expect(formatBtn).not.toBeDisabled()
    expect(validateBtn).not.toBeDisabled()
    expect(envSelect).not.toBeDisabled()

    // 触发保存提交
    const submitBtn = within(dialog).getByRole('button', { name: '确认' })
    await user.click(submitBtn)

    // 验证请求挂起时所有修改控件全部被禁用
    expect(textarea).toBeDisabled()
    expect(remoteTplBtn).toBeDisabled()
    expect(localTplBtn).toBeDisabled()
    expect(formatBtn).toBeDisabled()
    expect(validateBtn).toBeDisabled()
    expect(envSelect).toBeDisabled()
    expect(submitBtn).toBeDisabled()

    // backdrop 也不能绕过 pending 状态关闭弹窗，否则请求完成后的结果与错误将失去归属
    const backdrop = dialog.parentElement
    expect(backdrop).toHaveClass('modal-backdrop')
    fireEvent.mouseDown(backdrop!)
    expect(screen.getByRole('dialog', { name: /编辑 MCP 服务/ })).toBeInTheDocument()

    // 结束 update
    resolveUpdate()
    await waitFor(() => {
      expect(screen.queryByRole('dialog', { name: /编辑 MCP 服务/ })).toBeNull()
    })
  })

  /**
   * 测试意图：验证 MCP 发现请求挂起时 backdrop 不能绕过禁用按钮关闭编辑弹窗。
   */
  it('keeps the edit modal open when its backdrop is clicked during pending discovery', async () => {
    const user = userEvent.setup()
    vi.mocked(mcpServerService.pageServers).mockResolvedValue({
      pageNumber: 1,
      pageSize: 100,
      totalCount: 1,
      results: [server({ id: 'srv-1', name: 'filesystem', version: '1' })],
    })
    vi.mocked(mcpServerService.getServerConfig).mockResolvedValue({
      id: 'srv-1',
      name: 'filesystem',
      version: '1',
      configJson: '{"type":"remote","url":"https://example.com/mcp"}',
    })

    let resolveDiscovery!: () => void
    vi.mocked(mcpServerService.discoverServer).mockReturnValue(
      new Promise((resolve) => {
        resolveDiscovery = () =>
          resolve({
            server: server({ id: 'srv-1', name: 'filesystem', version: '1' }),
            operation: null,
          })
      }),
    )

    renderPage()
    await user.click(await screen.findByRole('button', { name: /编辑 MCP 服务/ }))

    const dialog = await screen.findByRole('dialog', { name: /编辑 MCP 服务/ })
    await user.click(await within(dialog).findByRole('button', { name: '发现工具' }))

    const backdrop = dialog.parentElement
    expect(backdrop).toHaveClass('modal-backdrop')
    fireEvent.mouseDown(backdrop!)
    expect(screen.getByRole('dialog', { name: /编辑 MCP 服务/ })).toBeInTheDocument()

    resolveDiscovery()
    await waitFor(() => {
      expect(screen.queryByRole('dialog', { name: /编辑 MCP 服务/ })).toBeNull()
    })
  })

  /**
   * 测试意图：验证读取 MCP 配置失败时呈现真实的本地化「重试」按钮，点击后能再次发起直读请求。
   */
  it('renders truthful localized retry button on getServerConfig failure and retries on click', async () => {
    const user = userEvent.setup()
    vi.mocked(mcpServerService.pageServers).mockResolvedValue({
      pageNumber: 1,
      pageSize: 100,
      totalCount: 1,
      results: [server({ id: 'srv-1', name: 'filesystem' })],
    })

    vi.mocked(mcpServerService.getServerConfig)
      .mockRejectedValueOnce(new Error('Network error loading config'))
      .mockResolvedValueOnce({
        id: 'srv-1',
        name: 'filesystem',
        version: '1',
        configJson: '{"type":"remote","url":"https://example.com/retried"}',
      })

    renderPage()
    const editBtn = await screen.findByRole('button', { name: /编辑 MCP 服务/ })
    await user.click(editBtn)

    const dialog = await screen.findByRole('dialog', { name: /编辑 MCP 服务/ })
    expect(await within(dialog).findByText('Network error loading config')).toBeInTheDocument()

    // 验证按钮显示真实本地化重试文案「重试」而非模棱两可的「确认」
    const retryBtn = within(dialog).getByRole('button', { name: '重试' })
    expect(retryBtn).toBeInTheDocument()

    await user.click(retryBtn)
    expect(mcpServerService.getServerConfig).toHaveBeenCalledTimes(2)

    const textarea = await within(dialog).findByRole('textbox', { name: /配置 JSON/ })
    expect((textarea as HTMLTextAreaElement).value).toContain('https://example.com/retried')
  })

  /**
   * 测试意图：验证 MCP 列表为空时，网格首项始终为 CreateCard，子工具栏无重复创建按钮，且不展示多余的通用空态文本块。
   */
  it('places CreateCard as the first grid item with exactly one creation entry without duplicate empty state block', async () => {
    vi.mocked(mcpServerService.pageServers).mockResolvedValue({
      pageNumber: 1,
      pageSize: 100,
      totalCount: 0,
      results: [],
    })
    renderPage()

    // 验证全页面仅有唯一个创建入口（即网格首张 CreateCard，子工具栏无重复创建按钮）
    const createButton = await screen.findByRole('button', { name: '创建 MCP 服务' })
    expect(createButton).toHaveClass('create-card')
    expect(screen.getAllByRole('button', { name: '创建 MCP 服务' })).toHaveLength(1)

    // 验证列表真正为空时不渲染多余的 StateBlock 文本
    expect(screen.queryByText('暂无配置的 MCP 服务')).not.toBeInTheDocument()
  })
})
