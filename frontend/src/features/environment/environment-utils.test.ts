import { describe, expect, it } from 'vitest'
import { ApiError } from '@/shared/api/client'
import { formatDateTime24 } from '@/shared/lib/date-format'
import {
  formatTimestamp,
  isEnvironmentRuntimeUnavailableError,
  presentUpdateProgress,
  updateDownloadPercent,
} from '@/features/environment/environment-utils'

/** 服务端 Instant 的 wire 形态是 epoch 毫秒整数。 */
const EPOCH_MILLIS = 1784505610250

describe('formatTimestamp', () => {
  // 测试意图：epoch 毫秒按本地 24 小时制渲染，缺值不渲染。
  it('renders epoch milliseconds in local time and empty values as empty', () => {
    expect(formatTimestamp(EPOCH_MILLIS, 'en-US')).toBe(
      formatDateTime24(new Date(EPOCH_MILLIS), 'en-US'),
    )
    expect(formatTimestamp(null, 'en-US')).toBe('')
    expect(formatTimestamp(undefined, 'en-US')).toBe('')
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
