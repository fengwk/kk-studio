import { describe, expect, it, vi } from 'vitest'
import { createAgentService } from '@/shared/api/agent-service'
import type { HttpClient } from '@/shared/api/client'

function createClient(): HttpClient {
  return {
    get: vi.fn(async () => ({})),
    post: vi.fn(async () => ({})),
    put: vi.fn(async () => ({})),
    delete: vi.fn(async () => ({})),
  }
}

describe('agentService', () => {
  it('maps resource list requests to provider/model/agent/session backend paths', async () => {
    const client = createClient()
    const service = createAgentService(client)

    await service.listProviders(2, 20)
    await service.listModels(3, 30)
    await service.listAgents(4, 40)
    await service.listSessions(5, 50)

    expect(client.get).toHaveBeenNthCalledWith(1, '/agent/providers', { params: { pageNumber: 2, pageSize: 20 } })
    expect(client.get).toHaveBeenNthCalledWith(2, '/agent/models', { params: { pageNumber: 3, pageSize: 30 } })
    expect(client.get).toHaveBeenNthCalledWith(3, '/agent/agents', { params: { pageNumber: 4, pageSize: 40 } })
    expect(client.get).toHaveBeenNthCalledWith(4, '/agent/sessions', { params: { pageNumber: 5, pageSize: 50 } })
  })

  it('maps provider, model and agent mutations to id-based CRUD endpoints', async () => {
    const client = createClient()
    const service = createAgentService(client)
    const providerBody = {
      name: 'minimax',
      providerType: 'openai',
      baseUrl: 'https://api.minimax.chat/v1',
      apiKey: 'key',
      timeoutMillis: 60000,
      streamIdleTimeoutMillis: 60000,
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
      subagentsJson: '[]',
      skillsJson: '[]',
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

    expect(client.post).toHaveBeenNthCalledWith(1, '/agent/providers', providerBody)
    expect(client.put).toHaveBeenNthCalledWith(1, '/agent/providers/101', updateProviderBody)
    expect(client.delete).toHaveBeenNthCalledWith(1, '/agent/providers/101')
    expect(client.post).toHaveBeenNthCalledWith(2, '/agent/models', modelBody)
    expect(client.put).toHaveBeenNthCalledWith(2, '/agent/models/202', updateModelBody)
    expect(client.delete).toHaveBeenNthCalledWith(2, '/agent/models/202')
    expect(client.post).toHaveBeenNthCalledWith(3, '/agent/agents', agentBody)
    expect(client.put).toHaveBeenNthCalledWith(3, '/agent/agents/303', updateAgentBody)
    expect(client.delete).toHaveBeenNthCalledWith(3, '/agent/agents/303')
  })

  it('maps session detail, edit, delete, events, runs and message requests', async () => {
    const client = createClient()
    const service = createAgentService(client)

    await service.createSession({ agentName: 'default-assistant', title: 'Draft' })
    await service.getSession('session-1')
    await service.updateSession('session-1', { title: 'Renamed' })
    await service.deleteSession('session-1')
    await service.listHeads('session-1')
    await service.listEvents('session-1', 'event-9')
    await service.listRuns('session-1')
    await service.createMessage('session-1', { content: 'hello' })

    expect(client.post).toHaveBeenNthCalledWith(1, '/agent/sessions', { agentName: 'default-assistant', title: 'Draft' })
    expect(client.get).toHaveBeenNthCalledWith(1, '/agent/sessions/session-1')
    expect(client.put).toHaveBeenNthCalledWith(1, '/agent/sessions/session-1', { title: 'Renamed' })
    expect(client.delete).toHaveBeenNthCalledWith(1, '/agent/sessions/session-1')
    expect(client.get).toHaveBeenNthCalledWith(2, '/agent/sessions/session-1/heads')
    expect(client.get).toHaveBeenNthCalledWith(3, '/agent/sessions/session-1/events', { params: { headEventId: 'event-9' } })
    expect(client.get).toHaveBeenNthCalledWith(4, '/agent/sessions/session-1/runs')
    expect(client.post).toHaveBeenNthCalledWith(2, '/agent/sessions/session-1/messages', { content: 'hello' })
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
