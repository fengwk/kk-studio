import { includesSearch, naturalNameCompare } from '@/shared/lib/search-utils'
import { ApiError } from '@/shared/api/client'
import { formatBackendDate } from '@/shared/lib/date-format'
import { formatByteCount } from '@/shared/lib/format-bytes'
import type {
  EnvironmentDTO,
  EnvironmentEventLevel,
  EnvironmentRuntimeDTO,
  EnvironmentRuntimeStatusDTO,
  EnvironmentUpdateProgressDTO,
} from '@/shared/api/generated/contracts'
import { compareEnvironmentVersions } from '@/features/environment/environment-config-codec'

type RuntimeConfiguration = NonNullable<EnvironmentRuntimeDTO['configuration']>
type RuntimeConfigurationError = NonNullable<RuntimeConfiguration['error']>

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

export function formatTimestamp(value: number | null | undefined): string {
  return value == null ? '' : formatBackendDate(value)
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

/**
 * 卡片配置状态只由卡片版本与当前 owner 运行快照派生，不保存客户端状态：
 * - 没有快照（离线或尚未建立 owner 会话）为待连接；
 * - 快照未上报 configuration 时为未知，不能伪装成已应用或直连；
 * - 当前版本失败优先于旧版本的已应用结果；
 * - appliedVersion 落后为待应用，超前于数据库版本是部署/重置异常，不宣称已应用。
 */
export type EnvironmentConfigState =
  | 'applied'
  | 'pendingApply'
  | 'pendingConnect'
  | 'failed'
  | 'unknown'

export type EnvironmentProxyView =
  | { known: false }
  | { known: true; url: string | null }

export interface EnvironmentConfigView {
  state: EnvironmentConfigState
  appliedVersion: string | null
  proxy: EnvironmentProxyView
  error: { code: string; field: string | null } | null
}

function unknownConfigView(): EnvironmentConfigView {
  return {
    state: 'unknown',
    appliedVersion: null,
    proxy: { known: false },
    error: null,
  }
}

function currentConfigError(
  error: RuntimeConfigurationError | null,
  cardVersion: string,
): { code: string; field: string | null } | null {
  if (error == null) return null
  if (compareEnvironmentVersions(error.version, cardVersion) !== 0) return null
  return { code: error.code, field: error.field ?? null }
}

export function presentEnvironmentConfig(
  environment: EnvironmentDTO,
  runtime: EnvironmentRuntimeStatusDTO | undefined,
): EnvironmentConfigView {
  // 没有有效连接租约时，即使 react-query 仍缓存着上一代快照，也只能显示待连接；
  // 旧快照不能冒充当前进程仍已应用，更不能把 proxy null 说成直连。
  if (environment.status === 'OFFLINE') {
    return { ...unknownConfigView(), state: 'pendingConnect' }
  }
  if (runtime == null) {
    return unknownConfigView()
  }
  const configuration: RuntimeConfiguration | null | undefined = runtime.status.configuration
  if (configuration == null) {
    return unknownConfigView()
  }

  const appliedVersion = configuration.appliedVersion ?? null
  const proxy: EnvironmentProxyView =
    appliedVersion == null
      ? { known: false }
      : { known: true, url: configuration.proxyUrl ?? null }
  const error = currentConfigError(configuration.error ?? null, environment.version)

  if (error != null) {
    return { state: 'failed', appliedVersion, proxy, error }
  }
  if (appliedVersion == null) {
    return { state: 'pendingApply', appliedVersion, proxy, error: null }
  }

  const order = compareEnvironmentVersions(appliedVersion, environment.version)
  if (order === 0) {
    return { state: 'applied', appliedVersion, proxy, error: null }
  }
  if (order === -1) {
    return { state: 'pendingApply', appliedVersion, proxy, error: null }
  }
  return { ...unknownConfigView(), appliedVersion, proxy }
}

/** 代理只在当前进程确有已应用快照时展示；否则保持未知，不能把卡片直连意图当作已生效。 */
export function environmentProxyText(view: EnvironmentConfigView, directLabel: string): string {
  if (!view.proxy.known) return '—'
  return view.proxy.url ?? directLabel
}
