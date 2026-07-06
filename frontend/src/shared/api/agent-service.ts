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
  AgentSessionHeadDTO,
  AgentSessionMessageCreateDTO,
  AgentSessionUpdateDTO,
  PageResult,
} from '@/shared/api/contracts'

export function createAgentService(client: HttpClient = apiClient) {
  return {
    listProviders: (pageNumber = 1, pageSize = 50): Promise<PageResult<AgentProviderDTO>> =>
      client.get('/agent/providers', { params: { pageNumber, pageSize } }),

    createProvider: (data: AgentProviderCreateDTO): Promise<AgentProviderDTO> => client.post('/agent/providers', data),

    updateProvider: (id: AgentResourceId, data: AgentProviderUpdateDTO): Promise<AgentProviderDTO> =>
      client.put(`/agent/providers/${encodeURIComponent(String(id))}`, data),

    deleteProvider: (id: AgentResourceId): Promise<void> =>
      client.delete(`/agent/providers/${encodeURIComponent(String(id))}`),

    listModels: (pageNumber = 1, pageSize = 50): Promise<PageResult<AgentModelDTO>> =>
      client.get('/agent/models', { params: { pageNumber, pageSize } }),

    createModel: (data: AgentModelCreateDTO): Promise<AgentModelDTO> => client.post('/agent/models', data),

    updateModel: (id: AgentResourceId, data: AgentModelUpdateDTO): Promise<AgentModelDTO> =>
      client.put(`/agent/models/${encodeURIComponent(String(id))}`, data),

    deleteModel: (id: AgentResourceId): Promise<void> =>
      client.delete(`/agent/models/${encodeURIComponent(String(id))}`),

    listAgents: (pageNumber = 1, pageSize = 50): Promise<PageResult<AgentDefinitionDTO>> =>
      client.get('/agent/agents', { params: { pageNumber, pageSize } }),

    createAgent: (data: AgentDefinitionCreateDTO): Promise<AgentDefinitionDTO> => client.post('/agent/agents', data),

    updateAgent: (id: AgentResourceId, data: AgentDefinitionUpdateDTO): Promise<AgentDefinitionDTO> =>
      client.put(`/agent/agents/${encodeURIComponent(String(id))}`, data),

    deleteAgent: (id: AgentResourceId): Promise<void> =>
      client.delete(`/agent/agents/${encodeURIComponent(String(id))}`),

    listSessions: (pageNumber = 1, pageSize = 50): Promise<PageResult<AgentSessionDTO>> =>
      client.get('/agent/sessions', { params: { pageNumber, pageSize } }),

    createSession: (data: AgentSessionCreateDTO): Promise<AgentSessionDTO> => client.post('/agent/sessions', data),

    getSession: (sessionId: string): Promise<AgentSessionDTO> => client.get(`/agent/sessions/${sessionId}`),

    updateSession: (sessionId: string, data: AgentSessionUpdateDTO): Promise<AgentSessionDTO> =>
      client.put(`/agent/sessions/${sessionId}`, data),

    deleteSession: (sessionId: string): Promise<void> => client.delete(`/agent/sessions/${sessionId}`),

    createMessage: (sessionId: string, data: AgentSessionMessageCreateDTO): Promise<AgentSessionEventDTO> =>
      client.post(`/agent/sessions/${sessionId}/messages`, data),

    listHeads: (sessionId: string): Promise<AgentSessionHeadDTO[]> => client.get(`/agent/sessions/${sessionId}/heads`),

    listEvents: (sessionId: string, headEventId?: string): Promise<AgentSessionEventDTO[]> =>
      client.get(`/agent/sessions/${sessionId}/events`, { params: headEventId ? { headEventId } : undefined }),

    listRuns: (sessionId: string): Promise<AgentRunDTO[]> => client.get(`/agent/sessions/${sessionId}/runs`),

    createEventStream: (sessionId: string, headEventId?: string): EventSource => {
      const params = new URLSearchParams()
      if (headEventId) {
        params.set('headEventId', headEventId)
      }
      const query = params.toString()
      const url = `${apiBaseUrl}/agent/sessions/${encodeURIComponent(sessionId)}/events/stream${query ? `?${query}` : ''}`
      return new EventSource(url)
    },
  }
}

export const agentService = createAgentService()
