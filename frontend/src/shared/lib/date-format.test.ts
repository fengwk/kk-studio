import { describe, expect, it } from 'vitest'
import { formatBackendDate, formatDateTime24, timestampMillis } from '@/shared/lib/date-format'

describe('formatBackendDate', () => {
  it('returns a placeholder for non-finite values without throwing', () => {
    expect(formatBackendDate(Number.NaN)).toBe('-')
    expect(formatBackendDate(Number.POSITIVE_INFINITY)).toBe('-')
    expect(formatBackendDate(1e20)).toBe('-')
  })

  it('formats epoch seconds in local time independently of the host timezone', () => {
    // Intl is an independent local-time oracle; fixed timezone browser cases cover the wire paths.
    const milliseconds = Date.parse('2026-10-08T13:15:00Z')
    const parts = new Intl.DateTimeFormat('en-CA', {
      year: 'numeric', month: '2-digit', day: '2-digit',
      hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
    }).formatToParts(milliseconds)
    const fields = Object.fromEntries(parts.map(({ type, value }) => [type, value]))
    const expected = `${fields.year}-${fields.month}-${fields.day} ${fields.hour}:${fields.minute}`
    expect(formatBackendDate(milliseconds / 1000)).toBe(expected)
    // fractional seconds keep the same minute
    expect(formatBackendDate(milliseconds / 1000 + 0.25)).toBe(expected)
  })
})

describe('formatDateTime24', () => {
  // 测试意图：24 小时制含秒渲染由 locale 决定字段顺序，两种语言都必须使用 24 小时制。
  it('renders the same instant in 24-hour form for both supported locales', () => {
    const date = new Date(2026, 9, 8, 13, 15, 30)
    expect(formatDateTime24(date, 'en-US')).toContain('2026')
    expect(formatDateTime24(date, 'en-US')).toContain('13:15:30')
    expect(formatDateTime24(date, 'zh-CN')).toContain('2026')
    expect(formatDateTime24(date, 'zh-CN')).toContain('13:15:30')
  })
})

describe('timestampMillis', () => {
  // 测试意图：wire 时间戳的三种形态都能换算为毫秒，秒/毫秒按数量级区分。
  it('converts epoch seconds, millisecond timestamps, ISO strings and UTC arrays', () => {
    const instant = Date.UTC(2026, 6, 28, 10, 0, 5)
    expect(timestampMillis(instant / 1000)).toBe(instant)
    expect(timestampMillis(instant / 1000 + 0.25)).toBe(instant + 250)
    expect(timestampMillis(instant)).toBe(instant)
    expect(timestampMillis('2026-07-28T10:00:05Z')).toBe(instant)
    expect(timestampMillis([2026, 7, 28, 10, 0, 5])).toBe(instant)
    // 只给年月日时，时分秒缺省为 0（UTC 零点）。
    expect(timestampMillis([2026, 7, 29])).toBe(Date.UTC(2026, 6, 29))
  })

  // 测试意图：无法识别的形态一律返回 null，绝不猜测为当前时间。
  it('returns null for missing or unparseable values', () => {
    expect(timestampMillis(null)).toBeNull()
    expect(timestampMillis(undefined)).toBeNull()
    expect(timestampMillis(Number.NaN)).toBeNull()
    expect(timestampMillis(Number.POSITIVE_INFINITY)).toBeNull()
    expect(timestampMillis('')).toBeNull()
    expect(timestampMillis('   ')).toBeNull()
    expect(timestampMillis('not-a-date')).toBeNull()
    expect(timestampMillis([2026, 7])).toBeNull()
    expect(timestampMillis([Number.NaN, 1, 2])).toBeNull()
  })
})
