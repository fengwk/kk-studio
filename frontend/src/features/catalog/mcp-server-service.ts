import { request } from '@/shared/api/client'
import type {
  McpServerCreateDTO,
  McpServerDTO,
  McpServerSummaryDTO,
  McpServerUpdateDTO,
  SecretUpdate,
} from '@/shared/api/generated/contracts'

/**
 * header 映射以整体秘密提交：非空映射为 SET（值是 JSON 对象文本），显式清除为 CLEAR，其余保持原值。
 *
 * <p>headers 从不回读，因此界面只能提交替换、清除或保持三种意图。
 */
export function headersUpdate(
  headers: Record<string, string> | null,
  clear: boolean,
): SecretUpdate {
  if (headers && Object.keys(headers).length > 0) {
    return { operation: 'SET', value: JSON.stringify(headers) }
  }
  return { operation: clear ? 'CLEAR' : 'KEEP' }
}

/** MCP Server 路由：列表是摘要数组，详情携带 URL 与 headers 的已配置事实。 */
export const mcpServerService = {
  listServers: (): Promise<McpServerSummaryDTO[]> =>
    request('GET /api/platform/mcp-servers', {}),

  getServer: (name: string): Promise<McpServerDTO> =>
    request('GET /api/platform/mcp-servers/{name}', { path: { name } }),

  createServer: (data: McpServerCreateDTO): Promise<McpServerDTO> =>
    request('POST /api/platform/mcp-servers', { body: data }),

  updateServer: (name: string, data: McpServerUpdateDTO): Promise<McpServerDTO> =>
    request('PUT /api/platform/mcp-servers/{name}', { path: { name }, body: data }),

  discoverServer: (name: string, expectedVersion: string): Promise<McpServerDTO> =>
    request('POST /api/platform/mcp-servers/{name}/discover', {
      path: { name },
      body: { expectedVersion },
    }),

  deleteServer: (name: string, expectedVersion: string): Promise<void> =>
    request('DELETE /api/platform/mcp-servers/{name}', {
      path: { name },
      query: { expectedVersion },
    }),
}
