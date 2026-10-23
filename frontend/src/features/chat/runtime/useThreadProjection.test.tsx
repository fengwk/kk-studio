import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { agentService } from '@/shared/api/agent-service'
import { harnessService } from '@/shared/api/harness-service'
import { ApplicationEventProvider } from '@/shared/app-events'
import { FakeWebSocketHarness } from '@/shared/app-events/__tests__/fake-websocket'
import { useThreadProjection } from './useThreadProjection'
import type { HarnessThreadSnapshotDTO } from '@/shared/api/generated/contracts'

const THREAD_ID = 'subagent-thread-101'
const ROOT_THREAD_ID = 'root-thread-001'

function createSnapshot(queuedCommands: HarnessThreadSnapshotDTO['queuedCommands'] = []): HarnessThreadSnapshotDTO {
  return {
    version: '1',
    thread: {
      threadId: THREAD_ID,
      parentThreadId: ROOT_THREAD_ID,
      name: 'subagent',
      sessionId: 'session-1',
      headEntryId: 'head-1',
      rootThreadId: ROOT_THREAD_ID,
      yoloEnabled: false,
      nextCommandSequence: '3',
      version: '1',
      status: 'IDLE',
      processing: false,
      executionControl: 'RUNNABLE',
      branchSettings: {
        agentName: 'worker',
        model: { modelName: 'MiniMax', variant: 'default' },
        environmentName: null,
        goal: null,
      },
      createTime: 1774958400,
      updateTime: 1774958400,
    },
    entries: [],
    queuedCommands,
    modelInvocation: null,
    toolInvocations: [],
    modelAttemptFailures: [],
    stopReceipts: [],
    manualCompaction: { available: false, disabledReason: null },
  }
}

describe('useThreadProjection queuedCustomMessages derivation', () => {
  let sockets: FakeWebSocketHarness
  let client: QueryClient

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
  })

  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>
      <ApplicationEventProvider url="ws://test/events/v1" socketFactory={sockets.factory}>
        {children}
      </ApplicationEventProvider>
    </QueryClientProvider>
  )

  it('从 snapshot queuedCommands 派生只读 QUEUED CUSTOM_MESSAGE，不进入可编辑 timeline.queuedMessages', async () => {
    vi.spyOn(harnessService, 'getThreadSnapshot').mockResolvedValue(
      createSnapshot([
        {
          threadId: THREAD_ID,
          sequence: '1',
          type: 'CUSTOM_MESSAGE',
          state: 'QUEUED',
          idempotencyKey: 'custom-1',
          payloadJson: JSON.stringify({
            message: {
              role: 'USER',
              contents: [{ type: 'text', text: '补充要求：增加边界测试' }],
            },
          }),
          cancelledAt: null,
          createTime: 1774958410,
        },
        {
          threadId: THREAD_ID,
          sequence: '2',
          type: 'USER_MESSAGE',
          state: 'QUEUED',
          idempotencyKey: 'user-1',
          payloadJson: JSON.stringify({
            message: {
              role: 'USER',
              contents: [{ type: 'text', text: '用户正常输入' }],
            },
          }),
          cancelledAt: null,
          createTime: 1774958420,
        },
      ]),
    )

    const { result } = renderHook(() => useThreadProjection(THREAD_ID), { wrapper })
    await waitFor(() => expect(result.current.snapshotQuery.isSuccess).toBe(true))

    // queuedCustomMessages 包含只读派生内容
    expect(result.current.queuedCustomMessages).toEqual([
      {
        id: 'custom-1',
        sequence: '1',
        text: '补充要求：增加边界测试',
      },
    ])

    // 不进入可编辑 timeline.queuedMessages
    expect(result.current.timeline.queuedMessages.some((msg) => msg.text.includes('补充要求'))).toBe(false)
  })

  it('应用后（非 QUEUED）快照更新，派生列表中自动移除该补充要求', async () => {
    const getSnapshot = vi.spyOn(harnessService, 'getThreadSnapshot').mockResolvedValue(
      createSnapshot([
        {
          threadId: THREAD_ID,
          sequence: '1',
          type: 'CUSTOM_MESSAGE',
          state: 'QUEUED',
          idempotencyKey: 'custom-1',
          payloadJson: JSON.stringify({
            message: {
              role: 'USER',
              contents: [{ type: 'text', text: '待处理补充要求' }],
            },
          }),
          cancelledAt: null,
          createTime: 1774958410,
        },
      ]),
    )

    const { result } = renderHook(() => useThreadProjection(THREAD_ID), { wrapper })
    await waitFor(() => expect(result.current.snapshotQuery.isSuccess).toBe(true))
    expect(result.current.queuedCustomMessages).toHaveLength(1)

    // 模拟应用后快照更新：该命令状态变为 APPLIED
    getSnapshot.mockResolvedValue(
      createSnapshot([
        {
          threadId: THREAD_ID,
          sequence: '1',
          type: 'CUSTOM_MESSAGE',
          state: 'APPLIED',
          idempotencyKey: 'custom-1',
          payloadJson: JSON.stringify({
            message: {
              role: 'USER',
              contents: [{ type: 'text', text: '待处理补充要求' }],
            },
          }),
          cancelledAt: null,
          createTime: 1774958410,
        },
      ]),
    )

    await result.current.snapshotQuery.refetch()
    await waitFor(() => expect(result.current.queuedCustomMessages).toHaveLength(0))
  })
})
