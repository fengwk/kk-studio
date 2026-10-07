import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, cleanup, renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { agentService } from '@/shared/api/agent-service'
import { harnessService } from '@/shared/api/harness-service'
import type { HarnessThreadSnapshotDTO, ToolInvocationDTO } from '@/shared/api/contracts/ai-runtime'
import { ApplicationEventProvider } from '@/shared/app-events'
import { FakeWebSocketHarness } from '@/shared/app-events/__tests__/fake-websocket'
import { useAgentThreadQueries } from './useAgentThreadQueries'

const ROOT = '11111111-2222-4333-8444-555555555555'
const CHILD = '22222222-2222-4333-8444-555555555555'
const OTHER = '33333333-2222-4333-8444-555555555555'

function snapshot(id = ROOT, overrides: Partial<ToolInvocationDTO> = {}): HarnessThreadSnapshotDTO {
  return {
    version: '1',
    thread: {
      threadId: id, name: 'Thread', sessionId: 'session', headEntryId: 'head',
      parentThreadId: id === ROOT ? null : ROOT,
      yoloPolicy: { mode: id === ROOT ? 'DISABLE' : 'FOLLOW', rootThreadId: id === ROOT ? null : ROOT },
      nextCommandSequence: '1', version: '1', status: 'TOOL_READY', processing: true,
      executionControl: 'RUNNABLE',
      branchSettings: { agentName: 'agent', model: { providerName: 'p', modelName: 'm', variant: null },
        environmentName: 'composer-is-not-the-route', goal: null },
      createTime: null, updateTime: null,
    },
    entries: [], queuedCommands: [], modelInvocation: null, modelAttemptFailures: [], stopReceipts: [],
    manualCompaction: { available: false, disabledReason: null },
    toolInvocations: [{
      id: 'tool', modelInvocationId: 'model', assistantEntryId: 'entry', callIndex: 0,
      status: 'READY', attempt: 1, toolCallId: 'call', toolName: 'bash', rendererKey: 'bash',
      environmentId: null, requiredEnvironmentId: 'frozen-environment',
      requiredEnvironmentName: 'archlinux', waitingForEnvironment: true, environmentWaitFreshnessAt: null,
      argumentsJson: '{"command":"pwd"}', approvalJson: null, resultJson: null, errorJson: null,
      createTime: null, updateTime: null, ...overrides,
    }],
  }
}

let sockets: FakeWebSocketHarness
let client: QueryClient
const wrapper = ({ children }: { children: ReactNode }) => (
  <QueryClientProvider client={client}>
    <ApplicationEventProvider url="ws://test/events/v1" socketFactory={sockets.factory}>
      {children}
    </ApplicationEventProvider>
  </QueryClientProvider>
)
beforeEach(() => {
  sockets = new FakeWebSocketHarness()
  client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } })
  vi.spyOn(agentService, 'listAgents').mockResolvedValue({ results: [] } as never)
  vi.spyOn(agentService, 'listModels').mockResolvedValue({ results: [] } as never)
})
afterEach(() => {
  cleanup()
  client.clear()
  vi.restoreAllMocks()
  vi.useRealTimers()
})

it.each([ROOT, CHILD])('根/readonly %s 消费真实根通知，同 version 离线→上线且不写入', async (id) => {
  const get = vi.spyOn(harnessService, 'getThreadSnapshot')
    .mockResolvedValue(snapshot(id))
  const write = vi.spyOn(harnessService, 'decideApproval')
  const { result } = renderHook(() => useAgentThreadQueries(id), { wrapper })
  await waitFor(() => expect(result.current.snapshotQuery.isSuccess).toBe(true))
  const socket = sockets.openLatest()
  const changed = (rootThreadId: string) => act(() => socket.emitServer({
    type: 'event', resource: { kind: 'interactions' }, name: 'changed', data: { rootThreadId },
  }))
  changed(OTHER)
  expect(get).toHaveBeenCalledTimes(1)
  get.mockResolvedValue(snapshot(id, { waitingForEnvironment: false }))
  changed(ROOT)
  await waitFor(() => expect(result.current.toolInvocations[0].waitingForEnvironment).toBe(false))
  expect(result.current.thread?.version).toBe('1')
  expect(get).toHaveBeenCalledTimes(2)
  expect(write).not.toHaveBeenCalled()
  get.mockResolvedValue(snapshot(id))
  act(() => socket.emitServer({ type: 'resync', resource: { kind: 'interactions' } }))
  await waitFor(() => expect(result.current.toolInvocations[0].waitingForEnvironment).toBe(true))
  act(() => socket.emitServer({ type: 'subscribed', resource: { kind: 'interactions' }, cursor: '0' }))
  await waitFor(() => expect(get).toHaveBeenCalledTimes(4))
})

it('没有 READY 环境调用就不订阅，换绑释放旧根', async () => {
  const get = vi.spyOn(harnessService, 'getThreadSnapshot')
    .mockResolvedValue(snapshot(ROOT, { requiredEnvironmentId: null }))
  const { result, rerender } = renderHook(({ id }) => useAgentThreadQueries(id), {
    wrapper, initialProps: { id: ROOT },
  })
  await waitFor(() => expect(result.current.snapshotQuery.isSuccess).toBe(true))
  expect(sockets.openLatest().sentMessages()).toEqual([])
  get.mockResolvedValue(snapshot(CHILD))
  rerender({ id: CHILD })
  await waitFor(() => expect(result.current.thread?.threadId).toBe(CHILD))
  const socket = sockets.openLatest()
  expect(socket.sentMessages()).toContainEqual({ version: 1, type: 'subscribe', resource: { kind: 'interactions' } })
  get.mockResolvedValue(snapshot(OTHER, { status: 'RUNNING' }))
  rerender({ id: OTHER })
  await waitFor(() => expect(result.current.thread?.threadId).toBe(OTHER))
  expect(socket.sentMessages()).toContainEqual({ version: 1, type: 'unsubscribe', resource: { kind: 'interactions' } })
})

it('snapshot 最早自然截止单次回读，续租注销旧 timer，旧过期投影不热循环', async () => {
  const now = Date.now()
  vi.useFakeTimers()
  vi.setSystemTime(now)
  let current = snapshot(ROOT, { environmentWaitFreshnessAt: (now + 10000) / 1000 })
  current.toolInvocations.push({
    ...current.toolInvocations[0], id: 'later', environmentWaitFreshnessAt: (now + 60000) / 1000,
  })
  const get = vi.spyOn(harnessService, 'getThreadSnapshot').mockImplementation(async () => current)
  const { result, unmount } = renderHook(() => useAgentThreadQueries(ROOT), { wrapper })
  await act(async () => vi.advanceTimersByTimeAsync(1))
  expect(result.current.snapshotQuery.isSuccess).toBe(true)
  const socket = sockets.openLatest()
  current = snapshot(ROOT, { environmentWaitFreshnessAt: (now + 20000) / 1000 })
  await act(async () => {
    socket.emitServer({ type: 'event', resource: { kind: 'interactions' }, name: 'changed', data: { rootThreadId: ROOT } })
    await vi.advanceTimersByTimeAsync(1)
  })
  await act(async () => vi.advanceTimersByTimeAsync(10250))
  expect(get).toHaveBeenCalledTimes(2)
  await act(async () => vi.advanceTimersByTimeAsync(10000))
  expect(get).toHaveBeenCalledTimes(3)
  await act(async () => vi.advanceTimersByTimeAsync(60000))
  expect(get).toHaveBeenCalledTimes(3)
  unmount()
})
