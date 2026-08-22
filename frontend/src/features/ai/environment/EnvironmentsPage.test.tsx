import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { EnvironmentsPage } from '@/features/ai/environment'
import { environmentService } from '@/shared/api/environment-service'
import type { LiveEnvironmentDTO } from '@/shared/api/contracts/ai-environment'

vi.mock('@/shared/api/environment-service', () => ({
  environmentService: { listEnvironments: vi.fn() },
}))

vi.mock('@/platform/workbench/WorkbenchSlots', () => ({
  NavigationSlot: () => null,
}))

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const view = render(
    <QueryClientProvider client={queryClient}>
      <EnvironmentsPage />
    </QueryClientProvider>,
  )
  return { queryClient, view }
}

function environment(overrides: Partial<LiveEnvironmentDTO>): LiveEnvironmentDTO {
  return {
    name: 'env',
    status: 'READY',
    ready: true,
    lastSeen: null,
    tools: [],
    skills: [],
    mcpServers: [],
    ...overrides,
  }
}

describe('EnvironmentsPage', () => {
  it('renders live registry entries with status tools skills and mcp summaries', async () => {
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([
      {
        name: 'local-dev',
        status: 'READY',
        ready: true,
        lastSeen: '2026-07-20T01:02:03.000Z',
        tools: [{ name: 'bash', version: '1', description: 'shell' }],
        skills: [{ name: 'dev', description: 'dev skill' }],
        mcpServers: [
          {
            name: 'fs',
            status: 'READY',
            error: null,
            tools: [{ name: 'read_file', description: 'Read a file' }],
          },
          {
            name: 'broken',
            status: 'FAILED',
            error: 'cannot connect',
            tools: [],
          },
        ],
      },
      {
        name: 'stale-box',
        status: 'READY',
        ready: false,
        lastSeen: '2026-07-19T00:00:00.000Z',
        tools: [],
        skills: [],
        mcpServers: [],
      },
      {
        name: 'connecting-box',
        status: 'CONNECTING',
        ready: false,
        lastSeen: null,
        tools: [],
        skills: [],
        mcpServers: [],
      },
    ])
    renderPage()
    expect(await screen.findByText('local-dev')).toBeInTheDocument()
    // 环境状态 pill 与 READY MCP server 状态 pill 各一枚；stale-box 必须显示 UNAVAILABLE 而非 READY。
    expect(screen.getAllByText('READY').length).toBe(2)
    expect(screen.getByText('UNAVAILABLE')).toBeInTheDocument()
    expect(screen.getByText('bash')).toBeInTheDocument()
    expect(screen.getByText('dev')).toBeInTheDocument()
    expect(screen.getByText('CONNECTING')).toBeInTheDocument()
    expect(screen.getAllByText('Tools').length).toBe(3)
    expect(screen.getAllByText('Skills').length).toBe(3)
    // MCP 摘要：server 名、状态、限长错误与工具名；FAILED 的 error 仅展示通用信息。
    expect(screen.getByText('fs')).toBeInTheDocument()
    expect(screen.getByText('read_file')).toBeInTheDocument()
    expect(screen.getByText('broken')).toBeInTheDocument()
    expect(screen.getByText('cannot connect')).toBeInTheDocument()
    expect(screen.getAllByText('MCP').length).toBe(2)
  })

  it('shows empty registry state', async () => {
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([])
    renderPage()
    await waitFor(() => expect(screen.getByText('当前没有 live Environment')).toBeInTheDocument())
  })

  it('shows the loading state while the registry request is pending', async () => {
    let resolveListing: ((value: LiveEnvironmentDTO[]) => void) | undefined
    vi.mocked(environmentService.listEnvironments).mockImplementation(
      () => new Promise<LiveEnvironmentDTO[]>((resolve) => {
        resolveListing = resolve
      }),
    )
    renderPage()

    // 请求未返回前：只显示 loading 占位，不渲染卡片。
    expect(screen.getByText('正在加载 Environments')).toBeInTheDocument()
    expect(screen.queryByRole('article')).not.toBeInTheDocument()

    await act(async () => {
      resolveListing?.([environment({ name: 'box-a' })])
    })
    expect(await screen.findByText('box-a')).toBeInTheDocument()
    expect(screen.queryByText('正在加载 Environments')).not.toBeInTheDocument()
  })

  it('renders the query error message with the danger tone', async () => {
    vi.mocked(environmentService.listEnvironments).mockRejectedValue(new Error('registry down'))
    renderPage()

    // 加载失败：展示错误消息，不渲染卡片与空态。
    expect(await screen.findByText('registry down')).toBeInTheDocument()
    expect(screen.queryByRole('article')).not.toBeInTheDocument()
    expect(screen.queryByText('当前没有 live Environment')).not.toBeInTheDocument()
  })

  it('formats numeric epoch lastSeen as seconds and milliseconds and falls back to raw text', async () => {
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([
      environment({ name: 'seconds-box', lastSeen: 1750000000 }),
      environment({ name: 'millis-box', lastSeen: 1750000000000 }),
      environment({ name: 'raw-box', lastSeen: 'not-a-date' }),
    ])
    renderPage()

    // 秒与毫秒 epoch 都格式化为本地 24 小时时间；不可解析文本原样回退展示。
    expect(await screen.findByText('seconds-box')).toBeInTheDocument()
    expect(screen.getAllByText(/最近查看 · /).length).toBe(3)
    expect(screen.getAllByText(/最近查看 · 2025\/06\/15/)).toHaveLength(2)
    expect(screen.getByText('最近查看 · not-a-date')).toBeInTheDocument()
  })

  it('formats fractional-second epoch strings and drops whitespace-only lastSeen', async () => {
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([
      environment({ name: 'fraction-box', lastSeen: '1750000000.5' }),
      environment({ name: 'blank-box', lastSeen: '   ' }),
    ])
    renderPage()

    // 小数秒 epoch 字符串同样按秒解析；空白字符串不显示时间后缀。
    expect(await screen.findByText('fraction-box')).toBeInTheDocument()
    expect(screen.getByText(/最近查看 · 2025\/06\/15/)).toBeInTheDocument()
    expect(screen.getByText('最近查看')).toBeInTheDocument()
  })

  it('truncates tag rows to three chips and shows the +N remainder', async () => {
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([
      environment({
        name: 'fat-box',
        tools: [
          { name: 'a', version: null, description: null },
          { name: 'b', version: null, description: null },
          { name: 'c', version: null, description: null },
          { name: 'd', version: null, description: null },
          { name: 'e', version: null, description: null },
        ],
      }),
    ])
    renderPage()

    // 工具名超过 3 个：只渲染前 3 个 chip，剩余数量以 +N 汇总。
    const card = (await screen.findByText('fat-box')).closest('article')
    expect(card).not.toBeNull()
    const toolRow = within(card!).getByText('Tools').closest('.env-tag-row')
    expect(toolRow).not.toBeNull()
    expect(toolRow!.querySelectorAll('.meta-chip')).toHaveLength(4)
    expect(within(toolRow!).getByText('+2')).toBeInTheDocument()
    expect(toolRow!.querySelector('.meta-chips')).toHaveAttribute('title', 'a, b, c, d, e')
  })
})
