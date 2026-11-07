import { describe, expect, it } from 'vitest'
import {
  formatBackendDate,
  formatBackendDateSeconds,
  parseCodecInstantMillis,
} from '@/shared/lib/date-format'

describe('formatBackendDate', () => {
  it('returns a placeholder for non-finite values without throwing', () => {
    expect(formatBackendDate(Number.NaN)).toBe('-')
    expect(formatBackendDate(Number.POSITIVE_INFINITY)).toBe('-')
    expect(formatBackendDate(1e20)).toBe('-')
    expect(formatBackendDateSeconds(Number.NaN)).toBe('-')
  })

  it('formats epoch milliseconds in local time independently of the host timezone', () => {
    // Intl is an independent local-time oracle; fixed timezone browser cases cover the wire paths.
    const milliseconds = Date.parse('2026-10-08T13:15:00Z')
    const parts = new Intl.DateTimeFormat('en-CA', {
      year: 'numeric', month: '2-digit', day: '2-digit',
      hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
    }).formatToParts(milliseconds)
    const fields = Object.fromEntries(parts.map(({ type, value }) => [type, value]))
    const expected = `${fields.year}-${fields.month}-${fields.day} ${fields.hour}:${fields.minute}`
    expect(formatBackendDate(milliseconds)).toBe(expected)
    // 秒与毫秒都不参与分钟精度格式。
    expect(formatBackendDate(milliseconds + 42_250)).toBe(expected)
    expect(formatBackendDateSeconds(milliseconds + 42_250)).toBe(
      `${expected}:${String(new Date(milliseconds + 42_250).getSeconds()).padStart(2, '0')}`,
    )
  })
})

describe('parseCodecInstantMillis', () => {
  // 测试意图：历史 codec JSON 只认 Java Instant.toString() 的毫秒精度 UTC 文本。
  it('accepts whole-second and millisecond canonical instants only', () => {
    expect(parseCodecInstantMillis('2026-07-28T10:00:05Z')).toBe(Date.UTC(2026, 6, 28, 10, 0, 5))
    expect(parseCodecInstantMillis('2026-07-28T10:00:05.120Z')).toBe(
      Date.UTC(2026, 6, 28, 10, 0, 5, 120),
    )
  })

  // 测试意图：非规范文本退回 null，由调用方展示 unknown entry，绝不猜测时间。
  it('rejects non-canonical, non-UTC, numeric and array shapes', () => {
    expect(parseCodecInstantMillis('2026-07-28T10:00:05.12Z')).toBeNull()
    expect(parseCodecInstantMillis('2026-07-28T10:00:05.120000Z')).toBeNull()
    // Java Instant.toString() 整秒时省略小数，显式 .000 即非规范文本。
    expect(parseCodecInstantMillis('2026-07-28T10:00:05.000Z')).toBeNull()
    expect(parseCodecInstantMillis('2026-07-28T10:00:05+00:00')).toBeNull()
    expect(parseCodecInstantMillis('2026-07-28T10:00:05')).toBeNull()
    expect(parseCodecInstantMillis('2026-02-30T00:00:00Z')).toBeNull()
    expect(parseCodecInstantMillis('not-a-date')).toBeNull()
    expect(parseCodecInstantMillis('')).toBeNull()
    expect(parseCodecInstantMillis(null)).toBeNull()
    expect(parseCodecInstantMillis(undefined)).toBeNull()
    expect(parseCodecInstantMillis(1785232800)).toBeNull()
    expect(parseCodecInstantMillis([2026, 7, 28, 10, 0, 5])).toBeNull()
  })
})
