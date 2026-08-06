import { afterEach, describe, expect, it, vi } from 'vitest'
import { createHarnessService } from '@/shared/api/harness-service'
import type { HttpClient } from '@/shared/api/client'

function createClient(): HttpClient {
  return { get: vi.fn(async () => ({})), post: vi.fn(async () => ({})), put: vi.fn(async () => ({})), delete: vi.fn(async () => ({})) }
}

describe('harnessService', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('exposes the snapshot-first Thread command surface', async () => {
    const client = createClient()
    const service = createHarnessService(client)
    await service.getThreadSnapshot('thread /1')
    await service.enqueueCommands('thread /1', {
      expectedHeadEntryId: '1',
      expectedNextCommandSequence: '0',
      commands: [
        { type: 'USER_MESSAGE', clientCommandId: 'cid-1', content: 'hello', role: 'user' },
        { type: 'SET_YOLO', clientCommandId: 'cid-2', yoloEnabled: true },
      ],
    })
    await service.stopThread('thread /1', { stopRequestId: 'stop-1', expectedRevision: '5' })
    await service.updateThreadHead('thread /1', {
      targetEntryId: '9007199254740993',
      expectedRevision: '5',
    })
    await service.decideApproval('thread /1', 'inv-9', {
      decision: 'ALLOW',
      decisionId: 'dec-1',
      actor: 'web',
      reason: null,
    })

    expect(client.get).toHaveBeenCalledWith('/ai/runtime/threads/thread%20%2F1/snapshot')
    expect(client.post).toHaveBeenNthCalledWith(1, '/ai/runtime/threads/thread%20%2F1/commands', {
      expectedHeadEntryId: '1',
      expectedNextCommandSequence: '0',
      commands: [
        { type: 'USER_MESSAGE', clientCommandId: 'cid-1', content: 'hello', role: 'user' },
        { type: 'SET_YOLO', clientCommandId: 'cid-2', yoloEnabled: true },
      ],
    })
    expect(client.post).toHaveBeenNthCalledWith(2, '/ai/runtime/threads/thread%20%2F1/stop', {
      stopRequestId: 'stop-1',
      expectedRevision: '5',
    })
    expect(client.post).toHaveBeenNthCalledWith(3, '/ai/runtime/threads/thread%20%2F1/tool-invocations/inv-9/approval', {
      decision: 'ALLOW',
      decisionId: 'dec-1',
      actor: 'web',
      reason: null,
    })
    expect(client.put).toHaveBeenNthCalledWith(1, '/ai/runtime/threads/thread%20%2F1/head', {
      targetEntryId: '9007199254740993',
      expectedRevision: '5',
    })
  })

  it('exposes only the snapshot-first Thread command surface', async () => {
    const client = createClient()
    const service = createHarnessService(client)
    expect(service.getThreadSnapshot).toBeTypeOf('function')
    expect(service.enqueueCommands).toBeTypeOf('function')
    expect(service.updateThreadHead).toBeTypeOf('function')
    expect(service.stopThread).toBeTypeOf('function')
    expect(service.decideApproval).toBeTypeOf('function')
    expect(service.createThreadRealtimeStream).toBeTypeOf('function')

    // Old endpoints are gone from the contract entirely.
    expect(service).not.toHaveProperty('listThreads')
    expect(service).not.toHaveProperty('listSessions')
    expect(service).not.toHaveProperty('listSessionEntries')
    expect(service).not.toHaveProperty('submitThreadMessage')
    expect(service).not.toHaveProperty('submitCustomMessage')
    expect(service).not.toHaveProperty('updateThreadEnvironment')
    expect(service).not.toHaveProperty('getRetryPolicy')
    expect(service).not.toHaveProperty('getRealtimeStreamPolicy')
  })

  it('uses the durable revision as the SSE resume cursor', () => {
    const eventSource = vi.fn()
    vi.stubGlobal('EventSource', eventSource)
    createHarnessService(createClient()).createThreadRealtimeStream('1', '9007199254740993')
    expect(eventSource).toHaveBeenCalledWith('/api/ai/runtime/threads/1/events/stream?afterRevision=9007199254740993')
  })
})
