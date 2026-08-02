import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useAgentThreadController } from '@/features/ai/runtime/useAgentThreadController'
import { agentService } from '@/shared/api/agent-service'
import { ApiError } from '@/shared/api/client'
import { harnessService } from '@/shared/api/harness-service'
import type { HarnessThreadDTO } from '@/shared/api/contracts/ai-runtime'

vi.mock('@/shared/api/agent-service', () => ({
  agentService: {
    listAgents: vi.fn(),
    listModels: vi.fn(),
  },
}))

vi.mock('@/shared/api/harness-service', () => ({
  harnessService: {
    getThreadSnapshot: vi.fn(),
    submitThreadMessage: vi.fn(),
    submitCustomMessage: vi.fn(),
    createThreadRealtimeStream: vi.fn(),
    stopThread: vi.fn(),
  },
}))

class FakeEventSource {
  private readonly listeners = new Map<string, EventListener[]>()

  addEventListener(type: string, listener: EventListener) {
    const existing = this.listeners.get(type) ?? []
    existing.push(listener)
    this.listeners.set(type, existing)
  }

  emitRealtime(data: string) {
    for (const listener of this.listeners.get('realtime') ?? []) {
      listener({ data } as MessageEvent<string>)
    }
  }

  close() {}
}

let realtimeSource: FakeEventSource

function wrapper({ children }: { children: ReactNode }) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>
}

describe('useAgentThreadController', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    realtimeSource = new FakeEventSource()
    vi.mocked(agentService.listAgents).mockResolvedValue({
      pageNumber: 1,
      pageSize: 50,
      totalCount: 1,
      results: [
        {
          name: 'assistant',
          description: null,
          systemPrompt: null,
          model: 'minimax/MiniMax-M2.7',
          variant: 'default',
          config: {
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
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(snapshot(thread))
    vi.mocked(harnessService.createThreadRealtimeStream).mockReturnValue(realtimeSource as EventSource)
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
    vi.mocked(harnessService.submitCustomMessage).mockResolvedValue({
      inputId: 'custom-i1',
      threadId: '1',
      sequence: 1,
      inputType: 'CUSTOM_MESSAGE',
      payloadJson: '{}',
      clientMessageId: 'cid',
      status: 'QUEUED',
      resolvedAt: null,
      createTime: null,
    })
    vi.mocked(harnessService.stopThread).mockResolvedValue({ executionEpoch: 8, cancelledInputs: [] })
  })

  it('submits messages, runs slash commands, and rejects unknown commands', async () => {
    const { result } = renderHook(
      () => useAgentThreadController('1', '', undefined, {
        agentName: 'assistant',
        environmentName: 'local',
        yoloEnabled: true,
      }),
      { wrapper },
    )
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
      expect.objectContaining({
        content: 'hello world',
        agentName: 'assistant',
        environmentName: 'local',
        yoloEnabled: true,
        expectedExecutionEpoch: 7,
      }),
    )
    expect(result.current.draft).toBe('')

    act(() => result.current.runCommand({ id: 'stop', label: 'stop', description: '' }))
    await waitFor(() =>
      expect(harnessService.stopThread).toHaveBeenCalledWith('1', { expectedExecutionEpoch: 7 }),
    )

    act(() => result.current.runCommand({ id: 'unknown', label: 'x', description: '' }))
    expect(result.current.actionError).toContain('未知命令')
    act(() => result.current.dismissActionError())
    expect(result.current.actionError).toBeNull()
  })

  it('retries a recovered first-send message on the same Thread with the same clientMessageId', async () => {
    vi.mocked(harnessService.submitThreadMessage)
      .mockRejectedValueOnce(new Error('first message failed'))
      .mockResolvedValueOnce({
        inputId: 'retry-input',
        threadId: '1',
        sequence: 2,
        inputType: 'USER_MESSAGE',
        payloadJson: '{}',
        clientMessageId: 'cid-first-send',
        status: 'QUEUED',
        resolvedAt: null,
        createTime: null,
      })

    const { result } = renderHook(
      () =>
        useAgentThreadController('1', 'retry me', {
          content: 'retry me',
          clientMessageId: 'cid-first-send',
          kind: 'USER_MESSAGE',
          role: 'user',
          agentName: 'assistant',
          environmentName: null,
          yoloEnabled: false,
          firstSendContext: { chatId: 'chat-1' },
        }, {
          agentName: 'assistant',
          environmentName: null,
          yoloEnabled: false,
        }),
      { wrapper },
    )
    await waitFor(() => expect(result.current.disabled).toBe(false))
    await waitFor(() => expect(result.current.draft).toBe('retry me'))

    await act(async () => {
      await result.current.submitMessage()
    })
    expect(result.current.draft).toBe('retry me')
    expect(harnessService.submitThreadMessage).toHaveBeenNthCalledWith(
      1,
      '1',
      expect.objectContaining({
        content: 'retry me',
        clientMessageId: 'cid-first-send',
      }),
    )

    await act(async () => {
      await result.current.submitMessage()
    })
    expect(harnessService.submitThreadMessage).toHaveBeenNthCalledWith(
      2,
      '1',
      expect.objectContaining({
        content: 'retry me',
        clientMessageId: 'cid-first-send',
      }),
    )
  })

  it('retries an identical CUSTOM_MESSAGE with the same id and role', async () => {
    vi.mocked(harnessService.submitCustomMessage)
      .mockRejectedValueOnce(new Error('custom response lost'))
      .mockResolvedValueOnce({
        inputId: 'custom-retry',
        threadId: '1',
        sequence: 2,
        inputType: 'CUSTOM_MESSAGE',
        payloadJson: '{}',
        clientMessageId: 'cid-custom',
        status: 'QUEUED',
        resolvedAt: null,
        createTime: null,
      })

    const { result } = renderHook(
      () =>
        useAgentThreadController(
          '1',
          'system instruction',
          {
            kind: 'CUSTOM_MESSAGE',
            role: 'system',
            content: 'system instruction',
            agentName: 'assistant',
            environmentName: null,
            yoloEnabled: false,
            firstSendContext: null,
            clientMessageId: 'cid-custom',
          },
          { agentName: 'assistant', environmentName: null, yoloEnabled: false },
        ),
      { wrapper },
    )
    await waitFor(() => expect(result.current.disabled).toBe(false))

    await act(async () => {
      await result.current.submitMessage()
    })
    await act(async () => {
      await result.current.submitMessage()
    })

    expect(harnessService.submitCustomMessage).toHaveBeenNthCalledWith(1, '1', {
      role: 'system',
      content: 'system instruction',
      agentName: 'assistant',
      environmentName: null,
      yoloEnabled: false,
      clientMessageId: 'cid-custom',
      expectedExecutionEpoch: 7,
    })
    expect(harnessService.submitCustomMessage).toHaveBeenNthCalledWith(2, '1', {
      role: 'system',
      content: 'system instruction',
      agentName: 'assistant',
      environmentName: null,
      yoloEnabled: false,
      clientMessageId: 'cid-custom',
      expectedExecutionEpoch: 7,
    })
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

  it('mints a new id and sends visible settings when a failed retry settings change', async () => {
    vi.mocked(harnessService.submitThreadMessage)
      .mockRejectedValueOnce(new Error('response lost'))
      .mockResolvedValueOnce({
        inputId: 'i2',
        threadId: '1',
        sequence: 2,
        inputType: 'USER_MESSAGE',
        payloadJson: '{}',
        clientMessageId: 'retry-with-new-settings',
        status: 'QUEUED',
        resolvedAt: null,
        createTime: null,
      })

    const { result, rerender } = renderHook(
      ({ environmentName, yoloEnabled }) =>
        useAgentThreadController(
          '1',
          'retry me',
          {
            content: 'retry me',
            clientMessageId: 'cid-original',
            kind: 'USER_MESSAGE',
            role: 'user',
            agentName: 'assistant',
            environmentName: null,
            yoloEnabled: false,
            firstSendContext: { chatId: 'chat-1' },
          },
          { agentName: 'assistant', environmentName, yoloEnabled },
        ),
      {
        initialProps: { environmentName: null as string | null, yoloEnabled: false },
        wrapper,
      },
    )
    await waitFor(() => expect(result.current.disabled).toBe(false))

    await act(async () => {
      await result.current.submitMessage()
    })
    const originalId = vi.mocked(harnessService.submitThreadMessage).mock.calls[0][1].clientMessageId
    expect(originalId).toBe('cid-original')
    expect(result.current.draft).toBe('retry me')

    rerender({ environmentName: 'local', yoloEnabled: true })
    await act(async () => {
      await result.current.submitMessage()
    })

    const changedCall = vi.mocked(harnessService.submitThreadMessage).mock.calls[1][1]
    expect(changedCall.clientMessageId).not.toBe(originalId)
    expect(changedCall).toMatchObject({
      content: 'retry me',
      agentName: 'assistant',
      environmentName: 'local',
      yoloEnabled: true,
    })
  })

  it('resolves footer model labels from agent catalog without unknown-model', async () => {
    const { result } = renderHook(
      () => useAgentThreadController('1', '', undefined, {
        agentName: 'assistant',
        environmentName: 'local',
        yoloEnabled: false,
      }),
      { wrapper },
    )
    await waitFor(() => expect(result.current.disabled).toBe(false))
    expect(result.current.runtimeLabels.modelName).toBe('minimax/MiniMax-M2.7')
    expect(result.current.runtimeLabels.providerName).toBe('minimax')
    expect(result.current.runtimeLabels.contextWindow).toBe(128000)
    expect(result.current.runtimeLabels.modelName).not.toBe('unknown-model')
  })

  it('projects model text and thinking deltas as a transient streaming assistant message', async () => {
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(
      snapshotWithRunningModel(thread),
    )
    const { result } = renderHook(() => useAgentThreadController('1'), { wrapper })
    await waitFor(() => expect(harnessService.createThreadRealtimeStream).toHaveBeenCalled())

    act(() => {
      realtimeSource.emitRealtime(
        '{"threadId":"1","subjectKind":"MODEL_INVOCATION","subjectId":"42","attempt":1,'
          + '"sequence":1,'
          + '"type":"MODEL_DELTA","payload":{"kind":"THINKING_DELTA","text":"先分析。"},'
          + '"createdAt":"2026-07-28T10:00:00Z"}',
      )
      realtimeSource.emitRealtime(
        '{"threadId":"1","subjectKind":"MODEL_INVOCATION","subjectId":"42","attempt":1,'
          + '"sequence":2,'
          + '"type":"MODEL_DELTA","payload":{"kind":"TEXT_DELTA","text":"这是答案。"},'
          + '"createdAt":"2026-07-28T10:00:01Z"}',
      )
    })

    await waitFor(() =>
      expect(result.current.timeline.messages).toMatchObject([
        {
          role: 'assistant',
          text: '这是答案。',
          thinking: '先分析。',
          status: 'streaming',
        },
      ]),
    )
  })

  it('does not carry a transient stream into a newly selected Thread', async () => {
    vi.mocked(harnessService.getThreadSnapshot).mockImplementation(async (threadId) =>
      threadId === '1'
        ? snapshotWithRunningModel(thread)
        : snapshot({ ...thread, threadId, sessionId: 's2', headEntryId: 'h2' }),
    )
    const { result, rerender } = renderHook(
      ({ threadId }) => useAgentThreadController(threadId),
      { initialProps: { threadId: '1' }, wrapper },
    )
    await waitFor(() => expect(harnessService.createThreadRealtimeStream).toHaveBeenCalledWith('1', '0'))

    act(() => {
      realtimeSource.emitRealtime(
        '{"threadId":"1","subjectKind":"MODEL_INVOCATION","subjectId":"42","attempt":1,'
          + '"sequence":1,'
          + '"type":"MODEL_DELTA","payload":{"kind":"TEXT_DELTA","text":"旧 Thread 输出"},'
          + '"createdAt":"2026-07-28T10:00:00Z"}',
      )
    })
    await waitFor(() =>
      expect(result.current.timeline.messages).toEqual(
        expect.arrayContaining([expect.objectContaining({ status: 'streaming', text: '旧 Thread 输出' })]),
      ),
    )

    rerender({ threadId: '2' })

    expect(result.current.timeline.messages).not.toEqual(
      expect.arrayContaining([expect.objectContaining({ status: 'streaming' })]),
    )
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
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(snapshot({ ...thread, status: 'RUNNING' }))
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

  it('reports a 409 as a stale-epoch conflict and refetches the Thread instead of swallowing it', async () => {
    vi.mocked(harnessService.submitThreadMessage).mockRejectedValueOnce(
      new ApiError('expected execution epoch mismatch', 409),
    )
    const { result } = renderHook(() => useAgentThreadController('1'), { wrapper })
    await waitFor(() => expect(result.current.disabled).toBe(false))
    const snapshotCallsBefore = vi.mocked(harnessService.getThreadSnapshot).mock.calls.length

    act(() => result.current.setDraft('stale message'))
    await act(async () => { await result.current.submitMessage() })

    expect(result.current.actionError).toContain('Thread 状态已变化')
    expect(result.current.actionError).toContain('expected execution epoch mismatch')
    expect(result.current.actionError).toContain('请重试')
    // The Thread snapshot is invalidated so the retry carries the fresh epoch.
    await waitFor(() =>
      expect(vi.mocked(harnessService.getThreadSnapshot).mock.calls.length).toBeGreaterThan(snapshotCallsBefore),
    )
    // The failed draft is still restored for an explicit retry.
    expect(result.current.draft).toBe('stale message')
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
  revision: '0',
  processing: false,
  createTime: null,
  updateTime: null,
}

function snapshot(threadValue: HarnessThreadDTO) {
  return {
    revision: threadValue.revision,
    thread: threadValue,
    entries: [],
    inputs: [],
    modelInvocations: [],
    toolInvocations: [],
    openInteractions: [],
    usage: {
      scopeType: 'thread',
      scopeId: threadValue.threadId,
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
    },
  }
}

function snapshotWithRunningModel(threadValue: HarnessThreadDTO) {
  return {
    ...snapshot(threadValue),
    modelInvocations: [
      {
        id: '42',
        threadId: threadValue.threadId,
        sourceHeadEntryId: threadValue.headEntryId ?? 'h1',
        executionEpoch: threadValue.executionEpoch,
        requestJson: '{}',
        status: 'RUNNING' as const,
        attempt: 1,
        nextAttemptAt: null,
        workerUntil: null,
        deadlineAt: null,
        lastActivityAt: null,
        resultJson: null,
        errorJson: null,
        appliedAt: null,
        createdAt: '2026-07-28T10:00:00Z',
        startedAt: '2026-07-28T10:00:00Z',
        finishedAt: null,
        safeStreamSnapshotJson: '{"text":"","thinking":"","sequence":0}',
      },
    ],
  }
}
