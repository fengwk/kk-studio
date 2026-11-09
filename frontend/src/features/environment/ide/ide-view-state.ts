import type { EnvironmentIdeOpenMode } from '@/shared/api/generated/contracts'
import type { ApplicationEventIdeUseView } from '@/shared/app-events/protocol'
import { isCanonicalUuid } from '@/shared/lib/uuid'
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

export const POSITIVE_DECIMAL = /^[1-9][0-9]*$/

/** 从 session 恢复的运行身份进入统一对账；不预置任何“已绑定”结论。 */
export function initialViews(
  presentation: IdePresentation,
  recoveryByEnvironment: Record<string, IdeRecovery>,
): Record<string, IdeViewState> {
  const views: Record<string, IdeViewState> = {}
  for (const environmentId of presentation.openedEnvironmentIds) {
    const recovery = recoveryByEnvironment[environmentId]
    if (recovery == null) {
      continue
    }
    views[environmentId] = {
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
  openedEnvironmentIds: readonly string[],
): Record<string, IdeRecovery> {
  const recovery: Record<string, IdeRecovery> = {}
  for (const environmentId of openedEnvironmentIds) {
    const view = views[environmentId]
    if (
      view == null
      || !isCanonicalUuid(view.viewId)
      || view.daemonInstanceId == null
      || !isCanonicalUuid(view.daemonInstanceId)
    ) {
      continue
    }
    const pending = view.pending
    recovery[environmentId] = {
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
  override?: {
    environmentId: string
    viewId: string
    claimId: string
    bindingEpoch?: string
  },
): ApplicationEventIdeUseView[] {
  const result: ApplicationEventIdeUseView[] = []
  for (const environmentId of openedEnvironmentIds) {
    const view = views[environmentId]
    if (view == null) {
      continue
    }
    const isOverride = override?.environmentId === environmentId
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
      && POSITIVE_DECIMAL.test(bindingEpoch)
      && (isOverride || view.status === 'ready')
    ) {
      entry.bindingEpoch = bindingEpoch
    }
    result.push(entry)
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
