import { apiClient, type HttpClient } from '@/shared/api/client'
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
  SkillPackageGitCreateDTO,
  SkillPackageGitPublishDTO,
  SkillPackagePreviewDTO,
  SkillPackageSummaryDTO,
  SkillPackageUploadPreviewDTO,
  SkillPackageUploadPublishDTO,
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

    listSkillPackages: (): Promise<SkillPackageSummaryDTO[]> => client.get('/skill-packages'),

    getSkillPackage: (name: string): Promise<SkillPackageDetailDTO> =>
      client.get(`/skill-packages/${encodeURIComponent(name)}`),

    getSkillPackageArchiveUrl: (name: string): Promise<SkillArchiveDownloadDTO> =>
      client.post(`/skill-packages/${encodeURIComponent(name)}/archive-url`),

    listSkillPackageEnvironments: (name: string): Promise<SkillEnvironmentSyncDTO[]> =>
      client.get(`/skill-packages/${encodeURIComponent(name)}/environments`),

    listSkillPackageFiles: (name: string): Promise<SkillPackageFileDTO[]> =>
      client.get(`/skill-packages/${encodeURIComponent(name)}/files`),

    createSkillPackage: (data: SkillPackageGitCreateDTO): Promise<SkillPackageDetailDTO> =>
      client.post('/skill-packages/git', data, { timeout: 0 }),

    editSkillPackage: (
      name: string,
      data: SkillPackageEditDTO,
    ): Promise<SkillPackageDetailDTO> =>
      client.put(`/skill-packages/${encodeURIComponent(name)}`, data),

    checkSkillPackage: (
      name: string,
      data: SkillPackageCheckDTO,
    ): Promise<SkillPackageDetailDTO> =>
      client.post(`/skill-packages/git/${encodeURIComponent(name)}/check`, data, { timeout: 0 }),

    publishSkillPackage: (
      name: string,
      data: SkillPackageGitPublishDTO,
    ): Promise<SkillPackageDetailDTO> =>
      client.post(`/skill-packages/git/${encodeURIComponent(name)}/publish`, data, { timeout: 0 }),

    deleteSkillPackage: (
      name: string,
      expectedVersion: string,
    ): Promise<void> =>
      client.delete(`/skill-packages/${encodeURIComponent(name)}`, {
        params: { expectedVersion },
      }),

    previewSkillPackageUpload: (
      data: SkillPackageUploadPreviewDTO,
    ): Promise<SkillPackagePreviewDTO> =>
      client.post('/skill-packages/uploads/preview', data, { timeout: 0 }),

    publishSkillPackageUpload: (
      data: SkillPackageUploadPublishDTO,
    ): Promise<SkillPackageDetailDTO> =>
      client.post('/skill-packages/uploads/publish', data, { timeout: 0 }),

    cancelSkillPackageUpload: (stagingUploadId: string): Promise<void> =>
      client.post(`/skill-packages/uploads/${encodeURIComponent(stagingUploadId)}/cancel`),

    resyncEnvironmentSkills: (environmentId: string): Promise<void> =>
      client.post(`/environments/${encodeURIComponent(environmentId)}/skills/resync`),

  }
}

export const agentService = createAgentService()
