import { ApiError, isNotFoundError } from '@/shared/api/client'
import type {
  EnvironmentIdeOpenViewDTO,
  EnvironmentIdeViewDTO,
} from '@/shared/api/generated/contracts'
import type { ApplicationEventIdeUseView } from '@/shared/app-events/protocol'
import { isCanonicalUuid } from '@/shared/lib/uuid'
import type {
  IdePresentation,
  IdeRecovery,
} from '@/features/environment/ide/ide-tab-state'

export type IdeActionMode = 'files' | 'shell'

export type IdeViewStatus =
  | 'pending'
  | 'registering'
  | 'claiming'
  | 'rebinding'
  | 'ready'
  | 'offline'
  | 'unknown'
  | 'gone'
  | 'in-use'
  | 'error'

export interface IdeViewState {
  environmentId: string
  viewId: string
  claimId: string
  mode: 'create' | 'resume'
  status: IdeViewStatus
  attempt: number
  claimInFlight: boolean
  entryUrl?: string
  daemonInstanceId?: string
  bindingEpoch?: string
  directory?: string
  workspacePath?: string
  iframeLoaded: boolean
  pendingShell: boolean
  errorMessage?: string
  closeError?: string
  terminalError?: string
  closeInFlight?: boolean
  /** 仅用于识别同一连接上的原 claim 对账；不持久化。 */
  claimRequest?: EnvironmentIdeOpenViewDTO
  claimRequestIdentity?: { eventNodeId: string; connectionId: string }
}

export const POSITIVE_DECIMAL = /^[1-9][0-9]*$/

export function ideErrorText(error: unknown): string {
  if (error instanceof ApiError) {
    const details = Object.values(error.errors ?? {}).filter(
      (value): value is string => typeof value === 'string',
    )
    return [error.code, error.message, ...details].filter(Boolean).join(' ')
  }
  if (error instanceof Error) {
    return error.message
  }
  return String(error)
}

export function ideErrorDetail(error: unknown): string {
  if (error instanceof ApiError) {
    const detail = error.errors?.detail
    if (typeof detail === 'string' && detail.length > 0) {
      return detail
    }
  }
  if (error instanceof Error) {
    return error.message
  }
  return ''
}

export function containsSensitivePath(value: string): boolean {
  return /\/(?:home|var|tmp|srv|Users)\/|[A-Za-z]:\\/.test(value)
}

export function safeIdeError(error: unknown): string {
  const detail = ideErrorDetail(error)
  if (detail.length === 0 || containsSensitivePath(detail)) {
    return ''
  }
  return detail
}

export function isViewGone(error: unknown): boolean {
  if (isNotFoundError(error)) {
    return true
  }
  return /VIEW_GONE|VIEW GONE|view is gone/i.test(ideErrorText(error))
}

export function isViewInUse(error: unknown): boolean {
  return /VIEW_IN_USE|VIEW IN USE|view in use|CLAIM_CONFLICT|claim conflict/i.test(
    ideErrorText(error),
  )
}

export function isDirtyClose(error: unknown): boolean {
  return /DIRTY|unsaved|save or discard/i.test(ideErrorText(error))
}

export function isUnknownIdeFailure(error: unknown): boolean {
  if (error instanceof ApiError) {
    if (error.status == null || error.status >= 500) {
      return true
    }
    return error.code === 'IDE_UNAVAILABLE'
  }
  // axios 拦截器之外抛出的网络错误没有 HTTP 状态，同样只能作为结果未知处理。
  return true
}

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
      claimId: '',
      mode: 'resume',
      status: 'pending',
      attempt: 0,
      claimInFlight: false,
      daemonInstanceId: recovery.daemonInstanceId,
      directory: recovery.directory,
      iframeLoaded: false,
      pendingShell: false,
    }
  }
  return views
}

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
      || view.directory == null
      || view.directory.length === 0
      || view.directory.length > 4096
    ) {
      continue
    }
    recovery[environmentId] = {
      viewId: view.viewId,
      daemonInstanceId: view.daemonInstanceId,
      directory: view.directory,
    }
  }
  return recovery
}

export function buildUsageViews(
  views: Record<string, IdeViewState>,
  openedEnvironmentIds: readonly string[],
): ApplicationEventIdeUseView[] {
  const result: ApplicationEventIdeUseView[] = []
  for (const environmentId of openedEnvironmentIds) {
    const view = views[environmentId]
    if (view == null || !isCanonicalUuid(view.claimId)) {
      continue
    }
    if (
      view.status !== 'ready'
      && view.status !== 'registering'
      && view.status !== 'claiming'
      && view.status !== 'rebinding'
      && view.status !== 'unknown'
    ) {
      continue
    }
    const entry: ApplicationEventIdeUseView = {
      environmentId,
      viewId: view.viewId,
      claimId: view.claimId,
    }
    if (view.status === 'ready' && view.bindingEpoch != null) {
      entry.bindingEpoch = view.bindingEpoch
    }
    result.push(entry)
  }
  return result
}

export interface BeginClaimSpec {
  mode: 'create' | 'resume'
  viewId: string
  claimId: string
  directory?: string | null
  pendingShell?: boolean
}

export type IdeClaimAction =
  | {
      type: 'BEGIN_CLAIM'
      environmentId: string
      spec: BeginClaimSpec
    }
  | {
      type: 'CLAIM_REQUEST_SUBMITTED'
      environmentId: string
      attempt: number
      request: EnvironmentIdeOpenViewDTO
    }
  | {
      type: 'CLAIM_SUCCEEDED'
      environmentId: string
      attempt: number
      claimId: string
      response: EnvironmentIdeViewDTO
      entryUrl: string
    }
  | {
      type: 'CLAIM_FAILED'
      environmentId: string
      attempt: number
      mode: 'create' | 'resume'
      error: unknown
    }
  | {
      type: 'CLAIM_VALIDATION_ERROR'
      environmentId: string
      attempt: number
      errorMessage: string
    }
  | {
      type: 'RESET_CLAIM_FLIGHT'
      environmentId: string
      attempt: number
    }
  | {
      type: 'SET_OFFLINE'
      environmentId: string
    }
  | {
      type: 'SET_PENDING_SHELL'
      environmentId: string
    }
  | {
      type: 'TERMINAL_SUBMITTED'
      environmentId: string
    }
  | {
      type: 'TERMINAL_FAILED'
      environmentId: string
      bindingEpoch: string
      terminalError: string
    }
  | {
      type: 'DIRECTORY_CHANGED'
      environmentId: string
      bindingEpoch: string
      directory: string
      workspacePath?: string
    }
  | {
      type: 'IFRAME_LOADED'
      environmentId: string
      entryUrl: string
    }
  | {
      type: 'CLOSE_SUBMITTED'
      environmentId: string
    }
  | {
      type: 'CLOSE_FAILED'
      environmentId: string
      dirty: boolean
      closeError?: string
    }
  | {
      type: 'DISMISS_CLOSE_ERROR'
      environmentId: string
    }
  | {
      type: 'REMOVE_VIEW'
      environmentId: string
    }
  | {
      type: 'PRUNE_VIEWS'
      availableEnvironmentIds: Set<string>
    }
  | {
      type: 'REPLACE_VIEW'
      environmentId: string
      view: IdeViewState
    }

/**
 * 纯 claim reducer：管理 views 映射中视图的状态（status）、尝试序号（attempt）、
 * 飞行动作（in-flight）、请求连接身份（claimRequestIdentity）及各生命周期跃迁。
 * 无副作用，不访问 storage、network 或 DOM。
 */
export function ideClaimReducer(
  state: Record<string, IdeViewState>,
  action: IdeClaimAction,
): Record<string, IdeViewState> {
  switch (action.type) {
    case 'BEGIN_CLAIM': {
      const current = state[action.environmentId]
      const attempt = (current?.attempt ?? 0) + 1
      const pendingShell = action.spec.pendingShell ?? current?.pendingShell ?? false
      const nextView: IdeViewState = {
        environmentId: action.environmentId,
        viewId: action.spec.viewId,
        claimId: action.spec.claimId,
        mode: action.spec.mode,
        status: action.spec.mode === 'resume' && current?.entryUrl != null ? 'rebinding' : 'registering',
        attempt,
        claimInFlight: true,
        entryUrl: action.spec.mode === 'create' ? undefined : current?.entryUrl,
        daemonInstanceId: action.spec.mode === 'create' ? undefined : current?.daemonInstanceId,
        directory: action.spec.mode === 'create'
          ? (action.spec.directory ?? current?.directory)
          : current?.directory,
        workspacePath: action.spec.mode === 'create' ? undefined : current?.workspacePath,
        iframeLoaded: action.spec.mode === 'create' ? false : (current?.iframeLoaded ?? false),
        pendingShell,
        terminalError: undefined,
      }
      return { ...state, [action.environmentId]: nextView }
    }

    case 'CLAIM_REQUEST_SUBMITTED': {
      const current = state[action.environmentId]
      if (current == null || current.attempt !== action.attempt) {
        return state
      }
      return {
        ...state,
        [action.environmentId]: {
          ...current,
          status: 'claiming',
          claimRequest: action.request,
          claimRequestIdentity: {
            eventNodeId: action.request.eventNodeId,
            connectionId: action.request.connectionId,
          },
        },
      }
    }

    case 'CLAIM_SUCCEEDED': {
      const current = state[action.environmentId]
      if (current == null || current.attempt !== action.attempt) {
        return state
      }
      const entryChanged = current.entryUrl !== action.entryUrl
      return {
        ...state,
        [action.environmentId]: {
          ...current,
          status: 'ready',
          claimInFlight: false,
          viewId: action.response.viewId,
          claimId: action.claimId,
          bindingEpoch: action.response.bindingEpoch,
          daemonInstanceId: action.response.daemonInstanceId,
          directory: action.response.directory,
          workspacePath: action.response.workspacePath,
          entryUrl: action.entryUrl,
          iframeLoaded: entryChanged ? false : current.iframeLoaded,
          errorMessage: undefined,
          closeError: undefined,
        },
      }
    }

    case 'CLAIM_FAILED': {
      const current = state[action.environmentId]
      if (current == null || current.attempt !== action.attempt) {
        return state
      }
      if (action.mode === 'resume' && isViewInUse(action.error)) {
        return {
          ...state,
          [action.environmentId]: {
            ...current,
            status: 'in-use',
            claimInFlight: false,
            errorMessage: undefined,
          },
        }
      }
      const gone = isViewGone(action.error)
      const unknown = !gone && isUnknownIdeFailure(action.error)
      return {
        ...state,
        [action.environmentId]: {
          ...current,
          status: gone ? 'gone' : unknown ? 'unknown' : 'error',
          claimInFlight: false,
          errorMessage: gone ? undefined : safeIdeError(action.error),
        },
      }
    }

    case 'CLAIM_VALIDATION_ERROR': {
      const current = state[action.environmentId]
      if (current == null || current.attempt !== action.attempt) {
        return state
      }
      return {
        ...state,
        [action.environmentId]: {
          ...current,
          status: 'error',
          claimInFlight: false,
          errorMessage: action.errorMessage,
        },
      }
    }

    case 'RESET_CLAIM_FLIGHT': {
      const current = state[action.environmentId]
      if (current == null || current.attempt !== action.attempt) {
        return state
      }
      return {
        ...state,
        [action.environmentId]: {
          ...current,
          status: 'pending',
          claimInFlight: false,
        },
      }
    }

    case 'SET_OFFLINE': {
      const current = state[action.environmentId]
      if (current == null || current.status === 'offline') {
        return state
      }
      if (current.status !== 'pending') {
        return state
      }
      return {
        ...state,
        [action.environmentId]: {
          ...current,
          status: 'offline',
        },
      }
    }

    case 'SET_PENDING_SHELL': {
      const current = state[action.environmentId]
      if (current == null) {
        return state
      }
      if (current.pendingShell && current.terminalError === undefined) {
        return state
      }
      return {
        ...state,
        [action.environmentId]: {
          ...current,
          pendingShell: true,
          terminalError: undefined,
        },
      }
    }

    case 'TERMINAL_SUBMITTED': {
      const current = state[action.environmentId]
      if (current == null) {
        return state
      }
      return {
        ...state,
        [action.environmentId]: {
          ...current,
          pendingShell: false,
          terminalError: undefined,
        },
      }
    }

    case 'TERMINAL_FAILED': {
      const current = state[action.environmentId]
      if (current == null || current.bindingEpoch !== action.bindingEpoch) {
        return state
      }
      return {
        ...state,
        [action.environmentId]: {
          ...current,
          terminalError: action.terminalError,
        },
      }
    }

    case 'DIRECTORY_CHANGED': {
      const current = state[action.environmentId]
      if (current == null || current.bindingEpoch !== action.bindingEpoch) {
        return state
      }
      return {
        ...state,
        [action.environmentId]: {
          ...current,
          directory: action.directory,
          workspacePath: action.workspacePath ?? current.workspacePath,
        },
      }
    }

    case 'IFRAME_LOADED': {
      const current = state[action.environmentId]
      if (current == null || current.entryUrl !== action.entryUrl || current.iframeLoaded) {
        return state
      }
      return {
        ...state,
        [action.environmentId]: {
          ...current,
          iframeLoaded: true,
        },
      }
    }

    case 'CLOSE_SUBMITTED': {
      const current = state[action.environmentId]
      if (current == null) {
        return state
      }
      return {
        ...state,
        [action.environmentId]: {
          ...current,
          closeInFlight: true,
          closeError: undefined,
        },
      }
    }

    case 'CLOSE_FAILED': {
      const current = state[action.environmentId]
      if (current == null) {
        return state
      }
      return {
        ...state,
        [action.environmentId]: {
          ...current,
          closeInFlight: false,
          closeError: action.dirty ? undefined : action.closeError,
        },
      }
    }

    case 'DISMISS_CLOSE_ERROR': {
      const current = state[action.environmentId]
      if (current == null || current.closeError == null) {
        return state
      }
      return {
        ...state,
        [action.environmentId]: {
          ...current,
          closeError: undefined,
        },
      }
    }

    case 'REMOVE_VIEW': {
      if (!(action.environmentId in state)) {
        return state
      }
      const next = { ...state }
      delete next[action.environmentId]
      return next
    }

    case 'PRUNE_VIEWS': {
      let changed = false
      const next = { ...state }
      for (const environmentId of Object.keys(state)) {
        if (!action.availableEnvironmentIds.has(environmentId)) {
          delete next[environmentId]
          changed = true
        }
      }
      return changed ? next : state
    }

    case 'REPLACE_VIEW': {
      return { ...state, [action.environmentId]: action.view }
    }

    default:
      return state
  }
}
