import { apiClient, type HttpClient } from '@/shared/api/client'
import type {
  EnvironmentDirectoryDTO,
  LiveEnvironmentDTO,
} from '@/shared/api/contracts/ai-environment'

export function createEnvironmentService(client: HttpClient = apiClient) {
  return {
    listEnvironments: (): Promise<LiveEnvironmentDTO[]> => client.get('/ai/environment'),
    /** 浏览 Environment Root 下单层目录；path 缺省为 '.'（root），wire 使用 '/' 分隔的 canonical 相对路径。 */
    listDirectories: (name: string, path = '.'): Promise<EnvironmentDirectoryDTO> =>
      client.get(`/ai/environments/${encodeURIComponent(name)}/directories`, {
        params: { path },
      }),
  }
}

export const environmentService = createEnvironmentService()
