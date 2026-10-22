import { apiClient, type HttpClient } from '@/shared/api/client'
import type { PageResult } from '@/shared/api/contracts/base'
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
  ModelCatalogPageDTO,
  SkillPackageCheckDTO,
  SkillPackageCreateDTO,
  SkillPackageDTO,
  SkillPackageEditDTO,
  SkillPackagePublishDTO,
  ToolCatalogEntryDTO,
} from '@/shared/api/generated/contracts'

function encodePathTail(value: string): string {
  return value.split('/').map(encodeURIComponent).join('/')
}

export function createAgentService(client: HttpClient = apiClient) {
  return {
    listProviders: (pageNumber = 1, pageSize = 50): Promise<PageResult<AgentProviderDTO>> =>
      client.get('/providers', { params: { pageNumber, pageSize } }),

    createProvider: (data: AgentProviderCreateDTO): Promise<AgentProviderDTO> =>
      client.post('/providers', data),

    updateProvider: (name: string, data: AgentProviderUpdateDTO): Promise<AgentProviderDTO> =>
      client.put(`/providers/${encodeURIComponent(name)}`, data),

    deleteProvider: (name: string, expectedVersion: string): Promise<void> =>
      client.delete(`/providers/${encodeURIComponent(name)}`, { params: { expectedVersion } }),

    listRemoteModels: (providerName: string): Promise<string[]> =>
      client.get(`/providers/${encodeURIComponent(providerName)}/remote-models`),

    listModels: (pageNumber = 1, pageSize = 50): Promise<PageResult<AgentModelDTO>> =>
      client.get('/models', { params: { pageNumber, pageSize } }),

    createModel: (data: AgentModelCreateDTO): Promise<AgentModelDTO> =>
      client.post('/models', data),

    updateModel: (
      modelName: string,
      data: AgentModelUpdateDTO,
    ): Promise<AgentModelDTO> =>
      client.put(`/models/${encodePathTail(modelName)}`, data),

    deleteModel: (
      modelName: string,
      expectedVersion: string,
    ): Promise<void> =>
      client.delete(`/models/${encodePathTail(modelName)}`, {
        params: { expectedVersion },
      }),

    listModelDefaults: (
      query = '',
      cursor?: string | null,
      pageSize = 20,
    ): Promise<ModelCatalogPageDTO> =>
      client.get('/model-defaults', {
        params: {
          ...(query.trim() ? { query: query.trim() } : {}),
          ...(cursor ? { cursor } : {}),
          pageSize,
        },
      }),

    refreshModelDefaults: (): Promise<ModelCatalogPageDTO> =>
      client.post('/model-defaults/refresh', {}),

    listAgents: (pageNumber = 1, pageSize = 50): Promise<PageResult<AgentDefinitionDTO>> =>
      client.get('/agents', { params: { pageNumber, pageSize } }),

    listTools: (): Promise<ToolCatalogEntryDTO[]> => client.get('/tools'),

    createAgent: (data: AgentDefinitionCreateDTO): Promise<AgentDefinitionDTO> =>
      client.post('/agents', data),

    updateAgent: (name: string, data: AgentDefinitionUpdateDTO): Promise<AgentDefinitionDTO> =>
      client.put(`/agents/${encodeURIComponent(name)}`, data),

    deleteAgent: (name: string, expectedVersion: string): Promise<void> =>
      client.delete(`/agents/${encodeURIComponent(name)}`, { params: { expectedVersion } }),

    // --- Global Skills & Skill Packages ---

    listSkillPackages: (): Promise<SkillPackageDTO[]> => client.get('/skill-packages'),

    createSkillPackage: (data: SkillPackageCreateDTO): Promise<SkillPackageDTO> =>
      client.post('/skill-packages', data, { timeout: 0 }),

    editSkillPackage: (
      name: string,
      data: SkillPackageEditDTO,
    ): Promise<SkillPackageDTO> =>
      client.put(`/skill-packages/${encodeURIComponent(name)}`, data),

    checkSkillPackage: (
      name: string,
      data: SkillPackageCheckDTO,
    ): Promise<SkillPackageDTO> =>
      client.post(`/skill-packages/${encodeURIComponent(name)}/check`, data, { timeout: 0 }),

    publishSkillPackage: (
      name: string,
      data: SkillPackagePublishDTO,
    ): Promise<SkillPackageDTO> =>
      client.post(`/skill-packages/${encodeURIComponent(name)}/update`, data, { timeout: 0 }),

    deleteSkillPackage: (
      name: string,
      expectedVersion: string,
    ): Promise<void> =>
      client.delete(`/skill-packages/${encodeURIComponent(name)}`, {
        params: { expectedVersion },
      }),

  }
}

export const agentService = createAgentService()
