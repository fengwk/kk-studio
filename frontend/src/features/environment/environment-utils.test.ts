import { describe, expect, it } from 'vitest'
import { ApiError } from '@/shared/api/client'
import { formatBackendDate } from '@/shared/lib/date-format'
import {
  environmentProxyText,
  formatTimestamp,
  isEnvironmentRuntimeUnavailableError,
  presentEnvironmentConfig,
  presentUpdateProgress,
  updateDownloadPercent,
} from '@/features/environment/environment-utils'
import {
  testEnvironment,
  testRuntimeStatus,
} from '@/test-support/environment-test-fixtures'

/** 服务端 Instant 的 wire 形态是 epoch 毫秒整数。 */
const EPOCH_MILLIS = 1784505610250

describe('formatTimestamp', () => {
  // 测试意图：卡片元数据使用共享的分钟精度本地格式，缺值不渲染。
  it('renders epoch milliseconds with the shared minute format and empty values as empty', () => {
    expect(formatTimestamp(EPOCH_MILLIS)).toBe(formatBackendDate(EPOCH_MILLIS))
    expect(formatTimestamp(null)).toBe('')
    expect(formatTimestamp(undefined)).toBe('')
  })
})

describe('update progress formatting', () => {
  // 测试意图：未知总量不计算百分比；已知总量向下取整，未完成时不会提前显示 100%。
  it('computes percentages only from a known positive total', () => {
    expect(updateDownloadPercent('524288', '1048576')).toBe(50)
    expect(updateDownloadPercent('1048575', '1048576')).toBe(99)
    expect(updateDownloadPercent('1048576', '1048576')).toBe(100)
    expect(updateDownloadPercent('8388608', null)).toBeNull()
    expect(updateDownloadPercent('0', '0')).toBeNull()
    expect(updateDownloadPercent('bad', '100')).toBeNull()
  })

  it('projects downloaded bytes, total and percent without inventing an unknown total', () => {
    expect(
      presentUpdateProgress({
        targetVersion: '1.3.0',
        downloadedBytes: '8388608',
        totalBytes: null,
      }),
    ).toEqual({ downloaded: '8 MB', total: null, percent: null })
  })

  // 测试意图：只有显式 code unavailable + reason ENVIRONMENT_UNAVAILABLE（409）才归为无 READY 快照，普通 409 仍是冲突。
  it('recognizes only the runtime-unavailable conflict code and reason', () => {
    expect(
      isEnvironmentRuntimeUnavailableError(
        new ApiError('unavailable', 409, 'unavailable', undefined, 'ENVIRONMENT_UNAVAILABLE'),
      ),
    ).toBe(true)
    expect(
      isEnvironmentRuntimeUnavailableError(
        new ApiError('busy', 409, 'conflict', undefined, 'ENVIRONMENT_BUSY'),
      ),
    ).toBe(false)
    expect(isEnvironmentRuntimeUnavailableError(new Error('network'))).toBe(false)
  })
})


describe('configuration status', () => {
  // 测试意图：只有当前 owner 快照的 appliedVersion 与卡片版本相等才是已应用，并明确区分直连与未知。
  it('derives applied, pending, failed, unavailable and unknown from the runtime snapshot', () => {
    expect(presentEnvironmentConfig(testEnvironment({ version: '3' }), undefined)).toMatchObject({
      state: 'unknown',
      appliedVersion: null,
      proxy: { known: false },
    })
    expect(
      presentEnvironmentConfig(
        testEnvironment({ version: '3', status: 'OFFLINE', ready: false }),
        undefined,
      ),
    ).toMatchObject({ state: 'pendingConnect', proxy: { known: false } })
    // 断线后即使缓存里仍有上一代已应用快照，也不得继续显示已应用或直连。
    expect(
      presentEnvironmentConfig(
        testEnvironment({ version: '3', status: 'OFFLINE', ready: false }),
        testRuntimeStatus({
          configuration: { appliedVersion: '3', proxyUrl: null, error: null },
        }),
      ),
    ).toEqual({
      state: 'pendingConnect',
      appliedVersion: null,
      proxy: { known: false },
      error: null,
    })

    const applied = presentEnvironmentConfig(
      testEnvironment({ version: '3' }),
      testRuntimeStatus({
        configuration: { appliedVersion: '3', proxyUrl: null, error: null },
      }),
    )
    expect(applied).toEqual({
      state: 'applied',
      appliedVersion: '3',
      proxy: { known: true, url: null },
      error: null,
    })
    expect(environmentProxyText(applied, '直连')).toBe('直连')

    expect(
      presentEnvironmentConfig(
        testEnvironment({ version: '3' }),
        testRuntimeStatus({
          configuration: { appliedVersion: '2', proxyUrl: 'http://127.0.0.1:7890', error: null },
        }),
      ),
    ).toMatchObject({ state: 'pendingApply', proxy: { known: true } })

    expect(
      presentEnvironmentConfig(
        testEnvironment({ version: '3' }),
        testRuntimeStatus({
          configuration: {
            appliedVersion: '2',
            proxyUrl: null,
            error: { version: '3', code: 'VALIDATION_FAILED', field: 'daemonConfig.proxyUrl' },
          },
        }),
      ),
    ).toEqual({
      state: 'failed',
      appliedVersion: '2',
      proxy: { known: true, url: null },
      error: { code: 'VALIDATION_FAILED', field: 'daemonConfig.proxyUrl' },
    })
  })

  it('treats an empty configuration object as not applied', () => {
    expect(
      presentEnvironmentConfig(
        testEnvironment({ version: '3' }),
        testRuntimeStatus({ configuration: {} }),
      ),
    ).toMatchObject({
      state: 'pendingApply',
      appliedVersion: null,
      proxy: { known: false },
      error: null,
    })
  })

  it('keeps a stale error from hiding the applied current version and never invents direct', () => {
    expect(
      presentEnvironmentConfig(
        testEnvironment({ version: '4' }),
        testRuntimeStatus({
          configuration: {
            appliedVersion: '4',
            proxyUrl: null,
            error: { version: '3', code: 'PERSIST_FAILED' },
          },
        }),
      ),
    ).toMatchObject({ state: 'applied', error: null })
    expect(
      environmentProxyText(
        presentEnvironmentConfig(
          testEnvironment({ version: '4' }),
          testRuntimeStatus({
            configuration: { appliedVersion: null, proxyUrl: null, error: null },
          }),
        ),
        '直连',
      ),
    ).toBe('—')
  })

  it('compares large versions exactly and treats a version ahead of the card as unknown', () => {
    expect(
      presentEnvironmentConfig(
        testEnvironment({ version: '9007199254740992' }),
        testRuntimeStatus({
          configuration: { appliedVersion: '9007199254740993', proxyUrl: null, error: null },
        }),
      ).state,
    ).toBe('unknown')
  })
})
