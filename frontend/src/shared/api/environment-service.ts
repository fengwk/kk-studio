import { apiClient, type HttpClient } from '@/shared/api/client'
import type {
  EnvironmentCardDTO,
  EnvironmentCreateDTO,
  EnvironmentEventDTO,
  EnvironmentRegistrationTokenDTO,
} from '@/shared/api/contracts/ai-environment'

export function createEnvironmentService(client: HttpClient = apiClient) {
  return {
    listEnvironments: (): Promise<EnvironmentCardDTO[]> => client.get('/harness/environments'),

    listEnvironmentEvents: (id: string): Promise<EnvironmentEventDTO[]> =>
      client.get(`/harness/environments/${encodeURIComponent(id)}/events`),

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
  }
}

export const environmentService = createEnvironmentService()
