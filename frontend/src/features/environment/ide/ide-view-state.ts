import type { EnvironmentIdeOpenMode } from '@/shared/api/generated/contracts'
import type { ApplicationEventIdeUseView } from '@/shared/app-events/protocol'
import { isCanonicalUuid } from '@/shared/lib/uuid'
import { CANONICAL_POSITIVE_DECIMAL } from '@/features/environment/ide/ide-decimal'
import type {
  IdePendingClaim,
  IdePresentation,
  IdeRecovery,
} from '@/features/environment/ide/ide-tab-state'

export type IdeViewStatus =
  | 'reconciling'
  | 'waiting-ready'
  | 'binding'
  | 'ready'
  | 'detached'
  | 'gone'
  | 'error'

export interface IdeViewState {
  environmentId: string
  viewId: string
  mode: EnvironmentIdeOpenMode
  status: IdeViewStatus
  claimId: string
  attempt: number
  /** waiting-ready 的阻塞来源：Daemon 未就绪时只能等新事件/显式重试，环境离线转就绪可再对账。 */
  waitingReason?: 'daemon' | 'environment'
  pending?: IdePendingClaim
  daemonInstanceId?: string
  bindingEpoch?: string
  eventNodeId?: string
  connectionId?: string
  directory?: string
  workspacePath?: string
  entryUrl?: string
  iframeLoaded: boolean
  pendingShell: boolean
  closeInFlight?: boolean
  closeError?: string
  terminalError?: string
  errorMessage?: string
}

/** 从 session 恢复的运行身份进入统一对账；不预置任何“已绑定”结论。 */
export function initialViews(
  presentation: IdePresentation,
  recoveryByView: Record<string, IdeRecovery>,
): Record<string, IdeViewState> {
  const views: Record<string, IdeViewState> = {}
  for (const [viewId, recovery] of Object.entries(recoveryByView)) {
    if (presentation.viewIdsByEnvironment[recovery.environmentId] != null
      && !presentation.viewIdsByEnvironment[recovery.environmentId]?.includes(viewId)) {
      continue
    }
    const environmentId = recovery.environmentId
    views[viewId] = {
      environmentId,
      viewId: recovery.viewId,
      mode: recovery.pending?.mode ?? 'resume',
      status: 'reconciling',
      claimId: recovery.pending?.claimId ?? '',
      attempt: 0,
      ...(recovery.pending == null ? {} : { pending: recovery.pending }),
      daemonInstanceId: recovery.daemonInstanceId,
      ...(recovery.bindingEpoch == null ? {} : { bindingEpoch: recovery.bindingEpoch }),
      ...(recovery.directory == null ? {} : { directory: recovery.directory }),
      iframeLoaded: false,
      pendingShell: false,
    }
  }
  return views
}

/** 只把可恢复身份写回 session；entryUrl、cookie 与终端状态永不持久化。 */
export function recoveryFromViews(
  views: Record<string, IdeViewState>,
): Record<string, IdeRecovery> {
  const recovery: Record<string, IdeRecovery> = {}
  for (const view of Object.values(views)) {
    if (
      view == null
      || !isCanonicalUuid(view.viewId)
      || view.daemonInstanceId == null
      || !isCanonicalUuid(view.daemonInstanceId)
    ) {
      continue
    }
    const pending = view.pending
    recovery[view.viewId] = {
      environmentId: view.environmentId,
      viewId: view.viewId,
      daemonInstanceId: view.daemonInstanceId,
      ...(view.bindingEpoch == null ? {} : { bindingEpoch: view.bindingEpoch }),
      ...(view.directory == null ? {} : { directory: view.directory }),
      ...(pending == null ? {} : { pending }),
    }
  }
  return recovery
}

/** 使用证明只声明当前 tab 仍持有的 view 意图；不据此推断 Daemon 绑定事实。 */
export function buildUsageViews(
  views: Record<string, IdeViewState>,
  openedEnvironmentIds: readonly string[],
  viewIdsByEnvironment?: Record<string, string[]>,
  override?: {
    environmentId: string
    viewId: string
    claimId: string
    bindingEpoch?: string
  },
): ApplicationEventIdeUseView[] {
  const result: ApplicationEventIdeUseView[] = []
  for (const environmentId of openedEnvironmentIds) {
    const viewIds = viewIdsByEnvironment?.[environmentId]
      ?? Object.keys(views).filter((key) => views[key]?.environmentId === environmentId)
    const completeViewIds = override?.environmentId === environmentId && !viewIds.includes(override.viewId)
      ? [...viewIds, override.viewId]
      : viewIds
    for (const viewId of completeViewIds.length > 0 ? completeViewIds : [environmentId]) {
      const view = views[viewId] ?? views[environmentId]
      if (view == null) {
        continue
      }
      const isOverride = override?.environmentId === environmentId && override.viewId === viewId
    const claimId = isOverride ? override.claimId : view.pending?.claimId ?? view.claimId
    if (
      !isOverride
      && view.status !== 'reconciling'
      && view.status !== 'waiting-ready'
      && view.status !== 'binding'
      && view.status !== 'ready'
    ) {
      continue
    }
    if (!isCanonicalUuid(claimId) || !isCanonicalUuid(view.viewId)) {
      continue
    }
    const entry: ApplicationEventIdeUseView = {
      environmentId,
      viewId: view.viewId,
      claimId,
    }
    const bindingEpoch = isOverride ? override.bindingEpoch : view.bindingEpoch
    if (
      bindingEpoch != null
      && CANONICAL_POSITIVE_DECIMAL.test(bindingEpoch)
      && (isOverride || view.status === 'ready')
    ) {
      entry.bindingEpoch = bindingEpoch
    }
      result.push(entry)
    }
  }
  return result
}

/** 会话互斥身份与当前连接绑定一致时，iframe 可以继续使用，不重新 bootstrap。 */
export function isBoundToConnection(
  view: IdeViewState | undefined,
  eventNodeId: string,
  connectionId: string,
): boolean {
  return view != null
    && view.status === 'ready'
    && view.entryUrl != null
    && view.eventNodeId === eventNodeId
    && view.connectionId === connectionId
}

/** 视图仍是同一个绑定（同 view、同实例、同 bindingEpoch）时，迟到的命令回执才有意义。 */
export function isSameBinding(
  current: IdeViewState | undefined,
  expected: Pick<IdeViewState, 'viewId' | 'daemonInstanceId' | 'bindingEpoch'>,
): boolean {
  return current != null
    && current.viewId === expected.viewId
    && current.daemonInstanceId === expected.daemonInstanceId
    && current.bindingEpoch === expected.bindingEpoch
}

/**
 * 只保留仍然存在的 environment 的视图。
 *
 * <p>返回 `null` 表示没有需要丢弃的视图，调用方据此跳过无意义的写入。
 */
export function retainAvailableViews(
  views: Record<string, IdeViewState>,
  available: Set<string>,
): Record<string, IdeViewState> | null {
  const next: Record<string, IdeViewState> = {}
  for (const [viewId, view] of Object.entries(views)) {
    if (available.has(view.environmentId)) {
      next[viewId] = view
    }
  }
  if (Object.keys(next).length === Object.keys(views).length) {
    return null
  }
  return next
}
