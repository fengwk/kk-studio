import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import { ExtensionHost } from '@/platform/extensions/ExtensionHost'
import { ExtensionHostProvider, useExtensionHostSnapshot } from '@/platform/extensions/ExtensionHostContext'
import { WorkbenchShell } from '@/platform/workbench/WorkbenchShell'

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
    })

    act(() => {
      host.register(extension('override', 'Override', 20))
    })
    expect(screen.getByRole('heading', { name: 'Override page' })).toBeInTheDocument()

    act(() => {
      host.unregister('override')
    })
    expect(screen.getByRole('heading', { name: 'Base page' })).toBeInTheDocument()

    act(() => {
      host.dispose()
    })
    expect(screen.getByText('页面不可用')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Base page' })).not.toBeInTheDocument()
  })

  it('requires an extension host provider', () => {
    // 显式失败可以防止 contribution hook 静默绑定到隐藏的全局状态。
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => undefined)
    try {
      expect(() => render(<MissingProviderProbe />)).toThrow('ExtensionHostProvider is required')
    } finally {
      consoleError.mockRestore()
    }
  })

  it('I5: mounts OverlayHost at WorkbenchShell root so registered pages and explicit children share the same overlay host', async () => {
    const host = new ExtensionHost()
    host.register({
      id: 'test-overlays',
      dialogs: [{ id: 'global.dialog', component: () => <div data-testid="global-dialog">Global Dialog</div> }],
      overlays: [{ id: 'global.overlay', component: () => <div data-testid="global-overlay">Global Overlay</div> }],
      pages: [{ id: 'test.page', path: 'test-page', component: () => <h1>Test Registered Page</h1> }],
    })

    // 1. 验证 Registered Page 路由下，根部的 OverlayHost 能够正常渲染全局 dialogs 与 overlays
    const { unmount } = renderWorkbench(host, '/test-page')
    expect(await screen.findByRole('heading', { name: 'Test Registered Page' })).toBeInTheDocument()
    expect(screen.getByTestId('global-dialog')).toBeInTheDocument()
    expect(screen.getByTestId('global-overlay')).toBeInTheDocument()
    unmount()

    // 2. 验证显式传入 children（如 /interactions 自定义页面）时，全局宿主 OverlayHost 同样挂载生效
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(
      <QueryClientProvider client={queryClient}>
        <ExtensionHostProvider host={host}>
          <MemoryRouter initialEntries={['/interactions']}>
            <WorkbenchShell>
              <div data-testid="explicit-children">Explicit Interactions Page</div>
            </WorkbenchShell>
          </MemoryRouter>
        </ExtensionHostProvider>
      </QueryClientProvider>,
    )

    expect(screen.getByTestId('explicit-children')).toBeInTheDocument()
    expect(screen.getByTestId('global-dialog')).toBeInTheDocument()
    expect(screen.getByTestId('global-overlay')).toBeInTheDocument()
    // 确保同一全局宿主只挂载一次，没有重复渲染
    expect(screen.getAllByTestId('global-dialog')).toHaveLength(1)
    expect(screen.getAllByTestId('global-overlay')).toHaveLength(1)
  })
})

function MissingProviderProbe() {
  useExtensionHostSnapshot()
  return null
}

function extension(id: string, label: string, priority: number) {
  return {
    id,
    pages: [
      {
        id: 'plugin.page',
        path: 'plugin',
        priority,
        component: () => <h1>{label} page</h1>,
      },
    ],
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
