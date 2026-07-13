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
  it('maps workspace resources to workspace-scoped paths and retains legacy session paths', async () => {
    const client = createClient()
    const service = createAgentService(client)

    await service.listWorkspaces(1, 10)
    await service.listProviders('workspace 1', 2, 20)
    await service.listModels('workspace 1', 3, 30)
    await service.listAgents('workspace 1', 4, 40)
    await service.listSessions(5, 50)

    expect(client.get).toHaveBeenNthCalledWith(1, '/workspaces', { params: { pageNumber: 1, pageSize: 10 } })
    expect(client.get).toHaveBeenNthCalledWith(2, '/workspaces/workspace%201/providers', { params: { pageNumber: 2, pageSize: 20 } })
    expect(client.get).toHaveBeenNthCalledWith(3, '/workspaces/workspace%201/models', { params: { pageNumber: 3, pageSize: 30 } })
    expect(client.get).toHaveBeenNthCalledWith(4, '/workspaces/workspace%201/agents', { params: { pageNumber: 4, pageSize: 40 } })
    expect(client.get).toHaveBeenNthCalledWith(5, '/agent/sessions', { params: { pageNumber: 5, pageSize: 50 } })
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

    await service.createProvider('workspace-1', providerBody)
    await service.updateProvider('workspace-1', 101, updateProviderBody)
    await service.deleteProvider('workspace-1', 101)
    await service.createModel('workspace-1', modelBody)
    await service.updateModel('workspace-1', 202, updateModelBody)
    await service.deleteModel('workspace-1', 202)
    await service.createAgent('workspace-1', agentBody)
    await service.updateAgent('workspace-1', 303, updateAgentBody)
    await service.deleteAgent('workspace-1', 303)

    expect(client.post).toHaveBeenNthCalledWith(1, '/workspaces/workspace-1/providers', providerBody)
    expect(client.put).toHaveBeenNthCalledWith(1, '/workspaces/workspace-1/providers/101', updateProviderBody)
    expect(client.delete).toHaveBeenNthCalledWith(1, '/workspaces/workspace-1/providers/101')
    expect(client.post).toHaveBeenNthCalledWith(2, '/workspaces/workspace-1/models', modelBody)
    expect(client.put).toHaveBeenNthCalledWith(2, '/workspaces/workspace-1/models/202', updateModelBody)
    expect(client.delete).toHaveBeenNthCalledWith(2, '/workspaces/workspace-1/models/202')
    expect(client.post).toHaveBeenNthCalledWith(3, '/workspaces/workspace-1/agents', agentBody)
    expect(client.put).toHaveBeenNthCalledWith(3, '/workspaces/workspace-1/agents/303', updateAgentBody)
    expect(client.delete).toHaveBeenNthCalledWith(3, '/workspaces/workspace-1/agents/303')
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
