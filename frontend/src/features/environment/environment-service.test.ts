import { afterEach, describe, expect, it, vi } from 'vitest'
import { request } from '@/shared/api/client'
import type {
  EnvironmentEventDTO,
  EnvironmentInstallCodeDTO,
  EnvironmentRuntimeStatusDTO,
  EnvironmentUpdateStartDTO,
} from '@/shared/api/generated/contracts'
import { createEnvironmentService } from '@/features/environment/environment-service'
import {
  testDaemonConfig,
  testEnvironment,
  testRuntimeStatus,
} from '@/test-support/environment-test-fixtures'

vi.mock('@/shared/api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/shared/api/client')>()
  return { ...actual, request: vi.fn() }
})

const apiRequest = vi.mocked(request)
const service = createEnvironmentService()

afterEach(() => apiRequest.mockReset())

describe('environmentService', () => {
  /** 测试意图：创建请求一次提交完整卡片属性，不再有第二套安装设置。 */
  it('creates a full environment card', async () => {
    const card = testEnvironment({ name: 'dev', registrationToken: 'tok-123' })
    apiRequest.mockResolvedValue(card)
    const daemonConfig = testDaemonConfig({ proxyUrl: 'http://127.0.0.1:7890' })
    const result = await service.createEnvironment({
      name: 'dev',
      note: 'note',
      daemonConfig,
    })
    expect(apiRequest).toHaveBeenCalledWith('POST /api/platform/environments', {
      body: { name: 'dev', note: 'note', daemonConfig },
    })
    expect(result.registrationToken).toBe('tok-123')
  })

  /** 测试意图：PUT 整体替换 note 与 daemonConfig，并携带 CAS 版本；请求不含 name。 */
  it('updates card configuration with the expected version', async () => {
    apiRequest.mockResolvedValue(testEnvironment({ version: '9' }))
    const daemonConfig = testDaemonConfig({ studioUrl: 'https://studio.example.com' })
    expect(
      await service.updateEnvironment('env/a', {
        expectedVersion: '8',
        note: 'new note',
        daemonConfig,
      }),
    ).toEqual(testEnvironment({ version: '9' }))
    expect(apiRequest).toHaveBeenCalledWith('PUT /api/platform/environments/{environmentId}', {
      path: { environmentId: 'env/a' },
      body: { expectedVersion: '8', note: 'new note', daemonConfig },
    })
  })

  /** 测试意图：验证 Environment 列表查询命中平台环境资源。 */
  it('lists environments from the platform resource', async () => {
    apiRequest.mockResolvedValue([])
    await service.listEnvironments()
    expect(apiRequest).toHaveBeenCalledWith('GET /api/platform/environments', {})
  })

  /** 测试意图：验证单个 Environment 详情以路径参数定位。 */
  it('gets a single environment by its identifier', async () => {
    apiRequest.mockResolvedValue(testEnvironment())
    await service.getEnvironment('env-1')
    expect(apiRequest).toHaveBeenCalledWith('GET /api/platform/environments/{environmentId}', {
      path: { environmentId: 'env-1' },
    })
  })

  /** 测试意图：签发 5 分钟安装 code，body 只有 expectedVersion。 */
  it('issues a short install code', async () => {
    const issued: EnvironmentInstallCodeDTO = {
      code: "code'汉字",
      expiresAt: 1_760_000_300_000,
    }
    apiRequest.mockResolvedValue(issued)
    const result = await service.createInstallCode('env/a', '8')
    expect(apiRequest).toHaveBeenCalledWith(
      'POST /api/platform/environments/{environmentId}/install-codes',
      { path: { environmentId: 'env/a' }, body: { expectedVersion: '8' } },
    )
    expect(result).toEqual(issued)
  })

  /** 测试意图：轮换 registration token 把 expectedVersion 放进 body。 */
  it('rotates the registration token with the CAS version', async () => {
    apiRequest.mockResolvedValue(testEnvironment({ registrationToken: 'tok-new', version: '2' }))
    const result = await service.rotateToken('env-1', '1')
    expect(apiRequest).toHaveBeenCalledWith(
      'POST /api/platform/environments/{environmentId}/rotate-token',
      { path: { environmentId: 'env-1' }, body: { expectedVersion: '1' } },
    )
    expect(result.registrationToken).toBe('tok-new')
  })

  /** 测试意图：删除 Environment 用 query 传递 expectedVersion。 */
  it('deletes an environment with the expected version', async () => {
    apiRequest.mockResolvedValue(undefined)
    await service.deleteEnvironment('env-1', '3')
    expect(apiRequest).toHaveBeenCalledWith('DELETE /api/platform/environments/{environmentId}', {
      path: { environmentId: 'env-1' },
      query: { expectedVersion: '3' },
    })
  })

  /** 测试意图：事件列表按路径参数定位环境。 */
  it('lists environment events by identifier', async () => {
    const events: EnvironmentEventDTO[] = [
      {
        occurredAt: 1_759_000_000_000,
        level: 'INFO',
        type: 'READY',
        message: 'Connected',
      },
    ]
    apiRequest.mockResolvedValue(events)
    const result = await service.listEnvironmentEvents('env/special:1')
    expect(apiRequest).toHaveBeenCalledWith(
      'GET /api/platform/environments/{environmentId}/events',
      { path: { environmentId: 'env/special:1' } },
    )
    expect(result).toEqual(events)
  })

  /** 测试意图：runtime 快照包含完整配置状态，不做旧形状降级。 */
  it('reads the current runtime snapshot with configuration state', async () => {
    const runtime: EnvironmentRuntimeStatusDTO = testRuntimeStatus({
      revision: '7',
      daemonVersion: '1.2.3',
      update: {
        targetVersion: '1.3.0',
        downloadedBytes: '8388608',
        totalBytes: null,
      },
      configuration: {
        appliedVersion: '7',
        proxyUrl: 'http://127.0.0.1:7890',
        error: null,
      },
    })
    apiRequest.mockResolvedValue(runtime)
    const result = await service.getEnvironmentRuntime('env/a')
    expect(apiRequest).toHaveBeenCalledWith(
      'GET /api/platform/environments/{environmentId}/runtime',
      { path: { environmentId: 'env/a' } },
    )
    expect(result).toEqual(runtime)
  })

  it('starts a managed daemon update and returns command status with the snapshot', async () => {
    const started: EnvironmentUpdateStartDTO = {
      commandStatus: 'SENT_UNCONFIRMED',
      runtimeStatus: testRuntimeStatus({ revision: '8', daemonVersion: '1.2.3' }),
    }
    apiRequest.mockResolvedValue(started)
    const result = await service.startEnvironmentUpdate('env/a')
    expect(apiRequest).toHaveBeenCalledWith(
      'POST /api/platform/environments/{environmentId}/update-daemon',
      { path: { environmentId: 'env/a' } },
    )
    expect(result).toEqual(started)
  })
})
