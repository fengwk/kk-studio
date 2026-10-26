import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import { CatalogRuntimeContext, useOptionalCatalogRuntime } from '@/features/catalog/CatalogRuntimeContext'
import {
  ApplicationContributionsProvider,
  useApplicationContributions,
} from '@/platform/extensions/ApplicationContributionsContext'
import type { ApplicationContributions, ExtensionComponentProps } from '@/platform/extensions/types'
import type { PrimaryNavItem } from '@/platform/shell/types'
import { WorkbenchShell } from '@/platform/workbench/WorkbenchShell'
import { ApplicationEventProvider } from '@/shared/app-events'
import { FakeWebSocketHarness } from '@/shared/app-events/__tests__/fake-websocket'
import { makeApplicationContributions } from '@/test-support/application-contributions'

const FIXTURE_NAV_ITEMS: readonly PrimaryNavItem[] = [
  {
    id: 'test',
    groupId: 'test',
    to: '/test',
    labelKey: 'platform.nav.ai',
    ariaKey: 'platform.nav.aiAria',
    shortLabel: 'T',
    icon: () => null,
  },
]

describe('WorkbenchShell', () => {
  it('renders a page from the injected static contribution table', async () => {
    const contributions = makeApplicationContributions({
      pages: [{ id: 'plugin.page', path: 'plugin', component: () => <h1>Plugin</h1> }],
    })
    renderWorkbench(contributions, '/plugin')

    expect(await screen.findByRole('heading', { name: 'Plugin' })).toBeInTheDocument()
  })

  it('uses an unknown contribution fallback for unmatched paths', () => {
    const contributions = makeApplicationContributions({
      pages: [{ id: 'plugin.page', path: 'plugin', component: () => <div>plugin</div> }],
    })
    renderWorkbench(contributions, '/missing')

    expect(screen.getByText('页面不可用')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '返回 Chat' })).toHaveAttribute('href', '/chats')
  })

  it('renders the current static fixture after the provider value changes', () => {
    const base = makeApplicationContributions({
      pages: [{ id: 'plugin.page', path: 'plugin', component: () => <h1>Base page</h1> }],
    })
    const replacement = makeApplicationContributions({
      pages: [{ id: 'plugin.page', path: 'plugin', component: () => <h1>Replacement page</h1> }],
    })
    const { rerender } = renderWorkbench(base, '/plugin')
    expect(screen.getByRole('heading', { name: 'Base page' })).toBeInTheDocument()

    rerender(workbenchTree(replacement, '/plugin'))
    expect(screen.getByRole('heading', { name: 'Replacement page' })).toBeInTheDocument()

    rerender(workbenchTree(makeApplicationContributions(), '/plugin'))
    expect(screen.getByText('页面不可用')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Replacement page' })).not.toBeInTheDocument()
  })

  it('requires an application contributions provider', () => {
    // 显式失败可以防止 contribution hook 静默绑定到隐藏的全局状态。
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => undefined)
    try {
      expect(() => render(<MissingProviderProbe />)).toThrow('ApplicationContributionsProvider is required')
    } finally {
      consoleError.mockRestore()
    }
  })

  it('I5: registered page 在自身 runtime provider 内挂载 OverlayHost，页面级上下文对 dialog 可见', async () => {
    const contributions = makeApplicationContributions({
      // 与真实 AI dialog contribution 相同：不 mock OptionalRuntime，直接消费真实 context。
      dialogs: [{ id: 'page.dialog', component: PageScopedDialog }],
      overlays: [{ id: 'page.overlay', component: PageScopedDialog }],
      pages: [{
        id: 'test.page',
        path: 'test-page',
        component: PageScopedPage,
      }],
    })

    renderWorkbench(contributions, '/test-page')

    // 页面自身的 runtime provider 必须能被子树内的 OverlayHost 读到；若 OverlayHost
    // 被挂到 shell 根部（页面 Provider 之外），这里只会渲染出空内容。
    expect(await screen.findByRole('heading', { name: 'Test Registered Page' })).toBeInTheDocument()
    expect(screen.getAllByTestId('page-scoped-dialog')).toHaveLength(2)
    for (const node of screen.getAllByTestId('page-scoped-dialog')) {
      expect(node).toHaveTextContent('page-scoped-controller')
    }
    // 页面路径不再在根部重复挂载宿主。
    expect(screen.getAllByTestId('page-scoped-dialog')).toHaveLength(
      contributions.dialogs.length + contributions.overlays.length,
    )
  })

  it('I5: 显式 children 路由在 WorkbenchShell 根部挂载唯一 OverlayHost', () => {
    const contributions = makeApplicationContributions({
      dialogs: [{ id: 'global.dialog', component: () => <div data-testid="global-dialog">Global Dialog</div> }],
      overlays: [{ id: 'global.overlay', component: () => <div data-testid="global-overlay">Global Overlay</div> }],
    })

    render(workbenchTree(
      contributions,
      '/interactions',
      <div data-testid="explicit-children">Explicit Interactions Page</div>,
    ))

    expect(screen.getByTestId('explicit-children')).toBeInTheDocument()
    expect(screen.getByTestId('global-dialog')).toBeInTheDocument()
    expect(screen.getByTestId('global-overlay')).toBeInTheDocument()
    // 确保同一全局宿主只挂载一次，没有重复渲染
    expect(screen.getAllByTestId('global-dialog')).toHaveLength(1)
    expect(screen.getAllByTestId('global-overlay')).toHaveLength(1)
  })
})

function MissingProviderProbe() {
  useApplicationContributions()
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

function workbenchTree(
  contributions: ApplicationContributions,
  entry: string,
  children?: React.ReactNode,
) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  // Shell 依赖唯一应用事件连接读取全局 pending：测试注入替身 WebSocket，保持与生产同一 Provider 契约。
  const sockets = new FakeWebSocketHarness()
  return (
    <QueryClientProvider client={queryClient}>
      <ApplicationEventProvider url="ws://test/events/v1" socketFactory={sockets.factory}>
        <ApplicationContributionsProvider contributions={contributions}>
          <MemoryRouter initialEntries={[entry]}>
            <Routes>
              <Route path="/*" element={<WorkbenchShell navItems={FIXTURE_NAV_ITEMS}>{children}</WorkbenchShell>} />
            </Routes>
          </MemoryRouter>
        </ApplicationContributionsProvider>
      </ApplicationEventProvider>
    </QueryClientProvider>
  )
}

function renderWorkbench(contributions: ApplicationContributions, entry: string) {
  return render(workbenchTree(contributions, entry))
}
