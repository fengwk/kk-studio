import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useAgentThreadController } from '@/features/ai/useAgentThreadController'
import { agentService } from '@/shared/api/agent-service'
import { ApiError } from '@/shared/api/client'
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
    getSession: vi.fn(),
    listSessionEntries: vi.fn(),
    getThread: vi.fn(),
    listThreadEntries: vi.fn(),
    listThreadInputs: vi.fn(),
    submitThreadMessage: vi.fn(),
    createThreadRealtimeStream: vi.fn(),
    setThreadYolo: vi.fn(),
    getThreadUsage: vi.fn(),
    listThreadToolInvocations: vi.fn(),
    setThreadAgent: vi.fn(),
    setThreadModel: vi.fn(),
    stopThread: vi.fn(),
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
          modelId: 'm1',
          variant: 'default',
          config: {
            environmentName: null,
            tools: [],
            skills: []
          },
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
          config: {
            limit: { context: 128000, output: 8192 },
            abilities: { tools: true, reasoning: false, inputModalities: ['TEXT'] },
            pricing: {
              currency: 'USD',
              pricingTier: 'default',
              serviceTier: 'default',
              serviceTierMultiplier: 1,
              version: 'v1',
              inputPerMillionTokens: 0,
              outputPerMillionTokens: 0,
              cacheReadPerMillionTokens: 0,
              cacheWritePerMillionTokens: 0,
              cacheWriteLongPerMillionTokens: 0,
              reasoningPerMillionTokens: 0,
            },
            defaultVariant: 'default',
            variants: [{ id: 'default' }],
          },
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
          configured: true,
          modelCallTimeoutMillis: 1800000,
          modelCallIdleTimeoutMillis: 120000,
          createTime: null,
          updateTime: null,
        },
      ],
    })
    vi.mocked(harnessService.getSession).mockResolvedValue({
      sessionId: 's1',
      title: 'title',
      createTime: null,
      updateTime: null,
    })
    vi.mocked(harnessService.listSessionEntries).mockResolvedValue([])
    vi.mocked(harnessService.getThread).mockResolvedValue(thread)
    vi.mocked(harnessService.listThreadEntries).mockResolvedValue([])
    vi.mocked(harnessService.listThreadInputs).mockResolvedValue([])
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
    vi.mocked(harnessService.createThreadRealtimeStream).mockReturnValue(new FakeEventSource() as EventSource)
    vi.mocked(harnessService.submitThreadMessage).mockResolvedValue({
      inputId: 'i1',
      threadId: '1',
      sequence: 1,
      inputType: 'user_message',
      payloadJson: '{}',
      clientMessageId: 'cid',
      status: 'QUEUED',
      resolvedAt: null,
      createTime: null,
    })
    vi.mocked(harnessService.setThreadYolo).mockResolvedValue({
      inputId: 'y1',
      threadId: '1',
      sequence: 2,
      inputType: 'set_yolo',
      payloadJson: '{}',
      clientMessageId: 'cid-yolo',
      status: 'QUEUED',
      resolvedAt: null,
      createTime: null,
    })
    vi.mocked(harnessService.setThreadAgent).mockResolvedValue({
      inputId: 'a1',
      threadId: '1',
      sequence: 3,
      inputType: 'SET_AGENT',
      payloadJson: '{}',
      clientMessageId: 'cid-agent',
      status: 'QUEUED',
      resolvedAt: null,
      createTime: null,
    })
    vi.mocked(harnessService.setThreadModel).mockResolvedValue({
      inputId: 'm1',
      threadId: '1',
      sequence: 4,
      inputType: 'SET_MODEL',
      payloadJson: '{}',
      clientMessageId: 'cid-model',
      status: 'QUEUED',
      resolvedAt: null,
      createTime: null,
    })
    vi.mocked(harnessService.stopThread).mockResolvedValue({ executionEpoch: 8, cancelledInputs: [] })
  })

  it('submits messages, runs slash commands, and rejects unknown commands', async () => {
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
    // Every mailbox mutation carries the Thread's currently known executionEpoch.
    expect(harnessService.submitThreadMessage).toHaveBeenCalledWith(
      '1',
      expect.objectContaining({ content: 'hello world', expectedExecutionEpoch: 7 }),
    )
    expect(result.current.draft).toBe('')

    act(() => result.current.runCommand({ id: 'yolo', label: 'yolo', description: '' }))
    await waitFor(() => expect(harnessService.setThreadYolo).toHaveBeenCalledWith(
      '1',
      expect.objectContaining({
        yoloEnabled: true,
        clientMessageId: expect.any(String),
        expectedExecutionEpoch: 7,
      }),
    ))

    act(() => result.current.runCommand({ id: 'stop', label: 'stop', description: '' }))
    await waitFor(() =>
      expect(harnessService.stopThread).toHaveBeenCalledWith('1', { expectedExecutionEpoch: 7 }),
    )

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
        status: 'QUEUED',
        resolvedAt: null,
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
        status: 'QUEUED',
        resolvedAt: null,
        createTime: null,
      })
      await promiseB
    })
    expect(result.current.pending).toBe(false)
  })

  it('keeps failed A replay identity when unrelated B succeeds first (out-of-order)', async () => {
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
        status: 'QUEUED',
        resolvedAt: null,
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
        status: 'QUEUED',
        resolvedAt: null,
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

    // Replaying A reuses the same clientMessageId; B success must not have cleared it.
    await act(async () => {
      await result.current.submitMessage()
    })
    expect(harnessService.submitThreadMessage).toHaveBeenCalledTimes(3)
    expect(vi.mocked(harnessService.submitThreadMessage).mock.calls[2][1].clientMessageId).toBe(idA)
    expect(vi.mocked(harnessService.submitThreadMessage).mock.calls[2][1].content).toBe('message-A')
  })

  it('keeps failed A replay identity when A fails before unrelated B succeeds', async () => {
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
        status: 'QUEUED',
        resolvedAt: null,
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
        status: 'QUEUED',
        resolvedAt: null,
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

  it('restores failed content with the same clientMessageId and resets replay identity after edit', async () => {
    vi.mocked(harnessService.submitThreadMessage)
      .mockRejectedValueOnce(new Error('queue full'))
      .mockResolvedValueOnce({
        inputId: 'i2',
        threadId: '1',
        sequence: 2,
        inputType: 'user_message',
        payloadJson: '{}',
        clientMessageId: 'retry',
        status: 'QUEUED',
        resolvedAt: null,
        createTime: null,
      })
      .mockResolvedValueOnce({
        inputId: 'i3',
        threadId: '1',
        sequence: 3,
        inputType: 'user_message',
        payloadJson: '{}',
        clientMessageId: 'new',
        status: 'QUEUED',
        resolvedAt: null,
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
    expect(result.current.runtimeLabels.modelName).toBe('minimax/MiniMax-M2.7')
    expect(result.current.runtimeLabels.providerName).toBe('minimax')
    expect(result.current.runtimeLabels.contextWindow).toBe(128000)
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

  it('surfaces stop failures without draft rewrite, then clears message replay identity on success', async () => {
    vi.mocked(harnessService.getThread).mockResolvedValue({ ...thread, status: 'RUNNING' } as never)
    vi.mocked(harnessService.stopThread)
      .mockRejectedValueOnce(new Error('network unavailable'))
      .mockResolvedValueOnce({ executionEpoch: 8, cancelledInputs: [] })
    const { result } = renderHook(() => useAgentThreadController('1'), { wrapper })
    await waitFor(() => expect(result.current.disabled).toBe(false))
    act(() => result.current.setDraft('current draft'))
    await act(async () => { await result.current.stopThread() })
    expect(result.current.actionError).toContain('network unavailable')
    expect(result.current.draft).toBe('current draft')
    expect(harnessService.stopThread).toHaveBeenCalledWith('1', { expectedExecutionEpoch: 7 })
    await act(async () => { await result.current.stopThread() })
    expect(result.current.draft).toBe('current draft')
    expect(vi.mocked(harnessService.stopThread)).toHaveBeenCalledTimes(2)
  })

  it('treats an UNBOUND Thread as a legal but non-writable state', async () => {
    vi.mocked(harnessService.getThread).mockResolvedValue({
      ...thread,
      sessionId: null,
      sessionTitle: null,
      headEntryId: null,
      status: 'UNBOUND',
    } as never)
    const { result } = renderHook(() => useAgentThreadController('1'), { wrapper })
    await waitFor(() => expect(result.current.thread?.status).toBe('UNBOUND'))

    // The composer is disabled and `bound` is false, but the pane still renders the Thread.
    expect(result.current.bound).toBe(false)
    expect(result.current.disabled).toBe(true)
    expect(result.current.sessionId).toBe('')

    act(() => result.current.setDraft('should not be sent'))
    await act(async () => { await result.current.submitMessage() })
    expect(harnessService.submitThreadMessage).not.toHaveBeenCalled()
    expect(result.current.actionError).toContain('未绑定 Session')

    // Configuration mutations are blocked client-side too: UNBOUND rejects every mailbox Input.
    await act(async () => { await result.current.setThreadAgent('agent-1') })
    expect(harnessService.setThreadAgent).not.toHaveBeenCalled()
    await act(async () => { await result.current.setThreadModel('m1', 'default') })
    expect(harnessService.setThreadModel).not.toHaveBeenCalled()
    await act(async () => { await result.current.stopThread() })
    expect(harnessService.stopThread).not.toHaveBeenCalled()
    act(() => result.current.runCommand({ id: 'yolo', label: 'yolo', description: '' }))
    expect(harnessService.setThreadYolo).not.toHaveBeenCalled()
  })

  it('reports a 409 as a stale-epoch conflict and refetches the Thread instead of swallowing it', async () => {
    vi.mocked(harnessService.submitThreadMessage).mockRejectedValueOnce(
      new ApiError('expected execution epoch mismatch', 409),
    )
    const { result } = renderHook(() => useAgentThreadController('1'), { wrapper })
    await waitFor(() => expect(result.current.disabled).toBe(false))
    const threadCallsBefore = vi.mocked(harnessService.getThread).mock.calls.length

    act(() => result.current.setDraft('stale message'))
    await act(async () => { await result.current.submitMessage() })

    expect(result.current.actionError).toContain('Thread 状态已变化')
    expect(result.current.actionError).toContain('expected execution epoch mismatch')
    expect(result.current.actionError).toContain('请重试')
    // The Thread detail query is invalidated so the retry carries the fresh epoch.
    await waitFor(() =>
      expect(vi.mocked(harnessService.getThread).mock.calls.length).toBeGreaterThan(threadCallsBefore),
    )
    // The failed draft is still restored for an explicit retry.
    expect(result.current.draft).toBe('stale message')
  })

  it('reports a 409 on config mutations with the action that failed', async () => {
    vi.mocked(harnessService.setThreadAgent).mockRejectedValueOnce(
      new ApiError('thread is not idle', 409),
    )
    const { result } = renderHook(() => useAgentThreadController('1'), { wrapper })
    await waitFor(() => expect(result.current.disabled).toBe(false))

    await act(async () => { await result.current.setThreadAgent('agent-1') })
    expect(harnessService.setThreadAgent).toHaveBeenCalledWith(
      '1',
      expect.objectContaining({ agentDefinitionId: 'agent-1', expectedExecutionEpoch: 7 }),
    )
    expect(result.current.actionError).toContain('切换 Agent 失败')
    expect(result.current.actionError).toContain('thread is not idle')
  })

  it('keeps non-409 failures as plain messages without conflict wording', async () => {
    vi.mocked(harnessService.setThreadModel).mockRejectedValueOnce(
      new ApiError('model not found', 404),
    )
    const { result } = renderHook(() => useAgentThreadController('1'), { wrapper })
    await waitFor(() => expect(result.current.disabled).toBe(false))

    await act(async () => { await result.current.setThreadModel('m1', 'default') })
    expect(result.current.actionError).toBe('model not found')
    expect(result.current.actionError).not.toContain('Thread 状态已变化')
  })
})

const thread = {
  threadId: '1',
  sessionId: 's1',
  sessionTitle: 'title',
  headEntryId: 'h1',
  executionEpoch: 7,
  status: 'IDLE' as const,
  inputSequence: 0,
  activeAgentDefinitionId: 'agent-1',
  activeAgentName: 'assistant',
  modelId: 'm1',
  variant: 'default',
  yoloEnabled: false,
  processing: false,
  createTime: null,
  updateTime: null,
}
