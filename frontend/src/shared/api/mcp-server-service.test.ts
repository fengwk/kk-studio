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
   * 测试意图：验证通过唯一 name 获取单个 MCP Server 安全元数据。
   */
  it('gets a single server by name via GET /ai/mcp-servers/{name}', async () => {
    const client = {
      get: vi.fn(async () => ({ name: 'fs', enabled: true, timeoutMillis: 60000 })),
      post: vi.fn(async () => ({})),
      put: vi.fn(async () => ({})),
      delete: vi.fn(async () => ({})),
    }
    const service = createMcpServerService(client)
    await service.getServer('fs')
    expect(client.get).toHaveBeenCalledWith('/ai/mcp-servers/fs')
  })

  /**
   * 测试意图：验证显式获取 MCP Server 完整配置使用 GET /ai/mcp-servers/{name}/config 且携带 no-store。
   */
  it('gets full server config via GET /ai/mcp-servers/{name}/config with no-store', async () => {
    const client = {
      get: vi.fn(async () => ({
        name: 'fs',
        version: '3',
        url: 'https://example.com/mcp',
        headers: { Authorization: 'Bearer token-123' },
        enabled: true,
        timeoutMillis: 60000,
      })),
      post: vi.fn(async () => ({})),
      put: vi.fn(async () => ({})),
      delete: vi.fn(async () => ({})),
    }
    const service = createMcpServerService(client)
    const result = await service.getServerConfig('fs')
    expect(client.get).toHaveBeenCalledWith('/ai/mcp-servers/fs/config', {
      headers: { 'Cache-Control': 'no-store' },
    })
    expect(result.name).toBe('fs')
    expect(result.version).toBe('3')
    expect(result.url).toBe('https://example.com/mcp')
    expect(result.headers).toEqual({ Authorization: 'Bearer token-123' })
  })

  /**
   * 测试意图：验证创建 MCP Server 发送 name, url, headers, enabled, timeoutMillis，使用 POST /ai/mcp-servers。
   */
  it('creates an MCP server with HTTP configuration', async () => {
    const client = {
      get: vi.fn(async () => ({})),
      post: vi.fn(async () => ({
        name: 'fs',
        enabled: true,
        timeoutMillis: 60000,
        discoveryStatus: 'UNVERIFIED',
      })),
      put: vi.fn(async () => ({})),
      delete: vi.fn(async () => ({})),
    }
    const service = createMcpServerService(client)
    await service.createServer({
      name: 'fs',
      url: 'https://example.com/mcp',
      headers: { Authorization: 'Bearer secret' },
      enabled: true,
      timeoutMillis: 30000,
    })
    expect(client.post).toHaveBeenCalledWith('/ai/mcp-servers', {
      name: 'fs',
      url: 'https://example.com/mcp',
      headers: { Authorization: 'Bearer secret' },
      enabled: true,
      timeoutMillis: 30000,
    })
  })

  /**
   * 测试意图：验证更新 MCP Server 发送 expectedVersion, url, headers, enabled, timeoutMillis 全量替换，使用 PUT /ai/mcp-servers/{name}。
   */
  it('updates an MCP server with full replacement PUT /ai/mcp-servers/{name}', async () => {
    const client = {
      get: vi.fn(async () => ({})),
      post: vi.fn(async () => ({})),
      put: vi.fn(async () => ({ name: 'fs', version: '2' })),
      delete: vi.fn(async () => ({})),
    }
    const service = createMcpServerService(client)
    await service.updateServer('fs', {
      expectedVersion: '1',
      url: 'https://example.com/mcp-updated',
      headers: { 'X-Custom': 'val' },
      enabled: false,
      timeoutMillis: 45000,
    })
    expect(client.put).toHaveBeenCalledWith('/ai/mcp-servers/fs', {
      expectedVersion: '1',
      url: 'https://example.com/mcp-updated',
      headers: { 'X-Custom': 'val' },
      enabled: false,
      timeoutMillis: 45000,
    })
  })

  /**
   * 测试意图：验证 MCP 工具发现契约，POST /ai/mcp-servers/{name}/discover?expectedVersion=...，返回 McpServerDTO。
   */
  it('discovers tools via POST /ai/mcp-servers/{name}/discover?expectedVersion=...', async () => {
    const client = {
      get: vi.fn(async () => ({})),
      post: vi.fn(async () => ({
        name: 'fs',
        version: '2',
        discoveryStatus: 'AVAILABLE',
        toolCount: 5,
      })),
      put: vi.fn(async () => ({})),
      delete: vi.fn(async () => ({})),
    }
    const service = createMcpServerService(client)
    const result = await service.discoverServer('fs', '1')
    expect(client.post).toHaveBeenCalledWith('/ai/mcp-servers/fs/discover?expectedVersion=1')
    expect(result.discoveryStatus).toBe('AVAILABLE')
    expect(result.toolCount).toBe(5)
  })

  /**
   * 测试意图：验证删除 MCP Server 使用 DELETE /ai/mcp-servers/{name}?expectedVersion=。
   */
  it('deletes an MCP server via DELETE /ai/mcp-servers/{name}?expectedVersion=', async () => {
    const client = {
      get: vi.fn(async () => ({})),
      post: vi.fn(async () => ({})),
      put: vi.fn(async () => ({})),
      delete: vi.fn(async () => undefined),
    }
    const service = createMcpServerService(client)
    await service.deleteServer('fs', '2')
    expect(client.delete).toHaveBeenCalledWith('/ai/mcp-servers/fs?expectedVersion=2')
  })
})
