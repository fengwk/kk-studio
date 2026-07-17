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

  it('maps session, entry, run and observability queries to the Harness API', async () => {
    const client = createClient()
    const service = createHarnessService(client)

    await service.listSessions()
    await service.createSession({ agentDefinitionId: '1', title: 'Draft' })
    await service.getSession('session /1')
    await service.listEntries('session /1')
    await service.createMessage('session /1', { content: 'hello', expectedLeafEntryId: '2' })
    await service.listRuns('session /1')
    await service.listRunEvents('run /2', 7)
    await service.listRootActivities('session /1', 9)
    await service.listToolInvocations('run /2')
    await service.getYolo('session /1')
    await service.setYolo('session /1', true)
    await service.decideToolInvocation('tool /3', 'allow')

    expect(client.get).toHaveBeenNthCalledWith(1, '/sessions')
    expect(client.post).toHaveBeenNthCalledWith(1, '/sessions', { agentDefinitionId: '1', title: 'Draft' })
    expect(client.get).toHaveBeenNthCalledWith(2, '/sessions/session%20%2F1')
    expect(client.get).toHaveBeenNthCalledWith(3, '/sessions/session%20%2F1/entries')
    expect(client.post).toHaveBeenNthCalledWith(2, '/sessions/session%20%2F1/messages', { content: 'hello', expectedLeafEntryId: '2' })
    expect(client.get).toHaveBeenNthCalledWith(4, '/sessions/session%20%2F1/runs')
    expect(client.get).toHaveBeenNthCalledWith(5, '/runs/run%20%2F2/events', { params: { afterSequence: 7 } })
    expect(client.get).toHaveBeenNthCalledWith(6, '/sessions/session%20%2F1/activities', { params: { afterEventId: 9 } })
    expect(client.get).toHaveBeenNthCalledWith(7, '/runs/run%20%2F2/tool-invocations')
    expect(client.get).toHaveBeenNthCalledWith(8, '/sessions/session%20%2F1/yolo')
    expect(client.put).toHaveBeenCalledWith('/sessions/session%20%2F1/yolo', { enabled: true })
    expect(client.post).toHaveBeenNthCalledWith(3, '/tool-invocations/tool%20%2F3/decision', { decision: 'allow' })
  })

  it('creates cursor-based run and root activity streams', () => {
    const eventSource = vi.fn()
    vi.stubGlobal('EventSource', eventSource)
    const service = createHarnessService(createClient())

    service.createRunEventStream('run /2', 7)
    service.createRootActivityStream('session /1', 9)

    expect(eventSource).toHaveBeenNthCalledWith(1, '/api/runs/run%20%2F2/events/stream?afterSequence=7')
    expect(eventSource).toHaveBeenNthCalledWith(2, '/api/sessions/session%20%2F1/activities/stream?afterEventId=9')
  })
})
