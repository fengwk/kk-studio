import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AccountContext } from '@/features/account/account-context'
import { DraftPaneRenderer } from '@/features/thread/workspace'
import type { PaneTarget } from '@/features/workspace/pane-target'
import { ApiError, request } from '@/shared/api/client'
import type {
  AgentSummaryDTO,
  EnvironmentSummaryDTO,
  ModelSummaryDTO,
  StudioAccountDTO,
} from '@/shared/api/generated/contracts'
import { ApplicationEventProvider } from '@/shared/app-events'
import { FakeWebSocketHarness } from '@/test-support/fake-websocket'

vi.mock('@/shared/api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/shared/api/client')>()
  return { ...actual, request: vi.fn() }
})

const mockedRequest = vi.mocked(request)
const sendStorageKey = 'kk-studio.session-send:workspace-draft:["u1","space-1","draft-1"]'

const READY_AGENTS: AgentSummaryDTO[] = [
  { name: 'default-agent', description: null, modelName: 'gpt-test', variant: 'high', driver: 'builtin' },
]

const READY_MODELS: ModelSummaryDTO[] = [
  { name: 'gpt-test', type: 'MODEL', variantIds: ['high', 'low'], defaultVariant: 'high' },
  { name: 'team-group', type: 'GROUP', variantIds: ['fast'], defaultVariant: 'fast' },
]

const READY_ENVS: EnvironmentSummaryDTO[] = [
  { id: 'env-1', name: 'local-env', status: 'READY', ready: true, capabilities: [] },
]

function renderDraftPane({
  user = { id: 'u1', username: 'alice', role: 'USER', profile: {} },
  defaultAgentName = 'default-agent' as string | null,
  defaultEnvironmentId = 'env-1' as string | null,
  replace = vi.fn<(next: PaneTarget) => void>(),
  onUnsentChange = vi.fn<(unsent: boolean) => void>(),
  openSpaceSettings = vi.fn<() => void>(),
}: {
  user?: StudioAccountDTO | null
  defaultAgentName?: string | null
  defaultEnvironmentId?: string | null
  replace?: (next: PaneTarget) => void
  onUnsentChange?: (unsent: boolean) => void
  openSpaceSettings?: () => void
} = {}) {
  const sockets = new FakeWebSocketHarness()
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } })
  const utils = render(
    <QueryClientProvider client={queryClient}>
      <AccountContext.Provider value={{ user, refresh: async () => {}, signOut: async () => {} }}>
        <ApplicationEventProvider url="ws://test/ws/events" socketFactory={sockets.factory}>
          <MemoryRouter>
            <DraftPaneRenderer
              space={{
                id: 'space-1',
                title: 'Workspace',
                version: '1',
                defaultAgentName,
                defaultEnvironmentId,
                defaultYoloEnabled: false,
              }}
              target={{ kind: 'draft', draftId: 'draft-1', folderId: 'folder-1' }}
              active
              replace={replace}
              open={vi.fn()}
              openBeside={vi.fn()}
              onUnsentChange={onUnsentChange}
              openSpaceSettings={openSpaceSettings}
            />
          </MemoryRouter>
        </ApplicationEventProvider>
      </AccountContext.Provider>
    </QueryClientProvider>,
  )
  return { ...utils, queryClient, replace, onUnsentChange, openSpaceSettings }
}

describe('DraftPaneRenderer', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  afterEach(() => {
    cleanup()
    vi.restoreAllMocks()
  })

  it('submits a new session, clears draft, and replaces the pane with the created thread', async () => {
    const user = userEvent.setup()
    mockedRequest.mockImplementation((async (endpoint: string, params: { body?: unknown }) => {
      if (endpoint === 'GET /api/platform/agent-summaries') return READY_AGENTS
      if (endpoint === 'GET /api/platform/model-summaries') return READY_MODELS
      if (endpoint === 'GET /api/platform/environment-summaries') return READY_ENVS
      if (endpoint === 'POST /api/chat-spaces/{spaceId}/sessions') {
        // The complete wire body and key must already be durable when the request starts.
        expect(sessionStorage.getItem(sendStorageKey)).toBe(JSON.stringify({ key: 'draft-1', body: params.body, rejected: false }))
        return { sessionId: 'sess-1', threadId: 'thread-new', commandId: 'cmd-1', treeVersion: '1' }
      }
      if (endpoint === 'GET /api/harness/threads/{threadId}/snapshot') {
        return { version: '1', thread: { threadId: 'thread-new' }, entries: [] }
      }
      throw new Error(`Unexpected endpoint: ${endpoint}`)
    }) as unknown as typeof request)

    const { replace, onUnsentChange } = renderDraftPane()
    const editor = await screen.findByRole('textbox')
    await waitFor(() => expect(screen.getByRole('button', { name: '环境' })).toHaveTextContent('local-env'))

    await user.type(editor, 'Hello workspace')
    await waitFor(() => expect(onUnsentChange).toHaveBeenCalledWith(true))
    await user.click(screen.getByRole('button', { name: '发送消息' }))

    await waitFor(() => expect(replace).toHaveBeenCalledWith({ kind: 'thread', threadId: 'thread-new' }))
    expect(onUnsentChange).toHaveBeenCalledWith(false)
    expect(sessionStorage.getItem(sendStorageKey)).toBeNull()
    expect(mockedRequest).toHaveBeenCalledWith(
      'POST /api/chat-spaces/{spaceId}/sessions',
      expect.objectContaining({
        path: { spaceId: 'space-1' },
        body: expect.objectContaining({
          folderId: 'folder-1',
          modelSelection: { modelName: 'gpt-test', variant: 'high' },
          yoloEnabled: false,
        }),
      }),
      { idempotencyKey: 'draft-1' },
    )
  })

  it('restores the editable draft independently of the first-send record', async () => {
    mockedRequest.mockResolvedValue([])
    localStorage.setItem('kk-studio.composer-draft:workspace-draft:draft-1', JSON.stringify({
      parts: [{ type: 'text', text: 'Previous unsent draft' }],
    }))

    const { onUnsentChange } = renderDraftPane()
    expect(await screen.findByRole('textbox')).toHaveTextContent('Previous unsent draft')
    expect(onUnsentChange).toHaveBeenCalledWith(true)
  })

  it('does not send when saving the replay record fails', async () => {
    const user = userEvent.setup()
    mockedRequest.mockImplementation(async (endpoint: string) => {
      if (endpoint === 'GET /api/platform/agent-summaries') return READY_AGENTS
      if (endpoint === 'GET /api/platform/model-summaries') return READY_MODELS
      if (endpoint === 'GET /api/platform/environment-summaries') return READY_ENVS
      throw new Error(`Unexpected endpoint: ${endpoint}`)
    })
    const setItem = Storage.prototype.setItem
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(function (this: Storage, key, value) {
      if (this === sessionStorage && key === sendStorageKey) throw new DOMException('Storage full', 'QuotaExceededError')
      setItem.call(this, key, value)
    })

    renderDraftPane()
    const editor = await screen.findByRole('textbox')
    await user.type(editor, 'Keep this draft')
    await user.click(screen.getByRole('button', { name: '发送消息' }))

    // A quota error leaves the composer intact and prevents any session POST.
    expect(await screen.findByText('无法保存发送记录，未发送消息；请重试。')).toBeInTheDocument()
    expect(editor).toHaveTextContent('Keep this draft')
    expect(mockedRequest.mock.calls.map(([endpoint]) => endpoint)).not.toContain('POST /api/chat-spaces/{spaceId}/sessions')
  })

  it.each([
    new ApiError('network lost', 0, 'network_error'),
    new ApiError('timed out', undefined, 'timeout'),
    new ApiError('unavailable', 503),
  ])('shows retry after $message and unlocks on INVALID_FOLDER', async (failure) => {
    const user = userEvent.setup()
    let sessionCall = 0
    mockedRequest.mockImplementation((async (endpoint: string) => {
      if (endpoint === 'GET /api/platform/agent-summaries') return READY_AGENTS
      if (endpoint === 'GET /api/platform/model-summaries') return READY_MODELS
      if (endpoint === 'GET /api/platform/environment-summaries') return READY_ENVS
      if (endpoint === 'POST /api/chat-spaces/{spaceId}/sessions') {
        sessionCall += 1
        if (sessionCall === 1) throw failure
        if (sessionCall === 2) throw new ApiError('invalid folder', 400, 'validation', undefined, 'INVALID_FOLDER')
        return { sessionId: 'sess-2', threadId: 'thread-recovered', commandId: 'cmd-2', treeVersion: '2' }
      }
      if (endpoint === 'GET /api/harness/threads/{threadId}/snapshot') return { version: '1', thread: { threadId: 'thread-recovered' }, entries: [] }
      throw new Error(`Unexpected endpoint: ${endpoint}`)
    }) as unknown as typeof request)

    const { replace } = renderDraftPane()
    const editor = await screen.findByRole('textbox')
    await user.type(editor, 'Test retry')
    await user.click(screen.getByRole('button', { name: '发送消息' }))

    // Network failure renders retry alert and locks editor
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('未收到发送结果')
    await user.click(screen.getByRole('button', { name: '重试' }))

    // A definite rejection unlocks editing without removing the record before success.
    await waitFor(() => expect(screen.getByText('此目录已不可用。请选择其他目录后再发送。')).toBeInTheDocument())
    expect(JSON.parse(sessionStorage.getItem(sendStorageKey)!)).toMatchObject({ rejected: true })

    // Third attempt succeeds
    fireEvent.keyDown(editor, { key: 'Enter' })
    await waitFor(() => expect(replace).toHaveBeenCalledWith({ kind: 'thread', threadId: 'thread-recovered' }))
  })

  it('keeps a pending first send intact when a retry requires login', async () => {
    const body = { message: [{ type: 'TEXT', text: 'May already be accepted' }], yoloEnabled: true }
    const frozen = JSON.stringify({ key: 'draft-1', body, rejected: false })
    sessionStorage.setItem(sendStorageKey, frozen)
    const post = vi.fn()
      .mockRejectedValueOnce(new ApiError('login required', 401, 'unauthorized', undefined, 'NOT_AUTHENTICATED'))
      .mockResolvedValueOnce({ threadId: 'thread-recovered' })
    mockedRequest.mockImplementation((async (endpoint: string, params: unknown, options: unknown) => {
      if (endpoint === 'GET /api/platform/agent-summaries') return READY_AGENTS
      if (endpoint === 'GET /api/platform/model-summaries') return READY_MODELS
      if (endpoint === 'GET /api/platform/environment-summaries') return READY_ENVS
      if (endpoint === 'POST /api/chat-spaces/{spaceId}/sessions') return post(params, options)
      throw new Error(`Unexpected endpoint: ${endpoint}`)
    }) as typeof request)

    const user = userEvent.setup()
    const { replace } = renderDraftPane()
    await user.click(await screen.findByRole('button', { name: '重试' }))
    await waitFor(() => expect(post).toHaveBeenCalledTimes(1))
    const retry = await screen.findByRole('button', { name: '重试' })
    expect(sessionStorage.getItem(sendStorageKey)).toBe(frozen)
    await user.click(retry)

    await waitFor(() => expect(replace).toHaveBeenCalledWith({ kind: 'thread', threadId: 'thread-recovered' }))
    expect(post.mock.calls).toEqual(Array.from({ length: 2 }, () => [
      { path: { spaceId: 'space-1' }, body }, { idempotencyKey: 'draft-1' },
    ]))
    expect(sessionStorage.getItem(sendStorageKey)).toBeNull()
  })

  it('handles agent load failure retry, missing default agent, and empty agent list for admin vs user', async () => {
    const user = userEvent.setup()
    let failAgents = true
    mockedRequest.mockImplementation(async (endpoint: string) => {
      if (endpoint === 'GET /api/platform/agent-summaries') {
        if (failAgents) throw new Error('agents down')
        return READY_AGENTS
      }
      if (endpoint === 'GET /api/platform/model-summaries') return []
      if (endpoint === 'GET /api/platform/environment-summaries') return []
      throw new Error(`Unexpected endpoint: ${endpoint}`)
    })

    const openSpaceSettings = vi.fn()
    renderDraftPane({ defaultAgentName: 'missing-agent', openSpaceSettings })
    expect(await screen.findByRole('alert')).toHaveTextContent('请求失败')
    failAgents = false
    await user.click(screen.getByRole('button', { name: '重试' }))

    // Now agents loaded, but space's defaultAgentName does not match -> openSpaceSettings button
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('请为空间选择可用的默认 Agent 后再发送。'))
    await user.click(screen.getByRole('button', { name: '空间设置' }))
    expect(openSpaceSettings).toHaveBeenCalledTimes(1)

    // Admin with 0 agents gets link to /settings/agents
    cleanup()
    mockedRequest.mockImplementation((async (endpoint: string) => {
      if (endpoint === 'GET /api/platform/agent-summaries') return []
      if (endpoint === 'GET /api/platform/model-summaries') return []
      if (endpoint === 'GET /api/platform/environment-summaries') return []
      return {}
    }) as unknown as typeof request)
    renderDraftPane({
      user: { id: 'admin-1', username: 'admin', role: 'ADMIN', profile: {} },
      defaultAgentName: null,
      defaultEnvironmentId: null,
    })
    await waitFor(() => expect(screen.getByRole('link', { name: 'Agent 设置' })).toHaveAttribute('href', '/settings/agents'))
  })
})
