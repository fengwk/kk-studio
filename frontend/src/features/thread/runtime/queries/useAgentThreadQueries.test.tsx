import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, cleanup, renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { agentService } from '@/shared/api/agent-service'
import { harnessService } from '@/shared/api/harness-service'
import { ApplicationEventProvider } from '@/shared/app-events'
import { FakeWebSocketHarness } from '@/test-support/fake-websocket'
import { useAgentThreadQueries } from './useAgentThreadQueries'
import type { HarnessThreadSnapshotDTO, ToolInvocationDTO } from '@/shared/api/generated/contracts'

const ROOT = '11111111-2222-4333-8444-555555555555'
const CHILD = '22222222-2222-4333-8444-555555555555'
const SESSION = '33333333-2222-4333-8444-555555555555'
const HEAD = '55555555-2222-4333-8444-555555555555'
const ENTRY = '55555555-2222-4333-8444-555555555555'
const INVOCATION = '66666666-2222-4333-8444-555555555555'
const TURN = '77777777-2222-4333-8444-555555555555'

function snapshot(
  id = ROOT,
  overrides: Partial<ToolInvocationDTO> = {},
): HarnessThreadSnapshotDTO {
  return {
    version: '1',
    thread: {
      threadId: id,
      name: 'Thread',
      sessionId: SESSION,
      headEntryId: HEAD,
      parentThreadId: id === ROOT ? null : ROOT,
      rootThreadId: ROOT,
      yoloEnabled: false,
      nextCommandSequence: '1',
      version: '1',
      status: 'RUNNING',
      executionControl: 'RUNNABLE',
      branchSettings: {
        agentName: 'agent',
        execution: { driver: 'builtin', settings: {} },
        environmentName: null,
      },
      createdAt: 0,
      updatedAt: 0,
    },
    baseEntryId: null,
    entries: [{
      entryId: ENTRY, sessionId: SESSION, parentEntryId: null,
      entryType: 'ROOT', payload: {}, createdAt: 0, usageCost: null,
    }],
    queuedCommands: [],
    activeAssistantOutput: null,
    generationFailures: [],
    stopReceipts: [],
    unconfirmedExecutions: [],
    toolInvocations: [{
      id: INVOCATION,
      threadId: id,
      childThreadId: null,
      turnStartEntryId: TURN,
      assistantEntryId: ENTRY,
      callIndex: 0,
      status: 'READY',
      attempt: 1,
      toolCallId: 'call',
      queuedBehindToolCallId: null,
      toolName: 'bash',
      rendererKey: 'bash',
      environmentId: null,
      requiredEnvironmentId: '88888888-2222-4333-8444-555555555555',
      requiredEnvironmentName: 'environment',
      waitingForEnvironment: true,
      environmentWaitFreshnessAt: Date.now() + 1000,
      argumentsJson: '{}',
      approval: null,
      result: null,
      error: null,
      createdAt: 0,
      updatedAt: 0,
      ...overrides,
    }],
  }
}

let sockets: FakeWebSocketHarness
let client: QueryClient
const wrapper = ({ children }: { children: ReactNode }) => (
  <QueryClientProvider client={client}>
    <ApplicationEventProvider url="ws://test/ws/events" socketFactory={sockets.factory}>
      {children}
    </ApplicationEventProvider>
  </QueryClientProvider>
)

beforeEach(() => {
  sockets = new FakeWebSocketHarness()
  client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } })
  vi.spyOn(agentService, 'listAgents').mockResolvedValue([])
  vi.spyOn(agentService, 'listModels').mockResolvedValue([])
})

afterEach(() => {
  cleanup()
  client.clear()
  vi.restoreAllMocks()
  vi.useRealTimers()
})

it('loads over HTTP, subscribes from the cached head, then applies same-version environment checkpoints without GET', async () => {
  const get = vi.spyOn(harnessService, 'getThreadSnapshot').mockResolvedValue(snapshot())
  const { result } = renderHook(() => useAgentThreadQueries(ROOT), { wrapper })
  await waitFor(() => expect(result.current.snapshotQuery.isSuccess).toBe(true))
  const socket = sockets.openLatest()
  expect(socket.sentMessages()).toContainEqual({
    type: 'subscribe', resource: { kind: 'thread', id: ROOT }, afterEntryId: HEAD,
  })
  expect(socket.sentMessages()).not.toContainEqual({ type: 'subscribe', resource: { kind: 'interactions' } })

  const updated = snapshot(ROOT, { waitingForEnvironment: false })
  act(() => socket.emitServer({
    type: 'event', resource: { kind: 'thread', id: ROOT }, name: 'snapshot', data: updated,
  }))
  await waitFor(() => expect(result.current.toolInvocations[0]?.waitingForEnvironment).toBe(false))
  expect(result.current.thread?.version).toBe('1')
  expect(get).toHaveBeenCalledTimes(1)
})

it('shares per-Thread subscriptions across independent consumers and releases on rebind', async () => {
  const get = vi.spyOn(harnessService, 'getThreadSnapshot').mockImplementation(async (threadId) => snapshot(threadId))
  const { result, rerender } = renderHook(({ id }) => useAgentThreadQueries(id), {
    wrapper, initialProps: { id: ROOT },
  })
  await waitFor(() => expect(result.current.snapshotQuery.isSuccess).toBe(true))
  const socket = sockets.openLatest()
  expect(socket.sentMessages()).toEqual([{
    type: 'subscribe', resource: { kind: 'thread', id: ROOT }, afterEntryId: HEAD,
  }])

  rerender({ id: CHILD })
  await waitFor(() => expect(result.current.thread?.threadId).toBe(CHILD))
  expect(socket.sentMessages()).toContainEqual({ type: 'unsubscribe', resource: { kind: 'thread', id: ROOT } })
  expect(socket.sentMessages()).toContainEqual({
    type: 'subscribe', resource: { kind: 'thread', id: CHILD }, afterEntryId: HEAD,
  })
  expect(get).toHaveBeenCalledTimes(2)
})

it('does not poll when an Environment freshness deadline passes', async () => {
  vi.useFakeTimers()
  const now = Date.now()
  vi.setSystemTime(now)
  const get = vi.spyOn(harnessService, 'getThreadSnapshot').mockResolvedValue(snapshot())
  const { result } = renderHook(() => useAgentThreadQueries(ROOT), { wrapper })
  await act(async () => vi.advanceTimersByTimeAsync(1))
  expect(result.current.snapshotQuery.isSuccess).toBe(true)
  await act(async () => vi.advanceTimersByTimeAsync(60_000))
  expect(get).toHaveBeenCalledTimes(1)
  expect(result.current.toolInvocations[0]?.waitingForEnvironment).toBe(true)
})
