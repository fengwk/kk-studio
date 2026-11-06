import { apiClient, request, type HttpClient } from '@/shared/api/client'
import type { PageResult } from '@/shared/api/contracts/base'
import { parseJsonPreservingPathEqualsNumbers } from '@/shared/lib/lossless-json-number'
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
  SkillArchiveDownloadDTO,
  SkillEnvironmentSyncDTO,
  SkillPackageCheckDTO,
  SkillPackageDetailDTO,
  SkillPackageEditDTO,
  SkillPackageFileDTO,
  SkillPackageCreateDTO,
  SkillPackagePublishDTO,
  SkillPackageStagingCreateDTO,
  SkillPackageStagingDTO,
  SkillPackageStagingPublishDTO,
  SkillPackageSummaryDTO,
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
      client.get('/models', {
        params: { pageNumber, pageSize },
        jsonParser: parseJsonPreservingPathEqualsNumbers,
      }),

    createModel: (data: AgentModelCreateDTO): Promise<AgentModelDTO> =>
      client.post('/models', data, { jsonParser: parseJsonPreservingPathEqualsNumbers }),

    updateModel: (
      modelName: string,
      data: AgentModelUpdateDTO,
    ): Promise<AgentModelDTO> =>
      client.put(`/models/${encodePathTail(modelName)}`, data, {
        jsonParser: parseJsonPreservingPathEqualsNumbers,
      }),

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
        jsonParser: parseJsonPreservingPathEqualsNumbers,
      }),

    refreshModelDefaults: (): Promise<ModelCatalogPageDTO> =>
      client.post('/model-defaults/refresh', {}, {
        jsonParser: parseJsonPreservingPathEqualsNumbers,
      }),

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

    listSkillPackages: (): Promise<SkillPackageSummaryDTO[]> =>
      request('GET /api/platform/skill-packages', {}),

    getSkillPackage: (name: string): Promise<SkillPackageDetailDTO> =>
      request('GET /api/platform/skill-packages/{name}', { path: { name } }),

    getSkillPackageArchiveUrl: (name: string): Promise<SkillArchiveDownloadDTO> =>
      request('POST /api/platform/skill-packages/{name}/archive-url', { path: { name } }),

    listSkillPackageEnvironments: (name: string): Promise<SkillEnvironmentSyncDTO[]> =>
      request('GET /api/platform/skill-packages/{name}/environments', { path: { name } }),

    listSkillPackageFiles: (name: string): Promise<SkillPackageFileDTO[]> =>
      request('GET /api/platform/skill-packages/{name}/files', { path: { name } }),

    createSkillPackage: (data: SkillPackageCreateDTO): Promise<SkillPackageDetailDTO> =>
      request('POST /api/platform/skill-packages', { body: data }, { timeoutMs: 0 }),

    editSkillPackage: (name: string, data: SkillPackageEditDTO): Promise<SkillPackageDetailDTO> =>
      request('PUT /api/platform/skill-packages/{name}', { path: { name }, body: data }),

    checkSkillPackage: (
      name: string,
      data: SkillPackageCheckDTO,
    ): Promise<SkillPackageDetailDTO> =>
      request(
        'POST /api/platform/skill-packages/{name}/check',
        { path: { name }, body: data },
        { timeoutMs: 0 },
      ),

    publishSkillPackage: (
      name: string,
      data: SkillPackagePublishDTO,
    ): Promise<SkillPackageDetailDTO> =>
      request(
        'POST /api/platform/skill-packages/{name}/publish',
        { path: { name }, body: data },
        { timeoutMs: 0 },
      ),

    deleteSkillPackage: (name: string, expectedVersion: string): Promise<void> =>
      request('DELETE /api/platform/skill-packages/{name}', {
        path: { name },
        query: { expectedVersion },
      }),

    createSkillPackageStaging: (
      data: SkillPackageStagingCreateDTO,
    ): Promise<SkillPackageStagingDTO> =>
      request('POST /api/platform/skill-package-stagings', { body: data }, { timeoutMs: 0 }),

    publishSkillPackageStaging: (
      stagingId: string,
      data: SkillPackageStagingPublishDTO,
    ): Promise<SkillPackageDetailDTO> =>
      request(
        'POST /api/platform/skill-package-stagings/{stagingId}/publish',
        { path: { stagingId }, body: data },
        { timeoutMs: 0 },
      ),

    cancelSkillPackageStaging: (stagingId: string): Promise<void> =>
      request('DELETE /api/platform/skill-package-stagings/{stagingId}', {
        path: { stagingId },
      }),

    resyncEnvironmentSkills: (environmentId: string): Promise<void> =>
      request('POST /api/platform/environments/{environmentId}/resync-skills', {
        path: { environmentId },
      }),

  }
}

export const agentService = createAgentService()
