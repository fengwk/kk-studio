import { describe, expect, it, vi } from 'vitest'
import { createMcpServerService } from '@/shared/api/mcp-server-service'

describe('mcpServerService', () => {
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

  it('creates an MCP server', async () => {
    const client = {
      get: vi.fn(async () => ({})),
      post: vi.fn(async () => ({ id: 'srv-1', name: 'fs', url: 'http://localhost:8000', timeoutMillis: 30000 })),
      put: vi.fn(async () => ({})),
      delete: vi.fn(async () => ({})),
    }
    const service = createMcpServerService(client)
    await service.createServer({ name: 'fs', url: 'http://localhost:8000', timeoutMillis: 30000 })
    expect(client.post).toHaveBeenCalledWith('/ai/mcp-servers', {
      name: 'fs',
      url: 'http://localhost:8000',
      timeoutMillis: 30000,
    })
  })

  it('updates an MCP server with PUT /ai/mcp-servers/{id}', async () => {
    const client = {
      get: vi.fn(async () => ({})),
      post: vi.fn(async () => ({})),
      put: vi.fn(async () => ({ id: 'srv-1', name: 'fs', version: '2' })),
      delete: vi.fn(async () => ({})),
    }
    const service = createMcpServerService(client)
    await service.updateServer('srv-1', {
      url: 'http://localhost:9000',
      expectedVersion: '1',
    })
    expect(client.put).toHaveBeenCalledWith('/ai/mcp-servers/srv-1', {
      url: 'http://localhost:9000',
      expectedVersion: '1',
    })
  })

  it('refreshes tools via POST /ai/mcp-servers/{id}/refresh?expectedVersion=', async () => {
    const client = {
      get: vi.fn(async () => ({})),
      post: vi.fn(async () => ({ id: 'srv-1', version: '2' })),
      put: vi.fn(async () => ({})),
      delete: vi.fn(async () => ({})),
    }
    const service = createMcpServerService(client)
    await service.refreshServer('srv-1', '1')
    expect(client.post).toHaveBeenCalledWith('/ai/mcp-servers/srv-1/refresh?expectedVersion=1')
  })

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
