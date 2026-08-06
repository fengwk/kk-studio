import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useAgentThreadController, retireStaleStopPending } from '@/features/ai/runtime/useAgentThreadController'
import {
  buildMessageBatchPlan,
  type CommandBatchPlan,
} from '@/features/ai/chat/command-batch-plan'
import { branchDraftFromThread, type BranchDraft } from '@/features/ai/chat/branch-draft'
import { agentService } from '@/shared/api/agent-service'
import { ApiError } from '@/shared/api/client'
import { harnessService } from '@/shared/api/harness-service'
import type {
  HarnessBranchSettingsDTO,
  HarnessModelSelectionDTO,
  HarnessThreadCommandDTO,
  HarnessThreadDTO,
  HarnessThreadSnapshotDTO,
  HarnessThreadStopResultDTO,
  ToolInvocationDTO,
} from '@/shared/api/contracts/ai-runtime'

vi.mock('@/shared/api/agent-service', () => ({
  agentService: {
    listAgents: vi.fn(),
    listModels: vi.fn(),
    listProviders: vi.fn(),
  },
}))
vi.mock('@/shared/api/harness-service', () => ({
  harnessService: {
    getThreadSnapshot: vi.fn(),
    enqueueCommands: vi.fn(),
    updateThreadHead: vi.fn(),
    stopThread: vi.fn(),
    decideApproval: vi.fn(),
    createThreadRealtimeStream: vi.fn(),
  },
}))

class FakeEventSource {
  private readonly listeners = new Map<string, EventListener[]>()

  addEventListener(type: string, listener: EventListener): void {
    const existing = this.listeners.get(type) ?? []
    existing.push(listener)
    this.listeners.set(type, existing)
  }

  removeEventListener(type: string, listener: EventListener): void {
    const existing = this.listeners.get(type) ?? []
    this.listeners.set(
      type,
      existing.filter((value) => value !== listener),
    )
  }

  emit(type: string): void {
    const listeners = this.listeners.get(type)
    if (listeners) {
      for (const listener of listeners) {
        listener({} as Event)
      }
    }
  }

  close(): void {
    this.listeners.clear()
  }
}

function modelSelection(
  overrides: Partial<HarnessModelSelectionDTO> = {},
): HarnessModelSelectionDTO {
  return {
    providerName: 'minimax',
    modelName: 'MiniMax',
    variant: 'default',
    ...overrides,
  }
}

function branchSettings(
  overrides: Partial<HarnessBranchSettingsDTO> = {},
): HarnessBranchSettingsDTO {
  return {
    environmentId: null,
    agentName: 'assistant',
    model: modelSelection(),
    thinkingLevel: 'off',
    activeTools: [],
    ...overrides,
  }
}

function threadFixture(overrides: Partial<HarnessThreadDTO> = {}): HarnessThreadDTO {
  return {
    threadId: 't1',
    sessionId: 's1',
    headEntryId: 'h1',
    yoloEnabled: false,
    nextCommandSequence: '1',
    revision: '0',
    status: 'IDLE',
    processing: false,
    branchSettings: branchSettings(),
    createTime: null,
    updateTime: null,
    ...overrides,
  }
}

function snapshotOf(
  currentThread: HarnessThreadDTO,
  extras: Partial<HarnessThreadSnapshotDTO> = {},
): HarnessThreadSnapshotDTO {
  return {
    revision: currentThread.revision,
    thread: currentThread,
    entries: [],
    queuedCommands: [],
    modelInvocation: null,
    toolInvocations: [],
    ...extras,
  }
}

const assistantAgentEntry = {
  name: 'assistant',
  description: null,
  systemPrompt: null,
  model: 'minimax/MiniMax',
  variant: 'default',
  config: { tools: [], skills: [] },
  version: '0',
  createTime: null,
  updateTime: null,
}

let realtimeSource: FakeEventSource

function wrapper({ children }: { children: ReactNode }) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>
}

function buildBatchFor(
  currentThread: HarnessThreadDTO,
  base: BranchDraft,
  draft: BranchDraft,
): (content: string) => CommandBatchPlan | null {
  return (content) =>
    buildMessageBatchPlan({ thread: currentThread, effectiveBase: base, draft, content })
}

describe('useAgentThreadController', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    realtimeSource = new FakeEventSource()
    vi.mocked(agentService.listAgents).mockResolvedValue({
      pageNumber: 1,
      pageSize: 50,
      totalCount: 1,
      results: [assistantAgentEntry],
    })
    vi.mocked(agentService.listModels).mockResolvedValue({
      pageNumber: 1,
      pageSize: 50,
      totalCount: 1,
      results: [
        {
          providerName: 'minimax',
          name: 'MiniMax',
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
          version: '0',
          createTime: null,
          updateTime: null,
        },
      ],
    })
    vi.mocked(harnessService.createThreadRealtimeStream).mockImplementation(
      () => realtimeSource as unknown as EventSource,
    )
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(snapshotOf(threadFixture()))
    vi.mocked(harnessService.enqueueCommands).mockResolvedValue(
      [] as HarnessThreadCommandDTO[],
    )
    vi.mocked(harnessService.stopThread).mockResolvedValue({
      status: 'IDLE',
      thread: threadFixture(),
      stoppedTurnEndEntryId: null,
      cancelledCommandCount: 0,
    } as HarnessThreadStopResultDTO)
    vi.mocked(harnessService.decideApproval).mockImplementation(
      async (_threadId, invocationId) =>
        ({
          id: invocationId,
          modelInvocationId: 'm1',
          assistantEntryId: 'a1',
          ordinal: 0,
          status: 'APPROVED',
          attempt: 1,
          toolCallId: 'call-1',
          toolName: 'demo',
          toolVersion: '1',
          toolType: 'PLATFORM',
          environmentId: null,
          argumentsJson: '{}',
          approvalJson: '{}',
          resultJson: null,
          errorJson: null,
          resultEntryId: null,
          createTime: null,
          updateTime: null,
        }) satisfies ToolInvocationDTO,
    )
  })

  it('submits a USER_MESSAGE plus a SET_* diff batch via the snapshot CAS cursors', async () => {
    const currentThread = threadFixture({
      branchSettings: branchSettings({ agentName: 'assistant' }),
    })
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(snapshotOf(currentThread))

    const base = branchDraftFromThread(currentThread)
    const draft: BranchDraft = {
      ...base,
      agentName: 'coder',
      yoloEnabled: true,
    }
    const { result } = renderHook(
      () =>
        useAgentThreadController(
          currentThread.threadId,
          '',
          undefined,
          buildBatchFor(currentThread, base, draft),
          new Map(),
        ),
      { wrapper },
    )
    await waitFor(() => expect(result.current.disabled).toBe(false))

    act(() => result.current.setDraft('hello world'))
    await act(async () => {
      await result.current.submitMessage()
    })

    await waitFor(() => expect(harnessService.enqueueCommands).toHaveBeenCalledTimes(1))
    const [threadIdArg, batchArg] = vi.mocked(harnessService.enqueueCommands).mock.calls[0]!
    expect(threadIdArg).toBe('t1')
    expect(batchArg.expectedHeadEntryId).toBe('h1')
    expect(batchArg.expectedNextCommandSequence).toBe('1')
    const types = batchArg.commands.map((command) => command.type)
    expect(types).toContain('SET_AGENT')
    expect(types).toContain('SET_YOLO')
    expect(types[types.length - 1]).toBe('USER_MESSAGE')
    const userMessage = batchArg.commands[batchArg.commands.length - 1]!
    expect(userMessage.content).toBe('hello world')
    // Strict wire: USER_MESSAGE never carries role.
    expect(userMessage).not.toHaveProperty('role')
    expect(result.current.draft).toBe('')
  })

  it('reports a 409 from send as threadStateChanged and invalidates the snapshot', async () => {
    vi.mocked(harnessService.enqueueCommands).mockRejectedValueOnce(
      new ApiError('expected revision mismatch', 409),
    )
    const currentThread = threadFixture()
    const base = branchDraftFromThread(currentThread)
    const draft = base
    const snapshotCallsBefore = vi.mocked(harnessService.getThreadSnapshot).mock.calls.length

    const { result } = renderHook(
      () =>
        useAgentThreadController(
          currentThread.threadId,
          '',
          undefined,
          buildBatchFor(currentThread, base, draft),
          new Map(),
        ),
      { wrapper },
    )
    await waitFor(() => expect(result.current.disabled).toBe(false))

    act(() => result.current.setDraft('stale message'))
    await act(async () => {
      await result.current.submitMessage()
    })

    await waitFor(() =>
      expect(result.current.actionError).toContain('Thread \u72b6\u6001\u5df2\u53d8\u5316'),
    )
    expect(result.current.actionError).toContain('expected revision mismatch')
    // Snapshot query is invalidated to refetch the current revision.
    await waitFor(() =>
      expect(harnessService.getThreadSnapshot.mock.calls.length).toBeGreaterThan(
        snapshotCallsBefore,
      ),
    )
    // Failed draft is restored so the user can retry without retyping.
    expect(result.current.draft).toBe('stale message')
  })

  it('reuses the same batch object when a retry carries identical content and draft', async () => {
    const currentThread = threadFixture()
    const base = branchDraftFromThread(currentThread)
    const draft = base
    vi.mocked(harnessService.enqueueCommands)
      .mockRejectedValueOnce(new Error('queue full'))
      .mockResolvedValueOnce([] as HarnessThreadCommandDTO[])

    const { result } = renderHook(
      () =>
        useAgentThreadController(
          currentThread.threadId,
          '',
          undefined,
          buildBatchFor(currentThread, base, draft),
          new Map(),
        ),
      { wrapper },
    )
    await waitFor(() => expect(result.current.disabled).toBe(false))

    act(() => result.current.setDraft('retry me'))
    await act(async () => {
      await result.current.submitMessage()
    })
    await waitFor(() => expect(harnessService.enqueueCommands).toHaveBeenCalledTimes(1))
    const firstBatch = vi.mocked(harnessService.enqueueCommands).mock.calls[0]?.[1]
    expect(firstBatch).toBeDefined()
    expect(result.current.draft).toBe('retry me')

    await act(async () => {
      await result.current.submitMessage()
    })
    await waitFor(() => expect(harnessService.enqueueCommands).toHaveBeenCalledTimes(2))
    const secondBatch = vi.mocked(harnessService.enqueueCommands).mock.calls[1]?.[1]
    // Identity match reuses the previous batch object byte-for-byte.
    expect(secondBatch).toBe(firstBatch)
  })

  it('replays the exact batch even when the queued SET_* projection mutates the effective base', async () => {
    // Scenario: base=A, draft=B failed with an uncertain network error; the next snapshot's
    // queued SET_* commands project base -> B. The user did NOT edit, so the retry must reuse
    // the original batch (same command ids) instead of minting a USER_MESSAGE-only batch.
    const currentThread = threadFixture()
    const baseA = branchDraftFromThread(currentThread)
    const draftB = { ...baseA, agentName: 'coder' }
    let projectionApplied = false
    const buildBatch = (content: string) =>
      buildMessageBatchPlan({
        // After the projection, effectiveBase equals the draft: a naive {content,base,draft}
        // identity would fail to match; the immutable-intent identity must still hit.
        thread: currentThread,
        effectiveBase: projectionApplied ? draftB : baseA,
        draft: draftB,
        content,
      })
    vi.mocked(harnessService.enqueueCommands)
      .mockRejectedValueOnce(new Error('queue full'))
      .mockImplementationOnce(async () => {
        projectionApplied = true
        return [] as HarnessThreadCommandDTO[]
      })

    const { result } = renderHook(
      () =>
        useAgentThreadController(
          currentThread.threadId,
          '',
          undefined,
          buildBatch,
          new Map(),
        ),
      { wrapper },
    )
    await waitFor(() => expect(result.current.disabled).toBe(false))

    act(() => result.current.setDraft('retry me'))
    await act(async () => {
      await result.current.submitMessage()
    })
    await waitFor(() => expect(harnessService.enqueueCommands).toHaveBeenCalledTimes(1))
    const firstBatch = vi.mocked(harnessService.enqueueCommands).mock.calls[0]?.[1]
    expect(firstBatch?.commands).toHaveLength(2) // SET_AGENT + USER_MESSAGE

    await act(async () => {
      await result.current.submitMessage()
    })
    await waitFor(() => expect(harnessService.enqueueCommands).toHaveBeenCalledTimes(2))
    const secondBatch = vi.mocked(harnessService.enqueueCommands).mock.calls[1]?.[1]
    // Exact replay: same batch object (SET_AGENT + USER_MESSAGE), never a message-only batch.
    expect(secondBatch).toBe(firstBatch)
    expect(secondBatch?.commands).toHaveLength(2)
  })

  it('mints a new batch when the draft diverges from the failed replay', async () => {
    const currentThread = threadFixture()
    const base = branchDraftFromThread(currentThread)
    const draft = base
    vi.mocked(harnessService.enqueueCommands)
      .mockRejectedValueOnce(new Error('queue full'))
      .mockResolvedValue([] as HarnessThreadCommandDTO[])

    const { result } = renderHook(
      () =>
        useAgentThreadController(
          currentThread.threadId,
          '',
          undefined,
          buildBatchFor(currentThread, base, draft),
          new Map(),
        ),
      { wrapper },
    )
    await waitFor(() => expect(result.current.disabled).toBe(false))

    act(() => result.current.setDraft('retry me'))
    await act(async () => {
      await result.current.submitMessage()
    })
    const firstId = vi.mocked(harnessService.enqueueCommands).mock.calls[0]?.[1]
      .commands[0]?.clientCommandId

    // Editing the composer to different content resets the replay identity.
    act(() => result.current.setDraft('changed content'))
    await act(async () => {
      await result.current.submitMessage()
    })
    const secondId = vi.mocked(harnessService.enqueueCommands).mock.calls[1]?.[1]
      .commands[0]?.clientCommandId
    expect(secondId).toBeTruthy()
    expect(secondId).not.toBe(firstId)
  })

  it('surfaces non-conflict send failures with the draft restored', async () => {
    vi.mocked(harnessService.enqueueCommands).mockRejectedValueOnce(new Error('queue full'))
    const currentThread = threadFixture()
    const base = branchDraftFromThread(currentThread)
    const draft = base

    const { result } = renderHook(
      () =>
        useAgentThreadController(
          currentThread.threadId,
          '',
          undefined,
          buildBatchFor(currentThread, base, draft),
          new Map(),
        ),
      { wrapper },
    )
    await waitFor(() => expect(result.current.disabled).toBe(false))

    act(() => result.current.setDraft('retry me'))
    await act(async () => {
      await result.current.submitMessage()
    })
    await waitFor(() => expect(result.current.actionError).toContain('queue full'))
    expect(result.current.draft).toBe('retry me')
  })

  it('clears the draft on a successful send', async () => {
    const currentThread = threadFixture()
    const base = branchDraftFromThread(currentThread)
    const draft = base

    const { result } = renderHook(
      () =>
        useAgentThreadController(
          currentThread.threadId,
          '',
          undefined,
          buildBatchFor(currentThread, base, draft),
          new Map(),
        ),
      { wrapper },
    )
    await waitFor(() => expect(result.current.disabled).toBe(false))

    act(() => result.current.setDraft('hello world'))
    await act(async () => {
      await result.current.submitMessage()
    })
    await waitFor(() => expect(harnessService.enqueueCommands).toHaveBeenCalledTimes(1))
    expect(result.current.draft).toBe('')
  })

  it('stops the Thread with a stable stopRequestId across a retry after failure', async () => {
    vi.mocked(harnessService.stopThread)
      .mockRejectedValueOnce(new Error('network unavailable'))
      .mockResolvedValueOnce({
        status: 'IDLE',
        thread: threadFixture(),
        stoppedTurnEndEntryId: null,
        cancelledCommandCount: 0,
      } as HarnessThreadStopResultDTO)

    const { result } = renderHook(() => useAgentThreadController('t1'), { wrapper })
    await waitFor(() => expect(result.current.disabled).toBe(false))

    await act(async () => {
      await result.current.stopThread()
    })
    expect(result.current.actionError).toContain('network unavailable')
    expect(harnessService.stopThread).toHaveBeenCalledTimes(1)
    const firstStopArg = vi.mocked(harnessService.stopThread).mock.calls[0]?.[1]
    expect(firstStopArg).toBeDefined()
    expect(firstStopArg?.expectedRevision).toBe('0')

    await act(async () => {
      await result.current.stopThread()
    })
    expect(harnessService.stopThread).toHaveBeenCalledTimes(2)
    const secondStopArg = vi.mocked(harnessService.stopThread).mock.calls[1]?.[1]
    // Stable idempotency key survives a transient failure.
    expect(secondStopArg?.stopRequestId).toBe(firstStopArg?.stopRequestId)
    expect(secondStopArg?.expectedRevision).toBe('0')
  })

  it('retires an ambiguous stop when the snapshot proves the old Turn ended and mints a fresh id', async () => {
    // The first Stop actually landed server-side but its response was lost.
    vi.mocked(harnessService.stopThread).mockRejectedValue(new Error('response lost'))
    const turn1 = threadFixture()
    const turn2 = threadFixture({ headEntryId: 'e-turn1-end', revision: '2' })
    // Mount fetch reads Turn 1; the invalidate triggered by the failed Stop reads the
    // advanced Turn 2 snapshot (the ambiguous Stop actually landed server-side).
    vi.mocked(harnessService.getThreadSnapshot)
      .mockResolvedValueOnce(snapshotOf(turn1))
      .mockResolvedValue(snapshotOf(turn2))
    const { result } = renderHook(() => useAgentThreadController('t1'), { wrapper })
    await waitFor(() => expect(result.current.disabled).toBe(false))

    await act(async () => {
      await result.current.stopThread()
    })
    expect(result.current.stopReplayPending).toBe(true)
    expect(harnessService.stopThread).toHaveBeenCalledTimes(1)
    const firstStopArg = vi.mocked(harnessService.stopThread).mock.calls[0]?.[1]
    expect(firstStopArg?.expectedRevision).toBe('0')

    // The authoritative snapshot advances to Turn 2 (head + revision moved): the ambiguous
    // operation auto-retires. A realtime revision signal drives the refetch.
    act(() => realtimeSource.emit('revision'))
    await waitFor(() => expect(result.current.thread?.revision).toBe('2'))
    await waitFor(() => expect(result.current.stopReplayPending).toBe(false))

    // A Stop on the NEW Turn must use a fresh id + the current revision — never the old id
    // stitched to a newer revision.
    vi.mocked(harnessService.stopThread).mockResolvedValue({
      status: 'IDLE',
      thread: threadFixture({ headEntryId: 'e-turn1-end', revision: '2' }),
      stoppedTurnEndEntryId: null,
      cancelledCommandCount: 0,
    } as HarnessThreadStopResultDTO)
    await act(async () => {
      await result.current.stopThread()
    })
    const secondStopArg = vi.mocked(harnessService.stopThread).mock.calls[1]?.[1]
    expect(secondStopArg?.stopRequestId).not.toBe(firstStopArg?.stopRequestId)
    expect(secondStopArg?.expectedRevision).toBe('2')
  })

  it('keeps the exact stop body for the retry while the snapshot basis is unchanged', async () => {
    vi.mocked(harnessService.stopThread)
      .mockRejectedValueOnce(new Error('network unavailable'))
      .mockResolvedValueOnce({
        status: 'IDLE',
        thread: threadFixture(),
        stoppedTurnEndEntryId: null,
        cancelledCommandCount: 0,
      } as HarnessThreadStopResultDTO)
    const { result } = renderHook(() => useAgentThreadController('t1'), { wrapper })
    await waitFor(() => expect(result.current.disabled).toBe(false))

    await act(async () => {
      await result.current.stopThread()
    })
    const firstStopArg = vi.mocked(harnessService.stopThread).mock.calls[0]?.[1]
    expect(result.current.stopReplayPending).toBe(true)

    // Snapshot refetch returns the SAME basis (head/revision unchanged): the retry must send
    // the exact original body, not a body re-derived from a newer snapshot.
    await act(async () => {
      await result.current.stopThread()
    })
    const secondStopArg = vi.mocked(harnessService.stopThread).mock.calls[1]?.[1]
    expect(secondStopArg?.stopRequestId).toBe(firstStopArg?.stopRequestId)
    expect(secondStopArg?.expectedRevision).toBe(firstStopArg?.expectedRevision)
    expect(result.current.stopReplayPending).toBe(false)
  })

  it('clears the ambiguous stop after a known 409 and mints a new operation on the next stop', async () => {
    vi.mocked(harnessService.stopThread)
      .mockRejectedValueOnce(new ApiError('stale revision', 409))
      .mockResolvedValueOnce({
        status: 'IDLE',
        thread: threadFixture(),
        stoppedTurnEndEntryId: null,
        cancelledCommandCount: 0,
      } as HarnessThreadStopResultDTO)
    const { result } = renderHook(() => useAgentThreadController('t1'), { wrapper })
    await waitFor(() => expect(result.current.disabled).toBe(false))

    await act(async () => {
      await result.current.stopThread()
    })
    expect(result.current.stopReplayPending).toBe(false)

    await act(async () => {
      await result.current.stopThread()
    })
    const firstStopArg = vi.mocked(harnessService.stopThread).mock.calls[0]?.[1]
    const secondStopArg = vi.mocked(harnessService.stopThread).mock.calls[1]?.[1]
    expect(secondStopArg?.stopRequestId).not.toBe(firstStopArg?.stopRequestId)
    expect(secondStopArg?.expectedRevision).toBe('0')
  })

  it('retireStaleStopPending retires exactly when the authoritative basis moved', () => {
    const pending = {
      stopRequestId: 's-1',
      expectedRevision: '0',
      basisHeadEntryId: 'h1',
      basisRevision: '0',
    }
    // Matching basis: the exact retry stays alive.
    expect(
      retireStaleStopPending(pending, { headEntryId: 'h1', revision: '0' }),
    ).toBe(pending)
    // Head moved (old Turn ended): retired.
    expect(
      retireStaleStopPending(pending, { headEntryId: 'h2', revision: '0' }),
    ).toBeNull()
    // Revision moved (Thread advanced): retired.
    expect(
      retireStaleStopPending(pending, { headEntryId: 'h1', revision: '1' }),
    ).toBeNull()
    // No pending operation or no loaded Thread: no-op.
    expect(retireStaleStopPending(null, { headEntryId: 'h1', revision: '0' })).toBeNull()
    expect(retireStaleStopPending(pending, null)).toBe(pending)
  })

  it('re-mints immediately when stopThread runs after the snapshot basis moved (synchronous fence)', async () => {
    // The first Stop landed server-side but its response was lost: ambiguous operation with
    // the Turn-1 basis stays pending.
    vi.mocked(harnessService.stopThread).mockRejectedValue(new Error('response lost'))
    const turn1 = threadFixture()
    const turn2 = threadFixture({ headEntryId: 'e-turn1-end', revision: '2' })
    vi.mocked(harnessService.getThreadSnapshot)
      .mockResolvedValueOnce(snapshotOf(turn1))
      .mockResolvedValue(snapshotOf(turn2))
    const { result } = renderHook(() => useAgentThreadController('t1'), { wrapper })
    await waitFor(() => expect(result.current.disabled).toBe(false))

    await act(async () => {
      await result.current.stopThread()
    })
    expect(result.current.stopReplayPending).toBe(true)
    const firstStopArg = vi.mocked(harnessService.stopThread).mock.calls[0]?.[1]
    expect(firstStopArg?.expectedRevision).toBe('0')

    // The Query snapshot advances to Turn 2 (head + revision moved) and renders. stopThread
    // must NOT depend on the passive cleanup effect having flushed: its own synchronous fence
    // retires the stale basis and mints a fresh id + the CURRENT revision. (Under RTL the
    // effect flushes with the commit, so the fence contract itself is pinned by the
    // retireStaleStopPending unit test above.)
    act(() => realtimeSource.emit('revision'))
    await waitFor(() => expect(result.current.thread?.revision).toBe('2'))
    vi.mocked(harnessService.stopThread).mockResolvedValue({
      status: 'IDLE',
      thread: turn2,
      stoppedTurnEndEntryId: null,
      cancelledCommandCount: 0,
    } as HarnessThreadStopResultDTO)
    await act(async () => {
      await result.current.stopThread()
    })
    const secondStopArg = vi.mocked(harnessService.stopThread).mock.calls[1]?.[1]
    expect(secondStopArg?.stopRequestId).not.toBe(firstStopArg?.stopRequestId)
    expect(secondStopArg?.expectedRevision).toBe('2')
    expect(result.current.stopReplayPending).toBe(false)
  })

  it('mints a fresh stopRequestId after a successful stop', async () => {
    const { result } = renderHook(() => useAgentThreadController('t1'), { wrapper })
    await waitFor(() => expect(result.current.disabled).toBe(false))

    await act(async () => {
      await result.current.stopThread()
    })
    await act(async () => {
      await result.current.stopThread()
    })
    expect(harnessService.stopThread).toHaveBeenCalledTimes(2)
    const firstId = vi.mocked(harnessService.stopThread).mock.calls[0]?.[1]?.stopRequestId
    const secondId = vi.mocked(harnessService.stopThread).mock.calls[1]?.[1]?.stopRequestId
    expect(firstId).toBeTruthy()
    expect(secondId).toBeTruthy()
    expect(secondId).not.toBe(firstId)
  })

  it('decides approval with a stable decisionId per invocation across a retry', async () => {
    vi.mocked(harnessService.decideApproval)
      .mockRejectedValueOnce(new Error('network unavailable'))
      .mockResolvedValueOnce({
        id: 'tool-1',
        modelInvocationId: 'm1',
        assistantEntryId: 'a1',
        ordinal: 0,
        status: 'APPROVED',
        attempt: 1,
        toolCallId: 'call-1',
        toolName: 'demo',
        toolVersion: '1',
        toolType: 'PLATFORM',
        environmentId: null,
        argumentsJson: '{}',
        approvalJson: '{}',
        resultJson: null,
        errorJson: null,
        resultEntryId: null,
        createTime: null,
        updateTime: null,
      } as ToolInvocationDTO)

    const { result } = renderHook(() => useAgentThreadController('t1'), { wrapper })
    await waitFor(() => expect(result.current.disabled).toBe(false))

    await act(async () => {
      await result.current.decideApproval('tool-1', 'ALLOW')
    })
    expect(harnessService.decideApproval).toHaveBeenCalledTimes(1)
    const firstCall = vi.mocked(harnessService.decideApproval).mock.calls[0]
    expect(firstCall?.[0]).toBe('t1')
    expect(firstCall?.[1]).toBe('tool-1')
    expect(firstCall?.[2]).toMatchObject({
      decision: 'ALLOW',
      actor: 'web',
      reason: null,
    })
    const firstDecisionId = firstCall?.[2]?.decisionId
    expect(firstDecisionId).toBeTruthy()

    await act(async () => {
      await result.current.decideApproval('tool-1', 'ALLOW')
    })
    expect(harnessService.decideApproval).toHaveBeenCalledTimes(2)
    const secondDecisionId = vi.mocked(harnessService.decideApproval).mock.calls[1]?.[2]
      ?.decisionId
    // Same invocation across retries → same idempotency key.
    expect(secondDecisionId).toBe(firstDecisionId)
  })

  it('mints a fresh decisionId for a different invocation', async () => {
    const { result } = renderHook(() => useAgentThreadController('t1'), { wrapper })
    await waitFor(() => expect(result.current.disabled).toBe(false))

    await act(async () => {
      await result.current.decideApproval('tool-A', 'ALLOW')
    })
    await act(async () => {
      await result.current.decideApproval('tool-B', 'DENY')
    })
    const aId = vi.mocked(harnessService.decideApproval).mock.calls[0]?.[2]?.decisionId
    const bId = vi.mocked(harnessService.decideApproval).mock.calls[1]?.[2]?.decisionId
    expect(aId).toBeTruthy()
    expect(bId).toBeTruthy()
    expect(bId).not.toBe(aId)
    expect(vi.mocked(harnessService.decideApproval).mock.calls[1]?.[2]).toMatchObject({
      decision: 'DENY',
      actor: 'web',
      reason: null,
    })
  })

  it('invalidates the snapshot after a successful stop', async () => {
    const { result } = renderHook(() => useAgentThreadController('t1'), { wrapper })
    await waitFor(() => expect(result.current.disabled).toBe(false))

    const initialCalls = vi.mocked(harnessService.getThreadSnapshot).mock.calls.length
    await act(async () => {
      await result.current.stopThread()
    })
    await waitFor(() =>
      expect(harnessService.getThreadSnapshot.mock.calls.length).toBeGreaterThan(initialCalls),
    )
  })

  it('exposes runtimeLabels from the snapshot branch settings and environment map', async () => {
    const environments = new Map<string, string>([['env-local', 'local']])
    const currentThread = threadFixture({
      branchSettings: branchSettings({ environmentId: 'env-local' }),
    })
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(snapshotOf(currentThread))

    const { result } = renderHook(
      () => useAgentThreadController(currentThread.threadId, '', undefined, null, environments),
      { wrapper },
    )
    await waitFor(() => expect(result.current.disabled).toBe(false))
    expect(result.current.runtimeLabels.agentName).toBe('assistant')
    expect(result.current.runtimeLabels.environmentDisplayName).toBe('local')
    expect(result.current.runtimeLabels.modelName).toBe('minimax/MiniMax')
    expect(result.current.thread?.headEntryId).toBe('h1')
    expect(result.current.thread?.nextCommandSequence).toBe('1')
  })
  it('clears the exact replay on 409 so the retry mints fresh command ids and cursors', async () => {
    const currentThread = threadFixture({
      revision: '1',
      nextCommandSequence: '3',
      headEntryId: 'h1',
    })
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(snapshotOf(currentThread))
    vi.mocked(harnessService.enqueueCommands).mockRejectedValueOnce(
      new ApiError('conflict', 409, 'CONFLICT'),
    )
    vi.mocked(harnessService.enqueueCommands).mockResolvedValueOnce([])
    const base = branchDraftFromThread(currentThread)

    const { result } = renderHook(
      () =>
        useAgentThreadController(
          currentThread.threadId,
          '',
          undefined,
          buildBatchFor(currentThread, base, base),
          new Map(),
        ),
      { wrapper },
    )
    await waitFor(() => expect(result.current.disabled).toBe(false))

    act(() => result.current.setDraft('retry me'))
    await act(async () => {
      await result.current.submitMessage()
      await result.current.submitMessage()
    })

    const calls = vi.mocked(harnessService.enqueueCommands).mock.calls
    expect(calls).toHaveLength(2)
    const first = calls[0]?.[1] as {
      expectedHeadEntryId: string
      expectedNextCommandSequence: string
      commands: Array<{ clientCommandId: string }>
    }
    const second = calls[1]?.[1] as {
      expectedHeadEntryId: string
      expectedNextCommandSequence: string
      commands: Array<{ clientCommandId: string }>
    }
    // 409 = the batch was NOT accepted: the retry uses the refreshed head/nextSequence with
    // NEW command ids instead of replaying the stale batch.
    expect(second.expectedHeadEntryId).toBe('h1')
    expect(second.expectedNextCommandSequence).toBe('3')
    expect(second.commands[0]?.clientCommandId).not.toBe(first.commands[0]?.clientCommandId)
    // Network/uncertain failures would keep the exact batch; 409 must not.
    void first
    void second
  })

  it('mints a new decision id when the user switches ALLOW -> DENY for the same invocation', async () => {
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(snapshotOf(threadFixture()))
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    const { result } = renderHook(() => useAgentThreadController('t1'), {
      wrapper: ({ children }: { children: ReactNode }) => (
        <QueryClientProvider client={client}>{children}</QueryClientProvider>
      ),
    })
    await waitFor(() => expect(result.current.disabled).toBe(false))

    // First ALLOW fails: the retry reuses the SAME decision id.
    vi.mocked(harnessService.decideApproval).mockRejectedValueOnce(new Error('network'))
    await act(async () => {
      await result.current.decideApproval('inv-1', 'ALLOW')
      await result.current.decideApproval('inv-1', 'ALLOW')
    })
    const allowCalls = vi.mocked(harnessService.decideApproval).mock.calls
    expect(allowCalls[0]?.[2].decisionId).toBe(allowCalls[1]?.[2].decisionId)

    vi.mocked(harnessService.decideApproval).mockClear()
    // A successful decision refreshes both the snapshot and the Chat-scoped list.
    const invalidateSpy = vi.spyOn(client, 'invalidateQueries')
    await act(async () => {
      await result.current.decideApproval('inv-1', 'DENY')
    })
    expect(invalidateSpy).toHaveBeenCalledWith(
      expect.objectContaining({ queryKey: ['chats'] }),
    )
    invalidateSpy.mockRestore()
    const denyCall = vi.mocked(harnessService.decideApproval).mock.calls[0]?.[2]
    expect(denyCall?.decisionId).toBeTruthy()
    expect(denyCall?.decisionId).not.toBe(allowCalls[0]?.[2].decisionId)
    expect(denyCall?.decision).toBe('DENY')
  })

})
