import { apiClient, type HttpClient } from '@/shared/api/client'
import type {
  EnvironmentCardDTO,
  EnvironmentCreateDTO,
  EnvironmentDirectoryDTO,
  EnvironmentUpdateDTO,
} from '@/shared/api/contracts/ai-environment'

export function createEnvironmentService(client: HttpClient = apiClient) {
  return {
    listEnvironments: (): Promise<EnvironmentCardDTO[]> => client.get('/ai/environments'),

    getEnvironment: (id: string): Promise<EnvironmentCardDTO> =>
      client.get(`/ai/environments/${encodeURIComponent(id)}`),

    createEnvironment: (data: EnvironmentCreateDTO): Promise<EnvironmentCardDTO> =>
      client.post('/ai/environments', data),

    updateEnvironment: (
      id: string,
      expectedVersion: string,
      data: EnvironmentUpdateDTO,
    ): Promise<EnvironmentCardDTO> =>
      client.put(
        `/ai/environments/${encodeURIComponent(id)}?expectedVersion=${encodeURIComponent(expectedVersion)}`,
        data,
      ),

    rotateToken: (id: string, expectedVersion: string): Promise<EnvironmentCardDTO> =>
      client.post(
        `/ai/environments/${encodeURIComponent(id)}/registration-token?expectedVersion=${encodeURIComponent(expectedVersion)}`,
      ),

    deleteEnvironment: (id: string, expectedVersion: string): Promise<void> =>
      client.delete(
        `/ai/environments/${encodeURIComponent(id)}?expectedVersion=${encodeURIComponent(expectedVersion)}`,
      ),

    /** 浏览 Environment Root 下单层目录；path 缺省为 '.'（root），wire 使用 '/' 分隔的 canonical 相对路径。 */
    listDirectories: (id: string, path = '.'): Promise<EnvironmentDirectoryDTO> =>
      client.get(
        `/ai/environments/${encodeURIComponent(id)}/directories?path=${encodeURIComponent(path)}`,
      ),
  }
}

export const environmentService = createEnvironmentService()
