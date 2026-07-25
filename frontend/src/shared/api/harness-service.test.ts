import { afterEach, describe, expect, it, vi } from 'vitest'
import { createHarnessService } from '@/shared/api/harness-service'
import type { HttpClient } from '@/shared/api/client'

function createClient(): HttpClient {
  return { get: vi.fn(async () => ({})), post: vi.fn(async () => ({})), put: vi.fn(async () => ({})), delete: vi.fn(async () => ({})) }
}

describe('harnessService', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('uses agentless Session create and Thread actor endpoints without toolset', async () => {
    const client = createClient()
    const service = createHarnessService(client)
    await service.createSession({ title: 'Draft' })
    await service.getSession('session /1')
    await service.listSessionThreads('session /1')
    await service.createSessionThread('session /1', { fromEntryId: '9007199254740993' })
    await service.submitThreadMessage('thread /1', { content: 'hello', clientMessageId: 'cid-1' })
    await service.stopThread('thread /1')
    await service.setThreadModel('thread /1', { modelId: 'model-1', variant: 'default', clientMessageId: 'cid-model' })
    await service.getThread('thread /1')
    await service.listThreadEntries('thread /1')
    await service.listThreadInputs('thread /1')
    await service.listThreadToolInvocations('thread /1')
    await service.getThreadUsage('thread /1')
    await service.setThreadYolo('thread /1', { yoloEnabled: true, clientMessageId: 'cid-yolo' })
    await service.setThreadAgent('thread /1', { agentDefinitionId: 'agent-1', clientMessageId: 'cid-agent' })
    await service.listSessions()
    await service.listSessionEntries('session /1')
    await service.listRootActivities('session /1', '9')
    await service.listSessionTasks('session /1')
    await service.getRetryPolicy()
    await service.updateRetryPolicy({
      maxRetries: 3,
      backoffStrategy: 'EXPONENTIAL',
      baseDelayMillis: 2_000,
      maxDelayMillis: 60_000,
    })

    expect(client.post).toHaveBeenNthCalledWith(1, '/sessions', { title: 'Draft' })
    expect(client.get).toHaveBeenNthCalledWith(1, '/sessions/session%20%2F1')
    expect(client.get).toHaveBeenNthCalledWith(2, '/sessions/session%20%2F1/threads')
    expect(client.post).toHaveBeenNthCalledWith(2, '/sessions/session%20%2F1/threads', { fromEntryId: '9007199254740993' })
    expect(client.post).toHaveBeenNthCalledWith(3, '/threads/thread%20%2F1/messages', { content: 'hello', clientMessageId: 'cid-1' })
    expect(client.post).toHaveBeenNthCalledWith(4, '/threads/thread%20%2F1/stop')
    expect(client.put).toHaveBeenNthCalledWith(1, '/threads/thread%20%2F1/model', { modelId: 'model-1', variant: 'default', clientMessageId: 'cid-model' })
    expect(client.put).toHaveBeenNthCalledWith(2, '/threads/thread%20%2F1/yolo', { yoloEnabled: true, clientMessageId: 'cid-yolo' })
    expect(client.put).toHaveBeenNthCalledWith(3, '/threads/thread%20%2F1/agent', { agentDefinitionId: 'agent-1', clientMessageId: 'cid-agent' })
    expect(client.get).toHaveBeenLastCalledWith('/harness/retry-policy')
    expect(client.put).toHaveBeenNthCalledWith(4, '/harness/retry-policy', {
      maxRetries: 3,
      backoffStrategy: 'EXPONENTIAL',
      baseDelayMillis: 2_000,
      maxDelayMillis: 60_000,
    })
    expect(client.put).not.toHaveBeenCalledWith(expect.stringContaining('/toolset'), expect.anything())
    expect(client.post).not.toHaveBeenCalledWith(expect.stringContaining('/decision'), expect.anything())
  })

  it('keeps Redis stream cursors as strings in SSE URLs', () => {
    const eventSource = vi.fn()
    vi.stubGlobal('EventSource', eventSource)
    createHarnessService(createClient()).createThreadRealtimeStream('1', '9007199254740993-0')
    expect(eventSource).toHaveBeenCalledWith('/api/threads/1/events/stream?afterEventId=9007199254740993-0')
  })
})
