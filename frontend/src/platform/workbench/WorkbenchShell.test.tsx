import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { ExtensionHost } from '@/platform/extensions/ExtensionHost'
import { ExtensionHostProvider } from '@/platform/extensions/ExtensionHostContext'
import { WorkbenchShell } from '@/platform/workbench/WorkbenchShell'

vi.mock('@/shared/api/agent-service', () => ({
  agentService: {
    listWorkspaces: vi.fn().mockResolvedValue({ pageNumber: 1, pageSize: 50, totalCount: 1, results: [{ id: 'w1', name: 'One', settingsJson: null, version: 1, createTime: null, updateTime: null }] }),
  },
}))

describe('WorkbenchShell', () => {
  it('renders a registered workspace page without a feature switch', async () => {
    const host = new ExtensionHost()
    host.register({ id: 'test', pages: [{ id: 'plugin.page', path: 'plugin', component: ({ workspaceId }) => <h1>Plugin {workspaceId}</h1> }] })
    renderWorkbench(host, '/workspaces/w1/plugin')

    expect(await screen.findByRole('heading', { name: 'Plugin w1' })).toBeInTheDocument()
  })

  it('uses an unknown contribution fallback for unmatched workspace paths', () => {
    const host = new ExtensionHost()
    host.register({ id: 'test', pages: [{ id: 'plugin.page', path: 'plugin', component: () => <div>plugin</div> }] })
    renderWorkbench(host, '/workspaces/w1/missing')

    expect(screen.getByText('页面不可用')).toBeInTheDocument()
  })
})

function renderWorkbench(host: ExtensionHost, entry: string) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <ExtensionHostProvider host={host}>
        <MemoryRouter initialEntries={[entry]}>
          <Routes><Route path="/workspaces/:workspaceId/*" element={<WorkbenchShell />} /></Routes>
        </MemoryRouter>
      </ExtensionHostProvider>
    </QueryClientProvider>,
  )
}
