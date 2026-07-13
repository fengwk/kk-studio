import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import App from '@/app/App'
import { AppProviders } from '@/app/providers'
import { agentService } from '@/shared/api/agent-service'

vi.mock('@/shared/api/agent-service', () => ({
  agentService: { listWorkspaces: vi.fn() },
}))

describe('App', () => {
  beforeEach(() => {
    window.history.replaceState({}, '', '/')
    vi.mocked(agentService.listWorkspaces).mockResolvedValue({
      pageNumber: 1, pageSize: 50, totalCount: 1,
      results: [{ id: 'workspace-1', name: 'Cloud Runtime', settingsJson: null, version: 1, createTime: null, updateTime: null }],
    })
  })

  it('redirects the root route to the workspace selector', async () => {
    render(<AppProviders><App /></AppProviders>)
    expect(await screen.findByRole('heading', { name: '工作区' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Cloud Runtime/ })).toHaveAttribute('href', '/workspaces/workspace-1/sessions')
  })
})
