import { apiClient, type HttpClient } from '@/shared/api/client'
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
      client.put(`/providers/${encodeURIComponent(id)}`, data),

    deleteProvider: (id: AgentResourceId, expectedVersion: string): Promise<void> =>
      client.delete(`/providers/${encodeURIComponent(id)}`, { params: { expectedVersion } }),

    listModels: (pageNumber = 1, pageSize = 50): Promise<PageResult<AgentModelDTO>> =>
      client.get('/models', { params: { pageNumber, pageSize } }),

    createModel: (data: AgentModelCreateDTO): Promise<AgentModelDTO> =>
      client.post('/models', data),

    updateModel: (id: AgentResourceId, data: AgentModelUpdateDTO): Promise<AgentModelDTO> =>
      client.put(`/models/${encodeURIComponent(id)}`, data),

    deleteModel: (id: AgentResourceId, expectedVersion: string): Promise<void> =>
      client.delete(`/models/${encodeURIComponent(id)}`, { params: { expectedVersion } }),

    listAgents: (pageNumber = 1, pageSize = 50): Promise<PageResult<AgentDefinitionDTO>> =>
      client.get('/agents', { params: { pageNumber, pageSize } }),

    createAgent: (data: AgentDefinitionCreateDTO): Promise<AgentDefinitionDTO> =>
      client.post('/agents', data),

    updateAgent: (id: AgentResourceId, data: AgentDefinitionUpdateDTO): Promise<AgentDefinitionDTO> =>
      client.put(`/agents/${encodeURIComponent(id)}`, data),

    deleteAgent: (id: AgentResourceId, expectedVersion: string): Promise<void> =>
      client.delete(`/agents/${encodeURIComponent(id)}`, { params: { expectedVersion } }),

    getSessionUsage: (sessionId: string): Promise<ModelUsageSummaryDTO> =>
      client.get(`/usage/sessions/${encodeURIComponent(sessionId)}`),

    getModelUsage: (modelId: AgentResourceId): Promise<ModelUsageSummaryDTO> =>
      client.get(`/usage/models/${encodeURIComponent(modelId)}`),

  }
}

export const agentService = createAgentService()
