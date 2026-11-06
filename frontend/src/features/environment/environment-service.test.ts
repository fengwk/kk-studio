import { afterEach, describe, expect, it, vi } from 'vitest'
import { createEnvironmentService } from '@/features/environment/environment-service'
import { createMockHttpClient } from '@/test-support/mock-http-client'

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('environmentService', () => {
  it('saves nested daemon settings with the CAS version and encoded ID', async () => {
    const client = createMockHttpClient()
    client.put.mockResolvedValue({ version: '9' })
    const config = { operatingSystem: 'linux' as const, daemon: { studioUrl: 'https://studio.example.com' } }
    expect(await createEnvironmentService(client).saveInstallConfig('env/a', '8', config)).toEqual({ version: '9' })
    expect(client.put).toHaveBeenCalledWith('/environments/env%2Fa/install-config', { expectedVersion: '8', installConfig: config })
  })
  /**
   * 测试意图：验证 Environment 列表查询端点为 /environments。
   */
  it('lists environments from GET /environments', async () => {
    const client = createMockHttpClient()
    client.get.mockResolvedValue([])
    const service = createEnvironmentService(client)
    await service.listEnvironments()
    expect(client.get).toHaveBeenCalledWith('/environments')
  })

  /**
   * 测试意图：验证通过 UUID 获取单个 Environment 详情端点使用 /environments/{id}。
   */
  it('gets a single environment by UUID via GET /environments/{id}', async () => {
    const client = createMockHttpClient()
    client.get.mockResolvedValue({ id: 'env-1', name: 'local' })
    const service = createEnvironmentService(client)
    await service.getEnvironment('env-1')
    expect(client.get).toHaveBeenCalledWith('/environments/env-1')
  })

  /**
   * 测试意图：验证创建 Environment 使用 POST /environments，且返回包含 registrationToken。
   */
  it('creates an environment card via POST /environments', async () => {
    const client = createMockHttpClient()
    client.post.mockResolvedValue({ id: 'env-1', name: 'dev', registrationToken: 'tok-123' })
    const service = createEnvironmentService(client)
    const result = await service.createEnvironment({ name: 'dev' })
    expect(client.post).toHaveBeenCalledWith('/environments', { name: 'dev' })
    expect(result.registrationToken).toBe('tok-123')
  })

  /**
   * 测试意图：签发 5 分钟安装 code 使用 POST /environments/{id}/install-code，
   * body 只有 expectedVersion，返回解包后的 code 与 expiresAt，不轮换长期 token。
   */
  it('issues a short install code via POST /environments/{id}/install-code', async () => {
    const issued = { code: "code'汉字", expiresAt: '2026-10-05T09:05:00.000Z' }
    const client = createMockHttpClient()
    client.post.mockResolvedValue(issued)
    const service = createEnvironmentService(client)
    const result = await service.createInstallCode('env/a', '8')
    expect(client.post).toHaveBeenCalledWith('/environments/env%2Fa/install-code', {
      expectedVersion: '8',
    })
    expect(result).toEqual(issued)
    expect(client.get).not.toHaveBeenCalled()
  })

  /**
   * 测试意图：验证轮换 registration token 端点使用 POST /environments/{id}/registration-token，
   * 且 expectedVersion 放在 POST body { expectedVersion } 中。
   */
  it('rotates registration token via POST /environments/{id}/registration-token with body {expectedVersion}', async () => {
    const client = createMockHttpClient()
    client.post.mockResolvedValue({ id: 'env-1', registrationToken: 'tok-new', version: '2' })
    const service = createEnvironmentService(client)
    const result = await service.rotateToken('env-1', '1')
    expect(client.post).toHaveBeenCalledWith(
      '/environments/env-1/registration-token',
      { expectedVersion: '1' },
    )
    expect(result.registrationToken).toBe('tok-new')
  })

  /**
   * 测试意图：验证删除 Environment 端点使用 DELETE /environments/{id}?expectedVersion=。
   */
  it('deletes environment via DELETE /environments/{id}?expectedVersion=', async () => {
    const client = createMockHttpClient()
    client.delete.mockResolvedValue(undefined)
    const service = createEnvironmentService(client)
    await service.deleteEnvironment('env-1', '3')
    expect(client.delete).toHaveBeenCalledWith(
      '/environments/env-1?expectedVersion=3',
    )
  })

  /**
   * 测试意图：验证获取 Environment 事件列表端点使用 GET /environments/{id}/events，
   * 且支持路径参数转义。
   */
  it('lists environment events via GET /environments/{id}/events with proper encoding', async () => {
    const events = [
      {
        time: '2026-07-20T01:00:00.000Z',
        level: 'INFO',
        type: 'READY',
        message: 'Connected',
      },
    ]
    const client = createMockHttpClient()
    client.get.mockResolvedValue(events)
    const service = createEnvironmentService(client)
    const result = await service.listEnvironmentEvents('env/special:1')
    expect(client.get).toHaveBeenCalledWith('/environments/env%2Fspecial%3A1/events')
    expect(result).toEqual(events)
  })

  it('reads the current runtime snapshot via GET /environments/{id}/runtime', async () => {
    const runtime = {
      revision: '7',
      status: {
        daemonVersion: '1.2.3',
        update: {
          targetVersion: '1.3.0',
          downloadedBytes: '8388608',
          totalBytes: null,
        },
      },
    }
    const client = createMockHttpClient()
    client.get.mockResolvedValue(runtime)
    const service = createEnvironmentService(client)
    const result = await service.getEnvironmentRuntime('env/a')
    expect(client.get).toHaveBeenCalledWith('/environments/env%2Fa/runtime')
    expect(client.post).not.toHaveBeenCalled()
    expect(result).toEqual(runtime)
  })

  it('starts a managed daemon update via POST /environments/{id}/update and returns command status with the snapshot', async () => {
    const started = {
      commandStatus: 'SENT_UNCONFIRMED',
      runtimeStatus: {
        revision: '8',
        status: { daemonVersion: '1.2.3', update: null },
      },
    }
    const client = createMockHttpClient()
    client.post.mockResolvedValue(started)
    const service = createEnvironmentService(client)
    const result = await service.startEnvironmentUpdate('env/a')
    expect(client.post).toHaveBeenCalledWith('/environments/env%2Fa/update')
    expect(client.get).not.toHaveBeenCalled()
    expect(result).toEqual(started)
  })
})
