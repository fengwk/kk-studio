import { includesSearch, naturalNameCompare } from '@/shared/lib/search-utils'
import type { AppLocale } from '@/shared/i18n'
import type { EnvironmentCardDTO } from '@/shared/api/generated/contracts'

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
export function environmentEventLevelClass(level: string): string {
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

/** 服务端 Instant 是 epoch 秒（可带小数，如 `1791332559.229`）；转为毫秒，空值为 null。 */
export function timestampMillis(value: number | null | undefined): number | null {
  return value == null ? null : value * 1000
}

export function formatTimestamp(value: number | null | undefined, locale: AppLocale): string {
  return value == null ? '' : formatDateTime24(new Date(value * 1000), locale)
}
