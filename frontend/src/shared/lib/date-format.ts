import type { AppLocale } from '@/shared/i18n'

/** 把 wire Instant（epoch 秒）格式化为本地 `YYYY-MM-DD HH:mm`；非法值显示 '-'。 */
export function formatBackendDate(value: number): string {
  const date = new Date(value * 1000)
  if (!Number.isFinite(date.getTime())) {
    return '-'
  }
  return formatLocalDateMinutes(date)
}

/** 本地日期时间，精确到分钟。调用方须传入有效日期。 */
export function formatLocalDateMinutes(date: Date): string {
  return `${String(date.getFullYear()).padStart(4, '0')}-${String(
    date.getMonth() + 1,
  ).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')} ${String(
    date.getHours(),
  ).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
}

/** 本地 24 小时制日期时间（含秒）；locale 决定分隔符与字段顺序。 */
export function formatDateTime24(date: Date, locale: AppLocale): string {
  return date.toLocaleString(locale, {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  })
}

/** wire 时间戳的合法形态：epoch 数字、ISO 字符串或 `[year, month, day, hour, minute, second]`。 */
export type TimestampInput = number | string | readonly number[] | null | undefined

/**
 * wire 时间戳转毫秒。数字按绝对值区分秒与毫秒（服务端 epoch 秒可能带小数，
 * 毫秒时间戳已超过 1e11）；字符串按可解析日期；数组按 UTC 日历字段。
 * 无法识别的形态返回 null，绝不猜测当前时间。
 */
export function timestampMillis(value: TimestampInput): number | null {
  if (typeof value === 'number') {
    if (!Number.isFinite(value)) {
      return null
    }
    return Math.abs(value) < 100_000_000_000 ? value * 1000 : value
  }
  if (typeof value === 'string') {
    if (!value.trim()) {
      return null
    }
    const parsed = Date.parse(value)
    return Number.isFinite(parsed) ? parsed : null
  }
  if (Array.isArray(value) && value.length >= 3) {
    const [year, month, day, hour = 0, minute = 0, second = 0] = value.map(Number)
    const parsed = Date.UTC(year, month - 1, day, hour, minute, second)
    return Number.isFinite(parsed) ? parsed : null
  }
  return null
}
