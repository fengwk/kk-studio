import { apiBaseUrl, apiClient, type HttpClient } from '@/shared/api/client'
import type {
  AgentDefinitionCreateDTO,
  AgentDefinitionDTO,
  AgentDefinitionUpdateDTO,
  AgentModelCreateDTO,
  AgentModelDTO,
  AgentModelUpdateDTO,
  AgentProviderCreateDTO,
  AgentProviderDTO,
  AgentProviderUpdateDTO,
  AgentResourceId,
  AgentRunDTO,
  AgentSessionCreateDTO,
  AgentSessionDTO,
  AgentSessionEventDTO,
  AgentSessionMessageCreateDTO,
  AgentSessionUpdateDTO,
  ModelUsageSummaryDTO,
  PageResult,
} from '@/shared/api/contracts'

export function createAgentService(client: HttpClient = apiClient) {
  return {
    listProviders: (pageNumber = 1, pageSize = 50): Promise<PageResult<AgentProviderDTO>> =>
      client.get('/providers', { params: { pageNumber, pageSize } }),

    createProvider: (data: AgentProviderCreateDTO): Promise<AgentProviderDTO> =>
      client.post('/providers', data),

    updateProvider: (id: AgentResourceId, data: AgentProviderUpdateDTO): Promise<AgentProviderDTO> =>
      client.put(`/providers/${encodeURIComponent(String(id))}`, data),

    deleteProvider: (id: AgentResourceId): Promise<void> =>
      client.delete(`/providers/${encodeURIComponent(String(id))}`),

    listModels: (pageNumber = 1, pageSize = 50): Promise<PageResult<AgentModelDTO>> =>
      client.get('/models', { params: { pageNumber, pageSize } }),

    createModel: (data: AgentModelCreateDTO): Promise<AgentModelDTO> =>
      client.post('/models', data),

    updateModel: (id: AgentResourceId, data: AgentModelUpdateDTO): Promise<AgentModelDTO> =>
      client.put(`/models/${encodeURIComponent(String(id))}`, data),

    deleteModel: (id: AgentResourceId): Promise<void> =>
      client.delete(`/models/${encodeURIComponent(String(id))}`),

    listAgents: (pageNumber = 1, pageSize = 50): Promise<PageResult<AgentDefinitionDTO>> =>
      client.get('/agents', { params: { pageNumber, pageSize } }),

    createAgent: (data: AgentDefinitionCreateDTO): Promise<AgentDefinitionDTO> =>
      client.post('/agents', data),

    updateAgent: (id: AgentResourceId, data: AgentDefinitionUpdateDTO): Promise<AgentDefinitionDTO> =>
      client.put(`/agents/${encodeURIComponent(String(id))}`, data),

    deleteAgent: (id: AgentResourceId): Promise<void> =>
      client.delete(`/agents/${encodeURIComponent(String(id))}`),

    getRunUsage: (runId: string): Promise<ModelUsageSummaryDTO> =>
      client.get(`/usage/runs/${encodeURIComponent(runId)}`),

    getSessionUsage: (sessionId: string): Promise<ModelUsageSummaryDTO> =>
      client.get(`/usage/sessions/${encodeURIComponent(sessionId)}`),

    getModelUsage: (modelId: AgentResourceId): Promise<ModelUsageSummaryDTO> =>
      client.get(`/usage/models/${encodeURIComponent(String(modelId))}`),

    // Session endpoints remain on the legacy API until T15 moves them.
    listSessions: (pageNumber = 1, pageSize = 50): Promise<PageResult<AgentSessionDTO>> =>
      client.get('/agent/sessions', { params: { pageNumber, pageSize } }),
    createSession: (data: AgentSessionCreateDTO): Promise<AgentSessionDTO> => client.post('/agent/sessions', data),
    getSession: (sessionId: string): Promise<AgentSessionDTO> => client.get(`/agent/sessions/${encodeURIComponent(sessionId)}`),
    updateSession: (sessionId: string, data: AgentSessionUpdateDTO): Promise<AgentSessionDTO> =>
      client.put(`/agent/sessions/${encodeURIComponent(sessionId)}`, data),
    deleteSession: (sessionId: string): Promise<void> => client.delete(`/agent/sessions/${encodeURIComponent(sessionId)}`),
    createMessage: (sessionId: string, data: AgentSessionMessageCreateDTO): Promise<AgentSessionEventDTO> =>
      client.post(`/agent/sessions/${encodeURIComponent(sessionId)}/messages`, data),
    listEvents: (sessionId: string, headEventId?: string): Promise<AgentSessionEventDTO[]> =>
      client.get(`/agent/sessions/${encodeURIComponent(sessionId)}/events`, { params: headEventId ? { headEventId } : undefined }),
    listRuns: (sessionId: string): Promise<AgentRunDTO[]> => client.get(`/agent/sessions/${encodeURIComponent(sessionId)}/runs`),
    createEventStream: (sessionId: string, headEventId?: string): EventSource => {
      const params = new URLSearchParams()
      if (headEventId) {
        params.set('headEventId', headEventId)
      }
      const query = params.toString()
      return new EventSource(`${apiBaseUrl}/agent/sessions/${encodeURIComponent(sessionId)}/events/stream${query ? `?${query}` : ''}`)
    },
  }
}

export const agentService = createAgentService()

/**
 * Temporary boundary for the legacy Session API. T15 can replace this adapter
 * with global endpoints without changing feature controllers.
 */
export function createSessionApi(service = agentService) {
  return {
    list: () => service.listSessions(),
    create: (data: AgentSessionCreateDTO) => service.createSession(data),
    get: (sessionId: string) => service.getSession(sessionId),
    update: (sessionId: string, data: AgentSessionUpdateDTO) => service.updateSession(sessionId, data),
    remove: (sessionId: string) => service.deleteSession(sessionId),
    createMessage: (sessionId: string, data: AgentSessionMessageCreateDTO) => service.createMessage(sessionId, data),
    listEvents: (sessionId: string, headEventId?: string) => service.listEvents(sessionId, headEventId),
    listRuns: (sessionId: string) => service.listRuns(sessionId),
    createEventStream: (sessionId: string, headEventId?: string) => service.createEventStream(sessionId, headEventId),
  }
}
