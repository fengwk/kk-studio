import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import { ExtensionHost } from '@/platform/extensions/ExtensionHost'
import { ExtensionHostProvider, useExtensionHost } from '@/platform/extensions/ExtensionHostContext'
import { WorkbenchShell } from '@/platform/workbench/WorkbenchShell'
import { NavigationSlot, WorkbenchSlot } from '@/platform/workbench/WorkbenchSlots'

describe('WorkbenchShell', () => {
  it('renders a registered Studio page without a feature switch', async () => {
    const host = new ExtensionHost()
    host.register({ id: 'test', pages: [{ id: 'plugin.page', path: 'plugin', component: () => <h1>Plugin</h1> }] })
    renderWorkbench(host, '/plugin')

    expect(await screen.findByRole('heading', { name: 'Plugin' })).toBeInTheDocument()
  })

  it('uses an unknown contribution fallback for unmatched paths', () => {
    const host = new ExtensionHost()
    host.register({ id: 'test', pages: [{ id: 'plugin.page', path: 'plugin', component: () => <div>plugin</div> }] })
    renderWorkbench(host, '/missing')

    expect(screen.getByText('页面不可用')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '返回 Chat' })).toHaveAttribute('href', '/chats')
  })

  it('reactively renders registered contributions, restores fallbacks, and clears on host disposal', async () => {
    const host = new ExtensionHost()
    renderWorkbench(host, '/plugin')
    expect(screen.getByText('页面不可用')).toBeInTheDocument()

    act(() => {
      host.register(extension('base', 'Base', 10))
    })
    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'Base page' })).toBeInTheDocument()
      expect(screen.getByRole('link', { name: 'Base nav' })).toHaveAttribute('href', '/plugin')
      expect(screen.getByText('Base widget')).toBeInTheDocument()
      expect(screen.getByText('Base inspector')).toBeInTheDocument()
    })

    act(() => {
      host.register(extension('override', 'Override', 20))
    })
    expect(screen.getByRole('heading', { name: 'Override page' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Override nav' })).toBeInTheDocument()
    expect(screen.getByText('Override widget')).toBeInTheDocument()
    expect(screen.getByText('Override inspector')).toBeInTheDocument()

    act(() => {
      host.unregister('override')
    })
    expect(screen.getByRole('heading', { name: 'Base page' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Base nav' })).toBeInTheDocument()
    expect(screen.getByText('Base widget')).toBeInTheDocument()
    expect(screen.getByText('Base inspector')).toBeInTheDocument()

    act(() => {
      host.dispose()
    })
    expect(screen.getByText('页面不可用')).toBeInTheDocument()
    expect(screen.queryByText('Base widget')).not.toBeInTheDocument()
    expect(screen.queryByText('Base inspector')).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Base nav' })).not.toBeInTheDocument()
  })

  it('requires an extension host provider', () => {
    // The explicit failure keeps contribution hooks from silently binding to hidden global state.
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => undefined)
    try {
      expect(() => render(<MissingProviderProbe />)).toThrow('ExtensionHostProvider is required')
    } finally {
      consoleError.mockRestore()
    }
  })
})

function MissingProviderProbe() {
  useExtensionHost()
  return null
}

function extension(id: string, label: string, priority: number) {
  return {
    id,
    pages: [{ id: 'plugin.page', path: 'plugin', priority, component: () => <><NavigationSlot /><WorkbenchSlot slot="inspector" /><h1>{label} page</h1></> }],
    navigation: [{ id: 'plugin.nav', label: `${label} nav`, path: 'plugin', priority }],
    widgets: [{ id: 'plugin.widget', slot: 'header' as const, priority, component: () => <div>{label} widget</div> }],
    inspectors: [{ id: 'plugin.inspector', priority, component: () => <div>{label} inspector</div> }],
  }
}

function renderWorkbench(host: ExtensionHost, entry: string) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <ExtensionHostProvider host={host}>
        <MemoryRouter initialEntries={[entry]}>
          <Routes><Route path="/*" element={<WorkbenchShell />} /></Routes>
        </MemoryRouter>
      </ExtensionHostProvider>
    </QueryClientProvider>,
  )
}
