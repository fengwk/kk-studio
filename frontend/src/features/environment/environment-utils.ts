import { includesSearch, naturalNameCompare } from '@/shared/lib/search-utils'
import { ApiError } from '@/shared/api/client'
import type { AppLocale } from '@/shared/i18n'
import type {
  EnvironmentCardDTO,
  EnvironmentUpdateProgressDTO,
} from '@/shared/api/generated/contracts'

export function filterEnvironments(
  environments: EnvironmentCardDTO[],
  search: string,
): EnvironmentCardDTO[] {
  return environments
    .filter((environment) => includesSearch(environment.name, search) || includesSearch(environment.id, search))
    .sort((left, right) => naturalNameCompare(left.name, right.name))
}

/** 统一可用性规则（服务端 ready 标记：READY + 连接打开 + 心跳未过期）；过期/未连接一律不可选。 */
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

/** 当前 runtime 端点只在 READY owner 可用；该稳定错误码表示尚无本代 READY 快照。 */
export function isEnvironmentRuntimeUnavailableError(error: unknown): boolean {
  return (
    error instanceof ApiError &&
    error.status === 409 &&
    error.code === 'environment_unavailable'
  )
}

const BYTE_UNITS = ['B', 'KB', 'MB', 'GB', 'TB'] as const

/**
 * 字节计数在 wire 中是十进制字符串。展示时按 1024 进制缩放并保留一位小数；
 * 非法或不安全整数保持原文，避免把未知值伪装成具体容量。
 */
export function formatByteCount(value: string): string {
  const bytes = Number(value)
  if (!Number.isSafeInteger(bytes) || bytes < 0) {
    return value
  }
  let amount = bytes
  let unit = 0
  while (amount >= 1024 && unit < BYTE_UNITS.length - 1) {
    amount /= 1024
    unit += 1
  }
  const text = unit === 0 ? String(amount) : amount.toFixed(1).replace(/\.0$/u, '')
  return `${text} ${BYTE_UNITS[unit]}`
}

/**
 * 只有已知且为正的总量才能计算百分比；总长未知时返回 null，由界面展示实际字节数。
 * 使用向下取整，避免下载尚未完成时提前显示 100%。
 */
export function updateDownloadPercent(
  downloadedBytes: string,
  totalBytes: string | null,
): number | null {
  if (totalBytes == null) {
    return null
  }
  const downloaded = Number(downloadedBytes)
  const total = Number(totalBytes)
  if (
    !Number.isSafeInteger(downloaded) ||
    !Number.isSafeInteger(total) ||
    downloaded < 0 ||
    total <= 0
  ) {
    return null
  }
  return Math.min(100, Math.floor((downloaded / total) * 100))
}

export interface UpdateProgressPresentation {
  downloaded: string
  total: string | null
  percent: number | null
}

/** 将 wire 进度投影为可直接展示的已下载量、总长度和可选百分比。 */
export function presentUpdateProgress(
  update: EnvironmentUpdateProgressDTO,
): UpdateProgressPresentation {
  return {
    downloaded: formatByteCount(update.downloadedBytes),
    total: update.totalBytes == null ? null : formatByteCount(update.totalBytes),
    percent: updateDownloadPercent(update.downloadedBytes, update.totalBytes),
  }
}
