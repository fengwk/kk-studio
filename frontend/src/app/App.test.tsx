import { render, screen, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import App from '@/app/App'
import { AppProviders } from '@/app/providers'
import { agentService } from '@/shared/api/agent-service'
import { chatService } from '@/shared/api/chat-service'
import { environmentService } from '@/shared/api/environment-service'
import { interactionService } from '@/shared/api/interaction-service'

const { fakeApplicationEvents } = vi.hoisted(() => {
  const manager = { subscribe: () => () => undefined }
  return { fakeApplicationEvents: { useApplicationEvents: () => manager } }
})
// jsdom 没有 WebSocket：应用事件 Provider 在 App 级测试中退化为透传。
vi.mock('@/shared/app-events', () => ({
  ApplicationEventProvider: ({ children }: { children: ReactNode }) => <>{children}</>,
  useApplicationEvents: fakeApplicationEvents.useApplicationEvents,
}))

vi.mock('@/shared/api/agent-service', () => ({
  agentService: { listProviders: vi.fn(), listModels: vi.fn(), listAgents: vi.fn() },
}))
vi.mock('@/shared/api/chat-service', () => ({ chatService: { listChats: vi.fn() } }))
vi.mock('@/shared/api/environment-service', () => ({
  environmentService: {
    listEnvironments: vi.fn(),
    getEnvironmentUpdate: vi.fn(),
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
    const router = createMemoryRouter(
      [{ path: '*', element: <AppProviders><App /></AppProviders> }],
      { initialEntries: ['/'] },
    )
    render(<RouterProvider router={router} />)
    await waitFor(() => expect(router.state.location.pathname).toBe('/chats'))
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
