import { apiClient, type HttpClient } from '@/shared/api/client'
import type {
  EnvironmentCardDTO,
  EnvironmentCreateDTO,
  EnvironmentInventoryDTO,
  EnvironmentOperationCreateDTO,
  EnvironmentOperationDTO,
  EnvironmentRegistrationTokenDTO,
  EnvironmentSkillDTO,
  EnvironmentSkillSourceCreateDTO,
  EnvironmentSkillSourceDTO,
  EnvironmentSkillSourceUpdateDTO,
} from '@/shared/api/contracts/ai-environment'

export const DEFAULT_OPERATION_LIMIT = 50

export function createEnvironmentService(client: HttpClient = apiClient) {
  return {
    listEnvironments: (): Promise<EnvironmentCardDTO[]> => client.get('/harness/environments'),

    getEnvironment: (id: string): Promise<EnvironmentCardDTO> =>
      client.get(`/harness/environments/${encodeURIComponent(id)}`),

    createEnvironment: (data: EnvironmentCreateDTO): Promise<EnvironmentCardDTO> =>
      client.post('/harness/environments', data),

    /**
     * 按需读取当前 registrationToken（不轮换、幂等、响应 no-store）。
     *
     * 该调用只在用户显式点击「复制 Token」时发起，绝不进入通用 query cache、LocalStorage 或 URL。
     */
    getRegistrationToken: (id: string): Promise<EnvironmentRegistrationTokenDTO> =>
      client.get(`/harness/environments/${encodeURIComponent(id)}/token`),

    rotateToken: (id: string, expectedVersion: string): Promise<EnvironmentCardDTO> =>
      client.post(`/harness/environments/${encodeURIComponent(id)}/registration-token`, {
        expectedVersion,
      }),

    deleteEnvironment: (id: string, expectedVersion: string): Promise<void> =>
      client.delete(
        `/harness/environments/${encodeURIComponent(id)}?expectedVersion=${encodeURIComponent(expectedVersion)}`,
      ),

    // --- Skill sources ---

    listSkillSources: (environmentId: string): Promise<EnvironmentSkillSourceDTO[]> =>
      client.get(`/harness/environments/${encodeURIComponent(environmentId)}/skill-sources`),

    getSkillSource: (environmentId: string, sourceId: string): Promise<EnvironmentSkillSourceDTO> =>
      client.get(
        `/harness/environments/${encodeURIComponent(environmentId)}/skill-sources/${encodeURIComponent(sourceId)}`,
      ),

    createSkillSource: (
      environmentId: string,
      data: EnvironmentSkillSourceCreateDTO,
    ): Promise<EnvironmentSkillSourceDTO> =>
      client.post(
        `/harness/environments/${encodeURIComponent(environmentId)}/skill-sources`,
        data,
      ),

    updateSkillSource: (
      environmentId: string,
      sourceId: string,
      data: EnvironmentSkillSourceUpdateDTO,
    ): Promise<EnvironmentSkillSourceDTO> =>
      client.put(
        `/harness/environments/${encodeURIComponent(environmentId)}/skill-sources/${encodeURIComponent(sourceId)}`,
        data,
      ),

    deleteSkillSource: (
      environmentId: string,
      sourceId: string,
      expectedVersion: string,
    ): Promise<void> =>
      client.delete(
        `/harness/environments/${encodeURIComponent(environmentId)}/skill-sources/${encodeURIComponent(sourceId)}?expectedVersion=${encodeURIComponent(expectedVersion)}`,
      ),

    // --- Persistent inventory ---

    getInventory: (environmentId: string): Promise<EnvironmentInventoryDTO> =>
      client.get(`/harness/environments/${encodeURIComponent(environmentId)}/inventory`),

    listInventorySkills: (
      environmentId: string,
      usableOnly = false,
    ): Promise<EnvironmentSkillDTO[]> =>
      client.get(
        `/harness/environments/${encodeURIComponent(environmentId)}/inventory/skills?usableOnly=${encodeURIComponent(String(usableOnly))}`,
      ),

    // --- Durable async management operations ---

    requestSkillSourceRefresh: (
      environmentId: string,
      sourceId: string,
      data: EnvironmentOperationCreateDTO,
    ): Promise<EnvironmentOperationDTO> =>
      client.post(
        `/harness/environments/${encodeURIComponent(environmentId)}/skill-sources/${encodeURIComponent(sourceId)}/refresh`,
        data,
      ),

    requestSkillSourceInstall: (
      environmentId: string,
      sourceId: string,
      data: EnvironmentOperationCreateDTO,
    ): Promise<EnvironmentOperationDTO> =>
      client.post(
        `/harness/environments/${encodeURIComponent(environmentId)}/skill-sources/${encodeURIComponent(sourceId)}/install`,
        data,
      ),

    requestSkillSourceUpdate: (
      environmentId: string,
      sourceId: string,
      data: EnvironmentOperationCreateDTO,
    ): Promise<EnvironmentOperationDTO> =>
      client.post(
        `/harness/environments/${encodeURIComponent(environmentId)}/skill-sources/${encodeURIComponent(sourceId)}/update`,
        data,
      ),

    listOperations: (
      environmentId: string,
      limit = DEFAULT_OPERATION_LIMIT,
    ): Promise<EnvironmentOperationDTO[]> =>
      client.get(
        `/harness/environments/${encodeURIComponent(environmentId)}/operations?limit=${encodeURIComponent(String(limit))}`,
      ),

    getOperation: (
      environmentId: string,
      operationId: string,
    ): Promise<EnvironmentOperationDTO> =>
      client.get(
        `/harness/environments/${encodeURIComponent(environmentId)}/operations/${encodeURIComponent(operationId)}`,
      ),

    cancelOperation: (
      environmentId: string,
      operationId: string,
    ): Promise<EnvironmentOperationDTO> =>
      client.post(
        `/harness/environments/${encodeURIComponent(environmentId)}/operations/${encodeURIComponent(operationId)}/cancel`,
      ),
  }
}

export const environmentService = createEnvironmentService()
