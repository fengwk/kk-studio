import { describe, expect, it } from 'vitest'
import { createEnvironmentService } from '@/shared/api/environment-service'
import { createMockHttpClient } from '@/test-support/mock-http-client'

describe('environmentService', () => {
  it('saves nested daemon settings with the CAS version and encoded ID', async () => {
    const client = createMockHttpClient()
    client.put.mockResolvedValue({ version: '9' })
    const config = { operatingSystem: 'linux' as const, daemon: { studioUrl: 'https://studio.example.com' } }
    expect(await createEnvironmentService(client).saveInstallConfig('env/a', '8', config)).toEqual({ version: '9' })
    expect(client.put).toHaveBeenCalledWith('/harness/environments/env%2Fa/install-config', { expectedVersion: '8', installConfig: config })
  })
  /**
   * 测试意图：验证 Environment 列表查询端点已迁移至 /harness/environments。
   */
  it('lists environments from GET /harness/environments', async () => {
    const client = createMockHttpClient()
    client.get.mockResolvedValue([])
    const service = createEnvironmentService(client)
    await service.listEnvironments()
    expect(client.get).toHaveBeenCalledWith('/harness/environments')
  })

  /**
   * 测试意图：验证通过 UUID 获取单个 Environment 详情端点使用 /harness/environments/{id}。
   */
  it('gets a single environment by UUID via GET /harness/environments/{id}', async () => {
    const client = createMockHttpClient()
    client.get.mockResolvedValue({ id: 'env-1', name: 'local' })
    const service = createEnvironmentService(client)
    await service.getEnvironment('env-1')
    expect(client.get).toHaveBeenCalledWith('/harness/environments/env-1')
  })

  /**
   * 测试意图：验证创建 Environment 使用 POST /harness/environments，且返回包含 registrationToken。
   */
  it('creates an environment card via POST /harness/environments', async () => {
    const client = createMockHttpClient()
    client.post.mockResolvedValue({ id: 'env-1', name: 'dev', registrationToken: 'tok-123' })
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
    const client = createMockHttpClient()
    client.get.mockResolvedValue({ id: 'env-1', registrationToken: 'tok-current', version: '3' })
    const service = createEnvironmentService(client)
    const result = await service.getRegistrationToken('env-1')
    expect(client.get).toHaveBeenCalledWith('/harness/environments/env-1/token')
    expect(client.post).not.toHaveBeenCalled()
    expect(result.registrationToken).toBe('tok-current')
    expect(result.version).toBe('3')
  })

  /**
   * 测试意图：签发 5 分钟安装 code 使用 POST /harness/environments/{id}/install-code，
   * body 只有 expectedVersion，返回解包后的 code 与 expiresAt，不轮换长期 token。
   */
  it('issues a short install code via POST /harness/environments/{id}/install-code', async () => {
    const issued = { code: "code'汉字", expiresAt: '2026-10-05T09:05:00.000Z' }
    const client = createMockHttpClient()
    client.post.mockResolvedValue(issued)
    const service = createEnvironmentService(client)
    const result = await service.createInstallCode('env/a', '8')
    expect(client.post).toHaveBeenCalledWith('/harness/environments/env%2Fa/install-code', {
      expectedVersion: '8',
    })
    expect(result).toEqual(issued)
    expect(client.get).not.toHaveBeenCalled()
  })

  /**
   * 测试意图：验证轮换 registration token 端点使用 POST /harness/environments/{id}/registration-token，
   * 且 expectedVersion 放在 POST body { expectedVersion } 中。
   */
  it('rotates registration token via POST /harness/environments/{id}/registration-token with body {expectedVersion}', async () => {
    const client = createMockHttpClient()
    client.post.mockResolvedValue({ id: 'env-1', registrationToken: 'tok-new', version: '2' })
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
    const client = createMockHttpClient()
    client.delete.mockResolvedValue(undefined)
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
    const client = createMockHttpClient()
    client.get.mockResolvedValue(events)
    const service = createEnvironmentService(client)
    const result = await service.listEnvironmentEvents('env/special:1')
    expect(client.get).toHaveBeenCalledWith('/harness/environments/env%2Fspecial%3A1/events')
    expect(result).toEqual(events)
  })

  it('reads managed daemon update status via GET /harness/environments/{id}/update', async () => {
    const update = {
      operationId: 'op-1',
      targetVersion: '1.3.0',
      phase: 'RUNNING' as const,
      createdAt: '2026-10-09T00:00:00.000Z',
      updatedAt: '2026-10-09T00:01:00.000Z',
    }
    const client = createMockHttpClient()
    client.get.mockResolvedValue(update)
    const service = createEnvironmentService(client)
    const result = await service.getEnvironmentUpdate('env/a')
    expect(client.get).toHaveBeenCalledWith('/harness/environments/env%2Fa/update')
    expect(client.post).not.toHaveBeenCalled()
    expect(result).toEqual(update)
  })

  it('starts managed daemon update via POST /harness/environments/{id}/update with no body', async () => {
    const card = { id: 'env/a', name: 'local', version: '2' }
    const client = createMockHttpClient()
    client.post.mockResolvedValue(card)
    const service = createEnvironmentService(client)
    const result = await service.startEnvironmentUpdate('env/a')
    expect(client.post).toHaveBeenCalledWith('/harness/environments/env%2Fa/update')
    expect(client.get).not.toHaveBeenCalled()
    expect(result).toEqual(card)
  })

  it('creates an ide view via POST /harness/environments/{id}/ide/views', async () => {
    const view = {
      viewId: 'v-1',
      environmentId: 'env-1',
      directory: '/home/user/workspace',
      entryUrl: 'https://env-1.ide.example.com/?workspace=xyz',
    }
    const client = createMockHttpClient()
    client.post.mockResolvedValue(view)
    const service = createEnvironmentService(client)
    const result = await service.createIdeView('env-1', { viewId: 'v-1' })
    expect(client.post).toHaveBeenCalledWith('/harness/environments/env-1/ide/views', { viewId: 'v-1' })
    expect(result).toEqual(view)
  })

  it('changes ide view directory via PUT /harness/environments/{id}/ide/views/{viewId}/directory', async () => {
    const updated = {
      viewId: 'v-1',
      environmentId: 'env-1',
      directory: '/new/path',
      entryUrl: null,
    }
    const client = createMockHttpClient()
    client.put.mockResolvedValue(updated)
    const service = createEnvironmentService(client)
    const result = await service.changeIdeViewDirectory('env-1', 'v-1', { directory: '/new/path' })
    expect(client.put).toHaveBeenCalledWith('/harness/environments/env-1/ide/views/v-1/directory', {
      directory: '/new/path',
    })
    expect(result).toEqual(updated)
  })

  it('opens an ide view terminal via POST /harness/environments/{id}/ide/views/{viewId}/terminal', async () => {
    const client = createMockHttpClient()
    client.post.mockResolvedValue(undefined)
    const service = createEnvironmentService(client)
    await service.openIdeViewTerminal('env-1', 'v-1')
    expect(client.post).toHaveBeenCalledWith('/harness/environments/env-1/ide/views/v-1/terminal')
  })

  it('closes an ide view via DELETE /harness/environments/{id}/ide/views/{viewId}', async () => {
    const client = createMockHttpClient()
    client.delete.mockResolvedValue(undefined)
    const service = createEnvironmentService(client)
    await service.closeIdeView('env-1', 'v-1')
    expect(client.delete).toHaveBeenCalledWith('/harness/environments/env-1/ide/views/v-1')
  })
})
