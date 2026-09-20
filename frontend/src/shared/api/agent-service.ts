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
  SkillPackageCheckDTO,
  SkillPackageCreateDTO,
  SkillPackageDTO,
  SkillPackageEditDTO,
  SkillPackagePublishDTO,
  ToolCatalogEntryDTO,
} from '@/shared/api/contracts/ai-catalog'
import type { PageResult } from '@/shared/api/contracts/base'

function encodePathTail(value: string): string {
  return value.split('/').map(encodeURIComponent).join('/')
}

export function createAgentService(client: HttpClient = apiClient) {
  return {
    listProviders: (pageNumber = 1, pageSize = 50): Promise<PageResult<AgentProviderDTO>> =>
      client.get('/ai/catalog/providers', { params: { pageNumber, pageSize } }),

    createProvider: (data: AgentProviderCreateDTO): Promise<AgentProviderDTO> =>
      client.post('/ai/catalog/providers', data),

    updateProvider: (name: string, data: AgentProviderUpdateDTO): Promise<AgentProviderDTO> =>
      client.put(`/ai/catalog/providers/${encodeURIComponent(name)}`, data),

    deleteProvider: (name: string, expectedVersion: string): Promise<void> =>
      client.delete(`/ai/catalog/providers/${encodeURIComponent(name)}`, { params: { expectedVersion } }),

    listModels: (pageNumber = 1, pageSize = 50): Promise<PageResult<AgentModelDTO>> =>
      client.get('/ai/catalog/models', { params: { pageNumber, pageSize } }),

    createModel: (data: AgentModelCreateDTO): Promise<AgentModelDTO> =>
      client.post('/ai/catalog/models', data),

    updateModel: (
      providerName: string,
      modelName: string,
      data: AgentModelUpdateDTO,
    ): Promise<AgentModelDTO> =>
      client.put(
        `/ai/catalog/models/${encodeURIComponent(providerName)}/${encodePathTail(modelName)}`,
        data,
      ),

    deleteModel: (
      providerName: string,
      modelName: string,
      expectedVersion: string,
    ): Promise<void> =>
      client.delete(
        `/ai/catalog/models/${encodeURIComponent(providerName)}/${encodePathTail(modelName)}`,
        {
          params: { expectedVersion },
        },
      ),

    listAgents: (pageNumber = 1, pageSize = 50): Promise<PageResult<AgentDefinitionDTO>> =>
      client.get('/ai/catalog/agents', { params: { pageNumber, pageSize } }),

    listTools: (): Promise<ToolCatalogEntryDTO[]> => client.get('/ai/catalog/tools'),

    createAgent: (data: AgentDefinitionCreateDTO): Promise<AgentDefinitionDTO> =>
      client.post('/ai/catalog/agents', data),

    updateAgent: (name: string, data: AgentDefinitionUpdateDTO): Promise<AgentDefinitionDTO> =>
      client.put(`/ai/catalog/agents/${encodeURIComponent(name)}`, data),

    deleteAgent: (name: string, expectedVersion: string): Promise<void> =>
      client.delete(`/ai/catalog/agents/${encodeURIComponent(name)}`, { params: { expectedVersion } }),

    // --- Global Skills & Skill Packages ---

    listSkillPackages: (): Promise<SkillPackageDTO[]> => client.get('/ai/catalog/skill-packages'),

    createSkillPackage: (data: SkillPackageCreateDTO): Promise<SkillPackageDTO> =>
      client.post('/ai/catalog/skill-packages', data),

    editSkillPackage: (
      name: string,
      data: SkillPackageEditDTO,
    ): Promise<SkillPackageDTO> =>
      client.put(`/ai/catalog/skill-packages/${encodeURIComponent(name)}`, data),

    checkSkillPackage: (
      name: string,
      data: SkillPackageCheckDTO,
    ): Promise<SkillPackageDTO> =>
      client.post(`/ai/catalog/skill-packages/${encodeURIComponent(name)}/check`, data),

    publishSkillPackage: (
      name: string,
      data: SkillPackagePublishDTO,
    ): Promise<SkillPackageDTO> =>
      client.post(`/ai/catalog/skill-packages/${encodeURIComponent(name)}/update`, data),

    deleteSkillPackage: (
      name: string,
      expectedVersion: string,
    ): Promise<void> =>
      client.delete(`/ai/catalog/skill-packages/${encodeURIComponent(name)}`, {
        params: { expectedVersion },
      }),

  }
}

export const agentService = createAgentService()
