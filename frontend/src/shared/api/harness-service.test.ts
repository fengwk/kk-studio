import { afterEach, describe, expect, it, vi } from 'vitest'
import { createHarnessService } from '@/shared/api/harness-service'
import type { HttpClient } from '@/shared/api/client'

function createClient(): HttpClient {
  return { get: vi.fn(async () => ({})), post: vi.fn(async () => ({})), put: vi.fn(async () => ({})), delete: vi.fn(async () => ({})) }
}

describe('harnessService', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('exposes flat Session read endpoints and Thread actor endpoints', async () => {
    const client = createClient()
    const service = createHarnessService(client)
    await service.getSession('session /1')
    await service.listSessions()
    await service.listSessionEntries('session /1')
    await service.submitThreadMessage('thread /1', {
      content: 'hello',
      clientMessageId: 'cid-1',
      expectedExecutionEpoch: 3,
    })
    await service.stopThread('thread /1', { expectedExecutionEpoch: 4 })
    await service.setThreadModel('thread /1', {
      modelId: 'model-1',
      variant: 'default',
      clientMessageId: 'cid-model',
      expectedExecutionEpoch: 4,
    })
    await service.getThread('thread /1')
    await service.listThreadEntries('thread /1')
    await service.listThreadInputs('thread /1')
    await service.listThreadToolInvocations('thread /1')
    await service.getThreadUsage('thread /1')
    await service.setThreadYolo('thread /1', {
      yoloEnabled: true,
      clientMessageId: 'cid-yolo',
      expectedExecutionEpoch: 4,
    })
    await service.setThreadAgent('thread /1', {
      agentDefinitionId: 'agent-1',
      clientMessageId: 'cid-agent',
      expectedExecutionEpoch: 4,
    })
    await service.getRetryPolicy()
    await service.updateRetryPolicy({
      maxRetries: 3,
      backoffStrategy: 'EXPONENTIAL',
      baseDelayMillis: 2_000,
      maxDelayMillis: 60_000,
    })
    await service.getRealtimeStreamPolicy()
    await service.updateRealtimeStreamPolicy({ maxLength: 5_000 })

    expect(client.post).not.toHaveBeenCalledWith('/sessions', expect.anything())
    expect(client.post).toHaveBeenNthCalledWith(1, '/threads/thread%20%2F1/messages', {
      content: 'hello',
      clientMessageId: 'cid-1',
      expectedExecutionEpoch: 3,
    })
    // stop is no longer a bodyless POST: it carries the epoch fencing token too.
    expect(client.post).toHaveBeenNthCalledWith(2, '/threads/thread%20%2F1/stop', { expectedExecutionEpoch: 4 })
    expect(client.get).toHaveBeenCalledWith('/sessions')
    expect(client.get).toHaveBeenCalledWith('/sessions/session%20%2F1')
    expect(client.get).toHaveBeenCalledWith('/sessions/session%20%2F1/entries')
    expect(client.put).toHaveBeenNthCalledWith(1, '/threads/thread%20%2F1/model', {
      modelId: 'model-1',
      variant: 'default',
      clientMessageId: 'cid-model',
      expectedExecutionEpoch: 4,
    })
    expect(client.put).toHaveBeenNthCalledWith(2, '/threads/thread%20%2F1/yolo', {
      yoloEnabled: true,
      clientMessageId: 'cid-yolo',
      expectedExecutionEpoch: 4,
    })
    expect(client.put).toHaveBeenNthCalledWith(3, '/threads/thread%20%2F1/agent', {
      agentDefinitionId: 'agent-1',
      clientMessageId: 'cid-agent',
      expectedExecutionEpoch: 4,
    })
    expect(client.get).toHaveBeenCalledWith('/harness/retry-policy')
    expect(client.get).toHaveBeenLastCalledWith('/harness/realtime-stream-policy')
    expect(client.put).toHaveBeenNthCalledWith(4, '/harness/retry-policy', {
      maxRetries: 3,
      backoffStrategy: 'EXPONENTIAL',
      baseDelayMillis: 2_000,
      maxDelayMillis: 60_000,
    })
    expect(client.put).toHaveBeenNthCalledWith(5, '/harness/realtime-stream-policy', { maxLength: 5_000 })
    expect(client.put).not.toHaveBeenCalledWith(expect.stringContaining('/toolset'), expect.anything())
    expect(client.post).not.toHaveBeenCalledWith(expect.stringContaining('/decision'), expect.anything())
    // Session-scoped Thread create/list are gone from the contract.
    expect(client.get).not.toHaveBeenCalledWith(expect.stringContaining('/sessions/session%20%2F1/threads'))
    expect(client.post).not.toHaveBeenCalledWith(
      expect.stringContaining('/sessions/session%20%2F1/threads'),
      expect.anything(),
    )
  })

  it('maps the global Thread lifecycle: list, create UNBOUND, bootstrap and head rebind', async () => {
    const client = createClient()
    const service = createHarnessService(client)
    await service.listThreads()
    await service.createThread()
    await service.bootstrapThread('thread /1', {
      title: 'First',
      agentDefinitionId: 'agent-1',
      yoloEnabled: false,
      expectedExecutionEpoch: 0,
    })
    await service.updateThreadHead('thread /1', {
      headEntryId: '9007199254740993',
      expectedExecutionEpoch: 1,
    })
    // A null headEntryId clears the head back to UNBOUND.
    await service.updateThreadHead('thread /1', { headEntryId: null, expectedExecutionEpoch: 2 })

    expect(client.get).toHaveBeenNthCalledWith(1, '/threads')
    // POST /threads takes no body.
    expect(client.post).toHaveBeenNthCalledWith(1, '/threads')
    expect(client.post).toHaveBeenNthCalledWith(2, '/threads/thread%20%2F1/bootstrap', {
      title: 'First',
      agentDefinitionId: 'agent-1',
      yoloEnabled: false,
      expectedExecutionEpoch: 0,
    })
    expect(client.put).toHaveBeenNthCalledWith(1, '/threads/thread%20%2F1/head', {
      headEntryId: '9007199254740993',
      expectedExecutionEpoch: 1,
    })
    expect(client.put).toHaveBeenNthCalledWith(2, '/threads/thread%20%2F1/head', {
      headEntryId: null,
      expectedExecutionEpoch: 2,
    })
  })

  it('exposes the current flat Session and Thread command surface', async () => {
    const client = createClient()
    const service = createHarnessService(client)
    expect(service.listThreads).toBeTypeOf('function')
    expect(service.getThread).toBeTypeOf('function')
    expect(service.createThread).toBeTypeOf('function')
    expect(service.bootstrapThread).toBeTypeOf('function')
    expect(service.updateThreadHead).toBeTypeOf('function')
    expect(service.submitThreadMessage).toBeTypeOf('function')
    expect(service.setThreadYolo).toBeTypeOf('function')
    expect(service.setThreadAgent).toBeTypeOf('function')
    expect(service.setThreadModel).toBeTypeOf('function')
    expect(service.stopThread).toBeTypeOf('function')
    expect(service.getRetryPolicy).toBeTypeOf('function')
    expect(service.updateRetryPolicy).toBeTypeOf('function')
    expect(service.getRealtimeStreamPolicy).toBeTypeOf('function')
    expect(service.updateRealtimeStreamPolicy).toBeTypeOf('function')
    expect(service.listThreadEntries).toBeTypeOf('function')
    expect(service.listThreadInputs).toBeTypeOf('function')
    expect(service.listThreadToolInvocations).toBeTypeOf('function')
    expect(service.getThreadUsage).toBeTypeOf('function')
    expect(service.listSessions).toBeTypeOf('function')
    expect(service.getSession).toBeTypeOf('function')
    expect(service.listSessionEntries).toBeTypeOf('function')
    expect(service.createThreadRealtimeStream).toBeTypeOf('function')

    // End-to-end call shape for the Session entries endpoint.
    await service.listSessionEntries('42')
    expect(client.get).toHaveBeenLastCalledWith('/sessions/42/entries')
  })

  it('keeps Redis stream cursors as strings in SSE URLs', () => {
    const eventSource = vi.fn()
    vi.stubGlobal('EventSource', eventSource)
    createHarnessService(createClient()).createThreadRealtimeStream('1', '9007199254740993-0')
    expect(eventSource).toHaveBeenCalledWith('/api/threads/1/events/stream?afterEventId=9007199254740993-0')
  })
})
