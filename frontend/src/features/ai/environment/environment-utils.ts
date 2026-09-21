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

export function formatTimestamp(value: InstantTimestamp | undefined | null, locale: AppLocale): string {
  if (value == null || value === '') {
    return ''
  }
  if (typeof value === 'number' && Number.isFinite(value)) {
    const ms = value < 1e12 ? value * 1000 : value
    return formatDateTime24(new Date(ms), locale)
  }
  const raw = String(value).trim()
  if (!raw) {
    return ''
  }
  if (/^\d+(\.\d+)?$/.test(raw)) {
    const n = Number(raw)
    if (Number.isFinite(n)) {
      const ms = n < 1e12 ? n * 1000 : n
      return formatDateTime24(new Date(ms), locale)
    }
  }
  const parsed = Date.parse(raw)
  if (Number.isFinite(parsed)) {
    return formatDateTime24(new Date(parsed), locale)
  }
  return raw
}
