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
   * 测试意图：验证按需读取当前 registration token 使用 GET /harness/environments/{id}/token，
   * 且该调用是普通只读 GET（不携带 expectedVersion、不触发轮换）。
   */
  it('reads the current registration token via GET /harness/environments/{id}/token', async () => {
    const client = {
      get: vi.fn(async () => ({ id: 'env-1', registrationToken: 'tok-current', version: '3' })),
      post: vi.fn(async () => ({})),
      put: vi.fn(async () => ({})),
      delete: vi.fn(async () => ({})),
    }
    const service = createEnvironmentService(client)
    const result = await service.getRegistrationToken('env-1')
    expect(client.get).toHaveBeenCalledWith('/harness/environments/env-1/token')
    expect(client.post).not.toHaveBeenCalled()
    expect(result.registrationToken).toBe('tok-current')
    expect(result.version).toBe('3')
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
   * 测试意图：验证获取 Environment 事件列表端点使用 GET /harness/environments/{id}/events，
   * 且支持路径参数转义。
   */
  it('lists environment events via GET /harness/environments/{id}/events with proper encoding', async () => {
    const events = [
      {
        time: '2026-07-20T01:00:00.000Z',
        level: 'INFO',
        type: 'READY',
        message: 'Connected',
      },
    ]
    const client = {
      get: vi.fn(async () => events),
      post: vi.fn(async () => ({})),
      put: vi.fn(async () => ({})),
      delete: vi.fn(async () => ({})),
    }
    const service = createEnvironmentService(client)
    const result = await service.listEnvironmentEvents('env/special:1')
    expect(client.get).toHaveBeenCalledWith('/harness/environments/env%2Fspecial%3A1/events')
    expect(result).toEqual(events)
  })
})
