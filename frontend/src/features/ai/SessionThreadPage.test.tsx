import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AgentThreadPage, SessionThreadPage, ThreadDeepLinkPage } from '@/features/ai/AgentThreadPage'
import { agentService } from '@/shared/api/agent-service'
import { harnessService } from '@/shared/api/harness-service'

vi.mock('@/shared/api/agent-service', () => ({ agentService: { listAgents: vi.fn(), listModels: vi.fn(), listProviders: vi.fn() } }))
vi.mock('@/shared/api/harness-service', () => ({
  harnessService: {
    getSession: vi.fn(), getThread: vi.fn(), listSessionThreads: vi.fn(), listSessionEntries: vi.fn(),
    listThreadEntries: vi.fn(), listThreadInputs: vi.fn(), listThreadEvents: vi.fn(), createThreadEventStream: vi.fn(),
    submitThreadMessage: vi.fn(), listRootActivities: vi.fn(), listSessionTasks: vi.fn(), getThreadUsage: vi.fn(),
    listThreadToolInvocations: vi.fn(), setThreadYolo: vi.fn(), decideToolInvocation: vi.fn(),
    createSessionThread: vi.fn(), stopThread: vi.fn(), retryThread: vi.fn(),
  },
}))

describe('Session thread navigation', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(harnessService.getSession).mockResolvedValue(session())
    vi.mocked(harnessService.getThread).mockResolvedValue(thread('main'))
    vi.mocked(harnessService.listSessionThreads).mockResolvedValue([thread('main'), thread('secondary')])
    vi.mocked(harnessService.listSessionEntries).mockResolvedValue([])
    vi.mocked(harnessService.listThreadEntries).mockResolvedValue([])
    vi.mocked(harnessService.listThreadInputs).mockResolvedValue([])
    vi.mocked(harnessService.listThreadEvents).mockResolvedValue([])
    vi.mocked(harnessService.createThreadEventStream).mockReturnValue({ addEventListener() {}, close() {} } as EventSource)
    vi.mocked(harnessService.listRootActivities).mockResolvedValue([])
    vi.mocked(harnessService.listSessionTasks).mockResolvedValue([])
    vi.mocked(harnessService.getThreadUsage).mockResolvedValue(usage())
    vi.mocked(harnessService.listThreadToolInvocations).mockResolvedValue([])
    vi.mocked(agentService.listAgents).mockResolvedValue(page([]))
    vi.mocked(agentService.listModels).mockResolvedValue(page([]))
    vi.mocked(agentService.listProviders).mockResolvedValue(page([]))
  })

  it('resolves /sessions/:sessionId to its stable mainThreadId', async () => {
    renderPage('/sessions/s1', <SessionThreadPage />)
    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('/sessions/s1/threads/main'))
    expect(harnessService.getSession).toHaveBeenCalledWith('s1')
  })

  it('keeps an explicit secondary Thread URL instead of loading the Session default', async () => {
    vi.mocked(harnessService.getThread).mockResolvedValue(thread('secondary'))
    renderPage('/sessions/s1/threads/secondary', <AgentThreadPage />)
    await screen.findByRole('log', { name: '会话消息' })
    expect(harnessService.getThread).toHaveBeenCalledWith('secondary')
    expect(screen.getByTestId('location')).toHaveTextContent('/sessions/s1/threads/secondary')
  })

  it('resolves an explicit legacy Thread deep link to its owning Session route', async () => {
    vi.mocked(harnessService.getThread).mockResolvedValue(thread('secondary'))
    renderPage('/threads/secondary', <ThreadDeepLinkPage />)
    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('/sessions/s1/threads/secondary'))
    expect(harnessService.getThread).toHaveBeenCalledWith('secondary')
  })

  it('creates a Session-local branch and carries editable USER text into its new Thread route', async () => {
    const user = userEvent.setup()
    vi.mocked(harnessService.listSessionEntries).mockResolvedValue([
      { entryId: 'root', sessionId: 's1', parentEntryId: null, entryType: 'agent_snapshot', payloadJson: '{}', createTime: null },
      { entryId: 'user', sessionId: 's1', parentEntryId: 'root', entryType: 'message', payloadJson: '{"message":{"role":"USER","contents":[{"text":"edit me"}]}}', createTime: null },
    ])
    vi.mocked(harnessService.createSessionThread).mockResolvedValue(thread('fork'))
    renderPage('/sessions/s1/threads/main', <AgentThreadPage />)
    await user.click(await screen.findByText('edit me'))
    await waitFor(() => expect(harnessService.createSessionThread).toHaveBeenCalledWith('s1', { fromEntryId: 'root' }))
    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('/sessions/s1/threads/fork'))
  })
})

function renderPage(path: string, element: React.ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<QueryClientProvider client={client}><MemoryRouter initialEntries={[path]}><Routes><Route path="/threads/:threadId" element={element} /><Route path="/sessions/:sessionId" element={element} /><Route path="/sessions/:sessionId/threads/:threadId" element={element} /></Routes><Location /></MemoryRouter></QueryClientProvider>)
}

function Location() { return <output data-testid="location">{useLocation().pathname}</output> }
function page<T>(results: T[]) { return { pageNumber: 1, pageSize: 50, totalCount: results.length, results } }
function session() { return { sessionId: 's1', title: 'S', mainThreadId: 'main', rootSessionId: 's1', parentSessionId: null, parentInvocationId: null, depth: 0, createTime: null, updateTime: null } }
function thread(threadId: string) { return { threadId, sessionId: 's1', sessionTitle: 'S', headEntryId: null, status: 'IDLE' as const, inputSequence: '0', processing: false, createTime: null, updateTime: null } }
function usage() { return { scopeType: 'thread' as const, scopeId: 'main', recordCount: 0, inputTokens: 0, outputTokens: 0, cacheReadTokens: 0, cacheWriteTokens: 0, cacheWriteLongTokens: 0, reasoningTokens: 0, providerTotalTokens: 0, cacheEligibleRecordCount: 0, cacheHitRecordCount: 0, cacheHitRatio: 0, tokenReadRatio: 0, unamortizedCacheWriteTokens: 0, costs: [] } }
