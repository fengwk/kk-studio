import { describe, expect, it } from 'vitest'
import { retryCountdownSeconds } from '@/features/ai/runtime/thread-panel/messages/retry-countdown'

/** 固定基准时间：2026-07-28T10:00:00Z，所有用例都显式传入 now，避免真实时钟抖动。 */
const NOW = Date.UTC(2026, 6, 28, 10, 0, 0)

describe('retryCountdownSeconds', () => {
  it('returns 0 for null, blank and unparseable timestamps', () => {
    expect(retryCountdownSeconds(null, NOW)).toBe(0)
    expect(retryCountdownSeconds('   ', NOW)).toBe(0)
    expect(retryCountdownSeconds('not-a-date', NOW)).toBe(0)
    expect(retryCountdownSeconds(NaN, NOW)).toBe(0)
    // 数组少于 3 项（年月日）时不算合法时间戳。
    expect(retryCountdownSeconds([2026, 99], NOW)).toBe(0)
    // 数组元素非法时 Date.UTC 产出 NaN，同样按 0 处理。
    expect(retryCountdownSeconds([Number.NaN, 1, 2], NOW)).toBe(0)
  })

  it('accepts numeric seconds (under 1e11) and millisecond timestamps', () => {
    const retryAt = NOW + 5_000
    expect(retryCountdownSeconds(retryAt / 1000, NOW)).toBe(5)
    expect(retryCountdownSeconds(retryAt, NOW)).toBe(5)
  })

  it('parses string timestamps and full/first-of-day UTC arrays', () => {
    expect(retryCountdownSeconds('2026-07-28T10:00:05Z', NOW)).toBe(5)
    expect(retryCountdownSeconds([2026, 7, 28, 10, 0, 5], NOW)).toBe(5)
    // 数组只给年月日时，时分秒缺省为 0（UTC 零点）。
    expect(retryCountdownSeconds([2026, 7, 29], NOW)).toBe(
      Math.ceil((Date.UTC(2026, 6, 29) - NOW) / 1000),
    )
  })

  it('ceil()s partial seconds and clamps past timestamps to 0', () => {
    expect(retryCountdownSeconds(NOW + 2_001, NOW)).toBe(3)
    expect(retryCountdownSeconds(NOW + 1, NOW)).toBe(1)
    expect(retryCountdownSeconds(NOW, NOW)).toBe(0)
    expect(retryCountdownSeconds(NOW - 1, NOW)).toBe(0)
  })
})
