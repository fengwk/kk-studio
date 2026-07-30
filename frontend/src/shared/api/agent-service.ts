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
  PageResult,
} from '@/shared/api/contracts'

export function createAgentService(client: HttpClient = apiClient) {
  return {
    listProviders: (pageNumber = 1, pageSize = 50): Promise<PageResult<AgentProviderDTO>> =>
      client.get('/ai/catalog/providers', { params: { pageNumber, pageSize } }),

    createProvider: (data: AgentProviderCreateDTO): Promise<AgentProviderDTO> =>
      client.post('/ai/catalog/providers', data),

    updateProvider: (id: AgentResourceId, data: AgentProviderUpdateDTO): Promise<AgentProviderDTO> =>
      client.put(`/ai/catalog/providers/${encodeURIComponent(id)}`, data),

    deleteProvider: (id: AgentResourceId, expectedVersion: string): Promise<void> =>
      client.delete(`/ai/catalog/providers/${encodeURIComponent(id)}`, { params: { expectedVersion } }),

    listModels: (pageNumber = 1, pageSize = 50): Promise<PageResult<AgentModelDTO>> =>
      client.get('/ai/catalog/models', { params: { pageNumber, pageSize } }),

    createModel: (data: AgentModelCreateDTO): Promise<AgentModelDTO> =>
      client.post('/ai/catalog/models', data),

    updateModel: (id: AgentResourceId, data: AgentModelUpdateDTO): Promise<AgentModelDTO> =>
      client.put(`/ai/catalog/models/${encodeURIComponent(id)}`, data),

    deleteModel: (id: AgentResourceId, expectedVersion: string): Promise<void> =>
      client.delete(`/ai/catalog/models/${encodeURIComponent(id)}`, { params: { expectedVersion } }),

    listAgents: (pageNumber = 1, pageSize = 50): Promise<PageResult<AgentDefinitionDTO>> =>
      client.get('/ai/catalog/agents', { params: { pageNumber, pageSize } }),

    createAgent: (data: AgentDefinitionCreateDTO): Promise<AgentDefinitionDTO> =>
      client.post('/ai/catalog/agents', data),

    updateAgent: (id: AgentResourceId, data: AgentDefinitionUpdateDTO): Promise<AgentDefinitionDTO> =>
      client.put(`/ai/catalog/agents/${encodeURIComponent(id)}`, data),

    deleteAgent: (id: AgentResourceId, expectedVersion: string): Promise<void> =>
      client.delete(`/ai/catalog/agents/${encodeURIComponent(id)}`, { params: { expectedVersion } }),

  }
}

export const agentService = createAgentService()
