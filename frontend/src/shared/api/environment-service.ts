import { apiClient, type HttpClient } from '@/shared/api/client'
import type {
  EnvironmentCardDTO,
  EnvironmentCreateDTO,
  EnvironmentEventDTO,
  EnvironmentIdeChangeDirectoryDTO,
  EnvironmentIdeOpenViewDTO,
  EnvironmentIdeViewDTO,
  EnvironmentInstallCodeDTO,
  EnvironmentInstallConfigDTO,
  EnvironmentUpdateDTO,
} from '@/shared/api/generated/contracts'

export function createEnvironmentService(client: HttpClient = apiClient) {
  return {
    listEnvironments: (): Promise<EnvironmentCardDTO[]> => client.get('/environments'),

    listEnvironmentEvents: (id: string): Promise<EnvironmentEventDTO[]> =>
      client.get(`/environments/${encodeURIComponent(id)}/events`),

    getEnvironment: (id: string): Promise<EnvironmentCardDTO> =>
      client.get(`/environments/${encodeURIComponent(id)}`),

    /** Read the latest managed Daemon update operation; returns null when never updated. */
    getEnvironmentUpdate: (id: string): Promise<EnvironmentUpdateDTO | null> =>
      client.get(`/environments/${encodeURIComponent(id)}/update`),

    /** Start a managed Daemon update to the platform-controlled release and return the refreshed card. */
    startEnvironmentUpdate: (id: string): Promise<EnvironmentCardDTO> =>
      client.post(`/environments/${encodeURIComponent(id)}/update`),

    createEnvironment: (data: EnvironmentCreateDTO): Promise<EnvironmentCardDTO> =>
      client.post('/environments', data),

    /**
     * 签发 5 分钟安装 code。客户端按现有 Result 解包得到 {code, expiresAt}。
     * 签发不轮换 registrationToken；下载安装脚本时服务端签发新凭证，code 随即失效。不自动重试。
     */
    createInstallCode: (id: string, expectedVersion: string): Promise<EnvironmentInstallCodeDTO> =>
      client.post(`/environments/${encodeURIComponent(id)}/install-code`, {
        expectedVersion,
      }),

    saveInstallConfig: (
      id: string,
      expectedVersion: string,
      installConfig: EnvironmentInstallConfigDTO,
    ): Promise<EnvironmentCardDTO> =>
      client.put(`/environments/${encodeURIComponent(id)}/install-config`, {
        expectedVersion,
        installConfig,
      }),

    rotateToken: (id: string, expectedVersion: string): Promise<EnvironmentCardDTO> =>
      client.post(`/environments/${encodeURIComponent(id)}/registration-token`, {
        expectedVersion,
      }),

    deleteEnvironment: (id: string, expectedVersion: string): Promise<void> =>
      client.delete(
        `/environments/${encodeURIComponent(id)}?expectedVersion=${encodeURIComponent(expectedVersion)}`,
      ),

    createIdeView: (environmentId: string, data: EnvironmentIdeOpenViewDTO): Promise<EnvironmentIdeViewDTO> =>
      client.post(`/environments/${encodeURIComponent(environmentId)}/ide/views`, data),

    changeIdeViewDirectory: (
      environmentId: string,
      viewId: string,
      data: EnvironmentIdeChangeDirectoryDTO,
    ): Promise<EnvironmentIdeViewDTO> =>
      client.put(
        `/environments/${encodeURIComponent(environmentId)}/ide/views/${encodeURIComponent(viewId)}/directory`,
        data,
      ),

    openIdeViewTerminal: (environmentId: string, viewId: string): Promise<void> =>
      client.post(
        `/environments/${encodeURIComponent(environmentId)}/ide/views/${encodeURIComponent(viewId)}/terminal`,
      ),

    closeIdeView: (environmentId: string, viewId: string): Promise<void> =>
      client.delete(
        `/environments/${encodeURIComponent(environmentId)}/ide/views/${encodeURIComponent(viewId)}`,
      ),
  }
}

export const environmentService = createEnvironmentService()
