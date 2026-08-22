import { render, screen, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import App from '@/app/App'
import { AppProviders } from '@/app/providers'
import { agentService } from '@/shared/api/agent-service'
import { chatService } from '@/shared/api/chat-service'
import { comfyuiService } from '@/shared/api/comfyui-service'
import { environmentService } from '@/shared/api/environment-service'

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
vi.mock('@/shared/api/environment-service', () => ({ environmentService: { listEnvironments: vi.fn() } }))
vi.mock('@/shared/api/comfyui-service', () => ({ comfyuiService: { listWorkflows: vi.fn() } }))

const page = <T,>(results: T[]) => ({ pageNumber: 1, pageSize: 50, totalCount: results.length, results })

describe('App routes', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    window.history.replaceState({}, '', '/')
    vi.mocked(agentService.listProviders).mockResolvedValue(page([]))
    vi.mocked(agentService.listModels).mockResolvedValue(page([]))
    vi.mocked(agentService.listAgents).mockResolvedValue(page([]))
    vi.mocked(chatService.listChats).mockResolvedValue([])
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([])
    vi.mocked(comfyuiService.listWorkflows).mockResolvedValue(page([]))
  })

  it('redirects root to Chat console', async () => {
    render(<AppProviders><App /></AppProviders>)
    await waitFor(() => expect(window.location.pathname).toBe('/chats'))
    // 路由重定向必须完成异步资源加载并渲染 Chat 控制台。
    expect(await screen.findByRole('button', { name: '新建 Chat' }, { timeout: 3000 })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'KK Studio' })).toHaveAttribute('href', '/chats')
  })
})
