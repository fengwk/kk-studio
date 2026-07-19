import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useAgentThreadController } from '@/features/ai/useAgentThreadController'
import { agentService } from '@/shared/api/agent-service'
import { harnessService } from '@/shared/api/harness-service'

vi.mock('@/shared/api/agent-service', () => ({
  agentService: {
    listAgents: vi.fn(),
    listModels: vi.fn(),
    listProviders: vi.fn(),
  },
}))

vi.mock('@/shared/api/harness-service', () => ({
  harnessService: {
    listThreads: vi.fn(),
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
    getThreadUsage: vi.fn(),
    listThreadToolInvocations: vi.fn(),
    decideToolInvocation: vi.fn(),
    stopThread: vi.fn(),
    retryThread: vi.fn(),
  },
}))

class FakeEventSource {
  addEventListener() {}
  close() {}
}

function wrapper({ children }: { children: ReactNode }) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>
}

describe('useAgentThreadController', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(agentService.listAgents).mockResolvedValue({
      pageNumber: 1,
      pageSize: 50,
      totalCount: 1,
      results: [
        {
          id: 'agent-1',
          name: 'assistant',
          description: null,
          systemPrompt: null,
          defaultProviderId: 'p1',
          defaultProviderName: 'minimax',
          defaultModelId: 'm1',
          defaultModelName: 'MiniMax-M2.7',
          defaultVariant: 'default',
          toolsJson: null,
          createTime: null,
          updateTime: null,
        },
      ],
    })
    vi.mocked(agentService.listModels).mockResolvedValue({
      pageNumber: 1,
      pageSize: 50,
      totalCount: 1,
      results: [
        {
          id: 'm1',
          providerId: 'p1',
          providerName: 'minimax',
          name: 'MiniMax-M2.7',
          description: null,
          defaultVariant: 'default',
          variantsJson: null,
          createTime: null,
          updateTime: null,
        },
      ],
    })
    vi.mocked(agentService.listProviders).mockResolvedValue({
      pageNumber: 1,
      pageSize: 50,
      totalCount: 1,
      results: [
        {
          id: 'p1',
          name: 'minimax',
          description: null,
          providerType: 'openai-compatible',
          baseUrl: null,
          apiKey: null,
          timeoutMillis: null,
          createTime: null,
          updateTime: null,
        },
      ],
    })
    vi.mocked(harnessService.listThreads).mockResolvedValue([thread])
    vi.mocked(harnessService.listSessionThreads).mockResolvedValue([thread])
    vi.mocked(harnessService.listSessionEntries).mockResolvedValue([])
    vi.mocked(harnessService.getThread).mockResolvedValue(thread)
    vi.mocked(harnessService.listThreadEntries).mockResolvedValue([])
    vi.mocked(harnessService.listThreadInputs).mockResolvedValue([])
    vi.mocked(harnessService.listThreadEvents).mockResolvedValue([])
    vi.mocked(harnessService.listRootActivities).mockResolvedValue([])
    vi.mocked(harnessService.listSessionTasks).mockResolvedValue([])
    vi.mocked(harnessService.getThreadUsage).mockResolvedValue({
      scopeType: 'thread',
      scopeId: '1',
      recordCount: 0,
      inputTokens: 0,
      outputTokens: 0,
      cacheReadTokens: 0,
      cacheWriteTokens: 0,
      cacheWriteLongTokens: 0,
      reasoningTokens: 0,
      providerTotalTokens: 0,
      cacheEligibleRecordCount: 0,
      cacheHitRecordCount: 0,
      cacheHitRatio: 0,
      tokenReadRatio: 0,
      unamortizedCacheWriteTokens: 0,
      costs: [],
    })
    vi.mocked(harnessService.listThreadToolInvocations).mockResolvedValue([])
    vi.mocked(harnessService.createThreadEventStream).mockReturnValue(new FakeEventSource() as EventSource)
    vi.mocked(harnessService.submitThreadMessage).mockResolvedValue({
      inputId: 'i1',
      threadId: '1',
      sequence: 1,
      inputType: 'user_message',
      payloadJson: '{}',
      clientMessageId: 'cid',
      appliedEntryId: null,
      appliedAt: null,
      createTime: null,
    })
    vi.mocked(harnessService.setThreadYolo).mockResolvedValue({
      inputId: 'y1',
      threadId: '1',
      sequence: 2,
      inputType: 'set_yolo',
      payloadJson: '{}',
      clientMessageId: null,
      appliedEntryId: null,
      appliedAt: null,
      createTime: null,
    })
    vi.mocked(harnessService.stopThread).mockResolvedValue({ stopId: 'stop-1', cancelledInputs: [], restoredMessages: ['queued A', 'queued B'] })
    vi.mocked(harnessService.retryThread).mockResolvedValue({ ...thread, status: 'RETRYING' } as never)
  })

  it('submits messages, runs yolo/clear commands, and rejects unknown commands', async () => {
    const { result } = renderHook(() => useAgentThreadController('1'), { wrapper })
    await waitFor(() => expect(result.current.disabled).toBe(false))

    act(() => result.current.setDraft('  '))
    await act(async () => {
      await result.current.submitMessage()
    })
    expect(harnessService.submitThreadMessage).not.toHaveBeenCalled()

    act(() => result.current.setDraft('hello world'))
    await act(async () => {
      await result.current.submitMessage()
    })
    expect(harnessService.submitThreadMessage).toHaveBeenCalled()
    expect(result.current.draft).toBe('')

    act(() => result.current.runCommand({ id: 'yolo', label: 'yolo', description: '' }))
    await waitFor(() => expect(harnessService.setThreadYolo).toHaveBeenCalledWith('1', { yoloEnabled: true }))

    act(() => result.current.setDraft('keep'))
    act(() => result.current.runCommand({ id: 'clear-draft', label: 'clear', description: '' }))
    expect(result.current.draft).toBe('')

    act(() => result.current.runCommand({ id: 'unknown', label: 'x', description: '' }))
    expect(result.current.actionError).toContain('未知命令')
    act(() => result.current.dismissActionError())
    expect(result.current.actionError).toBeNull()
  })

  it('allows concurrent submits with distinct clientMessageIds and tracks pending across overlap', async () => {
    let resolveA: (value: unknown) => void = () => {}
    let resolveB: (value: unknown) => void = () => {}
    const deferredA = new Promise((resolve) => {
      resolveA = resolve
    })
    const deferredB = new Promise((resolve) => {
      resolveB = resolve
    })
    vi.mocked(harnessService.submitThreadMessage)
      .mockImplementationOnce(() => deferredA as never)
      .mockImplementationOnce(() => deferredB as never)

    const { result } = renderHook(() => useAgentThreadController('1'), { wrapper })
    await waitFor(() => expect(result.current.disabled).toBe(false))

    let promiseA!: Promise<void>
    let promiseB!: Promise<void>
    act(() => result.current.setDraft('message-A'))
    act(() => {
      void (promiseA = result.current.submitMessage())
    })
    expect(result.current.draft).toBe('')
    expect(result.current.pending).toBe(true)

    act(() => result.current.setDraft('message-B'))
    act(() => {
      void (promiseB = result.current.submitMessage())
    })
    expect(result.current.draft).toBe('')
    expect(result.current.pending).toBe(true)

    await waitFor(() => expect(harnessService.submitThreadMessage).toHaveBeenCalledTimes(2))
    const firstId = vi.mocked(harnessService.submitThreadMessage).mock.calls[0][1].clientMessageId
    const secondId = vi.mocked(harnessService.submitThreadMessage).mock.calls[1][1].clientMessageId
    expect(firstId).toBeTruthy()
    expect(secondId).toBeTruthy()
    expect(firstId).not.toBe(secondId)

    await act(async () => {
      resolveA({
        inputId: 'i1',
        threadId: '1',
        sequence: 1,
        inputType: 'user_message',
        payloadJson: '{}',
        clientMessageId: firstId,
        appliedEntryId: null,
        appliedAt: null,
        createTime: null,
      })
      await promiseA
    })
    // B still in flight keeps pending true.
    expect(result.current.pending).toBe(true)

    await act(async () => {
      resolveB({
        inputId: 'i2',
        threadId: '1',
        sequence: 2,
        inputType: 'user_message',
        payloadJson: '{}',
        clientMessageId: secondId,
        appliedEntryId: null,
        appliedAt: null,
        createTime: null,
      })
      await promiseB
    })
    expect(result.current.pending).toBe(false)
  })

  it('keeps failed A retry identity when unrelated B succeeds first (out-of-order)', async () => {
    let rejectA: (reason?: unknown) => void = () => {}
    let resolveB: (value: unknown) => void = () => {}
    const deferredA = new Promise((_resolve, reject) => {
      rejectA = reject
    })
    const deferredB = new Promise((resolve) => {
      resolveB = resolve
    })
    vi.mocked(harnessService.submitThreadMessage)
      .mockImplementationOnce(() => deferredA as never)
      .mockImplementationOnce(() => deferredB as never)
      .mockResolvedValueOnce({
        inputId: 'i3',
        threadId: '1',
        sequence: 3,
        inputType: 'user_message',
        payloadJson: '{}',
        clientMessageId: 'retry',
        appliedEntryId: null,
        appliedAt: null,
        createTime: null,
      })

    const { result } = renderHook(() => useAgentThreadController('1'), { wrapper })
    await waitFor(() => expect(result.current.disabled).toBe(false))

    let promiseA!: Promise<void>
    let promiseB!: Promise<void>
    act(() => result.current.setDraft('message-A'))
    act(() => {
      void (promiseA = result.current.submitMessage())
    })
    act(() => result.current.setDraft('message-B'))
    act(() => {
      void (promiseB = result.current.submitMessage())
    })
    await waitFor(() => expect(harnessService.submitThreadMessage).toHaveBeenCalledTimes(2))
    const idA = vi.mocked(harnessService.submitThreadMessage).mock.calls[0][1].clientMessageId

    await act(async () => {
      resolveB({
        inputId: 'i2',
        threadId: '1',
        sequence: 2,
        inputType: 'user_message',
        payloadJson: '{}',
        clientMessageId: 'cid-b',
        appliedEntryId: null,
        appliedAt: null,
        createTime: null,
      })
      await promiseB
    })
    await act(async () => {
      rejectA(new Error('A failed'))
      await promiseA
    })
    expect(result.current.actionError).toContain('A failed')
    expect(result.current.draft).toBe('message-A')

    // Retry A reuses the same clientMessageId; B success must not have cleared it.
    await act(async () => {
      await result.current.submitMessage()
    })
    expect(harnessService.submitThreadMessage).toHaveBeenCalledTimes(3)
    expect(vi.mocked(harnessService.submitThreadMessage).mock.calls[2][1].clientMessageId).toBe(idA)
    expect(vi.mocked(harnessService.submitThreadMessage).mock.calls[2][1].content).toBe('message-A')
  })

  it('keeps failed A retry identity when A fails before unrelated B succeeds', async () => {
    let rejectA: (reason?: unknown) => void = () => {}
    let resolveB: (value: unknown) => void = () => {}
    const deferredA = new Promise((_resolve, reject) => {
      rejectA = reject
    })
    const deferredB = new Promise((resolve) => {
      resolveB = resolve
    })
    vi.mocked(harnessService.submitThreadMessage)
      .mockImplementationOnce(() => deferredA as never)
      .mockImplementationOnce(() => deferredB as never)
      .mockResolvedValueOnce({
        inputId: 'i3',
        threadId: '1',
        sequence: 3,
        inputType: 'user_message',
        payloadJson: '{}',
        clientMessageId: 'retry',
        appliedEntryId: null,
        appliedAt: null,
        createTime: null,
      })

    const { result } = renderHook(() => useAgentThreadController('1'), { wrapper })
    await waitFor(() => expect(result.current.disabled).toBe(false))

    let promiseA!: Promise<void>
    let promiseB!: Promise<void>
    act(() => result.current.setDraft('message-A'))
    act(() => {
      void (promiseA = result.current.submitMessage())
    })
    act(() => result.current.setDraft('message-B'))
    act(() => {
      void (promiseB = result.current.submitMessage())
    })
    await waitFor(() => expect(harnessService.submitThreadMessage).toHaveBeenCalledTimes(2))
    const idA = vi.mocked(harnessService.submitThreadMessage).mock.calls[0][1].clientMessageId

    await act(async () => {
      rejectA(new Error('A failed first'))
      await promiseA
    })
    expect(result.current.draft).toBe('message-A')

    await act(async () => {
      resolveB({
        inputId: 'i2',
        threadId: '1',
        sequence: 2,
        inputType: 'user_message',
        payloadJson: '{}',
        clientMessageId: 'cid-b',
        appliedEntryId: null,
        appliedAt: null,
        createTime: null,
      })
      await promiseB
    })
    // B success must not wipe restored A identity.
    expect(result.current.draft).toBe('message-A')

    await act(async () => {
      await result.current.submitMessage()
    })
    expect(harnessService.submitThreadMessage).toHaveBeenCalledTimes(3)
    expect(vi.mocked(harnessService.submitThreadMessage).mock.calls[2][1].clientMessageId).toBe(idA)
  })

  it('restores failed content with the same clientMessageId and resets identity after edit', async () => {
    vi.mocked(harnessService.submitThreadMessage)
      .mockRejectedValueOnce(new Error('queue full'))
      .mockResolvedValueOnce({
        inputId: 'i2',
        threadId: '1',
        sequence: 2,
        inputType: 'user_message',
        payloadJson: '{}',
        clientMessageId: 'retry',
        appliedEntryId: null,
        appliedAt: null,
        createTime: null,
      })
      .mockResolvedValueOnce({
        inputId: 'i3',
        threadId: '1',
        sequence: 3,
        inputType: 'user_message',
        payloadJson: '{}',
        clientMessageId: 'new',
        appliedEntryId: null,
        appliedAt: null,
        createTime: null,
      })

    const { result } = renderHook(() => useAgentThreadController('1'), { wrapper })
    await waitFor(() => expect(result.current.disabled).toBe(false))

    act(() => result.current.setDraft('retry me'))
    await act(async () => {
      await result.current.submitMessage()
    })
    expect(result.current.actionError).toContain('queue full')
    expect(result.current.draft).toBe('retry me')
    const firstId = vi.mocked(harnessService.submitThreadMessage).mock.calls[0][1].clientMessageId

    await act(async () => {
      await result.current.submitMessage()
    })
    expect(harnessService.submitThreadMessage).toHaveBeenCalledTimes(2)
    expect(vi.mocked(harnessService.submitThreadMessage).mock.calls[1][1].clientMessageId).toBe(firstId)

    act(() => result.current.setDraft('changed content'))
    await act(async () => {
      await result.current.submitMessage()
    })
    expect(harnessService.submitThreadMessage).toHaveBeenCalledTimes(3)
    expect(vi.mocked(harnessService.submitThreadMessage).mock.calls[2][1].clientMessageId).not.toBe(firstId)
  })

  it('resolves footer model labels from agent catalog without unknown-model', async () => {
    const { result } = renderHook(() => useAgentThreadController('1'), { wrapper })
    await waitFor(() => expect(result.current.disabled).toBe(false))
    expect(result.current.runtimeLabels.modelName).toBe('MiniMax-M2.7')
    expect(result.current.runtimeLabels.providerName).toBe('minimax')
    expect(result.current.runtimeLabels.modelName).not.toBe('unknown-model')
  })

  it('surfaces submit errors and ignores slash drafts', async () => {
    vi.mocked(harnessService.submitThreadMessage).mockRejectedValueOnce(new Error('queue full'))
    const { result } = renderHook(() => useAgentThreadController('1'), { wrapper })
    await waitFor(() => expect(result.current.disabled).toBe(false))

    act(() => result.current.setDraft('/not-send'))
    await act(async () => {
      await result.current.submitMessage()
    })
    expect(harnessService.submitThreadMessage).not.toHaveBeenCalled()

    act(() => result.current.setDraft('retry me'))
    await act(async () => {
      await result.current.submitMessage()
    })
    expect(result.current.actionError).toContain('queue full')
  })

  it('maps non-Error rejection shapes to actionError text', async () => {
    vi.mocked(harnessService.submitThreadMessage)
      .mockRejectedValueOnce('plain-string-error')
      .mockRejectedValueOnce(new Error('   '))

    const { result } = renderHook(() => useAgentThreadController('1'), { wrapper })
    await waitFor(() => expect(result.current.disabled).toBe(false))

    act(() => result.current.setDraft('first'))
    await act(async () => {
      await result.current.submitMessage()
    })
    expect(result.current.actionError).toBe('plain-string-error')
    expect(result.current.draft).toBe('first')

    act(() => result.current.setDraft('typing-next'))
    await act(async () => {
      await result.current.submitMessage()
    })
    // Blank Error.message falls back to the generic request-failed text.
    expect(result.current.actionError).toBe('请求失败')
    expect(result.current.draft).toBe('typing-next')
  })

  it('does not overwrite an in-progress draft when a prior submit fails late', async () => {
    let rejectDeferred: (reason?: unknown) => void = () => {}
    const deferred = new Promise((_resolve, reject) => {
      rejectDeferred = reject
    })
    vi.mocked(harnessService.submitThreadMessage).mockImplementationOnce(() => deferred as never)

    const { result } = renderHook(() => useAgentThreadController('1'), { wrapper })
    await waitFor(() => expect(result.current.disabled).toBe(false))

    let pending!: Promise<void>
    act(() => result.current.setDraft('failed-later'))
    act(() => {
      void (pending = result.current.submitMessage())
    })
    expect(result.current.draft).toBe('')
    act(() => result.current.setDraft('already-typing'))
    await act(async () => {
      rejectDeferred({ weird: true })
      await pending
    })
    expect(result.current.actionError).toBe('请求失败')
    expect(result.current.draft).toBe('already-typing')
  })

  it('restores a Stop receipt once with a stable request id and does not retry a running Thread', async () => {
    vi.mocked(harnessService.getThread).mockResolvedValue({ ...thread, status: 'RUNNING' } as never)
    const { result } = renderHook(() => useAgentThreadController('1', 's1'), { wrapper })
    await waitFor(() => expect(result.current.disabled).toBe(false))
    act(() => result.current.setDraft('current draft'))
    await act(async () => { await result.current.stopThread() })
    expect(result.current.draft).toBe('current draft\n\nqueued A\n\nqueued B')
    const requestId = vi.mocked(harnessService.stopThread).mock.calls[0][1].clientRequestId
    await act(async () => { await result.current.stopThread() })
    expect(vi.mocked(harnessService.stopThread).mock.calls[1][1].clientRequestId).toBe(requestId)
    expect(result.current.draft).toBe('current draft\n\nqueued A\n\nqueued B')
    await act(async () => { await result.current.retryThread() })
    expect(harnessService.retryThread).not.toHaveBeenCalled()
  })

  it('submits Retry only for a FAILED Thread', async () => {
    vi.mocked(harnessService.getThread).mockResolvedValue({ ...thread, status: 'FAILED' } as never)
    const { result } = renderHook(() => useAgentThreadController('1', 's1'), { wrapper })
    await waitFor(() => expect(result.current.thread?.status).toBe('FAILED'))
    await act(async () => { await result.current.retryThread() })
    expect(harnessService.retryThread).toHaveBeenCalledWith('1')
  })
})

const thread = {
  threadId: '1',
  sessionId: 's1',
  sessionTitle: 'title',
  headEntryId: 'h1',
  agentDefinitionId: 'agent-1',
  runtimeConfigJson: null,
  yoloEnabled: false,
  inputSequence: 0,
  processing: false,
  createTime: null,
  updateTime: null,
}
