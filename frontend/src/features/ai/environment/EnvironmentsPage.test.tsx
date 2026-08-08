import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { EnvironmentsPage } from '@/features/ai/environment'
import { environmentService } from '@/shared/api/environment-service'

vi.mock('@/shared/api/environment-service', () => ({
  environmentService: { listEnvironments: vi.fn() },
}))

vi.mock('@/platform/workbench/WorkbenchSlots', () => ({
  NavigationSlot: () => null,
}))

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
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(
      <QueryClientProvider client={queryClient}>
        <EnvironmentsPage />
      </QueryClientProvider>,
    )
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
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(
      <QueryClientProvider client={queryClient}>
        <EnvironmentsPage />
      </QueryClientProvider>,
    )
    await waitFor(() => expect(screen.getByText('当前没有 live Environment')).toBeInTheDocument())
  })
})
