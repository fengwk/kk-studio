import { beforeEach, describe, expect, it, vi } from 'vitest'
import { QueryClient } from '@tanstack/react-query'
import type {
  HarnessSessionEntryDTO,
  HarnessThreadDTO,
  HarnessThreadSnapshotDTO,
} from '@/shared/api/generated/contracts'
import { harnessService } from '@/shared/api/harness-service'
import { queryKeys } from '@/shared/lib/query-keys'
import {
  fetchThreadSnapshot,
  getSnapshotCursor,
  mergeThreadSnapshotCache,
} from '@/features/chat/runtime/thread-snapshot-cache'

function makeThread(overrides: Partial<HarnessThreadDTO> = {}): HarnessThreadDTO {
  return {
    threadId: 't-1',
    parentThreadId: null,
    name: 'Thread 1',
    sessionId: 's-1',
    headEntryId: 'entry-1',
    rootThreadId: 't-1',
    yoloEnabled: false,
    nextCommandSequence: '1',
    version: '1',
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
    ...overrides,
  }
}

function makeEntry(id: string, parentId: string | null = null): HarnessSessionEntryDTO {
  return {
    entryId: id,
    sessionId: 's-1',
    parentEntryId: parentId,
    entryType: 'USER_INPUT',
    payloadJson: JSON.stringify({ text: `message-${id}` }),
    createTime: 1000,
    usageCost: null,
  }
}

function makeSnapshot(overrides: Partial<HarnessThreadSnapshotDTO> = {}): HarnessThreadSnapshotDTO {
  const thread = overrides.thread ?? makeThread()
  return {
    version: overrides.version ?? thread.version,
    thread,
    baseEntryId: overrides.baseEntryId !== undefined ? overrides.baseEntryId : null,
    entries: overrides.entries ?? [makeEntry(thread.headEntryId)],
    queuedCommands: [],
    executionStatus: 'IDLE',
    activeAssistantOutput: null,
    generationFailures: [],
    toolInvocations: [],
    stopReceipts: [],
    unconfirmedExecutions: [],
    ...overrides,
  }
}

describe('thread-snapshot-cache', () => {
  describe('getSnapshotCursor', () => {
    it('returns undefined if cached is null or undefined', () => {
      expect(getSnapshotCursor(undefined, 't-1')).toBeUndefined()
    })

    it('returns undefined if threadId does not match', () => {
      const cached = makeSnapshot({ thread: makeThread({ threadId: 'other' }) })
      expect(getSnapshotCursor(cached, 't-1')).toBeUndefined()
    })

    it('returns undefined if cached baseEntryId is not null (incomplete baseline)', () => {
      const cached = makeSnapshot({ baseEntryId: 'entry-0' })
      expect(getSnapshotCursor(cached, 't-1')).toBeUndefined()
    })

    it('returns undefined if cached entries is empty', () => {
      const cached = makeSnapshot({ entries: [] })
      expect(getSnapshotCursor(cached, 't-1')).toBeUndefined()
    })

    it('returns undefined if last entry does not match thread headEntryId', () => {
      const cached = makeSnapshot({
        thread: makeThread({ headEntryId: 'entry-99' }),
        entries: [makeEntry('entry-1')],
      })
      expect(getSnapshotCursor(cached, 't-1')).toBeUndefined()
    })

    it('returns headEntryId when cache is complete and ends with thread headEntryId', () => {
      const cached = makeSnapshot({
        thread: makeThread({ headEntryId: 'entry-2' }),
        entries: [makeEntry('entry-1'), makeEntry('entry-2', 'entry-1')],
      })
      expect(getSnapshotCursor(cached, 't-1')).toBe('entry-2')
    })
  })

  describe('mergeThreadSnapshotCache', () => {
    it('never treats a delta as complete when cached is missing, returns undefined', () => {
      const incomingDelta = makeSnapshot({ baseEntryId: 'entry-1', entries: [makeEntry('entry-2')] })
      expect(mergeThreadSnapshotCache(undefined, incomingDelta)).toBeUndefined()
    })

    it('returns undefined for delta when cached threadId does not match incoming', () => {
      const cached = makeSnapshot({ thread: makeThread({ threadId: 't-1' }) })
      const incomingDelta = makeSnapshot({
        thread: makeThread({ threadId: 't-2' }),
        baseEntryId: 'entry-1',
      })
      expect(mergeThreadSnapshotCache(cached, incomingDelta)).toBeUndefined()
    })

    it('returns undefined for delta when cached itself is not full (baseEntryId !== null)', () => {
      const cached = makeSnapshot({ baseEntryId: 'entry-0', entries: [makeEntry('entry-1')] })
      const incomingDelta = makeSnapshot({ baseEntryId: 'entry-1' })
      expect(mergeThreadSnapshotCache(cached, incomingDelta)).toBeUndefined()
    })

    it('returns undefined for delta when base is not in cached entries chain', () => {
      const cached = makeSnapshot({ entries: [makeEntry('entry-1')] })
      const incomingDelta = makeSnapshot({ baseEntryId: 'entry-missing' })
      expect(mergeThreadSnapshotCache(cached, incomingDelta)).toBeUndefined()
    })

    it('retains cached when cached version is newer than incoming delta (old version does not overwrite)', () => {
      const cached = makeSnapshot({
        version: '10',
        thread: makeThread({ version: '10', headEntryId: 'entry-2' }),
        entries: [makeEntry('entry-1'), makeEntry('entry-2', 'entry-1')],
      })
      const incomingDelta = makeSnapshot({
        version: '5',
        thread: makeThread({ version: '5', headEntryId: 'entry-2' }),
        baseEntryId: 'entry-1',
        entries: [makeEntry('entry-2', 'entry-1')],
      })
      const merged = mergeThreadSnapshotCache(cached, incomingDelta)
      expect(merged).toBe(cached)
    })

    it('splices delta onto base prefix and normalizes baseEntryId to null', () => {
      const cached = makeSnapshot({
        version: '2',
        entries: [
          makeEntry('entry-1'),
          makeEntry('entry-2', 'entry-1'),
          makeEntry('entry-stale', 'entry-2'),
        ],
      })
      const incomingDelta = makeSnapshot({
        version: '3',
        baseEntryId: 'entry-2',
        entries: [makeEntry('entry-3', 'entry-2')],
      })
      const merged = mergeThreadSnapshotCache(cached, incomingDelta)
      expect(merged).toBeDefined()
      expect(merged?.version).toBe('3')
      expect(merged?.baseEntryId).toBeNull()
      expect(merged?.entries.map((e) => e.entryId)).toEqual(['entry-1', 'entry-2', 'entry-3'])
    })

    it('merges idempotently by entryId without duplicate append', () => {
      const cached = makeSnapshot({
        version: '2',
        entries: [makeEntry('entry-1'), makeEntry('entry-2', 'entry-1')],
      })
      const incomingDelta = makeSnapshot({
        version: '3',
        baseEntryId: 'entry-2',
        entries: [
          makeEntry('entry-2', 'entry-1'),
          makeEntry('entry-3', 'entry-2'),
          makeEntry('entry-3', 'entry-2'),
        ],
      })
      const merged = mergeThreadSnapshotCache(cached, incomingDelta)
      expect(merged?.entries.map((e) => e.entryId)).toEqual(['entry-1', 'entry-2', 'entry-3'])
    })

    it('updates current metadata even when delta is empty on same version checkpoint', () => {
      const cached = makeSnapshot({
        version: '5',
        entries: [makeEntry('entry-1')],
        executionStatus: 'RUNNING',
        activeAssistantOutput: null,
      })
      const activeOutput = {
        generationId: 'gen-1',
        turnStartEntryId: 'entry-1',
        requestHeadEntryId: 'entry-1',
        status: 'RUNNING',
        attempt: 1,
        sequence: '1',
        contents: [{ type: 'TEXT', text: 'streaming...' }],
        error: null,
        resultEntryId: null,
        createTime: 1000,
        updateTime: 1000,
      }
      const incomingDelta = makeSnapshot({
        version: '5',
        baseEntryId: 'entry-1',
        entries: [],
        executionStatus: 'RUNNING',
        activeAssistantOutput: activeOutput,
      })
      const merged = mergeThreadSnapshotCache(cached, incomingDelta)
      expect(merged?.version).toBe('5')
      expect(merged?.baseEntryId).toBeNull()
      expect(merged?.entries).toHaveLength(1)
      expect(merged?.activeAssistantOutput).toEqual(activeOutput)
    })

    it('normalizes baseEntryId to null for full snapshot replacement', () => {
      const cached = makeSnapshot({
        version: '1',
        entries: [makeEntry('entry-1')],
      })
      const incomingFull = makeSnapshot({
        version: '2',
        baseEntryId: null,
        entries: [makeEntry('entry-1'), makeEntry('entry-2', 'entry-1')],
      })
      const merged = mergeThreadSnapshotCache(cached, incomingFull)
      expect(merged?.version).toBe('2')
      expect(merged?.baseEntryId).toBeNull()
      expect(merged?.entries).toHaveLength(2)
    })

    it('retains cached if cached version is newer than incoming full snapshot', () => {
      const cached = makeSnapshot({
        version: '5',
        entries: [makeEntry('entry-5')],
      })
      const incomingFull = makeSnapshot({
        version: '4',
        baseEntryId: null,
        entries: [makeEntry('entry-1')],
      })
      const merged = mergeThreadSnapshotCache(cached, incomingFull)
      expect(merged).toBe(cached)
    })
  })

  describe('fetchThreadSnapshot', () => {
    beforeEach(() => {
      vi.restoreAllMocks()
    })

    it('fetches full snapshot when cache is absent and does not call setQueryData internally', async () => {
      const queryClient = new QueryClient()
      const threadId = 'thread-1'
      const fullSnapshot = makeSnapshot({
        thread: makeThread({ threadId }),
        entries: [makeEntry('e-1')],
      })
      vi.spyOn(harnessService, 'getThreadSnapshot').mockResolvedValueOnce(fullSnapshot)
      const setQueryDataSpy = vi.spyOn(queryClient, 'setQueryData')

      const result = await fetchThreadSnapshot(queryClient, threadId)
      expect(harnessService.getThreadSnapshot).toHaveBeenCalledWith(threadId, { signal: undefined })
      expect(result.thread.threadId).toBe(threadId)
      expect(setQueryDataSpy).not.toHaveBeenCalled()
    })

    it('requests cursor delta from complete cached head and merges with current cache', async () => {
      const queryClient = new QueryClient()
      const threadId = 'thread-1'
      const cachedSnapshot = makeSnapshot({
        version: '1',
        thread: makeThread({ threadId, headEntryId: 'e-1', version: '1' }),
        baseEntryId: null,
        entries: [makeEntry('e-1')],
      })
      queryClient.setQueryData(queryKeys.threads.snapshot(threadId), cachedSnapshot)

      const deltaSnapshot = makeSnapshot({
        version: '2',
        thread: makeThread({ threadId, headEntryId: 'e-2', version: '2' }),
        baseEntryId: 'e-1',
        entries: [makeEntry('e-2', 'e-1')],
      })
      vi.spyOn(harnessService, 'getThreadSnapshot').mockResolvedValueOnce(deltaSnapshot)

      const result = await fetchThreadSnapshot(queryClient, threadId)
      expect(harnessService.getThreadSnapshot).toHaveBeenCalledWith(threadId, {
        afterEntryId: 'e-1',
        signal: undefined,
      })
      expect(result.version).toBe('2')
      expect(result.baseEntryId).toBeNull()
      expect(result.entries.map((e) => e.entryId)).toEqual(['e-1', 'e-2'])
    })

    it('merges against latest cache if cache was updated in-flight, without overwriting newer cache', async () => {
      const queryClient = new QueryClient()
      const threadId = 'thread-1'
      const initialCached = makeSnapshot({
        version: '1',
        thread: makeThread({ threadId, headEntryId: 'e-1', version: '1' }),
        baseEntryId: null,
        entries: [makeEntry('e-1')],
      })
      queryClient.setQueryData(queryKeys.threads.snapshot(threadId), initialCached)

      // 模拟在途请求期间，缓存被更新到了更高版本 version: '5'
      const updatedCached = makeSnapshot({
        version: '5',
        thread: makeThread({ threadId, headEntryId: 'e-5', version: '5' }),
        baseEntryId: null,
        entries: [makeEntry('e-1'), makeEntry('e-5', 'e-1')],
      })

      const inFlightDelta = makeSnapshot({
        version: '2',
        thread: makeThread({ threadId, headEntryId: 'e-2', version: '2' }),
        baseEntryId: 'e-1',
        entries: [makeEntry('e-2', 'e-1')],
      })

      vi.spyOn(harnessService, 'getThreadSnapshot').mockImplementationOnce(async () => {
        // 请求在途期间，缓存更新为 updatedCached
        queryClient.setQueryData(queryKeys.threads.snapshot(threadId), updatedCached)
        return inFlightDelta
      })

      const result = await fetchThreadSnapshot(queryClient, threadId)
      // 验证旧 version 的 delta 不会覆盖已更新的最新缓存
      expect(result.version).toBe('5')
      expect(result.entries.map((e) => e.entryId)).toEqual(['e-1', 'e-5'])
    })

    it('does not swallow network/http error into full fallback', async () => {
      const queryClient = new QueryClient()
      const threadId = 'thread-1'
      const cachedSnapshot = makeSnapshot({
        version: '1',
        thread: makeThread({ threadId, headEntryId: 'e-1', version: '1' }),
        baseEntryId: null,
        entries: [makeEntry('e-1')],
      })
      queryClient.setQueryData(queryKeys.threads.snapshot(threadId), cachedSnapshot)

      const networkError = new Error('500 Internal Server Error')
      vi.spyOn(harnessService, 'getThreadSnapshot').mockRejectedValueOnce(networkError)

      await expect(fetchThreadSnapshot(queryClient, threadId)).rejects.toThrow('500 Internal Server Error')
      expect(harnessService.getThreadSnapshot).toHaveBeenCalledTimes(1)
    })

    it('explicitly refetches full exactly once when returned delta is missing baseline', async () => {
      const queryClient = new QueryClient()
      const threadId = 'thread-1'
      const cachedSnapshot = makeSnapshot({
        version: '1',
        thread: makeThread({ threadId, headEntryId: 'e-1', version: '1' }),
        baseEntryId: null,
        entries: [makeEntry('e-1')],
      })
      queryClient.setQueryData(queryKeys.threads.snapshot(threadId), cachedSnapshot)

      const deltaWithMissingBase = makeSnapshot({
        version: '5',
        thread: makeThread({ threadId, headEntryId: 'e-5', version: '5' }),
        baseEntryId: 'e-ancient',
        entries: [makeEntry('e-5', 'e-ancient')],
      })
      const fullSnapshot = makeSnapshot({
        version: '5',
        thread: makeThread({ threadId, headEntryId: 'e-5', version: '5' }),
        baseEntryId: null,
        entries: [makeEntry('e-1'), makeEntry('e-ancient'), makeEntry('e-5')],
      })
      vi.spyOn(harnessService, 'getThreadSnapshot')
        .mockResolvedValueOnce(deltaWithMissingBase)
        .mockResolvedValueOnce(fullSnapshot)

      const result = await fetchThreadSnapshot(queryClient, threadId)
      expect(harnessService.getThreadSnapshot).toHaveBeenNthCalledWith(1, threadId, {
        afterEntryId: 'e-1',
        signal: undefined,
      })
      expect(harnessService.getThreadSnapshot).toHaveBeenNthCalledWith(2, threadId, {
        signal: undefined,
      })
      expect(harnessService.getThreadSnapshot).toHaveBeenCalledTimes(2)
      expect(result.version).toBe('5')
      expect(result.entries.map((e) => e.entryId)).toEqual(['e-1', 'e-ancient', 'e-5'])
    })
  })
})
