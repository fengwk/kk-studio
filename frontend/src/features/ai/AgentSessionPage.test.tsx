import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AgentSessionPage } from '@/features/ai/AgentSessionPage'
import { agentService } from '@/shared/api/agent-service'
import { queryKeys } from '@/shared/lib/query-keys'

vi.mock('@/shared/api/agent-service', () => {
  const agentService = {
    listAgents: vi.fn(),
    listSessions: vi.fn(),
    getSession: vi.fn(),
    listEvents: vi.fn(),
    listRuns: vi.fn(),
    createMessage: vi.fn(),
    createEventStream: vi.fn(),
  }
  return {
    agentService,
    createWorkspaceSessionApi: () => ({
      list: () => agentService.listSessions(),
      get: (sessionId: string) => agentService.getSession(sessionId),
      createMessage: (sessionId: string, data: unknown) => agentService.createMessage(sessionId, data),
      listEvents: (sessionId: string) => agentService.listEvents(sessionId),
      listRuns: (sessionId: string) => agentService.listRuns(sessionId),
      createEventStream: (sessionId: string) => agentService.createEventStream(sessionId),
    }),
  }
})

class FakeEventSource {
  private readonly listeners = new Map<string, Array<(event: MessageEvent<string>) => void>>()

  closed = false

  addEventListener(type: string, listener: EventListener) {
    const listeners = this.listeners.get(type) ?? []
    listeners.push(listener as (event: MessageEvent<string>) => void)
    this.listeners.set(type, listeners)
  }

  emit(type: string, data: unknown) {
    for (const listener of this.listeners.get(type) ?? []) {
      listener({ data: JSON.stringify(data) } as MessageEvent<string>)
    }
  }

  listenerCount(type: string): number {
    return this.listeners.get(type)?.length ?? 0
  }

  close() {
    this.closed = true
  }
}

describe('AgentSessionPage', () => {
  const streams: FakeEventSource[] = []

  beforeEach(() => {
    vi.clearAllMocks()
    streams.length = 0
    vi.mocked(agentService.createEventStream).mockImplementation(() => {
      const stream = new FakeEventSource()
      streams.push(stream)
      return stream as unknown as EventSource
    })
    vi.mocked(agentService.listAgents).mockResolvedValue({
      pageNumber: 1,
      pageSize: 50,
      totalCount: 1,
      results: [
        {
          id: 'agent-1',
          agentName: 'default-assistant',
          name: 'Default Assistant',
          description: null,
          systemPrompt: null,
          defaultProviderId: 'provider-1',
          defaultProviderName: 'minimax',
          defaultModelId: 'model-1',
          defaultModelName: 'MiniMax-M2.7',
          defaultVariant: 'default',
          toolsJson: '[]',
          createTime: '2026-06-20T02:00:00',
          updateTime: '2026-06-20T02:00:00',
        },
      ],
    })
    vi.mocked(agentService.listSessions).mockResolvedValue({
      pageNumber: 1,
      pageSize: 50,
      totalCount: 1,
      results: [
        {
          sessionId: 'session-1',
          agentId: 'agent-1',
          agentName: 'default-assistant',
          title: 'Script Review',
          createTime: '2026-06-20T02:00:00',
          updateTime: '2026-06-20T02:01:00',
        },
      ],
    })
    vi.mocked(agentService.getSession).mockResolvedValue({
      sessionId: 'session-1',
      agentId: 'agent-1',
      agentName: 'default-assistant',
      title: 'Script Review',
      createTime: '2026-06-20T02:00:00',
      updateTime: '2026-06-20T02:01:00',
    })
    vi.mocked(agentService.listEvents).mockResolvedValue([
      {
        eventId: 'event-1',
        sessionId: 'session-1',
        parentEventId: 'root',
        runId: 'run-1',
        eventType: 'user_message',
        payloadJson: '{"content":"检查第一集大纲"}',
        createTime: '2026-06-20T02:00:00',
      },
      {
        eventId: 'event-2',
        sessionId: 'session-1',
        parentEventId: 'event-1',
        runId: 'run-1',
        eventType: 'assistant_delta',
        payloadJson: '{"textDelta":"结构完整"}',
        createTime: '2026-06-20T02:01:00',
      },
      {
        eventId: 'event-3',
        sessionId: 'session-1',
        parentEventId: 'event-2',
        runId: 'run-1',
        eventType: 'assistant_end',
        payloadJson: '{"metadata":{"finishReason":"stop"}}',
        createTime: '2026-06-20T02:01:01',
      },
    ])
    vi.mocked(agentService.listRuns).mockResolvedValue([
      {
        runId: 'run-1',
        sessionId: 'session-1',
        triggerEventId: 'event-1',
        status: 'succeeded',
        createTime: '2026-06-20T02:00:00',
        updateTime: '2026-06-20T02:01:02',
      },
    ])
    vi.mocked(agentService.createMessage).mockResolvedValue({
      eventId: 'event-4',
      sessionId: 'session-1',
      parentEventId: 'event-3',
      runId: 'run-2',
      eventType: 'user_message',
      payloadJson: '{"content":"继续"}',
      createTime: '2026-06-20T02:02:00',
    })
  })

  it('renders event-backed dialogue and submits new messages', async () => {
    const user = userEvent.setup()
    renderSession()

    expect(await screen.findByText('检查第一集大纲')).toBeInTheDocument()
    expect(screen.getByText('结构完整')).toBeInTheDocument()
    expect(screen.getByText('succeeded')).toBeInTheDocument()
    expect(screen.getByText('2026-06-20 02:01')).toBeInTheDocument()

    await user.type(screen.getByPlaceholderText('给 AI 发送消息...'), '继续')
    await user.click(screen.getByRole('button', { name: '发送消息' }))

    await waitFor(() => {
      expect(agentService.createMessage).toHaveBeenCalledWith('session-1', { content: '继续' })
    })
  })

  it('disables message submission while the session has an active run', async () => {
    vi.mocked(agentService.listRuns).mockResolvedValueOnce([
      {
        runId: 'run-active',
        sessionId: 'session-1',
        triggerEventId: 'event-1',
        status: 'running',
        createTime: '2026-06-20T02:00:00',
        updateTime: '2026-06-20T02:01:02',
      },
    ])

    renderSession()

    await waitFor(() => {
      expect(screen.getAllByText('running').length).toBeGreaterThan(0)
    })
    expect(screen.getByPlaceholderText('给 AI 发送消息...')).toBeDisabled()
    expect(agentService.createMessage).not.toHaveBeenCalled()
  })

  it('merges streamed events into the dialogue cache', async () => {
    vi.mocked(agentService.listEvents).mockResolvedValue([])
    const { queryClient } = renderSession()

    await waitFor(() => {
      expect(agentService.createEventStream).toHaveBeenCalledWith('session-1')
    })
    expect(await screen.findByText('发送消息以开启全新对话。')).toBeInTheDocument()
    expect(streams.at(-1)?.listenerCount('session_event')).toBe(1)

    await act(async () => {
      streams.at(-1)?.emit('session_event', {
        eventId: 'event-stream-1',
        sessionId: 'session-1',
        parentEventId: 'root',
        runId: 'run-stream',
        eventType: 'assistant_delta',
        payloadJson: '{"textDelta":"streamed answer"}',
        createTime: '2026-06-20T02:02:00',
      })
    })

    expect(queryClient.getQueryData(queryKeys.sessions.events('workspace-1', 'session-1'))).toEqual([
      {
        eventId: 'event-stream-1',
        sessionId: 'session-1',
        parentEventId: 'root',
        runId: 'run-stream',
        eventType: 'assistant_delta',
        payloadJson: '{"textDelta":"streamed answer"}',
        createTime: '2026-06-20T02:02:00',
      },
    ])

    expect(await screen.findByText('streamed answer')).toBeInTheDocument()
  })

  it('does not refetch session queries after merging streamed events', async () => {
    vi.mocked(agentService.listEvents).mockResolvedValue([])
    renderSession()

    await waitFor(() => {
      expect(agentService.createEventStream).toHaveBeenCalledWith('session-1')
    })
    expect(await screen.findByText('发送消息以开启全新对话。')).toBeInTheDocument()

    vi.mocked(agentService.listEvents).mockClear()
    vi.mocked(agentService.listRuns).mockClear()
    vi.mocked(agentService.getSession).mockClear()
    vi.mocked(agentService.listSessions).mockClear()

    await act(async () => {
      streams.at(-1)?.emit('session_event', {
        eventId: 'event-stream-2',
        sessionId: 'session-1',
        parentEventId: 'root',
        runId: 'run-stream',
        eventType: 'assistant_delta',
        payloadJson: '{"textDelta":"streamed answer"}',
        createTime: '2026-06-20T02:02:00',
      })
    })

    expect(await screen.findByText('streamed answer')).toBeInTheDocument()
    expect(agentService.listEvents).not.toHaveBeenCalled()
    expect(agentService.listRuns).not.toHaveBeenCalled()
    expect(agentService.getSession).not.toHaveBeenCalled()
    expect(agentService.listSessions).not.toHaveBeenCalled()
  })

  it('submits with Enter and ignores Shift Enter', async () => {
    const user = userEvent.setup()
    renderSession()

    expect(await screen.findByText('检查第一集大纲')).toBeInTheDocument()
    const input = screen.getByPlaceholderText('给 AI 发送消息...')
    await user.type(input, '键盘提交')
    await user.keyboard('{Shift>}{Enter}{/Shift}')
    expect(agentService.createMessage).not.toHaveBeenCalled()

    await user.keyboard('{Enter}')

    await waitFor(() => {
      expect(agentService.createMessage).toHaveBeenCalledWith('session-1', { content: '键盘提交' })
    })
  })

  it('renders empty dialogue and active run status', async () => {
    vi.mocked(agentService.getSession).mockResolvedValueOnce({
      sessionId: 'session-1',
      agentId: 'agent-1',
      agentName: 'default-assistant',
      title: null,
      createTime: '2026-06-20T02:00:00',
      updateTime: '2026-06-20T02:01:00',
    })
    vi.mocked(agentService.listEvents).mockResolvedValueOnce([])
    vi.mocked(agentService.listRuns).mockResolvedValueOnce([
      {
        runId: 'run-2',
        sessionId: 'session-1',
        triggerEventId: 'event-4',
        status: 'queued',
        createTime: '2026-06-20T02:02:00',
        updateTime: null,
      },
    ])

    renderSession()

    expect(await screen.findByText('发送消息以开启全新对话。')).toBeInTheDocument()
    expect(screen.getByText('queued')).toBeInTheDocument()
    expect(screen.getByText('running')).toBeInTheDocument()
    expect(screen.getByText('-')).toBeInTheDocument()
  })

  it('does not render empty assistant bubbles', async () => {
    vi.mocked(agentService.listEvents).mockResolvedValueOnce([
      {
        eventId: 'event-1',
        sessionId: 'session-1',
        parentEventId: 'root',
        runId: 'run-1',
        eventType: 'user_message',
        payloadJson: '{"content":"只看用户消息"}',
        createTime: '2026-06-20T02:00:00',
      },
      {
        eventId: 'event-2',
        sessionId: 'session-1',
        parentEventId: 'event-1',
        runId: 'run-1',
        eventType: 'assistant_start',
        payloadJson: '{}',
        createTime: '2026-06-20T02:01:00',
      },
      {
        eventId: 'event-3',
        sessionId: 'session-1',
        parentEventId: 'event-2',
        runId: 'run-1',
        eventType: 'assistant_end',
        payloadJson: '{}',
        createTime: '2026-06-20T02:01:01',
      },
    ])

    const { container } = renderSession()

    expect(await screen.findByText('只看用户消息')).toBeInTheDocument()
    expect(container.querySelectorAll('.msg-wrapper.bot')).toHaveLength(0)
  })

  it('renders tool execution messages from session events', async () => {
    // Tool lifecycle events should surface as first-class transcript nodes instead of being dropped.
    vi.mocked(agentService.listEvents).mockResolvedValueOnce([
      {
        eventId: 'event-1',
        sessionId: 'session-1',
        parentEventId: 'root',
        runId: 'run-1',
        eventType: 'assistant_start',
        payloadJson: '{}',
        createTime: '2026-06-20T02:00:00',
      },
      {
        eventId: 'event-2',
        sessionId: 'session-1',
        parentEventId: 'event-1',
        runId: 'run-1',
        eventType: 'assistant_delta',
        payloadJson: '{"textDelta":"我先查一下。"}',
        createTime: '2026-06-20T02:00:01',
      },
      {
        eventId: 'event-3',
        sessionId: 'session-1',
        parentEventId: 'event-2',
        runId: 'run-1',
        eventType: 'tool_start',
        payloadJson: '{"toolCallId":"tool-1","toolName":"web_search","arguments":"{\\"q\\":\\"上海天气\\"}"}',
        createTime: '2026-06-20T02:00:02',
      },
      {
        eventId: 'event-4',
        sessionId: 'session-1',
        parentEventId: 'event-3',
        runId: 'run-1',
        eventType: 'tool_delta',
        payloadJson: '{"toolCallId":"tool-1","contentDeltas":[{"index":0,"contentDelta":{"type":"text","text":"晴 32C"}}]}',
        createTime: '2026-06-20T02:00:03',
      },
      {
        eventId: 'event-5',
        sessionId: 'session-1',
        parentEventId: 'event-4',
        runId: 'run-1',
        eventType: 'tool_end',
        payloadJson: '{"toolCallId":"tool-1"}',
        createTime: '2026-06-20T02:00:04',
      },
    ])

    renderSession()

    expect(await screen.findByText('我先查一下。')).toBeInTheDocument()
    expect(screen.getByText('web_search')).toBeInTheDocument()
    expect(screen.getByText('{"q":"上海天气"}')).toBeInTheDocument()
    expect(screen.getByText('晴 32C')).toBeInTheDocument()
    expect(screen.getByText('done')).toBeInTheDocument()
  })

  it('renders actual media previews for tool attachments', async () => {
    // Base64 media and prebuilt data URLs should both render as previewable transcript attachments.
    vi.mocked(agentService.listEvents).mockResolvedValueOnce([
      {
        eventId: 'event-1',
        sessionId: 'session-1',
        parentEventId: 'root',
        runId: 'run-1',
        eventType: 'tool_start',
        payloadJson: '{"toolCallId":"tool-media","toolName":"media_tool","arguments":"{}"}',
        createTime: '2026-06-20T02:00:00',
      },
      {
        eventId: 'event-2',
        sessionId: 'session-1',
        parentEventId: 'event-1',
        runId: 'run-1',
        eventType: 'tool_delta',
        payloadJson: JSON.stringify({
          toolCallId: 'tool-media',
          contentDeltas: [
            { index: 0, contentDelta: { type: 'image', name: 'cover.png', mime: 'image/png', data: 'aW1n' } },
            { index: 1, contentDelta: { type: 'audio', name: 'preview.mp3', mime: 'audio/mpeg', data: 'YXVkaW8=' } },
            {
              index: 2,
              contentDelta: {
                type: 'video',
                name: 'preview.mp4',
                mime: 'video/mp4',
                data: 'data:video/mp4;base64,dmlkZW8=',
              },
            },
          ],
        }),
        createTime: '2026-06-20T02:00:01',
      },
      {
        eventId: 'event-3',
        sessionId: 'session-1',
        parentEventId: 'event-2',
        runId: 'run-1',
        eventType: 'tool_end',
        payloadJson: '{"toolCallId":"tool-media"}',
        createTime: '2026-06-20T02:00:02',
      },
    ])

    const { container } = renderSession()

    expect(await screen.findByText('media_tool')).toBeInTheDocument()
    expect(screen.getByAltText('cover.png')).toHaveAttribute('src', 'data:image/png;base64,aW1n')
    expect(container.querySelector('audio')).toHaveAttribute('src', 'data:audio/mpeg;base64,YXVkaW8=')
    expect(container.querySelector('video')).toHaveAttribute('src', 'data:video/mp4;base64,dmlkZW8=')
    expect(container.querySelectorAll('.tool-attachment-link')).toHaveLength(3)
  })

  it('renders session loading errors and disables the composer without session data', async () => {
    vi.mocked(agentService.getSession).mockRejectedValueOnce(new Error('not found'))

    renderSession()

    expect(screen.getByText('正在加载会话')).toBeInTheDocument()
    expect(await screen.findByText('会话加载失败')).toBeInTheDocument()
    expect(screen.getByPlaceholderText('给 AI 发送消息...')).toBeDisabled()
  })

  it('does not create a stream when the event snapshot query fails', async () => {
    vi.mocked(agentService.listEvents).mockRejectedValueOnce(new Error('events offline'))

    renderSession()

    expect(await screen.findByText('会话加载失败')).toBeInTheDocument()
    expect(agentService.createEventStream).not.toHaveBeenCalled()
  })

  it('closes the active event stream on unmount', async () => {
    const rendered = renderSession()

    await waitFor(() => {
      expect(agentService.createEventStream).toHaveBeenCalledWith('session-1')
    })

    const stream = streams.at(-1)
    expect(stream?.closed).toBe(false)

    rendered.unmount()

    expect(stream?.closed).toBe(true)
  })

  it('skips session-specific queries when session id is missing', async () => {
    renderSession({ initialEntries: ['/workspaces/workspace-1/sessions'], routePath: '/workspaces/:workspaceId/sessions' })

    await waitFor(() => {
      expect(agentService.listAgents).toHaveBeenCalled()
      expect(agentService.listSessions).toHaveBeenCalled()
    })

    expect(agentService.getSession).not.toHaveBeenCalled()
    expect(agentService.listEvents).not.toHaveBeenCalled()
    expect(agentService.listRuns).not.toHaveBeenCalled()
    expect(agentService.createEventStream).not.toHaveBeenCalled()
    expect(screen.getByPlaceholderText('给 AI 发送消息...')).toBeDisabled()
  })
})

function renderSession({
  initialEntries = ['/workspaces/workspace-1/sessions/session-1'],
  routePath = '/workspaces/:workspaceId/sessions/:sessionId',
}: {
  initialEntries?: string[]
  routePath?: string
} = {}) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
  const rendered = render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={initialEntries}>
        <Routes>
          <Route path={routePath} element={<AgentSessionPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
  return { queryClient, ...rendered }
}
