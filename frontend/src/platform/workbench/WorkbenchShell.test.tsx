import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import { CatalogRuntimeContext, useOptionalCatalogRuntime } from '@/features/ai/catalog/CatalogRuntimeContext'
import { ExtensionHost } from '@/platform/extensions/ExtensionHost'
import { ExtensionHostProvider, useExtensionHostSnapshot } from '@/platform/extensions/ExtensionHostContext'
import type { ExtensionComponentProps } from '@/platform/extensions/types'
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

  it('I5: registered page 在自身 runtime provider 内挂载 OverlayHost，页面级上下文对 dialog 可见', async () => {
    const host = new ExtensionHost()
    host.register({
      id: 'test-page-scoped-overlay',
      // 与真实 AI dialog contribution 相同：不 mock OptionalRuntime，直接消费真实 context。
      dialogs: [{ id: 'page.dialog', component: PageScopedDialog }],
      overlays: [{ id: 'page.overlay', component: PageScopedDialog }],
      pages: [{
        id: 'test.page',
        path: 'test-page',
        component: PageScopedPage,
      }],
    })

    renderWorkbench(host, '/test-page')

    // 页面自身的 runtime provider 必须能被子树内的 OverlayHost 读到；若 OverlayHost
    // 被挂到 shell 根部（页面 Provider 之外），这里只会渲染出空内容。
    expect(await screen.findByRole('heading', { name: 'Test Registered Page' })).toBeInTheDocument()
    expect(screen.getAllByTestId('page-scoped-dialog')).toHaveLength(2)
    for (const node of screen.getAllByTestId('page-scoped-dialog')) {
      expect(node).toHaveTextContent('page-scoped-controller')
    }
    // 页面路径不再在根部重复挂载宿主。
    expect(screen.getAllByTestId('page-scoped-dialog')).toHaveLength(host.dialogs.list().length + host.overlays.list().length)
  })

  it('I5: 显式 children 路由在 WorkbenchShell 根部挂载唯一 OverlayHost', async () => {
    const host = new ExtensionHost()
    host.register({
      id: 'test-overlays',
      dialogs: [{ id: 'global.dialog', component: () => <div data-testid="global-dialog">Global Dialog</div> }],
      overlays: [{ id: 'global.overlay', component: () => <div data-testid="global-overlay">Global Overlay</div> }],
    })

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

/** 真实页面级 runtime provider 的桩值；测试只验证 context 是否可被子树内的 dialog 读到。 */
const PAGE_SCOPED_CONTROLLER = { resourceEditorModal: { modal: null } } as never

/** 模拟真实 registered page：在自己的 runtime provider 内部渲染 children。 */
function PageScopedPage({ children }: ExtensionComponentProps) {
  return (
    <CatalogRuntimeContext.Provider value={PAGE_SCOPED_CONTROLLER}>
      <h1>Test Registered Page</h1>
      {children}
    </CatalogRuntimeContext.Provider>
  )
}

/** 模拟真实 AI dialog contribution：消费真实 OptionalRuntime hook，读到控制器才渲染。 */
function PageScopedDialog() {
  const controller = useOptionalCatalogRuntime()
  if (!controller) {
    return null
  }
  return <div data-testid="page-scoped-dialog">page-scoped-controller</div>
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
