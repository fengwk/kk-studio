import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { EnvironmentsPage } from '@/features/ai/EnvironmentsPage'
import { environmentService } from '@/shared/api/environment-service'

vi.mock('@/shared/api/environment-service', () => ({
  environmentService: { listEnvironments: vi.fn() },
}))

describe('EnvironmentsPage', () => {
  it('renders live registry entries with status tools and skills', async () => {
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([
      {
        name: 'local-dev',
        status: 'READY',
        lastSeen: '2026-07-20T01:02:03.000Z',
        tools: [{ name: 'bash', version: '1', description: 'shell' }],
        skills: [{ name: 'dev', description: 'dev skill' }],
      },
      {
        name: 'offline-box',
        status: 'DISCONNECTED',
        lastSeen: null,
        tools: [],
        skills: [],
      },
    ])
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(
      <QueryClientProvider client={queryClient}>
        <EnvironmentsPage />
      </QueryClientProvider>,
    )
    expect(await screen.findByText('local-dev')).toBeInTheDocument()
    expect(screen.getByText('READY')).toBeInTheDocument()
    expect(screen.getByText('bash')).toBeInTheDocument()
    expect(screen.getByText('dev')).toBeInTheDocument()
    expect(screen.getByText('DISCONNECTED')).toBeInTheDocument()
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
