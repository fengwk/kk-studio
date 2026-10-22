import { describe, expect, it } from 'vitest'
import {
  formatDateTime24,
  formatTimestamp,
  timestampMillis,
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
