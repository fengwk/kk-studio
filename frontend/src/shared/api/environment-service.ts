import { apiClient, type HttpClient } from '@/shared/api/client'
import type { LiveEnvironmentDTO } from '@/shared/api/contracts'

export function createEnvironmentService(client: HttpClient = apiClient) {
  return {
    listEnvironments: (): Promise<LiveEnvironmentDTO[]> => client.get('/ai/environment'),
  }
}

export const environmentService = createEnvironmentService()
