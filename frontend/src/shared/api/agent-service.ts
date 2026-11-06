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
  AgentCreateDTO,
  AgentDTO,
  AgentUpdateDTO,
  ModelCatalogPageDTO,
  ModelCreateDTO,
  ModelDTO,
  ModelUpdateDTO,
  ProviderCreateDTO,
  ProviderDTO,
  ProviderUpdateDTO,
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
  ApiRoutes,
} from '@/shared/api/generated/contracts'

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

    listAgents: (): Promise<AgentDTO[]> => catalogRequest('GET /api/platform/agents', {}),

    listTools: (): Promise<ToolCatalogEntryDTO[]> =>
      catalogRequest('GET /api/platform/tools', {}),

    createAgent: (data: AgentCreateDTO): Promise<AgentDTO> =>
      catalogRequest('POST /api/platform/agents', { body: data }),

    updateAgent: (name: string, data: AgentUpdateDTO): Promise<AgentDTO> =>
      catalogRequest('PUT /api/platform/agents/{name}', { path: { name }, body: data }),

    deleteAgent: (name: string, expectedVersion: string): Promise<void> =>
      catalogRequest('DELETE /api/platform/agents/{name}', {
        path: { name },
        query: { expectedVersion },
      }),

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
