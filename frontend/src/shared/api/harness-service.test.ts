import { afterEach, describe, expect, it, vi } from 'vitest'
import { createHarnessService } from '@/shared/api/harness-service'
import type { HttpClient } from '@/shared/api/client'

function createClient(): HttpClient {
  return {
    get: vi.fn(async () => ({})),
    post: vi.fn(async () => ({})),
    put: vi.fn(async () => ({})),
    delete: vi.fn(async () => ({})),
  }
}

describe('harnessService', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('maps Thread, entry, input and observability queries to the Thread API', async () => {
    const client = createClient()
    const service = createHarnessService(client)

    await service.listThreads()
    await service.createThread({ agentDefinitionId: '1', title: 'Draft' })
    await service.getThread('thread /1')
    await service.listSessionThreads('session /1')
    await service.submitThreadMessage('thread /1', { content: 'hello', clientMessageId: 'cid-1' })
    await service.setThreadYolo('thread /1', { yoloEnabled: true })
    await service.setThreadAgent('thread /1', { agentDefinitionId: '9' })
    await service.listThreadEntries('thread /1')
    await service.listThreadInputs('thread /1')
    await service.listThreadEvents('thread /1', '7')
    await service.listThreadEvents('thread /1', '9007199254740993', 50)
    await service.listThreadToolInvocations('thread /1')
    await service.getThreadUsage('thread /1')
    await service.listRootActivities('session /1', '9')
    await service.listSessionTasks('session /1')
    await service.decideToolInvocation('tool /3', 'allow')

    expect(client.get).toHaveBeenNthCalledWith(1, '/threads')
    expect(client.post).toHaveBeenNthCalledWith(1, '/threads', { agentDefinitionId: '1', title: 'Draft' })
    expect(client.get).toHaveBeenNthCalledWith(2, '/threads/thread%20%2F1')
    expect(client.get).toHaveBeenNthCalledWith(3, '/sessions/session%20%2F1/threads')
    expect(client.post).toHaveBeenNthCalledWith(2, '/threads/thread%20%2F1/messages', {
      content: 'hello',
      clientMessageId: 'cid-1',
    })
    expect(client.put).toHaveBeenNthCalledWith(1, '/threads/thread%20%2F1/yolo', { yoloEnabled: true })
    expect(client.put).toHaveBeenNthCalledWith(2, '/threads/thread%20%2F1/agent', { agentDefinitionId: '9' })
    expect(client.get).toHaveBeenNthCalledWith(4, '/threads/thread%20%2F1/entries')
    expect(client.get).toHaveBeenNthCalledWith(5, '/threads/thread%20%2F1/inputs')
    expect(client.get).toHaveBeenNthCalledWith(6, '/threads/thread%20%2F1/events', { params: { afterEventId: '7' } })
    expect(client.get).toHaveBeenNthCalledWith(7, '/threads/thread%20%2F1/events', {
      params: { afterEventId: '9007199254740993', limit: 50 },
    })
    expect(client.get).toHaveBeenNthCalledWith(8, '/threads/thread%20%2F1/tool-invocations')
    expect(client.get).toHaveBeenNthCalledWith(9, '/usage/threads/thread%20%2F1')
    expect(client.get).toHaveBeenNthCalledWith(10, '/sessions/session%20%2F1/activities', {
      params: { afterEventId: '9' },
    })
    expect(client.get).toHaveBeenNthCalledWith(11, '/sessions/session%20%2F1/tasks')
    expect(client.post).toHaveBeenNthCalledWith(3, '/tool-invocations/tool%20%2F3/decision', { decision: 'allow' })
  })

  it('creates cursor-based thread event streams with decimal string cursors', () => {
    const eventSource = vi.fn()
    vi.stubGlobal('EventSource', eventSource)
    const service = createHarnessService(createClient())

    service.createThreadEventStream('thread /2', '9007199254740993')

    expect(eventSource).toHaveBeenCalledWith(
      '/api/threads/thread%20%2F2/events/stream?afterEventId=9007199254740993',
    )
  })
})
