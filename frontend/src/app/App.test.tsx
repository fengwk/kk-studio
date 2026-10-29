import { render, screen, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { BrowserRouter, createMemoryRouter, RouterProvider } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import App from '@/app/App'
import { AppProviders } from '@/app/providers'
import { agentService } from '@/shared/api/agent-service'
import { chatService } from '@/shared/api/chat-service'
import { environmentService } from '@/shared/api/environment-service'
import { interactionService } from '@/shared/api/interaction-service'

// 保留真实事件管理器及 IDE 身份契约，仅替换 WebSocket，避免连接真实服务。
vi.mock('@/shared/app-events', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/shared/app-events')>()
  const { FakeWebSocketHarness } = await import('@/shared/app-events/__tests__/fake-websocket')
  const sockets = new FakeWebSocketHarness()
  return {
    ...actual,
    ApplicationEventProvider: ({ children }: { children: ReactNode }) => (
      <actual.ApplicationEventProvider url="ws://test/api/events/v2" socketFactory={sockets.factory}>
        {children}
      </actual.ApplicationEventProvider>
    ),
  }
})

vi.mock('@/shared/api/agent-service', () => ({
  agentService: { listProviders: vi.fn(), listModels: vi.fn(), listAgents: vi.fn() },
}))
vi.mock('@/shared/api/chat-service', () => ({ chatService: { listChats: vi.fn() } }))
vi.mock('@/shared/api/environment-service', () => ({
  environmentService: {
    listEnvironments: vi.fn(),
    getEnvironmentRuntime: vi.fn(),
    startEnvironmentUpdate: vi.fn(),
  },
}))
vi.mock('@/shared/api/interaction-service', () => ({
  interactionService: { listInteractions: vi.fn() },
}))

const page = <T,>(results: T[]) => ({ pageNumber: 1, pageSize: 50, totalCount: String(results.length), results })

describe('App routes', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    window.history.replaceState({}, '', '/')
    vi.mocked(agentService.listProviders).mockResolvedValue(page([]))
    vi.mocked(agentService.listModels).mockResolvedValue(page([]))
    vi.mocked(agentService.listAgents).mockResolvedValue(page([]))
    vi.mocked(chatService.listChats).mockResolvedValue([])
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([])
    vi.mocked(interactionService.listInteractions).mockResolvedValue({
      items: [],
      nextCursor: null,
      total: 0,
      freshnessAt: null,
    })
  })

  it('redirects root to Chat console', async () => {
    render(<BrowserRouter><AppProviders><App /></AppProviders></BrowserRouter>)
    await waitFor(() => expect(window.location.pathname).toBe('/chats'))
    // 路由重定向必须完成异步资源加载并渲染 Chat 控制台。
    expect(await screen.findByRole('button', { name: '新建 Chat' }, { timeout: 3000 })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'KK Studio' })).toHaveAttribute('href', '/chats')
  })

  it('renders lazy interactions page with workbench shell semantics', async () => {
    // 测试意图：验证 /interactions 路由通过 React.lazy 异步按需加载，
    // 在挂载完成后正确渲染 InteractionsPage 内容，并保持外层 WorkbenchShell 导航结构。
    const router = createMemoryRouter(
      [{ path: '*', element: <AppProviders><App /></AppProviders> }],
      { initialEntries: ['/interactions'] },
    )
    render(<RouterProvider router={router} />)

    expect(await screen.findByRole('heading', { name: '待处理事项', level: 1 }, { timeout: 3000 })).toBeInTheDocument()
    expect(await screen.findByRole('heading', { name: '暂无待处理事项' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'KK Studio' })).toHaveAttribute('href', '/interactions')
  })
})
