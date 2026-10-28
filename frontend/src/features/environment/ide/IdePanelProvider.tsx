import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type PropsWithChildren,
} from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useApplicationEvents } from '@/shared/app-events'
import type {
  ApplicationEventConnectionIdentity,
  ApplicationEventIdeUseView,
} from '@/shared/app-events/protocol'
import { useResourceSync } from '@/shared/app-events/useResourceSync'
import { ApiError, isNotFoundError } from '@/shared/api/client'
import { environmentService } from '@/shared/api/environment-service'
import type {
  EnvironmentCardDTO,
  EnvironmentIdeOpenViewDTO,
  EnvironmentIdeViewDTO,
} from '@/shared/api/generated/contracts'
import { queryKeys } from '@/shared/lib/query-keys'
import { createUuid, isCanonicalUuid } from '@/shared/lib/uuid'
import {
  IdePanelContext,
  type IdeEnvironmentInfo,
  type IdeOpenEnvironmentRequest,
} from '@/features/environment/ide/ide-panel-context'
import { validateIdeEntryUrl } from '@/features/environment/ide/ide-url'
import {
  initializeIdeTabState,
  saveIdePresentation,
  saveIdeSession,
  type IdePresentation,
  type IdeRecovery,
  type IdeTabStorage,
} from '@/features/environment/ide/ide-tab-state'
import { IdeOverlayPanel, type IdeCloseResult } from '@/features/environment/ide/IdeOverlayPanel'

type IdeActionMode = 'files' | 'shell'

type IdeViewStatus =
  | 'pending'
  | 'registering'
  | 'claiming'
  | 'rebinding'
  | 'ready'
  | 'offline'
  | 'unknown'
  | 'gone'
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

export interface IdePanelStorageProps {
  storage?: IdeTabStorage
}

const POSITIVE_DECIMAL = /^[1-9][0-9]*$/

function toEnvironmentInfo(card: EnvironmentCardDTO): IdeEnvironmentInfo {
  return { id: card.id, name: card.name, ready: card.ready }
}

function initialViews(
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

function recoveryFromViews(
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

function ideErrorText(error: unknown): string {
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

function ideErrorDetail(error: unknown): string {
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

function containsSensitivePath(value: string): boolean {
  return /\/(?:home|var|tmp|srv|Users)\/|[A-Za-z]:\\/.test(value)
}

function safeIdeError(error: unknown): string {
  const detail = ideErrorDetail(error)
  if (detail.length === 0 || containsSensitivePath(detail)) {
    return ''
  }
  return detail
}

function isViewGone(error: unknown): boolean {
  if (isNotFoundError(error)) {
    return true
  }
  return /VIEW_GONE|VIEW GONE|view is gone/i.test(ideErrorText(error))
}

function isViewInUse(error: unknown): boolean {
  return /VIEW_IN_USE|VIEW IN USE|view in use|CLAIM_CONFLICT|claim conflict/i.test(
    ideErrorText(error),
  )
}

function isDirtyClose(error: unknown): boolean {
  return /DIRTY|unsaved|save or discard/i.test(ideErrorText(error))
}

function isUnknownIdeFailure(error: unknown): boolean {
  if (error instanceof ApiError) {
    if (error.status == null || error.status >= 500) {
      return true
    }
    return error.code === 'IDE_UNAVAILABLE'
  }
  // axios 拦截器之外抛出的网络错误没有 HTTP 状态，同样只能作为结果未知处理。
  return true
}

/** tab 全局 IDE：内存是唯一事实源，session 保存恢复身份，local 只保存最后展示意图。 */
export function IdePanelProvider({
  children,
  storage,
}: PropsWithChildren<IdePanelStorageProps>) {
  const events = useApplicationEvents()
  const queryClient = useQueryClient()
  const [initial] = useState(() => initializeIdeTabState(storage))
  const [presentation, setPresentationState] = useState<IdePresentation>(initial.presentation)
  const [views, setViewsState] = useState<Record<string, IdeViewState>>(
    () => initialViews(initial.presentation, initial.recoveryByEnvironment),
  )
  const [identity, setIdentity] = useState<ApplicationEventConnectionIdentity | null>(
    () => events.getIdentity(),
  )
  const [knownEnvironments, setKnownEnvironments] = useState<Record<string, IdeEnvironmentInfo>>({})
  const [storageUnavailable, setStorageUnavailable] = useState(!initial.sessionSaved)

  const presentationRef = useRef(presentation)
  const viewsRef = useRef(views)
  const identityRef = useRef(identity)
  const mountedRef = useRef(false)
  const pageSuspendedRef = useRef(false)
  const documentIdRef = useRef(createUuid())
  const revisionRef = useRef(0n)
  const pendingModeRef = useRef<IdeActionMode | null>(null)
  const pendingShellEnvironmentsRef = useRef(new Set<string>())
  const actionsRef = useRef<{
    openEnvironment: (request: IdeOpenEnvironmentRequest) => void
    reconcileIdentity: (identity: ApplicationEventConnectionIdentity) => void
    reconcileEnvironments: () => void
    withdrawUsage: () => void
    restoreUsage: () => void
  } | null>(null)

  identityRef.current = identity

  const environmentsQuery = useQuery({
    queryKey: queryKeys.environments.list,
    queryFn: () => environmentService.listEnvironments(),
    enabled: presentation.panelVisible || presentation.openedEnvironmentIds.length > 0,
  })

  const invalidateEnvironments = useCallback(() => {
    void queryClient.invalidateQueries({ queryKey: queryKeys.environments.all })
  }, [queryClient])

  useResourceSync({ kind: 'environments' }, {
    sync: invalidateEnvironments,
    syncOn: (name) => name === 'changed',
  })

  const environments = useMemo(() => {
    const map = new Map<string, IdeEnvironmentInfo>()
    for (const card of environmentsQuery.data ?? []) {
      map.set(card.id, toEnvironmentInfo(card))
    }
    for (const environment of Object.values(knownEnvironments)) {
      if (!map.has(environment.id)) {
        map.set(environment.id, environment)
      }
    }
    return [...map.values()].sort((left, right) => left.name.localeCompare(right.name))
  }, [environmentsQuery.data, knownEnvironments])

  const environmentMap = useMemo(() => {
    return new Map(environments.map((environment) => [environment.id, environment]))
  }, [environments])
  const environmentMapRef = useRef(environmentMap)
  environmentMapRef.current = environmentMap

  useEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
    }
  }, [])

  useEffect(() => events.subscribeIdentity(setIdentity), [events])

  function rememberEnvironment(environment: IdeEnvironmentInfo) {
    setKnownEnvironments((current) => {
      const existing = current[environment.id]
      if (
        existing != null
        && existing.name === environment.name
        && existing.ready === environment.ready
      ) {
        return current
      }
      return { ...current, [environment.id]: environment }
    })
  }

  function replaceView(environmentId: string, next: IdeViewState): IdeViewState {
    viewsRef.current = { ...viewsRef.current, [environmentId]: next }
    if (mountedRef.current) {
      setViewsState(viewsRef.current)
    }
    return next
  }

  function updateView(
    environmentId: string,
    update: (current: IdeViewState) => IdeViewState,
  ): IdeViewState | null {
    const current = viewsRef.current[environmentId]
    if (current == null) {
      return null
    }
    const next = update(current)
    return next === current ? current : replaceView(environmentId, next)
  }

  function removeView(environmentId: string): void {
    if (!(environmentId in viewsRef.current)) {
      return
    }
    const next = { ...viewsRef.current }
    delete next[environmentId]
    viewsRef.current = next
    if (mountedRef.current) {
      setViewsState(next)
    }
  }

  function writeSession(): void {
    const saved = saveIdeSession(
      presentationRef.current,
      recoveryFromViews(viewsRef.current, presentationRef.current.openedEnvironmentIds),
      storage,
    )
    if (mountedRef.current) {
      setStorageUnavailable(!saved)
    }
  }

  function commitPresentation(next: IdePresentation, write: 'both' | 'session' | 'none' = 'both') {
    presentationRef.current = next
    if (mountedRef.current) {
      setPresentationState(next)
    }
    if (write === 'both') {
      const saved = saveIdePresentation(
        next,
        recoveryFromViews(viewsRef.current, next.openedEnvironmentIds),
        storage,
      )
      if (mountedRef.current) {
        setStorageUnavailable(!saved.sessionSaved)
      }
    } else if (write === 'session') {
      writeSession()
    }
  }

  function nextRevision(): string {
    revisionRef.current += 1n
    return revisionRef.current.toString()
  }

  function buildUsageViews(): ApplicationEventIdeUseView[] {
    const result: ApplicationEventIdeUseView[] = []
    for (const environmentId of presentationRef.current.openedEnvironmentIds) {
      const view = viewsRef.current[environmentId]
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

  function registerUsageNow(): Promise<unknown> {
    const currentIdentity = identityRef.current
    if (currentIdentity == null || pageSuspendedRef.current) {
      return Promise.reject(new Error('application event connection is not ready'))
    }
    return events.registerUsage({
      documentId: documentIdRef.current,
      revision: nextRevision(),
      views: buildUsageViews(),
    })
  }

  function registerUsageQuiet(): void {
    void registerUsageNow().catch(() => {
      // 断线由 reject 明示；恢复只由后续 WS connected 身份重新发起，不在这里重试。
    })
  }

  function setPendingShell(environmentId: string): void {
    const view = viewsRef.current[environmentId]
    if (view == null) {
      pendingShellEnvironmentsRef.current.add(environmentId)
      return
    }
    updateView(environmentId, (current) => (
      current.pendingShell ? current : { ...current, pendingShell: true, terminalError: undefined }
    ))
  }

  async function runTerminal(environmentId: string): Promise<void> {
    const view = viewsRef.current[environmentId]
    if (
      view == null
      || view.status !== 'ready'
      || view.bindingEpoch == null
      || view.claimInFlight
    ) {
      return
    }
    updateView(environmentId, (current) => ({
      ...current,
      pendingShell: false,
      terminalError: undefined,
    }))
    try {
      await environmentService.openIdeViewTerminal(environmentId, view.viewId, {
        documentId: documentIdRef.current,
        bindingEpoch: view.bindingEpoch,
      })
    } catch (error) {
      updateView(environmentId, (current) => {
        if (current.bindingEpoch !== view.bindingEpoch) {
          return current
        }
        return {
          ...current,
          terminalError: isUnknownIdeFailure(error)
            ? 'terminal-result-unknown'
            : safeIdeError(error) || 'terminal-failed',
        }
      })
    }
  }

  function maybeRunPendingShell(environmentId: string): void {
    const view = viewsRef.current[environmentId]
    if (
      view == null
      || !view.pendingShell
      || !view.iframeLoaded
      || view.status !== 'ready'
      || view.claimInFlight
    ) {
      return
    }
    void runTerminal(environmentId)
  }

  function beginClaim(
    environmentId: string,
    spec: {
      mode: 'create' | 'resume'
      viewId: string
      claimId: string
      directory?: string | null
      fixedRequest?: EnvironmentIdeOpenViewDTO
    },
  ): void {
    const current = viewsRef.current[environmentId]
    const attempt = (current?.attempt ?? 0) + 1
    const pendingShell = current?.pendingShell
      || pendingShellEnvironmentsRef.current.has(environmentId)
    pendingShellEnvironmentsRef.current.delete(environmentId)
    replaceView(environmentId, {
      environmentId,
      viewId: spec.viewId,
      claimId: spec.claimId,
      mode: spec.mode,
      status: spec.mode === 'resume' && current?.entryUrl != null ? 'rebinding' : 'registering',
      attempt,
      claimInFlight: true,
      entryUrl: spec.mode === 'create' ? undefined : current?.entryUrl,
      daemonInstanceId: spec.mode === 'create' ? undefined : current?.daemonInstanceId,
      directory: spec.mode === 'create'
        ? (spec.directory ?? current?.directory)
        : current?.directory,
      workspacePath: spec.mode === 'create' ? undefined : current?.workspacePath,
      iframeLoaded: spec.mode === 'create' ? false : (current?.iframeLoaded ?? false),
      pendingShell,
      terminalError: undefined,
    })
    void runClaim(environmentId, attempt, spec.fixedRequest)
  }

  function startCreate(environmentId: string, directory?: string | null): void {
    beginClaim(environmentId, {
      mode: 'create',
      viewId: createUuid(),
      claimId: createUuid(),
      directory,
    })
  }

  function startResume(
    environmentId: string,
    view: IdeViewState,
    claimId = createUuid(),
    mode: 'create' | 'resume' = 'resume',
  ): void {
    beginClaim(environmentId, {
      mode,
      viewId: view.viewId,
      claimId,
      directory: view.directory,
    })
  }

  function failClaim(
    environmentId: string,
    attempt: number,
    error: unknown,
    mode: 'create' | 'resume',
  ): void {
    const current = viewsRef.current[environmentId]
    if (current == null || current.attempt !== attempt) {
      return
    }
    if (mode === 'resume' && isViewInUse(error)) {
      // 新 tab 持有复制来的 session viewId 时不能抢占原 document；同一环境改用新 view。
      startCreate(environmentId, current.directory)
      return
    }
    const gone = isViewGone(error)
    const unknown = !gone && isUnknownIdeFailure(error)
    updateView(environmentId, (view) => ({
      ...view,
      status: gone ? 'gone' : unknown ? 'unknown' : 'error',
      claimInFlight: false,
      errorMessage: gone ? undefined : safeIdeError(error),
    }))
    if (gone || !unknown) {
      registerUsageQuiet()
    }
  }

  function settleClaimSuccess(
    environmentId: string,
    attempt: number,
    request: EnvironmentIdeOpenViewDTO,
    response: EnvironmentIdeViewDTO,
  ): void {
    const current = viewsRef.current[environmentId]
    if (current == null || current.attempt !== attempt) {
      return
    }
    let entryUrl: string
    try {
      if (
        response.viewId !== request.viewId
        || response.environmentId !== environmentId
        || response.claimId !== request.claimId
        || !isCanonicalUuid(response.daemonInstanceId)
        || !POSITIVE_DECIMAL.test(response.bindingEpoch)
        || typeof response.directory !== 'string'
        || response.directory.length === 0
      ) {
        throw new Error('Invalid IDE binding response')
      }
      entryUrl = validateIdeEntryUrl(response.entryUrl, environmentId, response.viewId)
    } catch (error) {
      updateView(environmentId, (view) => ({
        ...view,
        status: 'error',
        claimInFlight: false,
        errorMessage: safeIdeError(error),
      }))
      // 已建立但入口不合法的视图立刻尝试释放，避免留下无法访问的资源。
      if (
        isCanonicalUuid(response?.viewId)
        && POSITIVE_DECIMAL.test(response?.bindingEpoch ?? '')
      ) {
        void environmentService.closeIdeView(environmentId, response.viewId, {
          documentId: documentIdRef.current,
          bindingEpoch: response.bindingEpoch,
          discard: true,
        }).catch(() => {
          // 清理失败留给 Daemon 的 10 分钟回收，不在 UI 中伪造成功。
        })
      }
      return
    }

    const entryChanged = current.entryUrl !== entryUrl
    const next = replaceView(environmentId, {
      ...current,
      status: 'ready',
      claimInFlight: false,
      viewId: response.viewId,
      claimId: request.claimId,
      bindingEpoch: response.bindingEpoch,
      daemonInstanceId: response.daemonInstanceId,
      directory: response.directory,
      workspacePath: response.workspacePath,
      entryUrl,
      iframeLoaded: entryChanged ? false : current.iframeLoaded,
      errorMessage: undefined,
      closeError: undefined,
    })
    writeSession()
    const currentIdentity = identityRef.current
    if (
      currentIdentity != null
      && request.eventNodeId === currentIdentity.eventNodeId
      && request.connectionId === currentIdentity.connectionId
    ) {
      registerUsageQuiet()
    } else if (currentIdentity != null) {
      // 迟到成功来自旧连接：保留 viewId 立即按 resume 重新绑定到当前连接。
      startResume(environmentId, next)
    }
    maybeRunPendingShell(environmentId)
  }

  async function runClaim(
    environmentId: string,
    attempt: number,
    fixedRequest?: EnvironmentIdeOpenViewDTO,
  ): Promise<void> {
    const view = viewsRef.current[environmentId]
    if (view == null || view.attempt !== attempt) {
      return
    }
    const registrationIdentity = identityRef.current
    if (registrationIdentity == null) {
      updateView(environmentId, (current) => (
        current.attempt === attempt
          ? { ...current, status: 'pending', claimInFlight: false }
          : current
      ))
      return
    }
    try {
      await registerUsageNow()
    } catch (error) {
      const current = viewsRef.current[environmentId]
      if (current == null || current.attempt !== attempt) {
        return
      }
      if (identityRef.current?.connectionId !== registrationIdentity.connectionId) {
        updateView(environmentId, (item) => (
          item.attempt === attempt
            ? { ...item, status: 'pending', claimInFlight: false }
            : item
        ))
        const reconnectIdentity = identityRef.current
        if (reconnectIdentity != null) {
          actionsRef.current?.reconcileIdentity(reconnectIdentity)
        }
        return
      }
      failClaim(environmentId, attempt, error, view.mode)
      return
    }
    if (!mountedRef.current) {
      return
    }
    const current = viewsRef.current[environmentId]
    if (current == null || current.attempt !== attempt) {
      return
    }
    const currentIdentity = identityRef.current
    if (
      currentIdentity == null
      || currentIdentity.connectionId !== registrationIdentity.connectionId
    ) {
      updateView(environmentId, (item) => (
        item.attempt === attempt
          ? { ...item, status: 'pending', claimInFlight: false }
          : item
      ))
      const reconnectIdentity = identityRef.current
      if (reconnectIdentity != null) {
        actionsRef.current?.reconcileIdentity(reconnectIdentity)
      }
      return
    }

    const request: EnvironmentIdeOpenViewDTO = fixedRequest ?? {
      viewId: current.viewId,
      documentId: documentIdRef.current,
      eventNodeId: currentIdentity.eventNodeId,
      connectionId: currentIdentity.connectionId,
      claimId: current.claimId,
      mode: current.mode,
      ...(current.mode === 'create' && current.directory != null
        ? { directory: current.directory }
        : {}),
    }
    updateView(environmentId, (item) => ({
      ...item,
      status: 'claiming',
      claimRequest: request,
      claimRequestIdentity: {
        eventNodeId: request.eventNodeId,
        connectionId: request.connectionId,
      },
    }))
    try {
      const response = await environmentService.openIdeView(environmentId, request)
      if (!mountedRef.current) {
        return
      }
      settleClaimSuccess(environmentId, attempt, request, response)
    } catch (error) {
      if (!mountedRef.current) {
        return
      }
      failClaim(environmentId, attempt, error, current.mode)
    }
  }

  function retryClaim(environmentId: string): void {
    const view = viewsRef.current[environmentId]
    if (view == null || view.claimInFlight) {
      return
    }
    if (
      view.claimRequest != null
      && view.claimRequestIdentity != null
      && identityRef.current?.connectionId === view.claimRequestIdentity.connectionId
      && identityRef.current?.eventNodeId === view.claimRequestIdentity.eventNodeId
    ) {
      beginClaim(environmentId, {
        mode: view.mode,
        viewId: view.viewId,
        claimId: view.claimId,
        directory: view.directory,
        fixedRequest: view.claimRequest,
      })
      return
    }
    // 连接已变化时不能改写原 claim 的 eventNode/connection 形状；保留 viewId 重新 resume。
    startResume(environmentId, view)
  }

  function openNewView(environmentId: string): void {
    const view = viewsRef.current[environmentId]
    startCreate(environmentId, view?.directory)
  }

  function startOrFocus(
    environmentId: string,
    environment: IdeEnvironmentInfo | null,
    mode: IdeActionMode,
  ): void {
    if (mode === 'shell') {
      setPendingShell(environmentId)
    }
    const view = viewsRef.current[environmentId]
    if (view != null) {
      if (view.status === 'offline') {
        const ready = environment?.ready ?? environmentMapRef.current.get(environmentId)?.ready
        if (ready === true) {
          startResume(environmentId, view)
        }
      }
      // gone/unknown/error 只聚焦面板并保留恢复线索；替代 view 必须由用户明确打开。
      maybeRunPendingShell(environmentId)
      return
    }
    const ready = environment?.ready ?? environmentMapRef.current.get(environmentId)?.ready
    if (ready === true) {
      startCreate(environmentId)
    }
  }

  function applyOpenRequest(
    request: IdeOpenEnvironmentRequest,
    environment: IdeEnvironmentInfo | null,
  ): void {
    const environmentId = environment?.id ?? null
    if (environment != null) {
      rememberEnvironment(environment)
    }
    const current = presentationRef.current
    const openedEnvironmentIds = environmentId == null
      ? current.openedEnvironmentIds
      : current.openedEnvironmentIds.includes(environmentId)
        ? current.openedEnvironmentIds
        : [...current.openedEnvironmentIds, environmentId]
    const changed = (
      current.panelVisible !== true
      || current.selectedEnvironmentId !== environmentId
      || openedEnvironmentIds !== current.openedEnvironmentIds
    )
    if (changed) {
      commitPresentation({
        panelVisible: true,
        selectedEnvironmentId: environmentId,
        openedEnvironmentIds,
      }, 'both')
    }
    if (environmentId == null) {
      pendingModeRef.current = request.mode ?? null
      return
    }
    pendingModeRef.current = null
    startOrFocus(environmentId, environment, request.mode ?? 'files')
  }

  function openEnvironment(request: IdeOpenEnvironmentRequest): void {
    const environment = request.environment != null && isCanonicalUuid(request.environment.id)
      ? request.environment
      : null
    applyOpenRequest(request, environment)
  }

  function selectEnvironment(environmentId: string): void {
    const environment = environmentMapRef.current.get(environmentId) ?? null
    const mode = pendingModeRef.current ?? 'files'
    pendingModeRef.current = null
    applyOpenRequest({ environment, mode }, environment)
  }

  function collapsePanel(): void {
    const current = presentationRef.current
    if (!current.panelVisible) {
      return
    }
    commitPresentation({ ...current, panelVisible: false }, 'both')
  }

  async function closeEnvironment(environmentId: string, discard: boolean): Promise<IdeCloseResult> {
    const view = viewsRef.current[environmentId]
    if (view == null) {
      return { ok: true, dirty: false }
    }
    if (
      view.bindingEpoch == null
      || view.claimInFlight
      || view.closeInFlight
      || view.status !== 'ready'
    ) {
      return { ok: false, dirty: false, message: 'view-not-ready' }
    }
    updateView(environmentId, (current) => ({
      ...current,
      closeInFlight: true,
      closeError: undefined,
    }))
    try {
      await environmentService.closeIdeView(environmentId, view.viewId, {
        documentId: documentIdRef.current,
        bindingEpoch: view.bindingEpoch,
        discard,
      })
      removeView(environmentId)
      const current = presentationRef.current
      const openedEnvironmentIds = current.openedEnvironmentIds.filter(
        (id) => id !== environmentId,
      )
      const selectedEnvironmentId = current.selectedEnvironmentId === environmentId
        ? openedEnvironmentIds[0] ?? null
        : current.selectedEnvironmentId
      commitPresentation({
        panelVisible: current.panelVisible && openedEnvironmentIds.length > 0,
        selectedEnvironmentId,
        openedEnvironmentIds,
      }, 'both')
      registerUsageQuiet()
      return { ok: true, dirty: false }
    } catch (error) {
      const dirty = isDirtyClose(error)
      updateView(environmentId, (current) => ({
        ...current,
        closeInFlight: false,
        closeError: dirty
          ? undefined
          : isUnknownIdeFailure(error)
            ? 'close-result-unknown'
            : safeIdeError(error) || 'close-failed',
      }))
      return { ok: false, dirty, message: safeIdeError(error) }
    }
  }

  function dismissCloseError(environmentId: string): void {
    updateView(environmentId, (current) => (
      current.closeError == null ? current : { ...current, closeError: undefined }
    ))
  }

  async function changeDirectory(
    environmentId: string,
    directory: string,
  ): Promise<{ ok: boolean; message?: string }> {
    const view = viewsRef.current[environmentId]
    if (
      view == null
      || view.status !== 'ready'
      || view.bindingEpoch == null
      || view.claimInFlight
    ) {
      return { ok: false, message: 'view-not-ready' }
    }
    const epoch = view.bindingEpoch
    try {
      const response = await environmentService.changeIdeViewDirectory(environmentId, view.viewId, {
        documentId: documentIdRef.current,
        bindingEpoch: epoch,
        directory,
      })
      const current = viewsRef.current[environmentId]
      if (
        current != null
        && current.bindingEpoch === epoch
        && current.viewId === view.viewId
      ) {
        updateView(environmentId, (item) => ({
          ...item,
          directory: response.directory,
          workspacePath: response.workspacePath ?? item.workspacePath,
        }))
        writeSession()
      }
      return { ok: true }
    } catch (error) {
      const current = viewsRef.current[environmentId]
      if (current != null && current.bindingEpoch === epoch) {
        return {
          ok: false,
          message: isUnknownIdeFailure(error)
            ? 'directory-result-unknown'
            : safeIdeError(error) || 'directory-failed',
        }
      }
      return { ok: false, message: 'binding-changed' }
    }
  }

  function handleIframeLoaded(environmentId: string, entryUrl: string): void {
    const view = updateView(environmentId, (current) => (
      current.entryUrl === entryUrl && !current.iframeLoaded
        ? { ...current, iframeLoaded: true }
        : current
    ))
    if (view?.entryUrl === entryUrl) {
      maybeRunPendingShell(environmentId)
    }
  }

  function reconcileIdentity(nextIdentity: ApplicationEventConnectionIdentity): void {
    const currentPresentation = presentationRef.current
    for (const environmentId of currentPresentation.openedEnvironmentIds) {
      const view = viewsRef.current[environmentId]
      if (view == null) {
        continue
      }
      if (view.claimInFlight) {
        continue
      }
      const environmentReady = environmentMapRef.current.get(environmentId)?.ready
      if (environmentReady === false && (view.status === 'pending' || view.status === 'offline')) {
        updateView(environmentId, (current) => (
          current.status === 'offline' ? current : { ...current, status: 'offline' }
        ))
        continue
      }
      if (view.status === 'pending' || view.status === 'offline') {
        startResume(environmentId, view)
      } else if (view.status === 'unknown') {
        if (view.claimRequestIdentity?.connectionId !== nextIdentity.connectionId) {
          startResume(environmentId, view)
        }
      } else if (view.status === 'ready' || view.status === 'rebinding') {
        startResume(environmentId, view)
      }
    }
    if (!currentPresentation.panelVisible || currentPresentation.selectedEnvironmentId == null) {
      return
    }
    const selected = currentPresentation.selectedEnvironmentId
    if (viewsRef.current[selected] != null) {
      return
    }
    if (environmentMapRef.current.get(selected)?.ready === true) {
      startCreate(selected)
    }
  }

  function reconcileEnvironments(): void {
    if (!environmentsQuery.isSuccess) {
      return
    }
    const available = new Set((environmentsQuery.data ?? []).map((card) => card.id))
    const current = presentationRef.current
    const openedEnvironmentIds = current.openedEnvironmentIds.filter(
      (environmentId) => available.has(environmentId),
    )
    const removed = openedEnvironmentIds.length !== current.openedEnvironmentIds.length
    if (removed) {
      for (const environmentId of current.openedEnvironmentIds) {
        if (!available.has(environmentId)) {
          removeView(environmentId)
        }
      }
      const selectedEnvironmentId = current.selectedEnvironmentId != null
        && available.has(current.selectedEnvironmentId)
        ? current.selectedEnvironmentId
        : openedEnvironmentIds[0] ?? null
      commitPresentation({
        panelVisible: current.panelVisible && openedEnvironmentIds.length > 0,
        selectedEnvironmentId,
        openedEnvironmentIds,
      }, 'session')
    }
    setKnownEnvironments((current) => {
      const filtered: Record<string, IdeEnvironmentInfo> = {}
      let changed = false
      for (const [environmentId, environment] of Object.entries(current)) {
        if (available.has(environmentId)) {
          filtered[environmentId] = environment
        } else {
          changed = true
        }
      }
      return changed ? filtered : current
    })
    if (identityRef.current == null) {
      return
    }
    for (const environmentId of openedEnvironmentIds) {
      const view = viewsRef.current[environmentId]
      if (
        view != null
        && !view.claimInFlight
        && (view.status === 'pending' || view.status === 'offline')
        && environmentMapRef.current.get(environmentId)?.ready === true
      ) {
        startResume(environmentId, view)
      }
    }
    const selected = presentationRef.current.selectedEnvironmentId
    if (
      presentationRef.current.panelVisible
      && selected != null
      && viewsRef.current[selected] == null
      && environmentMapRef.current.get(selected)?.ready === true
    ) {
      startCreate(selected)
    }
  }

  function withdrawUsage(): void {
    if (identityRef.current == null) {
      return
    }
    void events.registerUsage({
      documentId: documentIdRef.current,
      revision: nextRevision(),
      views: [],
    }).catch(() => {
      // pagehide/断线后发送失败是预期路径；Daemon 按最后有效证明回收。
    })
  }

  function restoreUsage(): void {
    pageSuspendedRef.current = false
    if (identityRef.current != null) {
      registerUsageQuiet()
    }
  }

  actionsRef.current = {
    openEnvironment,
    reconcileIdentity,
    reconcileEnvironments,
    withdrawUsage,
    restoreUsage,
  }

  useEffect(() => {
    if (identity == null) {
      return
    }
    actionsRef.current?.reconcileIdentity(identity)
  }, [identity])

  useEffect(() => {
    if (!environmentsQuery.isSuccess) {
      return
    }
    actionsRef.current?.reconcileEnvironments()
  }, [environmentsQuery.data, environmentsQuery.isSuccess])

  useEffect(() => {
    const handlePageHide = (event: PageTransitionEvent) => {
      pageSuspendedRef.current = true
      actionsRef.current?.withdrawUsage()
      if (!event.persisted) {
        pageSuspendedRef.current = true
      }
    }
    const handlePageShow = (event: PageTransitionEvent) => {
      if (event.persisted) {
        actionsRef.current?.restoreUsage()
      }
    }
    window.addEventListener('pagehide', handlePageHide)
    window.addEventListener('pageshow', handlePageShow)
    return () => {
      window.removeEventListener('pagehide', handlePageHide)
      window.removeEventListener('pageshow', handlePageShow)
    }
  }, [])

  const openEnvironmentStable = useCallback((request: IdeOpenEnvironmentRequest) => {
    actionsRef.current?.openEnvironment(request)
  }, [])

  const contextValue = useMemo(
    () => ({ openEnvironment: openEnvironmentStable }),
    [openEnvironmentStable],
  )

  return (
    <IdePanelContext.Provider value={contextValue}>
      {children}
      <IdeOverlayPanel
        isOpen={presentation.panelVisible}
        selectedEnvironmentId={presentation.selectedEnvironmentId}
        openedEnvironmentIds={presentation.openedEnvironmentIds}
        views={views}
        environments={environments}
        environmentsLoading={environmentsQuery.isLoading}
        environmentsError={environmentsQuery.isError}
        identityReady={identity != null}
        storageUnavailable={storageUnavailable}
        onSelectEnvironment={selectEnvironment}
        onCollapse={collapsePanel}
        onCloseEnvironment={closeEnvironment}
        onDismissCloseError={dismissCloseError}
        onChangeDirectory={changeDirectory}
        onOpenTerminal={(environmentId) => {
          setPendingShell(environmentId)
          maybeRunPendingShell(environmentId)
        }}
        onIframeLoaded={handleIframeLoaded}
        onOpenNewView={openNewView}
        onRetryClaim={retryClaim}
        onReloadEnvironments={() => {
          void environmentsQuery.refetch()
        }}
      />
    </IdePanelContext.Provider>
  )
}
