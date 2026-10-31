import { act, renderHook, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { useRootThreadControl } from '@/features/chat/runtime/useRootThreadControl'
import { harnessService } from '@/shared/api/harness-service'
import type { ThreadProjection } from '@/features/chat/runtime/useThreadProjection'
import type { PaneTarget } from '@/features/chat/runtime/agent-pane'
import type { HarnessThreadDTO } from '@/shared/api/generated/contracts'

function makeThread(threadId: string): HarnessThreadDTO {
  return {
    threadId,
    parentThreadId: null,
    name: `Thread ${threadId}`,
    sessionId: `session-${threadId}`,
    headEntryId: 'entry-1',
    rootThreadId: threadId,
    yoloEnabled: false,
    nextCommandSequence: '1',
    version: '3',
    status: 'IDLE',
    executionControl: 'AUTONOMOUS',
    branchSettings: {
      agentName: 'assistant',
      model: { modelName: 'test-model', variant: 'default' },
      environmentName: null,
      goal: null,
    },
    createTime: 1000,
    updateTime: 1000,
  }
}

function makeMockProjection(threadId: string): ThreadProjection {
  const thread = makeThread(threadId)
  return {
    threadId,
    sessionId: thread.sessionId,
    agents: [],
    models: [],
    thread,
    bound: true,
    isRoot: true,
    title: thread.name,
    timeline: { messages: [], queuedMessages: [], hasPendingInputs: false },
    events: [],
    runtimeLabels: { environmentName: null, contextWindow: undefined },
    branchUsage: null,
    working: false,
    entries: [],
    messagesLoading: false,
    messagesError: null,
    bodyRef: { current: null },
    queuedCommands: [],
    queuedCustomMessages: [],
    stopReceipts: [],
    unconfirmedExecutions: [],
    activeAssistantOutput: null,
    toolInvocations: [],
    generationFailures: [],
    snapshotQuery: {
      isSuccess: true,
      isLoading: false,
      error: null,
      refetch: async () => {},
    } as never,
  }
}

import { ApplicationEventProvider } from '@/shared/app-events'
import { FakeWebSocketHarness } from '@/shared/app-events/__tests__/fake-websocket'

const mockDefaults = {
  agentName: 'assistant',
  environmentName: null,
  yoloEnabled: false,
  model: { modelName: 'test-model', variant: 'default' },
}

describe('useRootThreadControl advisory query lifecycle', () => {
  let queryClient: QueryClient
  let sockets: FakeWebSocketHarness

  beforeEach(() => {
    vi.restoreAllMocks()
    sockets = new FakeWebSocketHarness()
    queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false, staleTime: Infinity },
        mutations: { retry: false },
      },
    })
  })

  function wrapper({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={queryClient}>
        <ApplicationEventProvider url="ws://test/events/v1" socketFactory={sockets.factory}>
          {children}
        </ApplicationEventProvider>
      </QueryClientProvider>
    )
  }

  function renderControl(
    initialTarget: PaneTarget,
    initialProjection: ThreadProjection,
    setTarget: (next: PaneTarget) => void = () => {},
  ) {
    return renderHook(
      (props: { target: PaneTarget; projection: ThreadProjection }) =>
        useRootThreadControl({
          paneId: 'pane-1',
          agents: [],
          environments: [],
          focused: true,
          defaults: mockDefaults,
          target: props.target,
          projection: props.projection,
          setTarget,
        }),
      {
        wrapper,
        initialProps: { target: initialTarget, projection: initialProjection },
      },
    )
  }

  it('keeps zero advisory HTTP requests when command palette is closed', async () => {
    const threadId = 'thread-1'
    const target: PaneTarget = { kind: 'BOUND_THREAD', threadId }
    const projection = makeMockProjection(threadId)
    const spy = vi.spyOn(harnessService, 'getCompactionAvailability')

    renderControl(target, projection)

    // 未开启命令面板时，零请求
    expect(spy).not.toHaveBeenCalled()
  })

  it('queries compaction availability on command palette open, shows checking state while loading, and adopts advisory on success', async () => {
    const threadId = 'thread-1'
    const target: PaneTarget = { kind: 'BOUND_THREAD', threadId }
    const projection = makeMockProjection(threadId)

    let resolveAdvisory!: (val: { available: boolean; disabledReason: string | null }) => void
    const advisoryPromise = new Promise<{ available: boolean; disabledReason: string | null }>(
      (resolve) => {
        resolveAdvisory = resolve
      },
    )
    const spy = vi.spyOn(harnessService, 'getCompactionAvailability').mockReturnValueOnce(advisoryPromise)

    const { result } = renderControl(target, projection)

    expect(spy).not.toHaveBeenCalled()

    // 打开命令面板
    act(() => {
      result.current.composer.onCommandPaletteVisibilityChange?.(true)
    })

    // 请求触发
    expect(spy).toHaveBeenCalledWith(threadId, expect.anything())

    // 加载期间，compact 处于本地化 checking 状态
    const checkingCompact = result.current.composer.commands!.find((c) => c.id === 'compact')
    expect(checkingCompact?.disabled).toBe(true)
    expect(checkingCompact?.disabledReason).toBe('正在检查压缩可用性...')

    // 成功返回
    await act(async () => {
      resolveAdvisory({ available: true, disabledReason: null })
      await advisoryPromise
    })

    await waitFor(() => {
      const compact = result.current.composer.commands!.find((c) => c.id === 'compact')
      expect(compact?.disabled).toBe(false)
    })
  })

  it('shows localized failure state if advisory query fails', async () => {
    const threadId = 'thread-1'
    const target: PaneTarget = { kind: 'BOUND_THREAD', threadId }
    const projection = makeMockProjection(threadId)

    vi.spyOn(harnessService, 'getCompactionAvailability').mockRejectedValueOnce(
      new Error('Network error'),
    )

    const { result } = renderControl(target, projection)

    act(() => {
      result.current.composer.onCommandPaletteVisibilityChange?.(true)
    })

    await waitFor(() => {
      const compact = result.current.composer.commands!.find((c) => c.id === 'compact')
      expect(compact?.disabled).toBe(true)
      expect(compact?.disabledReason).toBe('检查压缩可用性失败')
    })
  })

  it('resets palette visibility on switching Thread and does not contaminate new target with late advisory responses', async () => {
    const threadId1 = 'thread-1'
    const threadId2 = 'thread-2'
    const currentTarget1: PaneTarget = { kind: 'BOUND_THREAD', threadId: threadId1 }
    const currentProjection1 = makeMockProjection(threadId1)
    const currentTarget2: PaneTarget = { kind: 'BOUND_THREAD', threadId: threadId2 }
    const currentProjection2 = makeMockProjection(threadId2)

    let resolveLate1!: (val: { available: boolean; disabledReason: string | null }) => void
    const latePromise1 = new Promise<{ available: boolean; disabledReason: string | null }>((resolve) => {
      resolveLate1 = resolve
    })

    vi.spyOn(harnessService, 'getCompactionAvailability').mockImplementation(async (tid) => {
      if (tid === threadId1) {
        return latePromise1
      }
      return { available: false, disabledReason: 'THREAD_BUSY' }
    })

    const { result, rerender } = renderControl(currentTarget1, currentProjection1)

    // 打开 thread-1 的命令面板
    act(() => {
      result.current.composer.onCommandPaletteVisibilityChange?.(true)
    })
    expect(harnessService.getCompactionAvailability).toHaveBeenCalledWith(threadId1, expect.anything())

    // 切换到 thread-2
    rerender({ target: currentTarget2, projection: currentProjection2 })

    // 此时 thread-1 的响应迟到返回
    await act(async () => {
      resolveLate1({ available: true, disabledReason: null })
      await latePromise1
    })

    // 切换后 palette 自动重置为关闭，thread-2 上零新增请求
    expect(harnessService.getCompactionAvailability).not.toHaveBeenCalledWith(threadId2, expect.anything())

    // thread-2 的 compact 命令不被 thread-1 的迟到 true 污染，仍为未开启面板的 checking 状态
    const compact2 = result.current.composer.commands!.find((c) => c.id === 'compact')
    expect(compact2?.disabled).toBe(true)
    expect(compact2?.disabledReason).toBe('正在检查压缩可用性...')
  })

  it('submits compact POST guarded by server authority with expectedVersion', async () => {
    const threadId = 'thread-1'
    const target: PaneTarget = { kind: 'BOUND_THREAD', threadId }
    const projection = makeMockProjection(threadId)

    vi.spyOn(harnessService, 'compactThread').mockResolvedValueOnce({
      thread: makeThread(threadId),
      turnStartEntryId: 't-start',
      childThreadId: null,
    })

    const { result } = renderControl(target, projection)

    await act(async () => {
      await result.current.composer.onCommand({
        id: 'compact',
        label: 'Compact',
        description: 'Compact thread',
        disabled: false,
        disabledReason: undefined,
      })
    })

    expect(harnessService.compactThread).toHaveBeenCalledWith(threadId, {
      expectedVersion: '3',
    })
  })
})
