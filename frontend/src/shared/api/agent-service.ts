import {
  apiClient,
  request,
  type HttpClient,
  type RequestOptions,
  type RouteArgs,
  type RouteResponse,
} from '@/shared/api/client'
import { parseJsonPreservingPathEqualsNumbers } from '@/shared/lib/lossless-json-number'
import type {
  AgentDefinitionCreateDTO,
  AgentDefinitionDTO,
  AgentDefinitionUpdateDTO,
  ModelCreateDTO,
  ModelDTO,
  ModelUpdateDTO,
  ProviderCreateDTO,
  ProviderDTO,
  ProviderUpdateDTO,
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
  ApiRoutes,
} from '@/shared/api/generated/contracts'
import type { PageResult } from '@/shared/api/contracts/base'

export type CatalogRequester = <R extends keyof ApiRoutes>(
  route: R,
  args: RouteArgs<R>,
  options?: RequestOptions,
) => Promise<RouteResponse<R>>

export function createAgentService(
  client: HttpClient = apiClient,
  catalogRequest: CatalogRequester = request,
) {
  return {
    listProviders: (): Promise<ProviderDTO[]> =>
      catalogRequest('GET /api/platform/providers', {}),

    createProvider: (data: ProviderCreateDTO): Promise<ProviderDTO> =>
      catalogRequest('POST /api/platform/providers', { body: data }),

    updateProvider: (name: string, data: ProviderUpdateDTO): Promise<ProviderDTO> =>
      catalogRequest('PUT /api/platform/providers/{name}', { path: { name }, body: data }),

    deleteProvider: (name: string, expectedVersion: string): Promise<void> =>
      catalogRequest('DELETE /api/platform/providers/{name}', {
        path: { name },
        query: { expectedVersion },
      }),

    listRemoteModels: (providerName: string): Promise<string[]> =>
      catalogRequest('GET /api/platform/providers/{name}/remote-models', {
        path: { name: providerName },
      }),

    listModels: (): Promise<ModelDTO[]> =>
      catalogRequest('GET /api/platform/models', {}, {
        jsonParser: parseJsonPreservingPathEqualsNumbers,
      }),

    createModel: (data: ModelCreateDTO): Promise<ModelDTO> =>
      catalogRequest('POST /api/platform/models', { body: data }, {
        jsonParser: parseJsonPreservingPathEqualsNumbers,
      }),

    updateModel: (modelName: string, data: ModelUpdateDTO): Promise<ModelDTO> =>
      catalogRequest('PUT /api/platform/models/{*name}', { path: { name: modelName }, body: data }, {
        jsonParser: parseJsonPreservingPathEqualsNumbers,
      }),

    deleteModel: (modelName: string, expectedVersion: string): Promise<void> =>
      catalogRequest('DELETE /api/platform/models/{*name}', {
        path: { name: modelName },
        query: { expectedVersion },
      }),

    listModelDefaults: (
      query = '',
      cursor?: string | null,
      limit = 20,
    ): Promise<ModelCatalogPageDTO> =>
      catalogRequest(
        'GET /api/platform/model-catalog',
        {
          query: {
            ...(query.trim() ? { query: query.trim() } : {}),
            ...(cursor ? { cursor } : {}),
            limit,
          },
        },
        { jsonParser: parseJsonPreservingPathEqualsNumbers },
      ),

    refreshModelDefaults: (): Promise<ModelCatalogPageDTO> =>
      catalogRequest('POST /api/platform/model-catalog/refresh', {}, {
        jsonParser: parseJsonPreservingPathEqualsNumbers,
      }),

    listAgents: (pageNumber = 1, pageSize = 50): Promise<PageResult<AgentDefinitionDTO>> =>
      client.get('/agents', { params: { pageNumber, pageSize } }),

    listTools: (): Promise<ToolCatalogEntryDTO[]> =>
      catalogRequest('GET /api/platform/tools', {}),

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
