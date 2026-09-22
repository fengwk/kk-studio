import { apiClient, type HttpClient } from '@/shared/api/client'
import type { RuntimeSessionSummaryDTO } from '@/shared/api/contracts/ai-runtime'

export function createOwnerService(client: HttpClient = apiClient) {
  return {
    listProjectSessions: (projectId: string): Promise<RuntimeSessionSummaryDTO[]> =>
      client.get(`/projects/${encodeURIComponent(projectId)}/sessions`),
  }
}

export const ownerService = createOwnerService()
