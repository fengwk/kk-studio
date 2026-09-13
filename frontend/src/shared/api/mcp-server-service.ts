import { apiClient, type HttpClient } from '@/shared/api/client'
import type {
  McpServerConfigDTO,
  McpServerCreateDTO,
  McpServerDiscoveryResponseDTO,
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

    /**
     * 按需读取当前完整配置 JSON（含连接与敏感参数，响应 no-store）。
     * 仅在显式编辑时直读，绝不进入 React Query、LocalStorage 或 URL。
     */
    getServerConfig: (id: string): Promise<McpServerConfigDTO> =>
      client.get(`/ai/mcp-servers/${encodeURIComponent(id)}/config`),

    createServer: (data: McpServerCreateDTO): Promise<McpServerDTO> =>
      client.post('/ai/mcp-servers', data),

    updateServer: (id: string, data: McpServerUpdateDTO): Promise<McpServerDTO> =>
      client.put(`/ai/mcp-servers/${encodeURIComponent(id)}`, data),

    /**
     * 触发 MCP Server 工具发现（HTTP 202 Accepted）。
     * Remote 同步完成，Local 异步创建 EnvironmentOperation。
     */
    discoverServer: (
      id: string,
      expectedVersion: string,
    ): Promise<McpServerDiscoveryResponseDTO> =>
      client.post(`/ai/mcp-servers/${encodeURIComponent(id)}/discover`, {
        expectedVersion,
      }),

    deleteServer: (id: string, expectedVersion: string): Promise<void> =>
      client.delete(
        `/ai/mcp-servers/${encodeURIComponent(id)}?expectedVersion=${encodeURIComponent(expectedVersion)}`,
      ),
  }
}

export const mcpServerService = createMcpServerService()
