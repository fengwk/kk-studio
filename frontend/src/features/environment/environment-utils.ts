import { includesSearch, naturalNameCompare } from '@/shared/lib/search-utils'
import { ApiError } from '@/shared/api/client'
import { formatDateTime24 } from '@/shared/lib/date-format'
import { formatByteCount } from '@/shared/lib/format-bytes'
import type { AppLocale } from '@/shared/i18n'
import type {
  EnvironmentDTO,
  EnvironmentEventLevel,
  EnvironmentUpdateProgressDTO,
} from '@/shared/api/generated/contracts'

export function filterEnvironments(
  environments: EnvironmentDTO[],
  search: string,
): EnvironmentDTO[] {
  return environments
    .filter((environment) => includesSearch(environment.name, search) || includesSearch(environment.id, search))
    .sort((left, right) => naturalNameCompare(left.name, right.name))
}

/** 统一可用性规则（服务端 ready 标记：READY + 连接打开 + 心跳未过期）；过期/未连接一律不可选。 */
/** 事件级别是封闭枚举，因此 class 映射只在这里定义一次，避免遗漏样式。 */
export function environmentEventLevelClass(level: EnvironmentEventLevel): string {
  return `is-${level.toLowerCase()}`
}

export function formatTimestamp(value: number | null | undefined, locale: AppLocale): string {
  return value == null ? '' : formatDateTime24(new Date(value), locale)
}

/** 当前 runtime 端点只在 READY owner 可用；按 code unavailable + reason ENVIRONMENT_UNAVAILABLE 识别。 */
export function isEnvironmentRuntimeUnavailableError(error: unknown): boolean {
  return (
    error instanceof ApiError &&
    error.status === 409 &&
    error.code === 'unavailable' &&
    error.reason === 'ENVIRONMENT_UNAVAILABLE'
  )
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
