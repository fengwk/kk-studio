import { afterEach, describe, expect, it, vi } from 'vitest'
import { createHarnessService } from '@/shared/api/harness-service'
import type { HttpClient } from '@/shared/api/client'

function createClient(): HttpClient {
  return { get: vi.fn(async () => ({})), post: vi.fn(async () => ({})), put: vi.fn(async () => ({})), delete: vi.fn(async () => ({})) }
}

describe('harnessService', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('exposes flat Session read endpoints and the snapshot-first Thread command surface', async () => {
    const client = createClient()
    const service = createHarnessService(client)
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
    await service.getThreadSnapshot('thread /1')
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

    expect(client.post).not.toHaveBeenCalledWith('/ai/runtime/sessions', expect.anything())
    expect(client.post).toHaveBeenNthCalledWith(1, '/ai/runtime/threads/thread%20%2F1/messages', {
      content: 'hello',
      clientMessageId: 'cid-1',
      expectedExecutionEpoch: 3,
    })
    // stop is no longer a bodyless POST: it carries the epoch fencing token too.
    expect(client.post).toHaveBeenNthCalledWith(2, '/ai/runtime/threads/thread%20%2F1/stop', { expectedExecutionEpoch: 4 })
    expect(client.get).toHaveBeenCalledWith('/ai/runtime/sessions')
    expect(client.get).toHaveBeenCalledWith('/ai/runtime/sessions/session%20%2F1/entries')
    expect(client.get).toHaveBeenCalledWith('/ai/runtime/threads/thread%20%2F1/snapshot')
    expect(client.put).toHaveBeenNthCalledWith(1, '/ai/runtime/threads/thread%20%2F1/model', {
      modelId: 'model-1',
      variant: 'default',
      clientMessageId: 'cid-model',
      expectedExecutionEpoch: 4,
    })
    expect(client.put).toHaveBeenNthCalledWith(2, '/ai/runtime/threads/thread%20%2F1/yolo', {
      yoloEnabled: true,
      clientMessageId: 'cid-yolo',
      expectedExecutionEpoch: 4,
    })
    expect(client.put).toHaveBeenNthCalledWith(3, '/ai/runtime/threads/thread%20%2F1/agent', {
      agentDefinitionId: 'agent-1',
      clientMessageId: 'cid-agent',
      expectedExecutionEpoch: 4,
    })
    expect(client.get).toHaveBeenCalledWith('/ai/runtime/settings/retry-policy')
    expect(client.get).toHaveBeenLastCalledWith('/ai/runtime/settings/realtime-stream-policy')
    expect(client.put).toHaveBeenNthCalledWith(4, '/ai/runtime/settings/retry-policy', {
      maxRetries: 3,
      backoffStrategy: 'EXPONENTIAL',
      baseDelayMillis: 2_000,
      maxDelayMillis: 60_000,
    })
    expect(client.put).toHaveBeenNthCalledWith(5, '/ai/runtime/settings/realtime-stream-policy', { maxLength: 5_000 })
    expect(client.put).not.toHaveBeenCalledWith(expect.stringContaining('/toolset'), expect.anything())
    expect(client.post).not.toHaveBeenCalledWith(expect.stringContaining('/decision'), expect.anything())
    // Session-scoped Thread create/list are gone from the contract.
    expect(client.get).not.toHaveBeenCalledWith(expect.stringContaining('/ai/runtime/sessions/session%20%2F1/threads'))
    expect(client.post).not.toHaveBeenCalledWith(
      expect.stringContaining('/ai/runtime/sessions/session%20%2F1/threads'),
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

    expect(client.get).toHaveBeenNthCalledWith(1, '/ai/runtime/threads', {
      params: { sort: 'recent', limit: 20 },
    })
    // POST /ai/runtime/threads takes no body.
    expect(client.post).toHaveBeenNthCalledWith(1, '/ai/runtime/threads')
    expect(client.post).toHaveBeenNthCalledWith(2, '/ai/runtime/threads/thread%20%2F1/bootstrap', {
      title: 'First',
      agentDefinitionId: 'agent-1',
      yoloEnabled: false,
      expectedExecutionEpoch: 0,
    })
    expect(client.put).toHaveBeenNthCalledWith(1, '/ai/runtime/threads/thread%20%2F1/head', {
      headEntryId: '9007199254740993',
      expectedExecutionEpoch: 1,
    })
    expect(client.put).toHaveBeenNthCalledWith(2, '/ai/runtime/threads/thread%20%2F1/head', {
      headEntryId: null,
      expectedExecutionEpoch: 2,
    })
  })

  it('exposes the current snapshot-first Session and Thread command surface', async () => {
    const client = createClient()
    const service = createHarnessService(client)
    expect(service.listThreads).toBeTypeOf('function')
    expect(service.getThreadSnapshot).toBeTypeOf('function')
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
    expect(service.listSessions).toBeTypeOf('function')
    expect(service.listSessionEntries).toBeTypeOf('function')
    expect(service.createThreadRealtimeStream).toBeTypeOf('function')

    // End-to-end call shape for the Session entries endpoint.
    await service.listSessionEntries('42')
    expect(client.get).toHaveBeenLastCalledWith('/ai/runtime/sessions/42/entries')
  })

  it('uses the durable revision as the SSE resume cursor', () => {
    const eventSource = vi.fn()
    vi.stubGlobal('EventSource', eventSource)
    createHarnessService(createClient()).createThreadRealtimeStream('1', '9007199254740993')
    expect(eventSource).toHaveBeenCalledWith('/api/ai/runtime/threads/1/events/stream?afterRevision=9007199254740993')
  })

  it('passes opaque keyset pagination parameters without decoding the cursor', async () => {
    const client = createClient()
    const service = createHarnessService(client)
    await service.listThreads({ sort: 'created', cursor: 'opaque/cursor', limit: 3 })
    expect(client.get).toHaveBeenCalledWith('/ai/runtime/threads', {
      params: { sort: 'created', limit: 3, cursor: 'opaque/cursor' },
    })
  })
})
