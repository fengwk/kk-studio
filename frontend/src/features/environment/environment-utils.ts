import { includesSearch, naturalNameCompare } from '@/shared/lib/search-utils'
import type {
  EnvironmentCardDTO,
  EnvironmentEventLevel,
} from '@/shared/api/contracts/ai-environment'
import type { InstantTimestamp } from '@/shared/api/contracts/base'
import type { AppLocale } from '@/shared/i18n'

export function filterEnvironments(
  environments: EnvironmentCardDTO[],
  search: string,
): EnvironmentCardDTO[] {
  return environments
    .filter((environment) => includesSearch(environment.name, search) || includesSearch(environment.id, search))
    .sort((left, right) => naturalNameCompare(left.name, right.name))
}

/** 统一可用性规则（服务端 ready 标记：READY + 连接打开 + 心跳未过期）；过期/未连接一律不可选。 */
export function filterReadyEnvironments(environments: EnvironmentCardDTO[]): EnvironmentCardDTO[] {
  return environments.filter((environment) => environment.ready)
}

/** 事件级别是封闭枚举，因此 class 映射只在这里定义一次，避免遗漏样式。 */
export function environmentEventLevelClass(level: EnvironmentEventLevel): string {
  return `is-${level.toLowerCase()}`
}

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

/**
 * 把服务端时间归一为毫秒时间戳；空值或无法解析时为 null。
 *
 * 后端 StrictJackson 以数字时间戳写时间（epoch 秒，可带小数，如 `1791332559.229`），
 * 其它入口也可能给出数字毫秒、数字字符串或 ISO 字符串，因此这里按数值量级统一归一：
 * 小于 1e12 的数值按秒处理，避免把 epoch 秒当成毫秒而把截止点落到过去。
 */
export function timestampMillis(value: InstantTimestamp | undefined | null): number | null {
  if (value == null || value === '') {
    return null
  }
  if (typeof value === 'number') {
    if (!Number.isFinite(value)) {
      return null
    }
    return value < 1e12 ? value * 1000 : value
  }
  const raw = String(value).trim()
  if (!raw) {
    return null
  }
  if (/^\d+(\.\d+)?$/.test(raw)) {
    const n = Number(raw)
    if (Number.isFinite(n)) {
      return n < 1e12 ? n * 1000 : n
    }
  }
  const parsed = Date.parse(raw)
  return Number.isFinite(parsed) ? parsed : null
}

export function formatTimestamp(value: InstantTimestamp | undefined | null, locale: AppLocale): string {
  const ms = timestampMillis(value)
  if (ms != null) {
    return formatDateTime24(new Date(ms), locale)
  }
  if (value == null || value === '') {
    return ''
  }
  const raw = String(value).trim()
  if (!raw) {
    return ''
  }
  // 无法解析时保留原始文本，便于暴露服务端给出了非时间值。
  return raw
}
