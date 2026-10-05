import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation, useNavigate } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ChatWorkspacePage } from './ChatWorkspacePage'
import type { ChatWorkspacePane } from './ChatWorkspacePane'
import { agentService } from '@/shared/api/agent-service'
import { chatService } from '@/shared/api/chat-service'
import { environmentService } from '@/shared/api/environment-service'
import { setLocale } from '@/shared/i18n'

const CHAT_ID = 'chat-1'
const THREAD_ID = '11111111-2222-4333-8444-555555555555'

vi.mock('@/shared/api/agent-service', () => ({
  agentService: { listAgents: vi.fn() },
}))
vi.mock('@/shared/api/chat-service', () => ({
  chatService: { getChat: vi.fn() },
}))
vi.mock('@/shared/api/environment-service', () => ({
  environmentService: { listEnvironments: vi.fn() },
}))

// 手动确认消费，确定性保留尚未清除的 query，检验父页面的目标 pane 归属。
vi.mock('@/features/ai/chat/ChatWorkspacePane', () => ({
  ChatWorkspacePane: ({
    pane, focused, onFocus, initialTarget, onTargetConsumed,
  }: Parameters<typeof ChatWorkspacePane>[0]) => (
    <section
      data-testid={pane.id}
      data-focused={focused}
      data-thread={initialTarget?.kind === 'BOUND_THREAD' ? initialTarget.threadId : ''}
    >
      <button onClick={onFocus}>Focus {pane.id}</button>
      <button onClick={() => {
        if (initialTarget) {
          onTargetConsumed?.(initialTarget)
        }
      }}>Consume {pane.id}</button>
    </section>
  ),
}))

function NavigationControls() {
  const location = useLocation()
  const navigate = useNavigate()
  return (
    <>
      <output data-testid="query">{location.search}</output>
      <button onClick={() => navigate(`/chats/${CHAT_ID}?thread=${THREAD_ID}`)}>Navigate</button>
    </>
  )
}

function openWorkspace(search = '') {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[`/chats/${CHAT_ID}${search}`]}>
        <Routes>
          <Route path="/chats/:chatId" element={<><NavigationControls /><ChatWorkspacePage /></>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  localStorage.clear()
  setLocale('zh-CN')
  localStorage.setItem(`kk-studio.chat-pane.${CHAT_ID}`, JSON.stringify({
    layout: 'split-2', focusedPaneId: 'pane-1', panes: [{ id: 'pane-1' }, { id: 'pane-2' }],
  }))
  vi.mocked(chatService.getChat).mockResolvedValue({
    id: CHAT_ID, title: 'Workspace', agentName: 'assistant',
    environment: null, yoloEnabled: false, version: '1', createTime: null, updateTime: null,
  })
  vi.mocked(agentService.listAgents).mockResolvedValue({
    pageNumber: 1, pageSize: 50, totalCount: 0, results: [],
  })
  vi.mocked(environmentService.listEnvironments).mockResolvedValue([])
})

describe('Chat deep-link pane ownership', () => {
  it('does not transfer an unconsumed target when focus changes', async () => {
    const user = userEvent.setup()
    openWorkspace(`?thread=${THREAD_ID}`)
    await screen.findByRole('heading', { name: 'Workspace' })
    expect(screen.getByTestId('pane-1')).toHaveAttribute('data-thread', THREAD_ID)
    expect(screen.getByTestId('pane-2')).toHaveAttribute('data-thread', '')

    await user.click(screen.getByRole('button', { name: 'Focus pane-2' }))
    expect(screen.getByTestId('pane-2')).toHaveAttribute('data-focused', 'true')
    expect(screen.getByTestId('pane-1')).toHaveAttribute('data-thread', THREAD_ID)
    expect(screen.getByTestId('pane-2')).toHaveAttribute('data-thread', '')
    expect(screen.getByTestId('query')).toHaveTextContent(`?thread=${THREAD_ID}`)

    await user.click(screen.getByRole('button', { name: 'Consume pane-1' }))
    await waitFor(() => expect(screen.getByTestId('query')).toBeEmptyDOMElement())
    expect(screen.getByTestId('pane-2')).toHaveAttribute('data-focused', 'true')
  })

  it('assigns each new navigation to its focused pane even for the same thread', async () => {
    const user = userEvent.setup()
    openWorkspace()
    await screen.findByRole('heading', { name: 'Workspace' })
    await user.click(screen.getByRole('button', { name: 'Focus pane-2' }))
    await user.click(screen.getByRole('button', { name: 'Navigate' }))
    expect(screen.getByTestId('pane-2')).toHaveAttribute('data-thread', THREAD_ID)

    await user.click(screen.getByRole('button', { name: 'Focus pane-1' }))
    expect(screen.getByTestId('pane-2')).toHaveAttribute('data-thread', THREAD_ID)
    expect(screen.getByTestId('pane-1')).toHaveAttribute('data-thread', '')
    await user.click(screen.getByRole('button', { name: 'Consume pane-2' }))
    await waitFor(() => expect(screen.getByTestId('query')).toBeEmptyDOMElement())

    await user.click(screen.getByRole('button', { name: 'Navigate' }))
    expect(screen.getByTestId('pane-1')).toHaveAttribute('data-thread', THREAD_ID)
    expect(screen.getByTestId('pane-2')).toHaveAttribute('data-thread', '')
  })
})
