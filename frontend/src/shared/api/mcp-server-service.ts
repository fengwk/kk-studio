import { apiClient, type HttpClient } from '@/shared/api/client'
import type {
  McpServerConfigDTO,
  McpServerCreateDTO,
  McpServerDTO,
  McpServerUpdateDTO,
} from '@/shared/api/contracts/ai-mcp'
import type { PageResult } from '@/shared/api/contracts/base'

export function createMcpServerService(client: HttpClient = apiClient) {
  return {
    pageServers: (pageNumber = 1, pageSize = 50): Promise<PageResult<McpServerDTO>> =>
      client.get('/ai/mcp-servers', { params: { pageNumber, pageSize } }),

    getServer: (name: string): Promise<McpServerDTO> =>
      client.get(`/ai/mcp-servers/${encodeURIComponent(name)}`),

    /**
     * 按需读取当前完整配置（含 URL 与 headers，强制 Cache-Control: no-store）。
     * 仅在显式编辑时直读，绝不进入 React Query、LocalStorage 或 URL。
     */
    getServerConfig: (name: string): Promise<McpServerConfigDTO> =>
      client.get(`/ai/mcp-servers/${encodeURIComponent(name)}/config`, {
        headers: { 'Cache-Control': 'no-store' },
      }),

    createServer: (data: McpServerCreateDTO): Promise<McpServerDTO> =>
      client.post('/ai/mcp-servers', data),

    updateServer: (name: string, data: McpServerUpdateDTO): Promise<McpServerDTO> =>
      client.put(`/ai/mcp-servers/${encodeURIComponent(name)}`, data),

    /**
     * 触发 MCP Server 工具发现（POST /ai/mcp-servers/{name}/discover?expectedVersion=...）。
     */
    discoverServer: (
      name: string,
      expectedVersion: string,
    ): Promise<McpServerDTO> =>
      client.post(
        `/ai/mcp-servers/${encodeURIComponent(name)}/discover?expectedVersion=${encodeURIComponent(expectedVersion)}`,
      ),

    deleteServer: (name: string, expectedVersion: string): Promise<void> =>
      client.delete(
        `/ai/mcp-servers/${encodeURIComponent(name)}?expectedVersion=${encodeURIComponent(expectedVersion)}`,
      ),
  }
}

export const mcpServerService = createMcpServerService()
