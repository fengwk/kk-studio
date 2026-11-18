import {
  useEffect,
  useRef,
  useState,
} from 'react'
import { useApplicationEvents } from '@/shared/app-events'
import type { ApplicationEventConnectionIdentity } from '@/shared/app-events/protocol'
import { createUuid, isCanonicalUuid } from '@/shared/lib/uuid'
import type { IdeActionMode, IdeEnvironmentInfo } from '@/features/environment/ide/ide-panel-context'
import {
  readRecentDirectories,
  type IdePendingClaim,
  type IdePresentation,
  type IdeTabLoadResult,
  type IdeTabStorage,
} from '@/features/environment/ide/ide-tab-state'
import {
  applyIdeMobileLayout,
  openIdeView,
  readIdeView,
  listIdeDirectories,
} from '@/features/environment/ide/environment-ide-service'
import {
  buildOpenRequest,
  IdeProtocolError,
  isIdeProtocolError,
  validateOpenResponse,
} from '@/features/environment/ide/ide-contract'
import { useIdeViewStore } from '@/features/environment/ide/useIdeViewStore'
import { useIdeOperationQueue } from '@/features/environment/ide/useIdeOperationQueue'
import { useIdeSnapshotCache } from '@/features/environment/ide/useIdeSnapshotCache'
import { useIdePendingShellIntent } from '@/features/environment/ide/useIdePendingShellIntent'
import { useIdeCloseCommands } from '@/features/environment/ide/useIdeCloseCommands'
import { useIdeTerminalCommands } from '@/features/environment/ide/useIdeTerminalCommands'
import { useIdeDirectoryCommand } from '@/features/environment/ide/useIdeDirectoryCommand'
import {
  useIdeSessionWriter,
  useIdeUsageProof,
} from '@/features/environment/ide/useIdeUsageProof'
import {
  isBoundToConnection,
  retainAvailableViews,
  type IdeViewState,
} from '@/features/environment/ide/ide-view-state'
import {
  failureStatus,
  ideFailureReason,
  isUnknownIdeFailure,
  isViewGone,
  safeIdeError,
} from '@/features/environment/ide/ide-failure'
import {
  isIdeBrowserSessionReady,
  type IdeBrowserSessionState,
} from '@/features/environment/ide/ide-browser-session'

export interface UseIdeClaimReconciliationOptions {
  storage?: IdeTabStorage
  initial: IdeTabLoadResult
  session: IdeBrowserSessionState
  activeEnvironmentId: string | null
  getPresentation: () => IdePresentation
  getEnvironmentMap: () => Map<string, IdeEnvironmentInfo>
  onStorageStatusChange: (unavailable: boolean) => void
  onViewCreated: (environmentId: string, viewId: string) => void
}

interface ReconcileOptions {
  allowRebind: boolean
}

/**
 * Chat IDE 恢复状态机。
 *
 * <p>一次 claim＝先纯读权威快照，再以冻结的 claim 发起绑定 CAS。READY、连接变化、
 * BFCache 返回和显式重试都进入同一对账入口；结构化失败只按 reason 决定下一步，
 * 不解析文案，不做定时抢占或无限重试。Web Locks 的 browserSessionId 是会话身份，
 * eventNodeId/connectionId 只证明当前连接意图，bindingEpoch 由 Daemon 权威快照给出。
 */
export function useIdeClaimReconciliation({
  storage,
  initial,
  session,
  activeEnvironmentId,
  getPresentation,
  getEnvironmentMap,
  onStorageStatusChange,
  onViewCreated,
}: UseIdeClaimReconciliationOptions) {
  const events = useApplicationEvents()
  const [identity, setIdentity] = useState<ApplicationEventConnectionIdentity | null>(
    () => events.getIdentity(),
  )

  const identityRef = useRef(identity)
  const mountedRef = useRef(false)
  const pageSuspendedRef = useRef(false)
  const sessionRef = useRef(session)
  const lastSessionStatusRef = useRef(session.status)
  const lastActiveEnvironmentRef = useRef(activeEnvironmentId)
  const lastIdentityRef = useRef<string | null>(null)
  const lastEnvironmentReadyRef = useRef(new Map<string, boolean>())
  const actionsRef = useRef<{
    reconcileAll: () => void
    withdrawUsage: () => void
    restoreUsage: () => void
  } | null>(null)

  const { views, viewsRef, commitViews, updateView: updateStoredView, startAttempt, clearViews } =
    useIdeViewStore(mountedRef, initial)

  function resolveViewId(key: string): string | null {
    if (viewsRef.current[key] != null) {
      return key
    }
    return viewIdForEnvironment(key)
  }

  function getView(key: string): IdeViewState | undefined {
    const direct = viewsRef.current[key]
    return direct ?? (() => {
      const viewId = viewIdForEnvironment(key)
      return viewId == null ? undefined : viewsRef.current[viewId]
    })()
  }

  function updateView(key: string, attempt: number, patch: Partial<IdeViewState>): IdeViewState | null {
    const viewId = resolveViewId(key)
    return viewId == null ? null : updateStoredView(viewId, attempt, patch)
  }

  function startViewAttempt(seed: Omit<IdeViewState, 'attempt'>): number {
    return startAttempt(seed.viewId, seed)
  }
  const { runExclusive, invalidateOperations } = useIdeOperationQueue(identityRef, mountedRef)
  const { acceptSnapshot, forgetView, clearSnapshots } = useIdeSnapshotCache(sessionRef)
  const {
    remember: rememberPendingShellIntent,
    take: takePendingShellIntent,
  } = useIdePendingShellIntent()
  const { writeSession } = useIdeSessionWriter({
    storage,
    sessionRef,
    viewsRef,
    mountedRef,
    getPresentation,
    preserveSession: initial.recoveryError,
    onStorageStatusChange,
  })
  const { registerUsageNow, registerUsageQuiet, usageOverride, resetRevision, withdrawUsage: withdrawUsageProof } = useIdeUsageProof({
    identityRef,
    sessionRef,
    viewsRef,
    getPresentation,
    sessionReady,
    isPageSuspended: () => pageSuspendedRef.current,
    setPageSuspended: (suspended) => {
      pageSuspendedRef.current = suspended
    },
    registerUsage: (usage) => events.registerUsage(usage),
  })
  const { closeView: closeEnvironment, dismissCloseError } = useIdeCloseCommands({
    viewsRef,
    sessionRef,
    updateView,
    commitViews,
    forgetView,
    writeSession,
    registerUsageNow,
    registerUsageQuiet,
  })
  const { setPendingShell, maybeRunPendingShell } = useIdeTerminalCommands({
    viewsRef,
    sessionRef,
    identityRef,
    updateView,
    registerUsageNow,
    rememberPendingShellIntent,
  })
  const { changeDirectory } = useIdeDirectoryCommand({
    viewsRef,
    sessionRef,
    identityRef,
    updateView,
    writeSession,
    registerUsageNow,
    reconcile,
  })

  // 事件处理器与串行队列在 effect 之外读取这两个镜像，必须在本次渲染期间就指向最新输入；
  // 改为 effect 写入会让提交前的回调看到上一轮的会话/身份。
  // eslint-disable-next-line react-hooks/refs -- 渲染期镜像，见上
  identityRef.current = identity
  // eslint-disable-next-line react-hooks/refs -- 渲染期镜像，见上
  sessionRef.current = session

  useEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
    }
  }, [])

  useEffect(() => events.subscribeIdentity(setIdentity), [events])

  function sessionReady(): boolean {
    return isIdeBrowserSessionReady(sessionRef.current.status)
  }

  function environmentIdFor(viewId: string): string {
    return viewsRef.current[viewId]?.environmentId ?? viewId
  }

  function viewIdForEnvironment(environmentId: string): string | null {
    const presentation = getPresentation()
    if (presentation.selectedEnvironmentId === environmentId
      && presentation.selectedViewId != null
      && viewsRef.current[presentation.selectedViewId] != null) {
      return presentation.selectedViewId
    }
    const ordered = presentation.viewIdsByEnvironment[environmentId] ?? []
    return ordered.find((viewId) => viewsRef.current[viewId] != null)
      ?? Object.entries(viewsRef.current).find(([, view]) => view.environmentId === environmentId)?.[0]
      ?? null
  }

  function canRun(viewId: string): boolean {
    return sessionReady()
      && identityRef.current != null
      && !pageSuspendedRef.current
      && getEnvironmentMap().get(environmentIdFor(viewId))?.ready === true
  }

  function setWaitingReady(
    environmentId: string,
    attempt: number,
    reason: 'daemon' | 'environment' = 'environment',
  ): void {
    updateView(environmentId, attempt, {
      status: 'waiting-ready',
      waitingReason: reason,
      errorMessage: undefined,
    })
  }

  function preparePending(
    attempt: number,
    pending: IdePendingClaim,
  ): boolean {
    const current = viewsRef.current[pending.viewId]
    if (current == null || current.attempt !== attempt) {
      return false
    }
    const next = updateView(pending.viewId, attempt, {
      mode: pending.mode,
      claimId: pending.claimId,
      pending,
      status: 'binding',
      daemonInstanceId: pending.expectedDaemonInstanceId,
      bindingEpoch: current.bindingEpoch,
      ...(pending.directory == null ? {} : { directory: pending.directory }),
      errorMessage: undefined,
    })
    if (next == null) {
      return false
    }
    const saved = writeSession()
    if (!saved) {
      updateView(pending.viewId, attempt, {
        status: 'error',
        pending: undefined,
        errorMessage: 'storageUnavailable',
      })
      return false
    }
    return true
  }

  /**
   * 统一失败落点：先复核 attempt 与冻结连接身份，再按结构化 reason 决定状态。
   * 只有 OPEN 的 STALE/未知结果允许一次纯读驱动的 CAS；纯读失败没有副作用，不自动重试。
   */
  async function settleFailure(
    environmentId: string,
    attempt: number,
    error: unknown,
    kind: 'open' | 'read',
    requestIdentity: Pick<
      ApplicationEventConnectionIdentity,
      'eventNodeId' | 'connectionId'
    > | null,
    options: ReconcileOptions,
  ): Promise<void> {
    if (!mountedRef.current) {
      return
    }
    const current = getView(environmentId)
    if (current == null || current.attempt !== attempt) {
      return
    }
    if (isIdeProtocolError(error)) {
      updateView(environmentId, attempt, {
        status: 'error',
        errorMessage: error.message,
        closeInFlight: false,
      })
      writeSession()
      return
    }
    if (requestIdentity != null) {
      const appliedIdentity = identityRef.current
      if (
        appliedIdentity == null
        || appliedIdentity.eventNodeId !== requestIdentity.eventNodeId
        || appliedIdentity.connectionId !== requestIdentity.connectionId
      ) {
        updateView(environmentId, attempt, {
          status: appliedIdentity == null ? 'waiting-ready' : 'reconciling',
          errorMessage: undefined,
        })
        if (appliedIdentity != null) {
          reconcile(environmentId, 'connection')
        }
        return
      }
    }
    const reason = ideFailureReason(error)
    if (reason === 'DAEMON_NOT_READY') {
      setWaitingReady(environmentId, attempt, 'daemon')
      registerUsageQuiet()
      return
    }
    if (isViewGone(error)) {
      updateView(environmentId, attempt, {
        status: 'gone',
        errorMessage: undefined,
        entryUrl: undefined,
        pending: undefined,
        closeInFlight: false,
      })
      writeSession()
      registerUsageQuiet()
      return
    }
    if (reason === 'VIEW_OWNED_BY_OTHER_SESSION') {
      updateView(environmentId, attempt, {
        status: 'detached',
        errorMessage: undefined,
        pending: undefined,
        closeInFlight: false,
      })
      writeSession()
      registerUsageQuiet()
      return
    }
    const unknown = reason === 'IDE_OUTCOME_UNKNOWN' || isUnknownIdeFailure(error)
    if (kind === 'open' && (reason === 'STALE_BINDING' || unknown) && options.allowRebind) {
      updateView(environmentId, attempt, {
        status: 'reconciling',
        errorMessage: undefined,
      })
      await reconcileNow(environmentId, 'outcome-unknown', { allowRebind: false })
      return
    }
    if (kind === 'read' && (reason === 'STALE_BINDING' || unknown) && options.allowRebind) {
      updateView(environmentId, attempt, {
        status: 'reconciling',
        errorMessage: undefined,
      })
      await reconcileNow(environmentId, 'read-stale', { allowRebind: false })
      return
    }
    const clearPending = reason === 'STALE_BINDING'
      || reason === 'CLAIM_CONFLICT'
      || reason === 'IDE_CAPACITY_EXCEEDED'
    const mapped = failureStatus(error)
    const status = unknown || reason === 'STALE_BINDING' ? 'error' : mapped.status
    updateView(environmentId, attempt, {
      status,
      errorMessage: mapped.message || (unknown ? safeIdeError(error) : undefined),
      ...(clearPending ? { pending: undefined } : {}),
      closeInFlight: false,
    })
    writeSession()
    if (status === 'detached' || status === 'gone' || status === 'error') {
      registerUsageQuiet()
    }
    if (identityRef.current == null) {
      actionsRef.current?.reconcileAll()
    }
  }

  async function sendOpen(
    attempt: number,
    pending: IdePendingClaim,
    options: ReconcileOptions,
  ): Promise<void> {
    const viewId = pending.viewId
    const view = viewsRef.current[viewId]
    if (view == null) {
      return
    }
    const ownerEnvironmentId = view.environmentId
    if (!preparePending(attempt, pending)) {
      updateView(viewId, attempt, { status: 'error', errorMessage: 'storageUnavailable' })
      return
    }
    const currentIdentity = identityRef.current
    const browserSessionId = sessionRef.current.browserSessionId
    if (currentIdentity == null || !sessionReady() || pageSuspendedRef.current) {
      setWaitingReady(viewId, attempt)
      return
    }
    try {
      await registerUsageNow(usageOverride(
        ownerEnvironmentId,
        viewId,
        pending.claimId,
        pending.mode === 'resume' ? pending.expectedBindingEpoch : undefined,
      ))
    } catch (error) {
      if (mountedRef.current) {
        await settleFailure(viewId, attempt, error, 'read', currentIdentity, options)
      }
      return
    }
    if (!mountedRef.current) {
      return
    }
    const appliedIdentity = identityRef.current
    const latest = viewsRef.current[viewId]
    if (
      latest == null || latest.attempt !== attempt
      || appliedIdentity == null
      || appliedIdentity.eventNodeId !== currentIdentity.eventNodeId
      || appliedIdentity.connectionId !== currentIdentity.connectionId
      || sessionRef.current.browserSessionId !== browserSessionId
      || !sessionReady() || pageSuspendedRef.current
    ) {
      if (latest?.attempt === attempt) {
        updateView(viewId, attempt, {
          status: appliedIdentity == null ? 'waiting-ready' : 'reconciling',
          pending,
          errorMessage: undefined,
        })
        if (appliedIdentity != null && sessionReady() && !pageSuspendedRef.current) {
          reconcile(viewId, 'connection')
        }
      }
      return
    }
    const request = buildOpenRequest(pending, browserSessionId, currentIdentity)
    try {
      const response = await openIdeView(ownerEnvironmentId, request)
      if (!mountedRef.current) {
        return
      }
      const validated = validateOpenResponse(ownerEnvironmentId, request, response)
      const current = viewsRef.current[viewId]
      if (current == null || current.attempt !== attempt) {
        return
      }
      const latestIdentity = identityRef.current
      if (
        latestIdentity == null
        || latestIdentity.eventNodeId !== request.eventNodeId
        || latestIdentity.connectionId !== request.connectionId
      ) {
        updateView(viewId, attempt, {
          status: latestIdentity == null ? 'waiting-ready' : 'reconciling',
          pending,
          errorMessage: undefined,
        })
        if (latestIdentity != null) {
          reconcile(viewId, 'connection')
        }
        return
      }
      const entryChanged = current.entryUrl !== validated.entryUrl
      updateView(viewId, attempt, {
        status: 'ready', claimId: pending.claimId, pending: undefined,
        viewId: validated.viewId, daemonInstanceId: validated.daemonInstanceId,
        bindingEpoch: validated.bindingEpoch, eventNodeId: request.eventNodeId,
        connectionId: request.connectionId, directory: validated.directory,
        workspacePath: validated.workspacePath, entryUrl: validated.entryUrl,
        iframeLoaded: entryChanged ? false : current.iframeLoaded,
        errorMessage: undefined, closeError: undefined, closeInFlight: false,
      })
      writeSession()
      registerUsageQuiet(usageOverride(
        ownerEnvironmentId, viewId, pending.claimId, validated.bindingEpoch,
      ))
      maybeRunPendingShell(viewId)
    } catch (error) {
      if (mountedRef.current) {
        await settleFailure(viewId, attempt, error, 'open', request, options)
      }
    }
  }

  async function runCreate(
    environmentId: string,
    attempt: number,
    viewId: string,
    claimId: string,
    directory?: string | null,
  ): Promise<void> {
    const current = viewsRef.current[viewId]
    if (current == null || current.attempt !== attempt) {
      return
    }
    if (!canRun(viewId)) {
      setWaitingReady(viewId, attempt)
      return
    }
    const currentIdentity = identityRef.current
    if (currentIdentity == null) {
      setWaitingReady(viewId, attempt)
      return
    }
    try {
      await registerUsageNow(usageOverride(environmentId, viewId, claimId))
    } catch (error) {
      await settleFailure(viewId, attempt, error, 'read', currentIdentity, { allowRebind: false })
      return
    }
    if (!mountedRef.current) {
      return
    }
    if (
      identityRef.current?.eventNodeId !== currentIdentity.eventNodeId
      || identityRef.current?.connectionId !== currentIdentity.connectionId
    ) {
      setWaitingReady(viewId, attempt)
      reconcile(viewId, 'connection')
      return
    }
    const query = {
      browserSessionId: sessionRef.current.browserSessionId,
      eventNodeId: currentIdentity.eventNodeId,
      connectionId: currentIdentity.connectionId,
      claimId,
    }
    try {
      const snapshot = await readIdeView(environmentId, viewId, query)
      if (!mountedRef.current) {
        return
      }
      if (
        identityRef.current?.eventNodeId !== currentIdentity.eventNodeId
        || identityRef.current?.connectionId !== currentIdentity.connectionId
      ) {
        setWaitingReady(viewId, attempt)
        reconcile(viewId, 'connection')
        return
      }
      const accepted = acceptSnapshot(environmentId, viewId, snapshot)
      if (!accepted.accepted) {
        updateView(viewId, attempt, { status: 'error', errorMessage: 'stale snapshot revision' })
        return
      }
      if (accepted.view != null && accepted.view.state === 'CLOSING') {
        updateView(viewId, attempt, { status: 'error', errorMessage: 'IDE_CLOSING' })
        writeSession()
        return
      }
      if (accepted.view == null) {
        await sendOpen(attempt, {
          mode: 'create', viewId, claimId, expectedBindingEpoch: '0',
          expectedDaemonInstanceId: accepted.daemonInstanceId,
          ...(directory == null ? {} : { directory }),
        }, { allowRebind: true })
        return
      }
      const resumeClaimId = createUuid()
      await sendOpen(attempt, {
        mode: 'resume', viewId, claimId: resumeClaimId,
        expectedBindingEpoch: accepted.view.bindingEpoch,
        expectedDaemonInstanceId: accepted.daemonInstanceId,
        ...(accepted.view.directory == null ? {} : { directory: accepted.view.directory }),
      }, { allowRebind: true })
    } catch (error) {
      await settleFailure(viewId, attempt, error, 'read', currentIdentity, { allowRebind: false })
    }
  }

  function beginCreate(environmentId: string, directory?: string | null): string {
    const viewId = createUuid()
    const claimId = createUuid()
    const attempt = startAttempt(viewId, {
      environmentId,
      viewId,
      mode: 'create',
      status: canRun(environmentId) ? 'reconciling' : 'waiting-ready',
      claimId,
      iframeLoaded: false,
      pendingShell: takePendingShellIntent(environmentId),
    })
    onViewCreated(environmentId, viewId)
    if (canRun(viewId)) {
      runExclusive(() => runCreate(environmentId, attempt, viewId, claimId, directory))
    }
    return viewId
  }

  async function runReconcile(
    attempt: number,
    viewId: string,
    claimId: string,
    options: ReconcileOptions,
  ): Promise<void> {
    const current = viewsRef.current[viewId]
    if (current == null || current.attempt !== attempt) {
      return
    }
    const environment = current.environmentId
    if (!canRun(viewId)) {
      setWaitingReady(viewId, attempt)
      return
    }
    const currentIdentity = identityRef.current
    if (currentIdentity == null) {
      setWaitingReady(viewId, attempt)
      return
    }
    try {
      await registerUsageNow(usageOverride(environment, viewId, claimId, current.bindingEpoch))
    } catch (error) {
      await settleFailure(viewId, attempt, error, 'read', currentIdentity, { allowRebind: false })
      return
    }
    if (!mountedRef.current) {
      return
    }
    if (
      identityRef.current?.eventNodeId !== currentIdentity.eventNodeId
      || identityRef.current?.connectionId !== currentIdentity.connectionId
    ) {
      setWaitingReady(viewId, attempt)
      reconcile(viewId, 'connection')
      return
    }
    const query = {
      browserSessionId: sessionRef.current.browserSessionId,
      eventNodeId: currentIdentity.eventNodeId,
      connectionId: currentIdentity.connectionId,
      claimId,
      ...(current.daemonInstanceId == null ? {} : { expectedDaemonInstanceId: current.daemonInstanceId }),
    }
    try {
      const snapshot = await readIdeView(environment, viewId, query)
      if (!mountedRef.current) {
        return
      }
      if (
        identityRef.current?.eventNodeId !== currentIdentity.eventNodeId
        || identityRef.current?.connectionId !== currentIdentity.connectionId
      ) {
        setWaitingReady(viewId, attempt)
        reconcile(viewId, 'connection')
        return
      }
      const accepted = acceptSnapshot(environment, viewId, snapshot)
      if (!accepted.accepted) {
        if (viewsRef.current[viewId]?.status === 'ready') {
          return
        }
        throw new IdeProtocolError('Invalid IDE view snapshot: stale revision')
      }
      const persisted = viewsRef.current[viewId]
      if (persisted?.daemonInstanceId != null && persisted.daemonInstanceId !== accepted.daemonInstanceId) {
        updateView(viewId, attempt, {
          status: 'gone', errorMessage: undefined, entryUrl: undefined, pending: undefined,
        })
        writeSession()
        return
      }
      if (accepted.view == null) {
        const pending = viewsRef.current[viewId]?.pending
        if (pending?.mode === 'create' && pending.expectedDaemonInstanceId === accepted.daemonInstanceId) {
          await sendOpen(attempt, pending, options)
          return
        }
        updateView(viewId, attempt, {
          status: 'gone', errorMessage: undefined, entryUrl: undefined, pending: undefined,
        })
        writeSession()
        return
      }
      if (accepted.view.state === 'CLOSING') {
        updateView(viewId, attempt, { status: 'error', errorMessage: 'IDE_CLOSING' })
        writeSession()
        return
      }
      const latest = viewsRef.current[viewId]
      if (latest == null || latest.attempt !== attempt) {
        return
      }
      const sameConnection = accepted.view.eventNodeId === currentIdentity.eventNodeId
        && accepted.view.connectionId === currentIdentity.connectionId
      if (sameConnection && latest.pending?.claimId === accepted.view.claimId) {
        await sendOpen(attempt, latest.pending, options)
        return
      }
      const authorityUnchanged = latest.bindingEpoch === accepted.view.bindingEpoch
      if (
        sameConnection
        && authorityUnchanged
        && latest.eventNodeId === currentIdentity.eventNodeId
        && latest.connectionId === currentIdentity.connectionId
        && latest.entryUrl != null
        && latest.daemonInstanceId === accepted.daemonInstanceId
      ) {
        updateView(viewId, attempt, {
          status: 'ready', claimId: latest.claimId, daemonInstanceId: accepted.daemonInstanceId,
          bindingEpoch: accepted.view.bindingEpoch, directory: accepted.view.directory,
          workspacePath: accepted.view.workspacePath, eventNodeId: currentIdentity.eventNodeId,
          connectionId: currentIdentity.connectionId, errorMessage: undefined,
        })
        writeSession()
        registerUsageQuiet(usageOverride(environment, viewId, latest.claimId, accepted.view.bindingEpoch))
        maybeRunPendingShell(viewId)
        return
      }
      const resumeClaimId = createUuid()
      await sendOpen(attempt, {
        mode: 'resume', viewId, claimId: resumeClaimId,
        expectedBindingEpoch: accepted.view.bindingEpoch,
        expectedDaemonInstanceId: accepted.daemonInstanceId,
        ...(accepted.view.directory == null ? {} : { directory: accepted.view.directory }),
      }, options)
    } catch (error) {
      await settleFailure(viewId, attempt, error, 'read', currentIdentity, options)
    }
  }

  async function reconcileNow(
    environmentId: string,
    reason: 'focus' | 'connection' | 'restore' | 'queued' | 'read-stale' | 'outcome-unknown' | 'directory' | 'retry',
    options: ReconcileOptions = { allowRebind: true },
  ): Promise<void> {
    const view = getView(environmentId)
    if (view == null) {
      return
    }
    if (!canRun(environmentId)) {
      const attempt = startViewAttempt({
        ...view,
        status: 'waiting-ready',
        errorMessage: undefined,
      })
      setWaitingReady(environmentId, attempt)
      return
    }
    if (view.mode === 'create' && view.daemonInstanceId == null) {
      // 显式 open 但命令尚未发出：沿用同一 viewId/claimId 从纯读发现开始，不新建替代资源。
      const claimId = isCanonicalUuid(view.claimId) ? view.claimId : createUuid()
      const attempt = startViewAttempt({
        ...view,
        status: 'reconciling',
        claimId,
        errorMessage: undefined,
      })
      await runCreate(environmentId, attempt, view.viewId, claimId, view.directory)
      return
    }
    if (reason === 'focus' && isBoundToConnection(
      view,
      identityRef.current?.eventNodeId ?? '',
      identityRef.current?.connectionId ?? '',
    )) {
      registerUsageQuiet()
      maybeRunPendingShell(environmentId)
      return
    }
    const claimId = view.pending?.claimId
      ?? (isCanonicalUuid(view.claimId) ? view.claimId : createUuid())
    const attempt = startViewAttempt({
      ...view,
      status: 'reconciling',
      claimId,
      errorMessage: undefined,
    })
    await runReconcile(attempt, view.viewId, claimId, options)
  }

  function reconcile(
    environmentId: string,
    reason: 'focus' | 'connection' | 'restore' | 'queued' | 'read-stale' | 'outcome-unknown' | 'directory' | 'retry',
    options: ReconcileOptions = { allowRebind: true },
  ): void {
    runExclusive(async () => {
      try {
        await reconcileNow(environmentId, reason, options)
      } catch (error) {
        const current = getView(environmentId)
        if (current != null) {
          updateView(environmentId, current.attempt, {
            status: 'error',
            errorMessage: safeIdeError(error),
          })
        }
      }
    })
  }

  function startOrFocus(
    environmentId: string,
    environment: IdeEnvironmentInfo | null,
    mode: IdeActionMode,
  ): string | null {
    if (mode === 'shell') {
      setPendingShell(environmentId)
    }
    const view = getView(environmentId)
    if (view != null) {
      if (view.status === 'ready') {
        maybeRunPendingShell(environmentId)
        if (!isBoundToConnection(
          view,
          identityRef.current?.eventNodeId ?? '',
          identityRef.current?.connectionId ?? '',
        )) {
          void reconcile(environmentId, 'focus')
        }
        return view.viewId
      }
      if (view.status === 'detached' || view.status === 'gone') {
        return view.viewId
      }
      void reconcile(view.viewId, 'focus')
      return view.viewId
    }
    const ready = environment?.ready ?? getEnvironmentMap().get(environmentId)?.ready
    if (ready === true) {
      // 用户显式打开：沿用该 Environment 最近一次确认打开的目录；无记录时由 Daemon 使用主目录。
      return beginCreate(environmentId, readRecentDirectories(environmentId, storage)[0])
    } else {
      const viewId = createUuid()
      const attempt = startAttempt(viewId, {
        environmentId,
        viewId,
        mode: 'create',
        status: 'waiting-ready',
        claimId: createUuid(),
        iframeLoaded: false,
        pendingShell: takePendingShellIntent(environmentId),
      })
      setWaitingReady(viewId, attempt)
      return viewId
    }
    return null
  }

  async function openNewView(environmentId: string, directory?: string): Promise<string | null> {
    const current = getView(environmentId)
    const candidate = directory ?? current?.directory ?? readRecentDirectories(environmentId, storage)[0]
    const listing = await listIdeDirectories(environmentId, candidate)
    const canonicalDirectory = listing.path
    const duplicate = Object.values(viewsRef.current).find((view) => (
      view.environmentId === environmentId && view.directory === canonicalDirectory
    ))
    if (duplicate != null) {
      return duplicate.viewId
    }
    return beginCreate(environmentId, canonicalDirectory)
  }

  function retryClaim(environmentId: string): void {
    const view = getView(environmentId)
    if (view == null) {
      return
    }
    if (view.mode === 'create' && view.daemonInstanceId == null) {
      const claimId = createUuid()
      const attempt = startViewAttempt({
        ...view,
        status: canRun(environmentId) ? 'reconciling' : 'waiting-ready',
        claimId,
        errorMessage: undefined,
      })
      if (canRun(environmentId)) {
        runExclusive(() => runCreate(environmentId, attempt, view.viewId, claimId, view.directory))
      }
      return
    }
    void reconcile(environmentId, 'retry')
  }

  function handleIframeLoaded(environmentId: string, entryUrl: string): void {
    const before = getView(environmentId)
    if (
      before == null
      || before.status !== 'ready'
      || before.entryUrl !== entryUrl
      || before.iframeLoaded
    ) {
      return
    }
    updateView(environmentId, before.attempt, { iframeLoaded: true })
    if (
      before.bindingEpoch != null
      && before.daemonInstanceId != null
      && window.matchMedia('(max-width: 760px)').matches
    ) {
      void (async () => {
        try {
          await registerUsageNow(usageOverride(
            environmentId,
            before.viewId,
            before.claimId,
            before.bindingEpoch,
          ))
          await applyIdeMobileLayout(environmentId, before.viewId, {
            browserSessionId: sessionRef.current.browserSessionId,
            bindingEpoch: before.bindingEpoch!,
            expectedDaemonInstanceId: before.daemonInstanceId!,
          })
        } catch {
          // 布局增强失败不回滚已建立的绑定，也不影响用户继续编辑。
        }
      })()
    }
    maybeRunPendingShell(environmentId)
  }

  function reconcileEnvironments(available: Set<string>): void {
    const retained = retainAvailableViews(viewsRef.current, available)
    if (retained != null) {
      commitViews(retained)
      writeSession()
    }
    const environmentMap = getEnvironmentMap()
    const previousReady = lastEnvironmentReadyRef.current
    for (const environmentId of available) {
      const ready = environmentMap.get(environmentId)?.ready === true
      if (ready && sessionReady() && identityRef.current != null) {
        const viewIds = Object.values(viewsRef.current)
          .filter((view) => view.environmentId === environmentId)
          .map((view) => view.viewId)
        for (const viewId of viewIds) {
          const view = viewsRef.current[viewId]
          if (view?.status === 'waiting-ready') {
            const wasReady = previousReady.get(environmentId)
            if (view.waitingReason !== 'daemon' || wasReady === false) {
              reconcile(viewId, 'connection')
            }
          }
        }
      }
      previousReady.set(environmentId, ready)
    }
    for (const environmentId of [...previousReady.keys()]) {
      if (!available.has(environmentId)) {
        previousReady.delete(environmentId)
      }
    }
  }

  function reconcileAll(): void {
    if (!sessionReady()) {
      return
    }
    for (const viewIds of Object.values(getPresentation().viewIdsByEnvironment)) {
      for (const viewId of viewIds) {
        const view = viewsRef.current[viewId]
        if (view != null && view.status !== 'detached' && view.status !== 'gone') {
          void reconcile(viewId, 'restore')
        }
      }
    }
  }

  function resetForClone(): void {
    clearViews()
    resetRevision()
    lastIdentityRef.current = null
    clearSnapshots()
    invalidateOperations()
    pageSuspendedRef.current = false
  }


  function withdrawUsage(): void {
    withdrawUsageProof(() => identityRef.current != null && sessionReady())
  }

  function restoreUsage(): void {
    pageSuspendedRef.current = false
    if (identityRef.current != null && sessionReady()) {
      registerUsageQuiet()
    }
  }

  // 父组件通过 ref 调用这三个动作；它们必须对应本次渲染的闭包，因此在渲染期写入。
  // eslint-disable-next-line react-hooks/refs -- 渲染期闭包镜像，见上
  actionsRef.current = {
    reconcileAll,
    withdrawUsage,
    restoreUsage,
  }

  useEffect(() => {
    if (identity == null) {
      return
    }
    // 连接变化使旧连接的在途绑定立即失效；新连接不得排在旧请求后面。
    invalidateOperations()
    // eslint-disable-next-line react-hooks/exhaustive-deps -- only a new transport connection invalidates operations
  }, [identity?.connectionId])

  useEffect(() => {
    if (identity == null || !sessionReady()) {
      return
    }
    const key = identity.connectionId
    if (lastIdentityRef.current === key) {
      return
    }
    lastIdentityRef.current = key
    actionsRef.current?.reconcileAll()
    // identity/connectionId 是网络绑定的全部身份；其余状态由操作自身推进。
  }, [identity, session.browserSessionId, session.status])

  useEffect(() => {
    const previous = lastSessionStatusRef.current
    lastSessionStatusRef.current = session.status
    // BFCache 恢复时 onRestore 早于 React 提交 ready 状态；等 sessionRef 更新后再对账。
    if (session.status === 'ready' && previous === 'suspended') {
      actionsRef.current?.reconcileAll()
    }
  }, [session.status])

  useEffect(() => {
    const previous = lastActiveEnvironmentRef.current
    lastActiveEnvironmentRef.current = activeEnvironmentId
    // 首次挂载/会话就绪由 identity 对账入口处理；这里只响应用户切换 Chat 的环境。
    if (activeEnvironmentId == null || previous === activeEnvironmentId) {
      return
    }
    const currentIdentity = identityRef.current
    if (!sessionReady() || currentIdentity == null) {
      return
    }
    const view = getView(activeEnvironmentId)
    if (view == null || view.status === 'detached' || view.status === 'gone') {
      return
    }
    if (
      view.status === 'ready'
      && isBoundToConnection(view, currentIdentity.eventNodeId, currentIdentity.connectionId)
    ) {
      registerUsageQuiet()
      maybeRunPendingShell(view.viewId)
      return
    }
    reconcile(view.viewId, 'focus')
    // activeEnvironmentId 切换只做聚焦/对账，不在没有 view 时自动创建。
    // eslint-disable-next-line react-hooks/exhaustive-deps -- only an Environment transition or session readiness needs focus reconciliation
  }, [activeEnvironmentId, session.browserSessionId, session.status])

  return {
    views,
    viewsRef,
    reconcileView: (viewId: string, reason: 'focus' | 'connection' | 'restore' | 'queued' | 'read-stale' | 'outcome-unknown' | 'directory' | 'retry') =>
      reconcile(viewId, reason),
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
    resetForClone,
    withdrawUsage,
    restoreUsage,
    reconcileAll,
  }
}
