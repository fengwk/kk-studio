import { apiClient, type HttpClient } from '@/shared/api/client'
import type {
  EnvironmentCardDTO,
  EnvironmentCreateDTO,
  EnvironmentEventDTO,
  EnvironmentInstallCodeDTO,
  EnvironmentInstallConfigDTO,
  EnvironmentRegistrationTokenDTO,
  EnvironmentUpdateDTO,
} from '@/shared/api/contracts/ai-environment'

export function createEnvironmentService(client: HttpClient = apiClient) {
  return {
    listEnvironments: (): Promise<EnvironmentCardDTO[]> => client.get('/harness/environments'),

    listEnvironmentEvents: (id: string): Promise<EnvironmentEventDTO[]> =>
      client.get(`/harness/environments/${encodeURIComponent(id)}/events`),

    getEnvironment: (id: string): Promise<EnvironmentCardDTO> =>
      client.get(`/harness/environments/${encodeURIComponent(id)}`),

    /** Read the latest managed Daemon update operation; returns null when never updated. */
    getEnvironmentUpdate: (id: string): Promise<EnvironmentUpdateDTO | null> =>
      client.get(`/harness/environments/${encodeURIComponent(id)}/update`),

    /** Start a managed Daemon update to the platform-controlled release and return the refreshed card. */
    startEnvironmentUpdate: (id: string): Promise<EnvironmentCardDTO> =>
      client.post(`/harness/environments/${encodeURIComponent(id)}/update`),

    createEnvironment: (data: EnvironmentCreateDTO): Promise<EnvironmentCardDTO> =>
      client.post('/harness/environments', data),

    /**
     * 按需读取当前 registrationToken（不轮换、幂等、响应 no-store）。
     *
     * 仅在用户显式生成安装命令时读取，不进入 query cache、LocalStorage 或 URL。
     */
    getRegistrationToken: (id: string): Promise<EnvironmentRegistrationTokenDTO> =>
      client.get(`/harness/environments/${encodeURIComponent(id)}/token`),

    /**
     * 签发 5 分钟安装 code。客户端按现有 Result 解包得到 {code, expiresAt}。
     * 不轮换 registrationToken，也不自动重试。
     */
    createInstallCode: (id: string, expectedVersion: string): Promise<EnvironmentInstallCodeDTO> =>
      client.post(`/harness/environments/${encodeURIComponent(id)}/install-code`, {
        expectedVersion,
      }),

    saveInstallConfig: (
      id: string,
      expectedVersion: string,
      installConfig: EnvironmentInstallConfigDTO,
    ): Promise<EnvironmentCardDTO> =>
      client.put(`/harness/environments/${encodeURIComponent(id)}/install-config`, {
        expectedVersion,
        installConfig,
      }),

    rotateToken: (id: string, expectedVersion: string): Promise<EnvironmentCardDTO> =>
      client.post(`/harness/environments/${encodeURIComponent(id)}/registration-token`, {
        expectedVersion,
      }),

    deleteEnvironment: (id: string, expectedVersion: string): Promise<void> =>
      client.delete(
        `/harness/environments/${encodeURIComponent(id)}?expectedVersion=${encodeURIComponent(expectedVersion)}`,
      ),
  }
}

export const environmentService = createEnvironmentService()
