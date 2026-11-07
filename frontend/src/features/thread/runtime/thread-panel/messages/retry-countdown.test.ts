import { describe, expect, it } from 'vitest'
import { retryCountdownSeconds } from '@/features/thread/runtime/thread-panel/messages/retry-countdown'

/** 固定基准时间：2026-07-28T10:00:00Z，所有用例都显式传入 now，避免真实时钟抖动。 */
const NOW = Date.UTC(2026, 6, 28, 10, 0, 0)

describe('retryCountdownSeconds', () => {
  // 测试意图：视图模型里的时间只有 epoch 毫秒数字一种形态，null 就是没有后续重试。
  it('returns 0 when no retry instant is projected', () => {
    expect(retryCountdownSeconds(null, NOW)).toBe(0)
  })

  it('counts from the epoch-millisecond instant', () => {
    const retryAt = NOW + 5_000
    expect(retryCountdownSeconds(retryAt, NOW)).toBe(5)
    expect(retryCountdownSeconds(5_000, 0)).toBe(5)
  })

  it('ceil()s partial seconds and clamps past timestamps to 0', () => {
    expect(retryCountdownSeconds(NOW + 2_001, NOW)).toBe(3)
    expect(retryCountdownSeconds(NOW + 1, NOW)).toBe(1)
    expect(retryCountdownSeconds(NOW, NOW)).toBe(0)
    expect(retryCountdownSeconds(NOW - 1, NOW)).toBe(0)
  })
})
