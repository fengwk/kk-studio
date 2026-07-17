import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AgentSessionPage } from '@/features/ai/AgentSessionPage'
import { agentService } from '@/shared/api/agent-service'
import { harnessService } from '@/shared/api/harness-service'
import type { HarnessRunDTO, HarnessSessionDTO, HarnessSessionEntryDTO, RunEventDTO } from '@/shared/api/contracts'
import { queryKeys } from '@/shared/lib/query-keys'

vi.mock('@/shared/api/agent-service', () => ({
  agentService: { listAgents: vi.fn() },
}))

vi.mock('@/shared/api/harness-service', () => ({
  harnessService: {
    listSessions: vi.fn(),
    getSession: vi.fn(),
    listEntries: vi.fn(),
    createMessage: vi.fn(),
    listRuns: vi.fn(),
    listRunEvents: vi.fn(),
    createRunEventStream: vi.fn(),
    listRootActivities: vi.fn(),
    createRootActivityStream: vi.fn(),
    listSessionTasks: vi.fn(),
    getYolo: vi.fn(),
    setYolo: vi.fn(),
    getSessionUsage: vi.fn(),
    listToolInvocations: vi.fn(),
    decideToolInvocation: vi.fn(),
    steer: vi.fn(),
    followUp: vi.fn(),
    abortRun: vi.fn(),
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

describe('AgentSessionPage', () => {
  const streams: FakeEventSource[] = []
  const rootStreams: FakeEventSource[] = []

  beforeEach(() => {
    vi.clearAllMocks()
    streams.length = 0
    rootStreams.length = 0
    vi.mocked(agentService.listAgents).mockResolvedValue(page([agent]))
    vi.mocked(harnessService.listSessions).mockResolvedValue([session])
    vi.mocked(harnessService.getSession).mockResolvedValue(session)
    vi.mocked(harnessService.listEntries).mockResolvedValue([
      entry('snapshot', 'agent_snapshot', { snapshot: { modelId: 'MiniMax-M2.7', variant: 'default' } }),
      entry('user-1', 'message', messagePayload('USER', [{ type: 'text', text: '检查第一集大纲' }])),
      entry('assistant-1', 'message', messagePayload('ASSISTANT', [{ type: 'text', text: '结构完整' }])),
    ])
    vi.mocked(harnessService.listRuns).mockResolvedValue([run('SUCCEEDED')])
    vi.mocked(harnessService.listRunEvents).mockResolvedValue([])
    vi.mocked(harnessService.listRootActivities).mockResolvedValue([])
    vi.mocked(harnessService.listSessionTasks).mockResolvedValue([])
    vi.mocked(harnessService.getYolo).mockResolvedValue({ sessionId: '1', rootSessionId: '1', enabled: false })
    vi.mocked(harnessService.setYolo).mockResolvedValue({ sessionId: '1', rootSessionId: '1', enabled: true })
    vi.mocked(harnessService.getSessionUsage).mockResolvedValue({
      scopeType: 'session',
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
      costs: [{ currency: 'USD', input: '0.000001', output: '0.000002', cacheRead: '0', cacheWrite: '0', cacheWriteLong: '0', reasoning: '0', total: '0.000003' }],
    })
    vi.mocked(harnessService.listToolInvocations).mockResolvedValue([])
    vi.mocked(harnessService.decideToolInvocation).mockResolvedValue({} as never)
    vi.mocked(harnessService.createRunEventStream).mockImplementation(() => {
      const stream = new FakeEventSource()
      streams.push(stream)
      return stream as unknown as EventSource
    })
    vi.mocked(harnessService.createRootActivityStream).mockImplementation(() => {
      const stream = new FakeEventSource()
      rootStreams.push(stream)
      return stream as unknown as EventSource
    })
    vi.mocked(harnessService.createMessage).mockResolvedValue(entry('user-2', 'message', messagePayload('USER', [{ type: 'text', text: '继续' }])))
    vi.mocked(harnessService.steer).mockResolvedValue({} as never)
    vi.mocked(harnessService.followUp).mockResolvedValue({} as never)
    vi.mocked(harnessService.abortRun).mockResolvedValue({} as never)
  })

  it('renders the durable Entry timeline and submits with the current leaf id', async () => {
    const user = userEvent.setup()
    renderSession()

    expect(await screen.findByText('检查第一集大纲')).toBeInTheDocument()
    expect(screen.getByText('结构完整')).toBeInTheDocument()
    expect(screen.getByText('SUCCEEDED')).toBeInTheDocument()

    await user.type(screen.getByPlaceholderText('给 AI 发送消息...'), '继续')
    await user.click(screen.getByRole('button', { name: '发送消息' }))

    await waitFor(() => {
      expect(harnessService.createMessage).toHaveBeenCalledWith('1', { content: '继续', expectedLeafEntryId: 'snapshot' })
    })
  })

  it('uses the active run SSE stream for live deltas without crossing run caches', async () => {
    vi.mocked(harnessService.listRuns).mockResolvedValue([run('RUNNING')])
    vi.mocked(harnessService.listEntries).mockResolvedValue([entry('snapshot', 'agent_snapshot', { snapshot: { modelId: 'MiniMax-M2.7', variant: 'default' } })])
    const { queryClient } = renderSession()
    queryClient.setQueryData(queryKeys.runs.events('other-run'), [runEvent('other', 'other-run', 1, 'assistant_started', {})])

    await waitFor(() => expect(harnessService.createRunEventStream).toHaveBeenCalledWith('2', 0))
    expect(screen.getByPlaceholderText('给 AI 发送消息...')).toBeEnabled()

    await act(async () => {
      streams[0]?.emit('run_event', runEvent('delta', '2', 1, 'assistant_delta_batch', { deltas: [{ kind: 'text', text: '实时回答' }] }))
    })

    expect(await screen.findByText('实时回答')).toBeInTheDocument()
    expect(queryClient.getQueryData(queryKeys.runs.events('other-run'))).toEqual([runEvent('other', 'other-run', 1, 'assistant_started', {})])
  })

  it('shows persisted permission, Environment and YOLO controls for an active invocation', async () => {
    const user = userEvent.setup()
    vi.mocked(harnessService.listRuns).mockResolvedValue([run('WAITING_TOOLS')])
    vi.mocked(harnessService.listToolInvocations).mockResolvedValue([{
      id: 'invocation-1',
      runId: '2',
      assistantEntryId: 'assistant-1',
      ordinal: 0,
      toolCallId: 'call-1',
      toolName: 'workspace_edit',
      toolVersion: '1.0',
      targetType: 'ENVIRONMENT',
      environmentId: '42',
      argumentsJson: '{}',
      status: 'WAITING_APPROVAL',
      permissionAction: 'ASK',
      permissionDecision: null,
      deadlineAt: null,
      leaseOwner: null,
      leaseUntil: null,
      cancelRequestedAt: null,
      resultJson: null,
      errorMessage: null,
      createTime: '2026-06-20T02:00:00',
      startedAt: null,
      finishedAt: null,
      updateTime: '2026-06-20T02:00:00',
    }])
    renderSession()

    expect(await screen.findByText('需要工具授权：workspace_edit')).toBeInTheDocument()
    expect(screen.getByText('ENVIRONMENT / environment:42')).toBeInTheDocument()
    expect(screen.getByText('Usage：15 tokens（cache 3） · USD0.000003')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '允许' }))
    await waitFor(() => expect(harnessService.decideToolInvocation).toHaveBeenCalledWith('invocation-1', 'allow'))

    await user.click(screen.getByRole('checkbox', { name: 'YOLO：自动批准工具调用' }))
    await waitFor(() => expect(harnessService.setYolo).toHaveBeenCalledWith('1', true))
  })

  it('opens the root activity stream and sends steer, follow-up and abort controls', async () => {
    const user = userEvent.setup()
    vi.mocked(harnessService.listRuns).mockResolvedValue([run('RUNNING')])
    renderSession()

    await waitFor(() => {
      expect(harnessService.createRootActivityStream).toHaveBeenCalledWith('1', '0')
    })
    const composer = screen.getByPlaceholderText('给 AI 发送消息...')
    await user.type(composer, '优先检查边界')
    await user.click(screen.getByRole('button', { name: '插入指令' }))
    await waitFor(() => expect(harnessService.steer).toHaveBeenCalledWith('1', '优先检查边界'))

    await user.type(composer, '完成后继续')
    await user.click(screen.getByRole('button', { name: '排队追问' }))
    await waitFor(() => expect(harnessService.followUp).toHaveBeenCalledWith('1', '完成后继续'))

    await user.click(screen.getByRole('button', { name: '终止运行' }))
    await waitFor(() => expect(harnessService.abortRun).toHaveBeenCalledWith('1'))
  })

  it('relays a child-session permission from the root activity stream and removes it after resolution', async () => {
    const user = userEvent.setup()
    renderSession()

    await waitFor(() => expect(harnessService.createRootActivityStream).toHaveBeenCalledWith('1', '0'))
    await act(async () => {
      rootStreams[0]?.emit('root_activity', rootActivity('100', 'subagent_started', '1', {
        childSessionId: 'child-1',
        targetAgent: 'researcher',
      }))
    })
    await waitFor(() => expect(harnessService.listSessionTasks).toHaveBeenCalledTimes(2))
    expect(await screen.findByText('启动子代理 researcher')).toBeInTheDocument()
    await act(async () => {
      rootStreams[0]?.emit('root_activity', rootActivity('101', 'permission_requested', 'child-1', {
        invocationId: 'child-invocation',
        tool: 'write_file',
        workdir: '/child/repo',
        arguments: '{}',
      }))
    })

    expect(await screen.findByText('子代理权限：write_file')).toBeInTheDocument()
    expect(screen.getByText('/child/repo · {}')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '允许' }))
    await waitFor(() => expect(harnessService.decideToolInvocation).toHaveBeenCalledWith('child-invocation', 'allow'))

    await act(async () => {
      rootStreams[0]?.emit('root_activity', rootActivity('102', 'permission_resolved', 'child-1', {
        invocationId: 'child-invocation',
        decision: 'ALLOW',
      }))
    })
    await waitFor(() => expect(screen.queryByText('子代理权限：write_file')).not.toBeInTheDocument())
  })

  it('does not open a run stream for a terminal session and closes an active stream on unmount', async () => {
    const terminal = renderSession()
    await screen.findByText('检查第一集大纲')
    expect(harnessService.createRunEventStream).not.toHaveBeenCalled()
    terminal.unmount()

    vi.mocked(harnessService.listRuns).mockResolvedValue([run('WAITING_TOOLS')])
    const active = renderSession()
    await waitFor(() => expect(harnessService.createRunEventStream).toHaveBeenCalled())
    const stream = streams.at(-1)

    active.unmount()

    expect(stream?.closed).toBe(true)
  })

  it('does not issue detail, Entry, run or stream requests without a session id', async () => {
    renderSession({ initialEntries: ['/sessions'], routePath: '/sessions' })

    await waitFor(() => {
      expect(agentService.listAgents).toHaveBeenCalled()
      expect(harnessService.listSessions).toHaveBeenCalled()
    })
    expect(harnessService.getSession).not.toHaveBeenCalled()
    expect(harnessService.listEntries).not.toHaveBeenCalled()
    expect(harnessService.listRuns).not.toHaveBeenCalled()
    expect(harnessService.createRunEventStream).not.toHaveBeenCalled()
  })
})

const agent = {
  id: 'agent-1',
  name: 'Default Assistant',
  description: null,
  systemPrompt: null,
  defaultProviderId: 'provider-1',
  defaultProviderName: 'minimax',
  defaultModelId: 'model-1',
  defaultModelName: 'MiniMax-M2.7',
  defaultVariant: 'default',
  toolsJson: '[]',
  createTime: null,
  updateTime: null,
}

const session: HarnessSessionDTO = {
  sessionId: '1',
  agentDefinitionId: 'agent-1',
  title: 'Script Review',
  rootSessionId: '1',
  parentSessionId: null,
  depth: 0,
  leafEntryId: 'snapshot',
  activeRunId: null,
  yoloEnabled: false,
  createTime: '2026-06-20T02:00:00',
  updateTime: '2026-06-20T02:01:00',
}

function page<T>(results: T[]) {
  return { pageNumber: 1, pageSize: 50, totalCount: results.length, results }
}

function entry(sessionEntryId: string, entryType: string, payload: Record<string, unknown>): HarnessSessionEntryDTO {
  return {
    sessionEntryId,
    sessionId: '1',
    parentEntryId: null,
    runId: entryType === 'agent_snapshot' ? null : '2',
    entryType,
    payloadJson: JSON.stringify(payload),
    createTime: '2026-06-20T02:00:00',
  }
}

function messagePayload(role: string, contents: Record<string, unknown>[]) {
  return { message: { role, contents }, assistantMetadata: role === 'ASSISTANT' ? {} : null }
}

function run(status: string): HarnessRunDTO {
  return {
    runId: '2',
    sessionId: '1',
    triggerEntryId: 'user-1',
    status,
    turnIndex: 0,
    attempt: 1,
    eventSequence: 0,
    nextAttemptAt: null,
    cancelRequestedAt: null,
    startedAt: null,
    finishedAt: null,
    createTime: '2026-06-20T02:00:00',
    updateTime: '2026-06-20T02:01:00',
  }
}

function runEvent(eventId: string, runId: string, sequence: number, type: string, payload: Record<string, unknown>): RunEventDTO {
  return {
    eventId,
    runId,
    sequence,
    type,
    payloadJson: JSON.stringify(payload),
    createTime: '2026-06-20T02:00:00',
  }
}

function rootActivity(eventId: string, type: string, sessionId: string, payload: Record<string, unknown>) {
  return {
    rootSessionId: '1',
    sessionId,
    runId: 'child-run',
    eventId,
    sequence: 1,
    type,
    payloadJson: JSON.stringify(payload),
    createTime: '2026-06-20T02:00:00',
  }
}

function renderSession({
  initialEntries = ['/sessions/1'],
  routePath = '/sessions/:sessionId',
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
