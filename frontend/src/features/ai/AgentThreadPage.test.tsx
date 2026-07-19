import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AgentThreadPage } from '@/features/ai/AgentThreadPage'
import { THREAD_COMMANDS } from '@/features/ai/thread-panel/thread-commands'
import { agentService } from '@/shared/api/agent-service'
import { harnessService } from '@/shared/api/harness-service'
import type {
  HarnessSessionEntryDTO,
  HarnessThreadDTO,
  HarnessThreadInputDTO,
  ThreadEventDTO,
} from '@/shared/api/contracts'
vi.mock('@/shared/api/agent-service', () => ({
  agentService: {
    listAgents: vi.fn(),
    listModels: vi.fn(),
    listProviders: vi.fn(),
  },
}))

vi.mock('@/shared/api/harness-service', () => ({
  harnessService: {
    getSession: vi.fn(),
    listSessionThreads: vi.fn(),
    listSessionEntries: vi.fn(),
    getThread: vi.fn(),
    listThreadEntries: vi.fn(),
    listThreadInputs: vi.fn(),
    listThreadEvents: vi.fn(),
    submitThreadMessage: vi.fn(),
    createThreadEventStream: vi.fn(),
    listRootActivities: vi.fn(),
    listSessionTasks: vi.fn(),
    setThreadYolo: vi.fn(),
    stopThread: vi.fn(),
    retryThread: vi.fn(),
    getThreadUsage: vi.fn(),
    listThreadToolInvocations: vi.fn(),
    decideToolInvocation: vi.fn(),
  },
}))

class FakeEventSource {
  private readonly listeners = new Map<string, Array<(event: MessageEvent<string>) => void>>()
  closed = false

  addEventListener(type: string, listener: EventListener) {
    this.listeners.set(type, [...(this.listeners.get(type) ?? []), listener as (event: MessageEvent<string>) => void])
  }

  emit(type: string, data: unknown) {
    for (const listener of this.listeners.get(type) ?? []) {
      listener({ data: JSON.stringify(data) } as MessageEvent<string>)
    }
  }

  close() {
    this.closed = true
  }
}

describe('AgentThreadPage', () => {
  const streams: FakeEventSource[] = []

  beforeEach(() => {
    vi.clearAllMocks()
    streams.length = 0
    vi.mocked(agentService.listAgents).mockResolvedValue(page([agent]))
    vi.mocked(agentService.listModels).mockResolvedValue(page([]))
    vi.mocked(agentService.listProviders).mockResolvedValue(page([]))
    vi.mocked(harnessService.getSession).mockResolvedValue({
      sessionId: 's1',
      title: 'Outline review',
      mainThreadId: '1',
      rootSessionId: 's1',
      parentSessionId: null,
      parentInvocationId: null,
      depth: 0,
      createTime: null,
      updateTime: null,
    })
    vi.mocked(harnessService.listSessionThreads).mockResolvedValue([thread])
    vi.mocked(harnessService.listSessionEntries).mockResolvedValue([])
    vi.mocked(harnessService.getThread).mockResolvedValue(thread)
    vi.mocked(harnessService.listThreadEntries).mockResolvedValue([
      entry('snapshot', 'agent_snapshot', { snapshot: { modelId: 'MiniMax-M2.7', variant: 'default' } }),
      entry('user-1', 'message', messagePayload('USER', [{ type: 'text', text: '检查第一集大纲' }])),
      entry('assistant-1', 'message', messagePayload('ASSISTANT', [{ type: 'text', text: '结构完整' }])),
    ])
    vi.mocked(harnessService.listThreadInputs).mockResolvedValue([])
    vi.mocked(harnessService.listThreadEvents).mockResolvedValue([])
    vi.mocked(harnessService.listRootActivities).mockResolvedValue([])
    vi.mocked(harnessService.listSessionTasks).mockResolvedValue([])
    vi.mocked(harnessService.setThreadYolo).mockResolvedValue(input('yolo-1', 'set_yolo', { yoloEnabled: true }))
    vi.mocked(harnessService.stopThread).mockResolvedValue({
      stopId: 'stop-1',
      cancelledInputs: [],
      restoredMessages: [],
    })
    vi.mocked(harnessService.retryThread).mockResolvedValue({ ...thread, status: 'RETRYING' })
    vi.mocked(harnessService.getThreadUsage).mockResolvedValue({
      scopeType: 'thread',
      scopeId: '1',
      recordCount: 1,
      inputTokens: 10,
      outputTokens: 5,
      cacheReadTokens: 3,
      cacheWriteTokens: 0,
      cacheWriteLongTokens: 0,
      reasoningTokens: 0,
      providerTotalTokens: 15,
      cacheEligibleRecordCount: 1,
      cacheHitRecordCount: 1,
      cacheHitRatio: '1',
      tokenReadRatio: '0.2',
      unamortizedCacheWriteTokens: 0,
      costs: [
        {
          currency: 'USD',
          input: '0.000001',
          output: '0.000002',
          cacheRead: '0',
          cacheWrite: '0',
          cacheWriteLong: '0',
          reasoning: '0',
          total: '0.000003',
        },
      ],
    })
    vi.mocked(harnessService.listThreadToolInvocations).mockResolvedValue([])
    vi.mocked(harnessService.decideToolInvocation).mockResolvedValue({} as never)
    vi.mocked(harnessService.createThreadEventStream).mockImplementation(() => {
      const stream = new FakeEventSource()
      streams.push(stream)
      return stream as unknown as EventSource
    })
    vi.mocked(harnessService.submitThreadMessage).mockResolvedValue(
      input('in-2', 'user_message', messagePayload('USER', [{ type: 'text', text: '继续' }])),
    )
  })

  it('renders durable Entry timeline and submits with clientMessageId while processing', async () => {
    const user = userEvent.setup()
    vi.mocked(harnessService.getThread).mockResolvedValue({ ...thread, processing: true })
    renderThread()

    expect(await screen.findByText('检查第一集大纲')).toBeInTheDocument()
    expect(screen.getByText('结构完整')).toBeInTheDocument()

    const composer = screen.getByPlaceholderText(/告诉 Agent/)
    expect(composer).toBeEnabled()

    await user.type(composer, '继续')
    await user.click(screen.getByRole('button', { name: '发送消息' }))

    await waitFor(() => {
      expect(harnessService.submitThreadMessage).toHaveBeenCalledWith(
        '1',
        expect.objectContaining({ content: '继续', clientMessageId: expect.any(String) }),
      )
    })
  })

  it('shows queued input below Working instead of in transcript and keeps composer open', async () => {
    const user = userEvent.setup()
    vi.mocked(harnessService.getThread).mockResolvedValue({ ...thread, processing: true })
    vi.mocked(harnessService.listThreadInputs).mockResolvedValue([
      input('pending-1', 'user_message', messagePayload('USER', [{ type: 'text', text: '排队消息' }])),
    ])
    renderThread()

    const queuedMessage = await screen.findByText('排队消息')
    expect(queuedMessage).toBeInTheDocument()
    expect(within(screen.getByRole('log', { name: '会话消息' })).queryByText('排队消息')).not.toBeInTheDocument()
    expect(within(screen.getByLabelText('等待处理的消息')).getByText('排队消息')).toBeInTheDocument()
    expect(screen.getByText('Working...')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Stop' })).not.toBeInTheDocument()
    expect(screen.queryByText('IDLE')).not.toBeInTheDocument()
    const composer = screen.getByPlaceholderText(/告诉 Agent/)
    expect(composer).toBeEnabled()
    await user.type(composer, '第二条')
    await user.click(screen.getByRole('button', { name: '发送消息' }))
    await waitFor(() => {
      expect(harnessService.submitThreadMessage).toHaveBeenCalledWith(
        '1',
        expect.objectContaining({ content: '第二条' }),
      )
    })
  })

  it('subscribes to thread SSE and merges live deltas by eventId cursor', async () => {
    vi.mocked(harnessService.listThreadEvents).mockResolvedValue([
      threadEvent('1', 'assistant_started', '99', {}),
    ])
    renderThread()

    await waitFor(() =>
      expect(harnessService.createThreadEventStream).toHaveBeenCalledWith('1', '1'),
    )

    await act(async () => {
      streams[0]?.emit(
        'thread_event',
        threadEvent('2', 'assistant_delta_batch', '99', {
          deltas: [{ kind: 'text', text: '实时回答' }],
        }),
      )
    })

    expect(await screen.findByText('实时回答')).toBeInTheDocument()
  })

  it('exposes slash-only commands and executes /stop without standalone controls', async () => {
    const user = userEvent.setup()
    renderThread()
    await screen.findByText('检查第一集大纲')

    const composer = screen.getByLabelText('给 AI 发送消息')
    expect(screen.queryByRole('button', { name: '打开命令表' })).not.toBeInTheDocument()
    await user.type(composer, '/')
    expect(await screen.findByText('yolo')).toBeInTheDocument()
    expect(screen.getByText('stop')).toBeInTheDocument()
    expect(screen.getByText('retry')).toBeInTheDocument()
    expect(screen.getByText('clear')).toBeInTheDocument()
    expect(THREAD_COMMANDS.map((command) => command.id)).toEqual(['yolo', 'stop', 'retry', 'clear-draft'])

    await user.click(screen.getByText('yolo'))
    await waitFor(() => {
      expect(harnessService.setThreadYolo).toHaveBeenCalledWith(
        '1',
        expect.objectContaining({ yoloEnabled: true, clientMessageId: expect.any(String) }),
      )
    })

    await user.type(composer, '/stop')
    await user.keyboard('{Enter}')
    await waitFor(() => {
      expect(harnessService.stopThread).toHaveBeenCalledWith(
        '1',
        expect.objectContaining({ clientRequestId: expect.any(String) }),
      )
    })
    expect(screen.queryByRole('button', { name: 'Stop' })).not.toBeInTheDocument()
  })

  it('reloads the same thread id on refresh without creating a new thread', async () => {
    renderThread()
    await screen.findByText('检查第一集大纲')
    expect(harnessService.getThread).toHaveBeenCalledWith('1')
    // Reloading only projects the durable Thread; it never creates a replacement Thread.
    expect('createSessionThread' in harnessService).toBe(false)
  })
})

function renderThread() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/sessions/s1/threads/1']}>
        <Routes>
          <Route path="/sessions/:sessionId/threads/:threadId" element={<AgentThreadPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
  return { queryClient }
}

function page<T>(results: T[]) {
  return { pageNumber: 1, pageSize: 50, totalCount: results.length, results }
}

const agent = {
  id: 'agent-1',
  name: 'default-assistant',
  description: null,
  systemPrompt: null,
  defaultProviderId: 'p1',
  defaultProviderName: 'stub',
  defaultModelId: 'm1',
  defaultModelName: 'model',
  defaultVariant: 'default',
  toolsJson: null,
  createTime: null,
  updateTime: null,
}

const thread: HarnessThreadDTO = {
  threadId: '1',
  sessionId: 's1',
  sessionTitle: 'Outline review',
  headEntryId: 'assistant-1',
  status: 'IDLE',
  inputSequence: 1,
  processing: false,
  createTime: null,
  updateTime: null,
}

function entry(entryId: string, entryType: string, payload: Record<string, unknown>): HarnessSessionEntryDTO {
  return {
    entryId,
    sessionId: 's1',
    parentEntryId: null,
    entryType,
    payloadJson: JSON.stringify(payload),
    createTime: '2026-01-01T00:00:00',
  }
}

function input(inputId: string, inputType: string, payload: Record<string, unknown>): HarnessThreadInputDTO {
  return {
    inputId,
    threadId: '1',
    sequence: 1,
    inputType: inputType.toUpperCase() as HarnessThreadInputDTO['inputType'],
    payloadJson: JSON.stringify(payload),
    clientMessageId: `cid-${inputId}`,
    status: 'QUEUED',
    appliedEntryId: null,
    resolvedAt: null,
    cancelledByStopId: null,
    createTime: '2026-01-01T00:00:00',
  }
}

function threadEvent(
  eventId: string,
  eventType: string,
  subjectEntryId: string | null,
  payload: Record<string, unknown>,
): ThreadEventDTO {
  return {
    eventId,
    threadId: '1',
    subjectEntryId,
    eventType,
    payloadJson: JSON.stringify(payload),
    createTime: '2026-01-01T00:00:00',
  }
}

function messagePayload(role: string, contents: Array<Record<string, unknown>>) {
  return { message: { role, contents }, assistantMetadata: null }
}
