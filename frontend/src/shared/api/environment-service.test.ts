import { describe, expect, it, vi } from 'vitest'
import { createEnvironmentService } from '@/shared/api/environment-service'

describe('environmentService', () => {
  it('lists environments from GET /ai/environments', async () => {
    const client = {
      get: vi.fn(async () => []),
      post: vi.fn(async () => ({})),
      put: vi.fn(async () => ({})),
      delete: vi.fn(async () => ({})),
    }
    const service = createEnvironmentService(client)
    await service.listEnvironments()
    expect(client.get).toHaveBeenCalledWith('/ai/environments')
  })

  it('gets a single environment by UUID', async () => {
    const client = {
      get: vi.fn(async () => ({ id: 'env-1', name: 'local' })),
      post: vi.fn(async () => ({})),
      put: vi.fn(async () => ({})),
      delete: vi.fn(async () => ({})),
    }
    const service = createEnvironmentService(client)
    await service.getEnvironment('env-1')
    expect(client.get).toHaveBeenCalledWith('/ai/environments/env-1')
  })

  it('creates an environment card via POST /ai/environments', async () => {
    const client = {
      get: vi.fn(async () => ({})),
      post: vi.fn(async () => ({ id: 'env-1', name: 'dev', registrationToken: 'tok-123' })),
      put: vi.fn(async () => ({})),
      delete: vi.fn(async () => ({})),
    }
    const service = createEnvironmentService(client)
    const result = await service.createEnvironment({ name: 'dev' })
    expect(client.post).toHaveBeenCalledWith('/ai/environments', { name: 'dev' })
    expect(result.registrationToken).toBe('tok-123')
  })

  it('updates environment name with expectedVersion query', async () => {
    const client = {
      get: vi.fn(async () => ({})),
      post: vi.fn(async () => ({})),
      put: vi.fn(async () => ({ id: 'env-1', name: 'dev-renamed', version: '2' })),
      delete: vi.fn(async () => ({})),
    }
    const service = createEnvironmentService(client)
    await service.updateEnvironment('env-1', '1', { name: 'dev-renamed' })
    expect(client.put).toHaveBeenCalledWith(
      '/ai/environments/env-1?expectedVersion=1',
      { name: 'dev-renamed' },
    )
  })

  it('rotates registration token via POST /ai/environments/{id}/registration-token', async () => {
    const client = {
      get: vi.fn(async () => ({})),
      post: vi.fn(async () => ({ id: 'env-1', registrationToken: 'tok-new', version: '2' })),
      put: vi.fn(async () => ({})),
      delete: vi.fn(async () => ({})),
    }
    const service = createEnvironmentService(client)
    const result = await service.rotateToken('env-1', '1')
    expect(client.post).toHaveBeenCalledWith(
      '/ai/environments/env-1/registration-token?expectedVersion=1',
    )
    expect(result.registrationToken).toBe('tok-new')
  })

  it('deletes environment via DELETE /ai/environments/{id}?expectedVersion=', async () => {
    const client = {
      get: vi.fn(async () => ({})),
      post: vi.fn(async () => ({})),
      put: vi.fn(async () => ({})),
      delete: vi.fn(async () => undefined),
    }
    const service = createEnvironmentService(client)
    await service.deleteEnvironment('env-1', '3')
    expect(client.delete).toHaveBeenCalledWith(
      '/ai/environments/env-1?expectedVersion=3',
    )
  })

  it('lists a single-level directory with UUID encoded and wire path query param', async () => {
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
    await service.listDirectories('env-uuid-1', 'proj/sub')
    expect(client.get).toHaveBeenCalledWith(
      '/ai/environments/env-uuid-1/directories?path=proj%2Fsub',
    )

    // path 缺省为 root '.'。
    await service.listDirectories('env-uuid-1')
    expect(client.get).toHaveBeenCalledWith(
      '/ai/environments/env-uuid-1/directories?path=.',
    )
  })
})
