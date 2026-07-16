import { describe, expect, it, vi } from 'vitest'
import { createAgentService, createSessionApi } from '@/shared/api/agent-service'
import type { HttpClient } from '@/shared/api/client'
import type { ModelUsageSummaryDTO } from '@/shared/api/contracts'

function createClient(): HttpClient {
  return {
    get: vi.fn(async () => ({})),
    post: vi.fn(async () => ({})),
    put: vi.fn(async () => ({})),
    delete: vi.fn(async () => ({})),
  }
}

describe('agentService', () => {
  it('maps global resources to top-level paths and retains legacy session paths', async () => {
    const client = createClient()
    const service = createAgentService(client)

    await service.listProviders(2, 20)
    await service.listModels(3, 30)
    await service.listAgents(4, 40)
    await service.listSessions(5, 50)

    expect(client.get).toHaveBeenNthCalledWith(1, '/providers', { params: { pageNumber: 2, pageSize: 20 } })
    expect(client.get).toHaveBeenNthCalledWith(2, '/models', { params: { pageNumber: 3, pageSize: 30 } })
    expect(client.get).toHaveBeenNthCalledWith(3, '/agents', { params: { pageNumber: 4, pageSize: 40 } })
    expect(client.get).toHaveBeenNthCalledWith(4, '/agent/sessions', { params: { pageNumber: 5, pageSize: 50 } })
  })

  it('uses the standard page defaults for global resource lists', async () => {
    const client = createClient()
    const service = createAgentService(client)

    await service.listProviders()
    await service.listModels()
    await service.listAgents()

    expect(client.get).toHaveBeenNthCalledWith(1, '/providers', { params: { pageNumber: 1, pageSize: 50 } })
    expect(client.get).toHaveBeenNthCalledWith(2, '/models', { params: { pageNumber: 1, pageSize: 50 } })
    expect(client.get).toHaveBeenNthCalledWith(3, '/agents', { params: { pageNumber: 1, pageSize: 50 } })
  })

  it('maps provider, model and agent mutations to global id-based CRUD endpoints', async () => {
    const client = createClient()
    const service = createAgentService(client)
    const providerBody = {
      name: 'minimax',
      providerType: 'openai',
      baseUrl: 'https://api.minimax.chat/v1',
      apiKey: 'key',
      timeoutMillis: 60000,
    }
    const modelBody = {
      provider: 'minimax',
      name: 'MiniMax-M2.7',
      defaultVariant: 'default',
      variantsJson: '[{"name":"default"}]',
    }
    const agentBody = {
      name: 'default-assistant',
      defaultProvider: 'minimax',
      defaultModel: 'MiniMax-M2.7',
      defaultVariant: 'default',
      toolsJson: '[]',
    }
    const updateProviderBody = { description: 'updated' }
    const updateModelBody = { defaultVariant: 'fast' }
    const updateAgentBody = { description: 'updated' }

    await service.createProvider(providerBody)
    await service.updateProvider(101, updateProviderBody)
    await service.deleteProvider(101)
    await service.createModel(modelBody)
    await service.updateModel(202, updateModelBody)
    await service.deleteModel(202)
    await service.createAgent(agentBody)
    await service.updateAgent(303, updateAgentBody)
    await service.deleteAgent(303)

    expect(client.post).toHaveBeenNthCalledWith(1, '/providers', providerBody)
    expect(client.put).toHaveBeenNthCalledWith(1, '/providers/101', updateProviderBody)
    expect(client.delete).toHaveBeenNthCalledWith(1, '/providers/101')
    expect(client.post).toHaveBeenNthCalledWith(2, '/models', modelBody)
    expect(client.put).toHaveBeenNthCalledWith(2, '/models/202', updateModelBody)
    expect(client.delete).toHaveBeenNthCalledWith(2, '/models/202')
    expect(client.post).toHaveBeenNthCalledWith(3, '/agents', agentBody)
    expect(client.put).toHaveBeenNthCalledWith(3, '/agents/303', updateAgentBody)
    expect(client.delete).toHaveBeenNthCalledWith(3, '/agents/303')
  })

  it('maps usage summaries to encoded run, session and model endpoints', async () => {
    // Explicit promise annotations keep all three service return contracts tied to the backend summary DTO.
    const client = createClient()
    const service = createAgentService(client)

    const runUsage: Promise<ModelUsageSummaryDTO> = service.getRunUsage('run /1')
    const sessionUsage: Promise<ModelUsageSummaryDTO> = service.getSessionUsage('session /2')
    const modelUsage: Promise<ModelUsageSummaryDTO> = service.getModelUsage('model /3')
    await Promise.all([runUsage, sessionUsage, modelUsage])

    expect(client.get).toHaveBeenNthCalledWith(1, '/usage/runs/run%20%2F1')
    expect(client.get).toHaveBeenNthCalledWith(2, '/usage/sessions/session%20%2F2')
    expect(client.get).toHaveBeenNthCalledWith(3, '/usage/models/model%20%2F3')
  })

  it('maps session detail, edit, delete, events, runs and message requests', async () => {
    const client = createClient()
    const service = createAgentService(client)

    await service.createSession({ agentName: 'default-assistant', title: 'Draft' })
    await service.getSession('session-1')
    await service.updateSession('session-1', { title: 'Renamed' })
    await service.deleteSession('session-1')
    await service.listEvents('session-1', 'event-9')
    await service.listRuns('session-1')
    await service.createMessage('session-1', { content: 'hello' })

    expect(client.post).toHaveBeenNthCalledWith(1, '/agent/sessions', { agentName: 'default-assistant', title: 'Draft' })
    expect(client.get).toHaveBeenNthCalledWith(1, '/agent/sessions/session-1')
    expect(client.put).toHaveBeenNthCalledWith(1, '/agent/sessions/session-1', { title: 'Renamed' })
    expect(client.delete).toHaveBeenNthCalledWith(1, '/agent/sessions/session-1')
    expect(client.get).toHaveBeenNthCalledWith(2, '/agent/sessions/session-1/events', { params: { headEventId: 'event-9' } })
    expect(client.get).toHaveBeenNthCalledWith(3, '/agent/sessions/session-1/runs')
    expect(client.post).toHaveBeenNthCalledWith(2, '/agent/sessions/session-1/messages', { content: 'hello' })
  })

  it('exposes the legacy session service through a context-free adapter', async () => {
    // Exercising every method proves the temporary adapter needs only the service dependency.
    const client = createClient()
    const sessionApi = createSessionApi(createAgentService(client))
    const eventSourceMock = vi.fn()
    vi.stubGlobal('EventSource', eventSourceMock)

    await sessionApi.list()
    await sessionApi.create({ agentName: 'default-assistant' })
    await sessionApi.get('session-1')
    await sessionApi.update('session-1', { title: 'Renamed' })
    await sessionApi.remove('session-1')
    await sessionApi.createMessage('session-1', { content: 'hello' })
    await sessionApi.listEvents('session-1')
    await sessionApi.listRuns('session-1')
    sessionApi.createEventStream('session-1')

    expect(client.get).toHaveBeenCalledWith('/agent/sessions', { params: { pageNumber: 1, pageSize: 50 } })
    expect(client.get).toHaveBeenCalledWith('/agent/sessions/session-1/events', { params: undefined })
    expect(eventSourceMock).toHaveBeenCalledWith('/api/agent/sessions/session-1/events/stream')

    vi.unstubAllGlobals()
  })

  it('creates event streams with encoded session ids and optional head event query', () => {
    const client = createClient()
    const service = createAgentService(client)
    const eventSourceMock = vi.fn()
    vi.stubGlobal('EventSource', eventSourceMock)

    service.createEventStream('session 1')
    service.createEventStream('session/1', 'event-9')

    expect(eventSourceMock).toHaveBeenNthCalledWith(1, '/api/agent/sessions/session%201/events/stream')
    expect(eventSourceMock).toHaveBeenNthCalledWith(2, '/api/agent/sessions/session%2F1/events/stream?headEventId=event-9')

    vi.unstubAllGlobals()
  })
})
