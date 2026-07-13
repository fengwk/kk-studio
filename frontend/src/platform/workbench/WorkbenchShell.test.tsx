import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { ExtensionHost } from '@/platform/extensions/ExtensionHost'
import { ExtensionHostProvider } from '@/platform/extensions/ExtensionHostContext'
import { WorkbenchShell } from '@/platform/workbench/WorkbenchShell'
import { NavigationSlot } from '@/platform/workbench/WorkbenchSlots'

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

  it('reactively renders registered contributions, restores fallbacks, and clears on host disposal', async () => {
    const host = new ExtensionHost()
    renderWorkbench(host, '/workspaces/w1/plugin')
    expect(screen.getByText('页面不可用')).toBeInTheDocument()

    act(() => {
      host.register(extension('base', 'Base', 10))
    })
    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'Base page' })).toBeInTheDocument()
      expect(screen.getByRole('link', { name: 'Base nav' })).toBeInTheDocument()
      expect(screen.getByText('Base widget')).toBeInTheDocument()
    })

    act(() => {
      host.register(extension('override', 'Override', 20))
    })
    expect(screen.getByRole('heading', { name: 'Override page' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Override nav' })).toBeInTheDocument()
    expect(screen.getByText('Override widget')).toBeInTheDocument()

    act(() => {
      host.unregister('override')
    })
    expect(screen.getByRole('heading', { name: 'Base page' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Base nav' })).toBeInTheDocument()
    expect(screen.getByText('Base widget')).toBeInTheDocument()

    act(() => {
      host.dispose()
    })
    expect(screen.getByText('页面不可用')).toBeInTheDocument()
    expect(screen.queryByText('Base widget')).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Base nav' })).not.toBeInTheDocument()
  })
})

function extension(id: string, label: string, priority: number) {
  return {
    id,
    pages: [{ id: 'plugin.page', path: 'plugin', priority, component: ({ workspaceId }: { workspaceId: string }) => <><NavigationSlot workspaceId={workspaceId} /><h1>{label} page</h1></> }],
    navigation: [{ id: 'plugin.nav', label: `${label} nav`, path: 'plugin', priority }],
    widgets: [{ id: 'plugin.widget', slot: 'header' as const, priority, component: () => <div>{label} widget</div> }],
  }
}

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
