import { describe, expect, it, vi } from 'vitest'
import { createEnvironmentService } from '@/shared/api/environment-service'

describe('environmentService', () => {
  /**
   * 测试意图：验证 Environment 列表查询端点已迁移至 /harness/environments。
   */
  it('lists environments from GET /harness/environments', async () => {
    const client = {
      get: vi.fn(async () => []),
      post: vi.fn(async () => ({})),
      put: vi.fn(async () => ({})),
      delete: vi.fn(async () => ({})),
    }
    const service = createEnvironmentService(client)
    await service.listEnvironments()
    expect(client.get).toHaveBeenCalledWith('/harness/environments')
  })

  /**
   * 测试意图：验证通过 UUID 获取单个 Environment 详情端点使用 /harness/environments/{id}。
   */
  it('gets a single environment by UUID via GET /harness/environments/{id}', async () => {
    const client = {
      get: vi.fn(async () => ({ id: 'env-1', name: 'local' })),
      post: vi.fn(async () => ({})),
      put: vi.fn(async () => ({})),
      delete: vi.fn(async () => ({})),
    }
    const service = createEnvironmentService(client)
    await service.getEnvironment('env-1')
    expect(client.get).toHaveBeenCalledWith('/harness/environments/env-1')
  })

  /**
   * 测试意图：验证创建 Environment 使用 POST /harness/environments，且返回包含 registrationToken。
   */
  it('creates an environment card via POST /harness/environments', async () => {
    const client = {
      get: vi.fn(async () => ({})),
      post: vi.fn(async () => ({ id: 'env-1', name: 'dev', registrationToken: 'tok-123' })),
      put: vi.fn(async () => ({})),
      delete: vi.fn(async () => ({})),
    }
    const service = createEnvironmentService(client)
    const result = await service.createEnvironment({ name: 'dev' })
    expect(client.post).toHaveBeenCalledWith('/harness/environments', { name: 'dev' })
    expect(result.registrationToken).toBe('tok-123')
  })

  /**
   * 测试意图：验证更新 Environment 端点使用 PUT /harness/environments/{id}，
   * 且 expectedVersion 包含在请求 Body 中，不再放在 query string。
   */
  it('updates environment name with expectedVersion included in the request body', async () => {
    const client = {
      get: vi.fn(async () => ({})),
      post: vi.fn(async () => ({})),
      put: vi.fn(async () => ({ id: 'env-1', name: 'dev-renamed', version: '2' })),
      delete: vi.fn(async () => ({})),
    }
    const service = createEnvironmentService(client)
    await service.updateEnvironment('env-1', '1', { name: 'dev-renamed' })
    expect(client.put).toHaveBeenCalledWith(
      '/harness/environments/env-1',
      { name: 'dev-renamed', expectedVersion: '1' },
    )
  })

  /**
   * 测试意图：验证轮换 registration token 端点使用 POST /harness/environments/{id}/registration-token，
   * 且 expectedVersion 放在 POST body { expectedVersion } 中。
   */
  it('rotates registration token via POST /harness/environments/{id}/registration-token with body {expectedVersion}', async () => {
    const client = {
      get: vi.fn(async () => ({})),
      post: vi.fn(async () => ({ id: 'env-1', registrationToken: 'tok-new', version: '2' })),
      put: vi.fn(async () => ({})),
      delete: vi.fn(async () => ({})),
    }
    const service = createEnvironmentService(client)
    const result = await service.rotateToken('env-1', '1')
    expect(client.post).toHaveBeenCalledWith(
      '/harness/environments/env-1/registration-token',
      { expectedVersion: '1' },
    )
    expect(result.registrationToken).toBe('tok-new')
  })

  /**
   * 测试意图：验证删除 Environment 端点使用 DELETE /harness/environments/{id}?expectedVersion=。
   */
  it('deletes environment via DELETE /harness/environments/{id}?expectedVersion=', async () => {
    const client = {
      get: vi.fn(async () => ({})),
      post: vi.fn(async () => ({})),
      put: vi.fn(async () => ({})),
      delete: vi.fn(async () => undefined),
    }
    const service = createEnvironmentService(client)
    await service.deleteEnvironment('env-1', '3')
    expect(client.delete).toHaveBeenCalledWith(
      '/harness/environments/env-1?expectedVersion=3',
    )
  })

  /**
   * 测试意图：验证浏览 Environment 目录端点使用 /harness/environments/{id}/directories?path=，
   * 确保路径编码及默认 path 为 '.' 正常生效。
   */
  it('lists a single-level directory with UUID encoded and wire path query param under /harness', async () => {
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
      '/harness/environments/env-uuid-1/directories?path=proj%2Fsub',
    )

    // path 缺省为 root '.'。
    await service.listDirectories('env-uuid-1')
    expect(client.get).toHaveBeenCalledWith(
      '/harness/environments/env-uuid-1/directories?path=.',
    )
  })
})
