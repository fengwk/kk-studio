import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import App from '@/app/App'
import { AppProviders } from '@/app/providers'
import { agentService } from '@/shared/api/agent-service'

vi.mock('@/shared/api/agent-service', () => ({
  agentService: {
    listProviders: vi.fn(),
    listModels: vi.fn(),
    listAgents: vi.fn(),
    listSessions: vi.fn(),
  },
}))

describe('App', () => {
  beforeEach(() => {
    window.history.replaceState({}, '', '/')
    vi.mocked(agentService.listProviders).mockResolvedValue({
      pageNumber: 1,
      pageSize: 50,
      totalCount: 0,
      results: [],
    })
    vi.mocked(agentService.listModels).mockResolvedValue({
      pageNumber: 1,
      pageSize: 50,
      totalCount: 0,
      results: [],
    })
    vi.mocked(agentService.listAgents).mockResolvedValue({
      pageNumber: 1,
      pageSize: 50,
      totalCount: 0,
      results: [],
    })
    vi.mocked(agentService.listSessions).mockResolvedValue({
      pageNumber: 1,
      pageSize: 50,
      totalCount: 0,
      results: [],
    })
  })

  it('redirects the root route to the AI session console', async () => {
    render(
      <AppProviders>
        <App />
      </AppProviders>,
    )

    expect(await screen.findByText('新建 Chat')).toBeInTheDocument()
  })
})
