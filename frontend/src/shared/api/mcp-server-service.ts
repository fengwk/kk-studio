import { apiClient, type HttpClient } from '@/shared/api/client'
import type {
  McpServerCreateDTO,
  McpServerDTO,
  McpServerUpdateDTO,
} from '@/shared/api/contracts/ai-mcp'
import type { PageResult } from '@/shared/api/contracts/base'

export function createMcpServerService(client: HttpClient = apiClient) {
  return {
    pageServers: (pageNumber = 1, pageSize = 50): Promise<PageResult<McpServerDTO>> =>
      client.get('/ai/mcp-servers', { params: { pageNumber, pageSize } }),

    getServer: (id: string): Promise<McpServerDTO> =>
      client.get(`/ai/mcp-servers/${encodeURIComponent(id)}`),

    createServer: (data: McpServerCreateDTO): Promise<McpServerDTO> =>
      client.post('/ai/mcp-servers', data),

    updateServer: (id: string, data: McpServerUpdateDTO): Promise<McpServerDTO> =>
      client.put(`/ai/mcp-servers/${encodeURIComponent(id)}`, data),

    refreshServer: (id: string, expectedVersion: string): Promise<McpServerDTO> =>
      client.post(`/ai/mcp-servers/${encodeURIComponent(id)}/refresh`, {
        expectedVersion,
      }),

    deleteServer: (id: string, expectedVersion: string): Promise<void> =>
      client.delete(
        `/ai/mcp-servers/${encodeURIComponent(id)}?expectedVersion=${encodeURIComponent(expectedVersion)}`,
      ),
  }
}

export const mcpServerService = createMcpServerService()
