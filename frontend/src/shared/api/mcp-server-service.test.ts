import { describe, expect, it, vi } from 'vitest'
import { createMcpServerService } from '@/shared/api/mcp-server-service'

describe('mcpServerService', () => {
  /**
   * 测试意图：验证 MCP server 分页查询使用 GET /ai/mcp-servers 并携带分页参数。
   */
  it('pages servers via GET /ai/mcp-servers', async () => {
    const client = {
      get: vi.fn(async () => ({ pageNumber: 1, pageSize: 50, totalCount: 0, results: [] })),
      post: vi.fn(async () => ({})),
      put: vi.fn(async () => ({})),
      delete: vi.fn(async () => ({})),
    }
    const service = createMcpServerService(client)
    await service.pageServers(1, 20)
    expect(client.get).toHaveBeenCalledWith('/ai/mcp-servers', {
      params: { pageNumber: 1, pageSize: 20 },
    })
  })

  /**
   * 测试意图：验证通过 UUID 获取单个 MCP Server 安全元数据。
   */
  it('gets a single server by UUID', async () => {
    const client = {
      get: vi.fn(async () => ({ id: 'srv-1', name: 'fs' })),
      post: vi.fn(async () => ({})),
      put: vi.fn(async () => ({})),
      delete: vi.fn(async () => ({})),
    }
    const service = createMcpServerService(client)
    await service.getServer('srv-1')
    expect(client.get).toHaveBeenCalledWith('/ai/mcp-servers/srv-1')
  })

  /**
   * 测试意图：验证显式获取 MCP Server 完整配置使用 GET /ai/mcp-servers/{id}/config。
   */
  it('gets full server config via GET /ai/mcp-servers/{id}/config', async () => {
    const client = {
      get: vi.fn(async () => ({
        id: 'srv-1',
        name: 'fs',
        version: '3',
        configJson: '{"type":"remote","url":"https://example.com/mcp"}',
      })),
      post: vi.fn(async () => ({})),
      put: vi.fn(async () => ({})),
      delete: vi.fn(async () => ({})),
    }
    const service = createMcpServerService(client)
    const result = await service.getServerConfig('srv-1')
    expect(client.get).toHaveBeenCalledWith('/ai/mcp-servers/srv-1/config')
    expect(result.version).toBe('3')
    expect(result.configJson).toContain('https://example.com/mcp')
  })

  /**
   * 测试意图：验证创建 MCP Server 仅发送 name 与 configJson，使用 POST /ai/mcp-servers。
   */
  it('creates an MCP server with name and configJson', async () => {
    const client = {
      get: vi.fn(async () => ({})),
      post: vi.fn(async () => ({
        id: 'srv-1',
        name: 'fs',
        type: 'remote',
        timeoutMillis: 60000,
      })),
      put: vi.fn(async () => ({})),
      delete: vi.fn(async () => ({})),
    }
    const service = createMcpServerService(client)
    await service.createServer({
      name: 'fs',
      configJson: '{"type":"remote","url":"https://example.com/mcp"}',
    })
    expect(client.post).toHaveBeenCalledWith('/ai/mcp-servers', {
      name: 'fs',
      configJson: '{"type":"remote","url":"https://example.com/mcp"}',
    })
  })

  /**
   * 测试意图：验证更新 MCP Server 仅发送 configJson 与 expectedVersion，使用 PUT /ai/mcp-servers/{id}。
   */
  it('updates an MCP server with PUT /ai/mcp-servers/{id}', async () => {
    const client = {
      get: vi.fn(async () => ({})),
      post: vi.fn(async () => ({})),
      put: vi.fn(async () => ({ id: 'srv-1', name: 'fs', version: '2' })),
      delete: vi.fn(async () => ({})),
    }
    const service = createMcpServerService(client)
    await service.updateServer('srv-1', {
      configJson: '{"type":"remote","url":"https://example.com/mcp-updated"}',
      expectedVersion: '1',
    })
    expect(client.put).toHaveBeenCalledWith('/ai/mcp-servers/srv-1', {
      configJson: '{"type":"remote","url":"https://example.com/mcp-updated"}',
      expectedVersion: '1',
    })
  })

  /**
   * 测试意图：验证 MCP 工具发现契约，POST /ai/mcp-servers/{id}/discover 且 expectedVersion 放在 POST body {expectedVersion} 中。
   */
  it('discovers tools via POST /ai/mcp-servers/{id}/discover with body {expectedVersion}', async () => {
    const client = {
      get: vi.fn(async () => ({})),
      post: vi.fn(async () => ({
        server: { id: 'srv-1', version: '2', discoveryStatus: 'AVAILABLE' },
        operation: null,
      })),
      put: vi.fn(async () => ({})),
      delete: vi.fn(async () => ({})),
    }
    const service = createMcpServerService(client)
    const result = await service.discoverServer('srv-1', '1')
    expect(client.post).toHaveBeenCalledWith(
      '/ai/mcp-servers/srv-1/discover',
      { expectedVersion: '1' },
    )
    expect(result.server.discoveryStatus).toBe('AVAILABLE')
    expect(result.operation).toBeNull()
  })

  /**
   * 测试意图：验证删除 MCP Server 使用 DELETE /ai/mcp-servers/{id}?expectedVersion=。
   */
  it('deletes an MCP server via DELETE /ai/mcp-servers/{id}?expectedVersion=', async () => {
    const client = {
      get: vi.fn(async () => ({})),
      post: vi.fn(async () => ({})),
      put: vi.fn(async () => ({})),
      delete: vi.fn(async () => undefined),
    }
    const service = createMcpServerService(client)
    await service.deleteServer('srv-1', '2')
    expect(client.delete).toHaveBeenCalledWith('/ai/mcp-servers/srv-1?expectedVersion=2')
  })
})
