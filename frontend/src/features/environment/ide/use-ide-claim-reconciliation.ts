import {
  useCallback,
  useEffect,
  useRef,
  useState,
} from 'react'
import { useApplicationEvents } from '@/shared/app-events'
import type { ApplicationEventConnectionIdentity } from '@/shared/app-events/protocol'
import { environmentService } from '@/shared/api/environment-service'
import type {
  EnvironmentIdeOpenViewDTO,
  EnvironmentIdeViewDTO,
} from '@/shared/api/generated/contracts'
import { createUuid, isCanonicalUuid } from '@/shared/lib/uuid'
import type { IdeEnvironmentInfo } from '@/features/environment/ide/ide-panel-context'
import { validateIdeEntryUrl } from '@/features/environment/ide/ide-url'
import {
  saveIdeSession,
  type IdePresentation,
  type IdeRecovery,
  type IdeTabStorage,
} from '@/features/environment/ide/ide-tab-state'
import type { IdeCloseResult } from '@/features/environment/ide/IdeOverlayPanel'
import {
  buildUsageViews,
  ideClaimReducer,
  initialViews,
  isDirtyClose,
  isUnknownIdeFailure,
  isViewGone,
  isViewInUse,
  POSITIVE_DECIMAL,
  recoveryFromViews,
  safeIdeError,
  type IdeActionMode,
  type IdeClaimAction,
  type IdeViewState,
} from '@/features/environment/ide/ide-claim-reducer'

export interface UseIdeClaimReconciliationOptions {
  storage?: IdeTabStorage
  initialPresentation: IdePresentation
  initialRecovery: Record<string, IdeRecovery>
  getPresentation: () => IdePresentation
  getEnvironmentMap: () => Map<string, IdeEnvironmentInfo>
  onStorageStatusChange: (unavailable: boolean) => void
}

/**
 * 连接身份 claim 对账 hook：
 * 1. 承接 WebSocket 应用事件连接身份（ApplicationEventConnectionIdentity）订阅与重连对账；
 * 2. 保证使用证明登记（registerUsage）与 claim 发起的严格同连接顺序，拦截跨连接 race；
 * 3. 施加 bindingEpoch 围栏，防止过期视图操作（关闭、切目录、打开终端）改写新绑定状态；
 * 4. 驱动纯 claim reducer 进行状态跃迁。
 */
export function useIdeClaimReconciliation({
  storage,
  initialPresentation,
  initialRecovery,
  getPresentation,
  getEnvironmentMap,
  onStorageStatusChange,
}: UseIdeClaimReconciliationOptions) {
  const events = useApplicationEvents()
  const [identity, setIdentity] = useState<ApplicationEventConnectionIdentity | null>(
    () => events.getIdentity(),
  )
  const [views, setViews] = useState<Record<string, IdeViewState>>(
    () => initialViews(initialPresentation, initialRecovery),
  )

  const viewsRef = useRef(views)
  const identityRef = useRef(identity)
  const mountedRef = useRef(false)
  const pageSuspendedRef = useRef(false)
  const documentIdRef = useRef(createUuid())
  const revisionRef = useRef(0n)
  const pendingShellEnvironmentsRef = useRef(new Set<string>())
  const actionsRef = useRef<{
    reconcileIdentity: (identity: ApplicationEventConnectionIdentity) => void
    withdrawUsage: () => void
    restoreUsage: () => void
  } | null>(null)

  useEffect(() => {
    identityRef.current = identity
  }, [identity])

  useEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
    }
  }, [])

  useEffect(() => events.subscribeIdentity(setIdentity), [events])

  const dispatch = useCallback((action: IdeClaimAction): Record<string, IdeViewState> => {
    const next = ideClaimReducer(viewsRef.current, action)
    if (next !== viewsRef.current) {
      viewsRef.current = next
      if (mountedRef.current) {
        setViews(next)
      }
    }
    return next
  }, [])

  function writeSession(): void {
    const presentation = getPresentation()
    const saved = saveIdeSession(
      presentation,
      recoveryFromViews(viewsRef.current, presentation.openedEnvironmentIds),
      storage,
    )
    if (mountedRef.current) {
      onStorageStatusChange(!saved)
    }
  }

  function nextRevision(): string {
    revisionRef.current += 1n
    return revisionRef.current.toString()
  }

  function registerUsageNow(): Promise<unknown> {
    const currentIdentity = identityRef.current
    if (currentIdentity == null || pageSuspendedRef.current) {
      return Promise.reject(new Error('application event connection is not ready'))
    }
    return events.registerUsage({
      documentId: documentIdRef.current,
      revision: nextRevision(),
      views: buildUsageViews(viewsRef.current, getPresentation().openedEnvironmentIds),
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
    dispatch({ type: 'SET_PENDING_SHELL', environmentId })
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
    dispatch({ type: 'TERMINAL_SUBMITTED', environmentId })
    try {
      await environmentService.openIdeViewTerminal(environmentId, view.viewId, {
        documentId: documentIdRef.current,
        bindingEpoch: view.bindingEpoch,
      })
    } catch (error) {
      const terminalError = isUnknownIdeFailure(error)
        ? 'terminal-result-unknown'
        : safeIdeError(error) || 'terminal-failed'
      dispatch({
        type: 'TERMINAL_FAILED',
        environmentId,
        bindingEpoch: view.bindingEpoch,
        terminalError,
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
    dispatch({
      type: 'CLAIM_FAILED',
      environmentId,
      attempt,
      mode,
      error,
    })
    const gone = isViewGone(error)
    const unknown = !gone && isUnknownIdeFailure(error)
    if (mode === 'resume' && isViewInUse(error)) {
      registerUsageQuiet()
      return
    }
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
      dispatch({
        type: 'CLAIM_VALIDATION_ERROR',
        environmentId,
        attempt,
        errorMessage: safeIdeError(error),
      })
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

    const nextState = dispatch({
      type: 'CLAIM_SUCCEEDED',
      environmentId,
      attempt,
      claimId: request.claimId,
      response,
      entryUrl,
    })
    const next = nextState[environmentId]
    writeSession()
    const currentIdentity = identityRef.current
    if (
      currentIdentity != null
      && request.eventNodeId === currentIdentity.eventNodeId
      && request.connectionId === currentIdentity.connectionId
    ) {
      registerUsageQuiet()
    } else if (currentIdentity != null && next != null) {
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
      dispatch({ type: 'RESET_CLAIM_FLIGHT', environmentId, attempt })
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
        dispatch({ type: 'RESET_CLAIM_FLIGHT', environmentId, attempt })
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
      dispatch({ type: 'RESET_CLAIM_FLIGHT', environmentId, attempt })
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
    dispatch({
      type: 'CLAIM_REQUEST_SUBMITTED',
      environmentId,
      attempt,
      request,
    })
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
    const pendingShell = current?.pendingShell
      || pendingShellEnvironmentsRef.current.has(environmentId)
    pendingShellEnvironmentsRef.current.delete(environmentId)
    const nextState = dispatch({
      type: 'BEGIN_CLAIM',
      environmentId,
      spec: { ...spec, pendingShell },
    })
    const updatedView = nextState[environmentId]
    if (updatedView != null) {
      void runClaim(environmentId, updatedView.attempt, spec.fixedRequest)
    }
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

  function retryClaim(environmentId: string): void {
    const view = viewsRef.current[environmentId]
    if (view == null || view.claimInFlight) {
      return
    }
    if (view.status === 'in-use') {
      // 占用不是未决结果；用户明确重试时以新 claimId 恢复同一 viewId。
      startResume(environmentId, view)
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
    dispatch({ type: 'CLOSE_SUBMITTED', environmentId })
    try {
      await environmentService.closeIdeView(environmentId, view.viewId, {
        documentId: documentIdRef.current,
        bindingEpoch: view.bindingEpoch,
        discard,
      })
      dispatch({ type: 'REMOVE_VIEW', environmentId })
      registerUsageQuiet()
      return { ok: true, dirty: false }
    } catch (error) {
      const dirty = isDirtyClose(error)
      const closeError = dirty
        ? undefined
        : isUnknownIdeFailure(error)
          ? 'close-result-unknown'
          : safeIdeError(error) || 'close-failed'
      dispatch({
        type: 'CLOSE_FAILED',
        environmentId,
        dirty,
        closeError,
      })
      return { ok: false, dirty, message: safeIdeError(error) }
    }
  }

  function dismissCloseError(environmentId: string): void {
    dispatch({ type: 'DISMISS_CLOSE_ERROR', environmentId })
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
        dispatch({
          type: 'DIRECTORY_CHANGED',
          environmentId,
          bindingEpoch: epoch,
          directory: response.directory,
          workspacePath: response.workspacePath,
        })
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
    dispatch({ type: 'IFRAME_LOADED', environmentId, entryUrl })
    const view = viewsRef.current[environmentId]
    if (view?.entryUrl === entryUrl) {
      maybeRunPendingShell(environmentId)
    }
  }

  function reconcileIdentity(nextIdentity: ApplicationEventConnectionIdentity): void {
    const currentPresentation = getPresentation()
    const envMap = getEnvironmentMap()
    for (const environmentId of currentPresentation.openedEnvironmentIds) {
      const view = viewsRef.current[environmentId]
      if (view == null) {
        continue
      }
      if (view.claimInFlight) {
        continue
      }
      const environmentReady = envMap.get(environmentId)?.ready
      if (environmentReady === false && (view.status === 'pending' || view.status === 'offline')) {
        dispatch({ type: 'SET_OFFLINE', environmentId })
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
    if (envMap.get(selected)?.ready === true) {
      startCreate(selected)
    }
  }

  function reconcileEnvironments(available: Set<string>): void {
    dispatch({ type: 'PRUNE_VIEWS', availableEnvironmentIds: available })
    if (identityRef.current == null) {
      return
    }
    const envMap = getEnvironmentMap()
    const currentPresentation = getPresentation()
    for (const environmentId of currentPresentation.openedEnvironmentIds) {
      const view = viewsRef.current[environmentId]
      if (
        view != null
        && !view.claimInFlight
        && (view.status === 'pending' || view.status === 'offline')
        && envMap.get(environmentId)?.ready === true
      ) {
        startResume(environmentId, view)
      }
    }
    const selected = currentPresentation.selectedEnvironmentId
    if (
      currentPresentation.panelVisible
      && selected != null
      && viewsRef.current[selected] == null
      && envMap.get(selected)?.ready === true
    ) {
      startCreate(selected)
    }
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
    const envMap = getEnvironmentMap()
    if (view != null) {
      if (view.status === 'offline') {
        const ready = environment?.ready ?? envMap.get(environmentId)?.ready
        if (ready === true) {
          startResume(environmentId, view)
        }
      }
      // gone/unknown/error 只聚焦面板并保留恢复线索；替代 view 必须由用户明确打开。
      maybeRunPendingShell(environmentId)
      return
    }
    const ready = environment?.ready ?? envMap.get(environmentId)?.ready
    if (ready === true) {
      startCreate(environmentId)
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
    reconcileIdentity,
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

  return {
    views,
    viewsRef,
    identity,
    startOrFocus,
    openNewView,
    retryClaim,
    closeEnvironment,
    dismissCloseError,
    changeDirectory,
    setPendingShell,
    maybeRunPendingShell,
    handleIframeLoaded,
    reconcileEnvironments,
    writeSession,
  }
}
