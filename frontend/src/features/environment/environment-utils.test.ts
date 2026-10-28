import { describe, expect, it } from 'vitest'
import { ApiError } from '@/shared/api/client'
import {
  formatByteCount,
  formatDateTime24,
  formatTimestamp,
  isEnvironmentRuntimeUnavailableError,
  presentUpdateProgress,
  timestampMillis,
  updateDownloadPercent,
} from '@/features/environment/environment-utils'

/** 服务端 Instant 的 wire 形态是 epoch 秒，可带小数。 */
const EPOCH_SECONDS = 1784505610.25
const EPOCH_MILLIS = 1784505610250

describe('timestampMillis', () => {
  // 测试意图：epoch 秒（含小数）换算为毫秒，空值为 null。
  it('converts epoch seconds, including fractions, to milliseconds', () => {
    expect(timestampMillis(EPOCH_SECONDS)).toBe(EPOCH_MILLIS)
    expect(timestampMillis(1784505610)).toBe(1784505610000)
    expect(timestampMillis(null)).toBeNull()
    expect(timestampMillis(undefined)).toBeNull()
  })
})

describe('formatTimestamp', () => {
  // 测试意图：epoch 秒按本地 24 小时制渲染，缺值不渲染。
  it('renders epoch seconds in local time and empty values as empty', () => {
    expect(formatTimestamp(EPOCH_SECONDS, 'en-US')).toBe(
      formatDateTime24(new Date(EPOCH_MILLIS), 'en-US'),
    )
    expect(formatTimestamp(null, 'en-US')).toBe('')
    expect(formatTimestamp(undefined, 'en-US')).toBe('')
  })
})

describe('update progress formatting', () => {
  // 测试意图：字节按可读单位展示，非法 wire 值保持原文而不伪造容量。
  it('formats byte counts and preserves malformed values', () => {
    expect(formatByteCount('0')).toBe('0 B')
    expect(formatByteCount('1024')).toBe('1 KB')
    expect(formatByteCount('8388608')).toBe('8 MB')
    expect(formatByteCount('not-a-number')).toBe('not-a-number')
    expect(formatByteCount('-1')).toBe('-1')
  })

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

  // 测试意图：只有显式 environment_unavailable（409）才归为无 READY 快照，普通 409 仍是冲突。
  it('recognizes only the runtime-unavailable conflict code', () => {
    expect(
      isEnvironmentRuntimeUnavailableError(
        new ApiError('unavailable', 409, 'environment_unavailable'),
      ),
    ).toBe(true)
    expect(
      isEnvironmentRuntimeUnavailableError(new ApiError('busy', 409, 'environment_busy')),
    ).toBe(false)
    expect(isEnvironmentRuntimeUnavailableError(new Error('network'))).toBe(false)
  })
})
