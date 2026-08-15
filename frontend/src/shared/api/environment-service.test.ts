import { describe, expect, it, vi } from 'vitest'
import { createEnvironmentService } from '@/shared/api/environment-service'

describe('environmentService', () => {
  it('lists live environments from the read-only registry', async () => {
    const client = {
      get: vi.fn(async () => []),
      post: vi.fn(async () => ({})),
      put: vi.fn(async () => ({})),
      delete: vi.fn(async () => ({})),
    }
    const service = createEnvironmentService(client)
    await service.listEnvironments()
    expect(client.get).toHaveBeenCalledWith('/ai/environment')
  })

  it('lists a single-level directory with the name URL-encoded and the wire path as a query param', async () => {
    const client = {
      get: vi.fn(async () => ({
        path: 'proj/sub',
        displayPath: 'sub',
        parentPath: 'proj',
        truncated: false,
        gitBranch: null,
        entries: [],
      })),
      post: vi.fn(async () => ({})),
      put: vi.fn(async () => ({})),
      delete: vi.fn(async () => ({})),
    }
    const service = createEnvironmentService(client)
    await service.listDirectories('my env/1', 'proj/sub')
    expect(client.get).toHaveBeenCalledWith('/ai/environments/my%20env%2F1/directories', {
      params: { path: 'proj/sub' },
    })
    // path 缺省为 root '.'。
    await service.listDirectories('local')
    expect(client.get).toHaveBeenCalledWith('/ai/environments/local/directories', {
      params: { path: '.' },
    })
  })
})
