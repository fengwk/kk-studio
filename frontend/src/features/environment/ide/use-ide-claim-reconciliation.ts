import {
  useCallback,
  useEffect,
  useRef,
  useState,
} from 'react'
import { useApplicationEvents } from '@/shared/app-events'
import type { ApplicationEventConnectionIdentity } from '@/shared/app-events/protocol'
import type {
  EnvironmentIdeChangeDirectoryDTO,
  EnvironmentIdeCloseViewDTO,
  EnvironmentIdeViewBindingDTO,
  EnvironmentIdeViewDTO,
  EnvironmentIdeViewSnapshotDTO,
} from '@/shared/api/generated/contracts'
import { createUuid, isCanonicalUuid } from '@/shared/lib/uuid'
import type { IdeActionMode, IdeEnvironmentInfo } from '@/features/environment/ide/ide-panel-context'
import {
  readRecentDirectories,
  saveIdeSession,
  type IdePendingClaim,
  type IdePresentation,
  type IdeTabLoadResult,
  type IdeTabStorage,
} from '@/features/environment/ide/ide-tab-state'
import type { IdeCloseResult } from '@/features/environment/ide/IdeOverlayPanel'
import {
  applyIdeMobileLayout,
  changeIdeViewDirectory,
  closeIdeView,
  focusIdeTerminal,
  openIdeView,
  readIdeView,
} from '@/features/environment/ide/environment-ide-service'
import {
  buildOpenRequest,
  compareDecimal,
  IdeProtocolError,
  isIdeProtocolError,
  validateCommandView,
  validateOpenResponse,
  validateViewSnapshot,
  viewFingerprint,
} from '@/features/environment/ide/ide-contract'
import {
  buildUsageViews,
  initialViews,
  isBoundToConnection,
  recoveryFromViews,
  type IdeViewState,
} from '@/features/environment/ide/ide-view-state'
import {
  failureStatus,
  ideFailureReason,
  isUnknownIdeFailure,
  isViewGone,
  safeIdeError,
} from '@/features/environment/ide/ide-failure'
import type { IdeBrowserSessionState } from '@/features/environment/ide/ide-browser-session'

export interface UseIdeClaimReconciliationOptions {
  storage?: IdeTabStorage
  initial: IdeTabLoadResult
  session: IdeBrowserSessionState
  activeEnvironmentId: string | null
  getPresentation: () => IdePresentation
  getEnvironmentMap: () => Map<string, IdeEnvironmentInfo>
  onStorageStatusChange: (unavailable: boolean) => void
}

interface SnapshotCacheEntry {
  revision: string
  fingerprint: string
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
}: UseIdeClaimReconciliationOptions) {
  const events = useApplicationEvents()
  const [identity, setIdentity] = useState<ApplicationEventConnectionIdentity | null>(
    () => events.getIdentity(),
  )
  const [views, setViews] = useState<Record<string, IdeViewState>>(
    () => initialViews(initial.presentation, initial.recoveryByEnvironment),
  )

  const viewsRef = useRef(views)
  const identityRef = useRef(identity)
  const mountedRef = useRef(false)
  const pageSuspendedRef = useRef(false)
  const revisionRef = useRef(0n)
  const bindingRef = useRef(false)
  const operationConnectionRef = useRef<string | null>(null)
  const operationGenerationRef = useRef(0)
  const operationQueueRef = useRef<Array<() => Promise<void>>>([])
  const sessionRef = useRef(session)
  const lastSessionStatusRef = useRef(session.status)
  const lastActiveEnvironmentRef = useRef(activeEnvironmentId)
  const lastIdentityRef = useRef<string | null>(null)
  const lastEnvironmentReadyRef = useRef(new Map<string, boolean>())
  const snapshotCacheRef = useRef(new Map<string, SnapshotCacheEntry>())
  const pendingShellEnvironmentsRef = useRef(new Set<string>())
  const actionsRef = useRef<{
    reconcileAll: () => void
    withdrawUsage: () => void
    restoreUsage: () => void
  } | null>(null)

  identityRef.current = identity
  sessionRef.current = session

  useEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
    }
  }, [])

  useEffect(() => events.subscribeIdentity(setIdentity), [events])

  const commitViews = useCallback((next: Record<string, IdeViewState>) => {
    viewsRef.current = next
    if (mountedRef.current) {
      setViews(next)
    }
  }, [])

  function updateView(
    environmentId: string,
    attempt: number,
    patch: Partial<IdeViewState>,
  ): IdeViewState | null {
    const current = viewsRef.current[environmentId]
    if (current == null || current.attempt !== attempt) {
      return null
    }
    const next = { ...current, ...patch }
    commitViews({ ...viewsRef.current, [environmentId]: next })
    return next
  }

  function sessionReady(): boolean {
    const status = sessionRef.current.status
    return status === 'ready' || status === 'cloned'
  }

  function canRun(environmentId: string): boolean {
    return sessionReady()
      && identityRef.current != null
      && !pageSuspendedRef.current
      && getEnvironmentMap().get(environmentId)?.ready === true
  }

  function writeSession(): boolean {
    const presentation = getPresentation()
    const saved = saveIdeSession(
      sessionRef.current.browserSessionId,
      presentation,
      recoveryFromViews(viewsRef.current, presentation.openedEnvironmentIds),
      storage,
    )
    if (mountedRef.current) {
      onStorageStatusChange(!saved)
    }
    return saved
  }

  function nextRevision(): string {
    revisionRef.current += 1n
    return revisionRef.current.toString()
  }

  function usageOverride(
    environmentId: string,
    viewId: string,
    claimId: string,
    bindingEpoch?: string,
  ) {
    return { environmentId, viewId, claimId, ...(bindingEpoch == null ? {} : { bindingEpoch }) }
  }

  function registerUsageNow(override?: {
    environmentId: string
    viewId: string
    claimId: string
    bindingEpoch?: string
  }): Promise<unknown> {
    const currentIdentity = identityRef.current
    const browserSessionId = sessionRef.current.browserSessionId
    if (currentIdentity == null || !sessionReady() || pageSuspendedRef.current) {
      return Promise.reject(new Error('application event connection is not ready'))
    }
    const opened = getPresentation().openedEnvironmentIds
    const environmentIds = override != null && !opened.includes(override.environmentId)
      ? [...opened, override.environmentId]
      : opened
    return events.registerUsage({
      browserSessionId,
      revision: nextRevision(),
      views: buildUsageViews(viewsRef.current, environmentIds, override),
    })
  }

  function registerUsageQuiet(override?: {
    environmentId: string
    viewId: string
    claimId: string
    bindingEpoch?: string
  }): void {
    void registerUsageNow(override).catch(() => {
      // 断线由 reject 明示；后续只由新的 connected 身份重新对账，不在这里重试。
    })
  }

  function startAttempt(
    environmentId: string,
    seed: Omit<IdeViewState, 'environmentId' | 'attempt'>,
  ): number {
    const current = viewsRef.current[environmentId]
    const attempt = (current?.attempt ?? 0) + 1
    commitViews({
      ...viewsRef.current,
      [environmentId]: { ...seed, environmentId, attempt },
    })
    return attempt
  }

  /** 当前连接内只允许一个在途绑定操作；旧连接的在途操作不阻塞新连接立即对账。 */
  function runExclusive(task: () => Promise<void>): void {
    const connectionId = identityRef.current?.connectionId ?? null
    if (bindingRef.current && operationConnectionRef.current === connectionId) {
      operationQueueRef.current.push(task)
      return
    }
    const generation = operationGenerationRef.current + 1
    operationGenerationRef.current = generation
    bindingRef.current = true
    operationConnectionRef.current = connectionId
    void (async () => {
      try {
        await task()
      } finally {
        if (operationGenerationRef.current === generation) {
          bindingRef.current = false
          operationConnectionRef.current = null
          const next = operationQueueRef.current.shift()
          if (next != null && mountedRef.current) {
            runExclusive(next)
          }
        }
      }
    })()
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
    environmentId: string,
    attempt: number,
    pending: IdePendingClaim,
  ): boolean {
    const current = viewsRef.current[environmentId]
    if (current == null || current.attempt !== attempt) {
      return false
    }
    const next = updateView(environmentId, attempt, {
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
      updateView(environmentId, attempt, {
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
    requestIdentity: ApplicationEventConnectionIdentity | null,
    options: ReconcileOptions,
  ): Promise<void> {
    if (!mountedRef.current) {
      return
    }
    const current = viewsRef.current[environmentId]
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
    environmentId: string,
    attempt: number,
    pending: IdePendingClaim,
    options: ReconcileOptions,
  ): Promise<void> {
    if (!preparePending(environmentId, attempt, pending)) {
      updateView(environmentId, attempt, {
        status: 'error',
        errorMessage: 'storageUnavailable',
      })
      return
    }
    const currentIdentity = identityRef.current
    const browserSessionId = sessionRef.current.browserSessionId
    if (currentIdentity == null || !sessionReady() || pageSuspendedRef.current) {
      setWaitingReady(environmentId, attempt)
      return
    }
    try {
      // OPEN 前必须用新 claim 重新登记 usage；上一次纯读的旧 claim ACK 不能代替。
      await registerUsageNow(usageOverride(
        environmentId,
        pending.viewId,
        pending.claimId,
        pending.mode === 'resume' ? pending.expectedBindingEpoch : undefined,
      ))
    } catch (error) {
      if (!mountedRef.current) {
        return
      }
      await settleFailure(environmentId, attempt, error, 'read', currentIdentity, options)
      return
    }
    if (!mountedRef.current) {
      return
    }
    const appliedIdentity = identityRef.current
    const latest = viewsRef.current[environmentId]
    if (
      latest == null
      || latest.attempt !== attempt
      || appliedIdentity == null
      || appliedIdentity.eventNodeId !== currentIdentity.eventNodeId
      || appliedIdentity.connectionId !== currentIdentity.connectionId
      || sessionRef.current.browserSessionId !== browserSessionId
      || !sessionReady()
      || pageSuspendedRef.current
    ) {
      if (latest != null && latest.attempt === attempt) {
        updateView(environmentId, attempt, {
          status: appliedIdentity == null ? 'waiting-ready' : 'reconciling',
          pending,
          errorMessage: undefined,
        })
        if (appliedIdentity != null && sessionReady() && !pageSuspendedRef.current) {
          reconcile(environmentId, 'connection')
        }
      }
      return
    }
    const request = buildOpenRequest(pending, browserSessionId, currentIdentity)
    try {
      const response = await openIdeView(environmentId, request)
      if (!mountedRef.current) {
        return
      }
      const validated = validateOpenResponse(
        environmentId,
        request.browserSessionId,
        pending,
        response,
      )
      const current = viewsRef.current[environmentId]
      if (current == null || current.attempt !== attempt) {
        return
      }
      const appliedIdentity = identityRef.current
      if (
        appliedIdentity == null
        || appliedIdentity.eventNodeId !== request.eventNodeId
        || appliedIdentity.connectionId !== request.connectionId
      ) {
        // 迟到回执不能把旧连接绑定当成当前绑定；保留 pending 走新连接对账。
        updateView(environmentId, attempt, {
          status: appliedIdentity == null ? 'waiting-ready' : 'reconciling',
          pending,
          errorMessage: undefined,
        })
        if (appliedIdentity != null) {
          reconcile(environmentId, 'connection')
        }
        return
      }
      const entryChanged = current.entryUrl !== validated.entryUrl
      updateView(environmentId, attempt, {
        status: 'ready',
        claimId: pending.claimId,
        pending: undefined,
        viewId: validated.viewId,
        daemonInstanceId: validated.daemonInstanceId,
        bindingEpoch: validated.bindingEpoch,
        eventNodeId: request.eventNodeId,
        connectionId: request.connectionId,
        directory: validated.directory,
        workspacePath: validated.workspacePath,
        entryUrl: validated.entryUrl,
        iframeLoaded: entryChanged ? false : current.iframeLoaded,
        errorMessage: undefined,
        closeError: undefined,
        closeInFlight: false,
      })
      writeSession()
      registerUsageQuiet(usageOverride(
        environmentId,
        pending.viewId,
        pending.claimId,
        validated.bindingEpoch,
      ))
      maybeRunPendingShell(environmentId)
    } catch (error) {
      if (!mountedRef.current) {
        return
      }
      await settleFailure(environmentId, attempt, error, 'open', request, options)
    }
  }

  async function runCreate(
    environmentId: string,
    attempt: number,
    viewId: string,
    claimId: string,
    directory?: string | null,
  ): Promise<void> {
    const current = viewsRef.current[environmentId]
    if (current == null || current.attempt !== attempt) {
      return
    }
    if (!canRun(environmentId)) {
      setWaitingReady(environmentId, attempt)
      return
    }
    const currentIdentity = identityRef.current
    if (currentIdentity == null) {
      setWaitingReady(environmentId, attempt)
      return
    }
    try {
      await registerUsageNow(usageOverride(environmentId, viewId, claimId))
    } catch (error) {
      await settleFailure(environmentId, attempt, error, 'read', currentIdentity, { allowRebind: false })
      return
    }
    if (!mountedRef.current) {
      return
    }
    if (
      identityRef.current?.eventNodeId !== currentIdentity.eventNodeId
      || identityRef.current?.connectionId !== currentIdentity.connectionId
    ) {
      setWaitingReady(environmentId, attempt)
      reconcile(environmentId, 'connection')
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
        setWaitingReady(environmentId, attempt)
        reconcile(environmentId, 'connection')
        return
      }
      const accepted = acceptSnapshot(environmentId, viewId, snapshot)
      if (!accepted.accepted) {
        updateView(environmentId, attempt, {
          status: 'error',
          errorMessage: 'stale snapshot revision',
        })
        return
      }
      if (accepted.view != null && accepted.view.state === 'CLOSING') {
        updateView(environmentId, attempt, {
          status: 'error',
          errorMessage: 'IDE_CLOSING',
        })
        writeSession()
        return
      }
      if (accepted.view == null) {
        await sendOpen(environmentId, attempt, {
          mode: 'create',
          viewId,
          claimId,
          expectedBindingEpoch: '0',
          expectedDaemonInstanceId: accepted.daemonInstanceId,
          ...(directory == null ? {} : { directory }),
        }, { allowRebind: true })
        return
      }
      const resumeClaimId = createUuid()
      await sendOpen(environmentId, attempt, {
        mode: 'resume',
        viewId,
        claimId: resumeClaimId,
        expectedBindingEpoch: accepted.view.bindingEpoch,
        expectedDaemonInstanceId: accepted.daemonInstanceId,
        ...(accepted.view.directory == null ? {} : { directory: accepted.view.directory }),
      }, { allowRebind: true })
    } catch (error) {
      await settleFailure(environmentId, attempt, error, 'read', currentIdentity, { allowRebind: false })
    }
  }

  function beginCreate(environmentId: string, directory?: string | null): void {
    const viewId = createUuid()
    const claimId = createUuid()
    const attempt = startAttempt(environmentId, {
      viewId,
      mode: 'create',
      status: canRun(environmentId) ? 'reconciling' : 'waiting-ready',
      claimId,
      iframeLoaded: false,
      pendingShell: pendingShellEnvironmentsRef.current.delete(environmentId),
    })
    if (!canRun(environmentId)) {
      return
    }
    runExclusive(() => runCreate(environmentId, attempt, viewId, claimId, directory))
  }

  function acceptSnapshot(
    environmentId: string,
    viewId: string,
    snapshot: EnvironmentIdeViewSnapshotDTO,
  ): { accepted: boolean; daemonInstanceId: string; view: EnvironmentIdeViewDTO | null } {
    const validated = validateViewSnapshot(
      environmentId,
      viewId,
      sessionRef.current.browserSessionId,
      snapshot,
    )
    const cacheKey = `${validated.daemonInstanceId}:${viewId}`
    const cached = snapshotCacheRef.current.get(cacheKey)
    if (cached != null) {
      const compare = compareDecimal(validated.revision, cached.revision)
      if (compare < 0) {
        return {
          accepted: false,
          daemonInstanceId: validated.daemonInstanceId,
          view: null,
        }
      }
      if (compare === 0 && cached.fingerprint !== viewFingerprint(validated.view)) {
        throw new IdeProtocolError('Invalid IDE view snapshot: conflicting revision')
      }
    }
    snapshotCacheRef.current.set(cacheKey, {
      revision: validated.revision,
      fingerprint: viewFingerprint(validated.view),
    })
    return { accepted: true, daemonInstanceId: validated.daemonInstanceId, view: validated.view }
  }

  async function runReconcile(
    environmentId: string,
    attempt: number,
    viewId: string,
    claimId: string,
    options: ReconcileOptions,
  ): Promise<void> {
    const current = viewsRef.current[environmentId]
    if (current == null || current.attempt !== attempt) {
      return
    }
    if (!canRun(environmentId)) {
      setWaitingReady(environmentId, attempt)
      return
    }
    const currentIdentity = identityRef.current
    if (currentIdentity == null) {
      setWaitingReady(environmentId, attempt)
      return
    }
    try {
      await registerUsageNow(usageOverride(
        environmentId,
        viewId,
        claimId,
        current.bindingEpoch,
      ))
    } catch (error) {
      await settleFailure(environmentId, attempt, error, 'read', currentIdentity, { allowRebind: false })
      return
    }
    if (!mountedRef.current) {
      return
    }
    if (
      identityRef.current?.eventNodeId !== currentIdentity.eventNodeId
      || identityRef.current?.connectionId !== currentIdentity.connectionId
    ) {
      setWaitingReady(environmentId, attempt)
      reconcile(environmentId, 'connection')
      return
    }
    const query = {
      browserSessionId: sessionRef.current.browserSessionId,
      eventNodeId: currentIdentity.eventNodeId,
      connectionId: currentIdentity.connectionId,
      claimId,
      ...(current.daemonInstanceId == null
        ? {}
        : { expectedDaemonInstanceId: current.daemonInstanceId }),
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
        setWaitingReady(environmentId, attempt)
        reconcile(environmentId, 'connection')
        return
      }
      const accepted = acceptSnapshot(environmentId, viewId, snapshot)
      if (!accepted.accepted) {
        const latest = viewsRef.current[environmentId]
        if (latest?.status === 'ready') {
          return
        }
        throw new IdeProtocolError('Invalid IDE view snapshot: stale revision')
      }
      const persisted = viewsRef.current[environmentId]
      if (
        persisted?.daemonInstanceId != null
        && persisted.daemonInstanceId !== accepted.daemonInstanceId
      ) {
        updateView(environmentId, attempt, {
          status: 'gone',
          errorMessage: undefined,
          entryUrl: undefined,
          pending: undefined,
        })
        writeSession()
        return
      }
      if (accepted.view == null) {
        const latest = viewsRef.current[environmentId]
        const pending = latest?.pending
        if (
          pending != null
          && pending.mode === 'create'
          && pending.expectedDaemonInstanceId === accepted.daemonInstanceId
        ) {
          await sendOpen(environmentId, attempt, pending, options)
          return
        }
        updateView(environmentId, attempt, {
          status: 'gone',
          errorMessage: undefined,
          entryUrl: undefined,
          pending: undefined,
        })
        writeSession()
        return
      }
      if (accepted.view.state === 'CLOSING') {
        updateView(environmentId, attempt, {
          status: 'error',
          errorMessage: 'IDE_CLOSING',
        })
        writeSession()
        return
      }
      const latest = viewsRef.current[environmentId]
      if (latest == null || latest.attempt !== attempt) {
        return
      }
      const sameConnection = isBoundToConnection(
        latest,
        currentIdentity.eventNodeId,
        currentIdentity.connectionId,
      )
      const authorityUnchanged = latest.bindingEpoch === accepted.view.bindingEpoch
      if (
        sameConnection
        && authorityUnchanged
        && latest.entryUrl != null
        && latest.daemonInstanceId === accepted.daemonInstanceId
      ) {
        updateView(environmentId, attempt, {
          status: 'ready',
          claimId: latest.claimId,
          daemonInstanceId: accepted.daemonInstanceId,
          bindingEpoch: accepted.view.bindingEpoch,
          directory: accepted.view.directory,
          workspacePath: accepted.view.workspacePath,
          eventNodeId: currentIdentity.eventNodeId,
          connectionId: currentIdentity.connectionId,
          errorMessage: undefined,
        })
        writeSession()
        registerUsageQuiet(usageOverride(
          environmentId,
          viewId,
          latest.claimId,
          accepted.view.bindingEpoch,
        ))
        maybeRunPendingShell(environmentId)
        return
      }
      const resumeClaimId = createUuid()
      await sendOpen(environmentId, attempt, {
        mode: 'resume',
        viewId,
        claimId: resumeClaimId,
        expectedBindingEpoch: accepted.view.bindingEpoch,
        expectedDaemonInstanceId: accepted.daemonInstanceId,
        ...(accepted.view.directory == null ? {} : { directory: accepted.view.directory }),
      }, options)
    } catch (error) {
      await settleFailure(environmentId, attempt, error, 'read', currentIdentity, options)
    }
  }

  async function reconcileNow(
    environmentId: string,
    reason: 'focus' | 'connection' | 'restore' | 'queued' | 'read-stale' | 'outcome-unknown' | 'directory' | 'retry',
    options: ReconcileOptions = { allowRebind: true },
  ): Promise<void> {
    const view = viewsRef.current[environmentId]
    if (view == null) {
      return
    }
    if (!canRun(environmentId)) {
      const attempt = startAttempt(environmentId, {
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
      const attempt = startAttempt(environmentId, {
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
    const attempt = startAttempt(environmentId, {
      ...view,
      status: 'reconciling',
      claimId,
      errorMessage: undefined,
    })
    await runReconcile(environmentId, attempt, view.viewId, claimId, options)
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
        const current = viewsRef.current[environmentId]
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
  ): void {
    if (mode === 'shell') {
      setPendingShell(environmentId)
    }
    const view = viewsRef.current[environmentId]
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
        return
      }
      if (view.status === 'detached' || view.status === 'gone') {
        return
      }
      void reconcile(environmentId, 'focus')
      return
    }
    const ready = environment?.ready ?? getEnvironmentMap().get(environmentId)?.ready
    if (ready === true) {
      // 用户显式打开：沿用该 Environment 最近一次确认打开的目录；无记录时由 Daemon 使用主目录。
      beginCreate(environmentId, readRecentDirectories(environmentId, storage)[0])
    } else {
      const attempt = startAttempt(environmentId, {
        viewId: createUuid(),
        mode: 'create',
        status: 'waiting-ready',
        claimId: createUuid(),
        iframeLoaded: false,
        pendingShell: pendingShellEnvironmentsRef.current.delete(environmentId),
      })
      setWaitingReady(environmentId, attempt)
    }
  }

  function openNewView(environmentId: string): void {
    const view = viewsRef.current[environmentId]
    beginCreate(environmentId, view?.directory ?? readRecentDirectories(environmentId, storage)[0])
  }

  function retryClaim(environmentId: string): void {
    const view = viewsRef.current[environmentId]
    if (view == null) {
      return
    }
    if (view.mode === 'create' && view.daemonInstanceId == null) {
      const claimId = createUuid()
      const attempt = startAttempt(environmentId, {
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

  function setPendingShell(environmentId: string): void {
    const view = viewsRef.current[environmentId]
    if (view == null) {
      pendingShellEnvironmentsRef.current.add(environmentId)
      return
    }
    if (view.pendingShell && view.terminalError === undefined) {
      return
    }
    updateView(environmentId, view.attempt, {
      pendingShell: true,
      terminalError: undefined,
    })
  }

  async function runTerminal(environmentId: string): Promise<void> {
    const view = viewsRef.current[environmentId]
    if (
      view == null
      || view.status !== 'ready'
      || view.bindingEpoch == null
      || view.daemonInstanceId == null
      || view.closeInFlight
    ) {
      return
    }
    updateView(environmentId, view.attempt, {
      pendingShell: false,
      terminalError: undefined,
    })
    const requestIdentity = identityRef.current
    try {
      await registerUsageNow(usageOverride(
        environmentId,
        view.viewId,
        view.claimId,
        view.bindingEpoch,
      ))
      const body: EnvironmentIdeViewBindingDTO = {
        browserSessionId: sessionRef.current.browserSessionId,
        bindingEpoch: view.bindingEpoch,
        expectedDaemonInstanceId: view.daemonInstanceId,
      }
      await focusIdeTerminal(environmentId, view.viewId, body)
    } catch (error) {
      const current = viewsRef.current[environmentId]
      const appliedIdentity = identityRef.current
      if (
        !bindingMatches(current, view)
        || requestIdentity == null
        || appliedIdentity == null
        || appliedIdentity.eventNodeId !== requestIdentity.eventNodeId
        || appliedIdentity.connectionId !== requestIdentity.connectionId
      ) {
        return
      }
      updateView(environmentId, view.attempt, {
        terminalError: isUnknownIdeFailure(error)
          ? 'terminal-result-unknown'
          : safeIdeError(error) || 'terminal-failed',
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
      || view.closeInFlight
    ) {
      return
    }
    void runTerminal(environmentId)
  }

  function bindingMatches(
    current: IdeViewState | undefined,
    expected: Pick<IdeViewState, 'viewId' | 'daemonInstanceId' | 'bindingEpoch'>,
  ): boolean {
    return current != null
      && current.viewId === expected.viewId
      && current.daemonInstanceId === expected.daemonInstanceId
      && current.bindingEpoch === expected.bindingEpoch
  }

  async function closeEnvironment(environmentId: string, discard: boolean): Promise<IdeCloseResult> {
    const view = viewsRef.current[environmentId]
    if (view == null) {
      return { ok: true, dirty: false }
    }
    if (
      view.bindingEpoch == null
      || view.daemonInstanceId == null
      || view.closeInFlight
      || view.status === 'binding'
      || view.status === 'reconciling'
    ) {
      return { ok: false, dirty: false, message: 'view-not-ready' }
    }
    updateView(environmentId, view.attempt, {
      closeInFlight: true,
      closeError: undefined,
    })
    try {
      await registerUsageNow(usageOverride(
        environmentId,
        view.viewId,
        view.claimId,
        view.bindingEpoch,
      ))
      const body: EnvironmentIdeCloseViewDTO = {
        browserSessionId: sessionRef.current.browserSessionId,
        bindingEpoch: view.bindingEpoch,
        expectedDaemonInstanceId: view.daemonInstanceId,
        discard,
      }
      await closeIdeView(environmentId, view.viewId, body)
      const current = viewsRef.current[environmentId]
      if (!bindingMatches(current, view)) {
        return { ok: false, dirty: false, message: 'binding-changed' }
      }
      const next = { ...viewsRef.current }
      delete next[environmentId]
      commitViews(next)
      writeSession()
      registerUsageQuiet()
      return { ok: true, dirty: false }
    } catch (error) {
      const current = viewsRef.current[environmentId]
      if (!bindingMatches(current, view)) {
        return { ok: false, dirty: false, message: 'binding-changed' }
      }
      const unknown = isUnknownIdeFailure(error) || ideFailureReason(error) === 'IDE_OUTCOME_UNKNOWN'
      const dirty = !discard && unknown
      const closeError = dirty
        ? undefined
        : unknown
          ? 'close-result-unknown'
          : safeIdeError(error) || 'close-failed'
      updateView(environmentId, view.attempt, {
        closeInFlight: false,
        closeError,
      })
      return { ok: false, dirty, message: safeIdeError(error) }
    }
  }

  function dismissCloseError(environmentId: string): void {
    const view = viewsRef.current[environmentId]
    if (view == null || view.closeError == null) {
      return
    }
    updateView(environmentId, view.attempt, { closeError: undefined })
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
      || view.daemonInstanceId == null
      || view.closeInFlight
    ) {
      return { ok: false, message: 'view-not-ready' }
    }
    const epoch = view.bindingEpoch
    const requestIdentity = identityRef.current
    try {
      await registerUsageNow(usageOverride(
        environmentId,
        view.viewId,
        view.claimId,
        epoch,
      ))
      const body: EnvironmentIdeChangeDirectoryDTO = {
        browserSessionId: sessionRef.current.browserSessionId,
        bindingEpoch: epoch,
        expectedDaemonInstanceId: view.daemonInstanceId,
        directory,
      }
      const response = await changeIdeViewDirectory(environmentId, view.viewId, body)
      const validated = validateCommandView(
        environmentId,
        sessionRef.current.browserSessionId,
        view.viewId,
        response,
      )
      if (
        validated.daemonInstanceId !== view.daemonInstanceId
        || compareDecimal(validated.bindingEpoch, epoch) < 0
        || (validated.workspacePath !== view.workspacePath
          && compareDecimal(validated.bindingEpoch, epoch) === 0)
      ) {
        throw new IdeProtocolError('Invalid IDE command response: binding regression')
      }
      const current = viewsRef.current[environmentId]
      const appliedIdentity = identityRef.current
      if (
        requestIdentity == null
        || appliedIdentity == null
        || appliedIdentity.eventNodeId !== requestIdentity.eventNodeId
        || appliedIdentity.connectionId !== requestIdentity.connectionId
      ) {
        if (appliedIdentity != null) {
          reconcile(environmentId, 'connection')
        }
        return { ok: false, message: 'binding-changed' }
      }
      if (bindingMatches(current, view)) {
        updateView(environmentId, view.attempt, {
          daemonInstanceId: validated.daemonInstanceId,
          bindingEpoch: validated.bindingEpoch,
          eventNodeId: validated.eventNodeId,
          connectionId: validated.connectionId,
          directory: validated.directory,
          workspacePath: validated.workspacePath,
        })
        writeSession()
        if (validated.bindingEpoch !== epoch) {
          reconcile(environmentId, 'directory', { allowRebind: true })
        }
      }
      return { ok: true }
    } catch (error) {
      const current = viewsRef.current[environmentId]
      if (bindingMatches(current, view)) {
        if (isIdeProtocolError(error)) {
          updateView(environmentId, view.attempt, {
            status: 'error',
            errorMessage: error.message,
          })
          return { ok: false, message: error.message }
        }
        const unknown = isUnknownIdeFailure(error)
          || ideFailureReason(error) === 'IDE_OUTCOME_UNKNOWN'
          || ideFailureReason(error) === 'STALE_BINDING'
        if (unknown) {
          reconcile(environmentId, 'directory', { allowRebind: false })
          return { ok: false, message: 'directory-result-unknown' }
        }
        return {
          ok: false,
          message: safeIdeError(error) || 'directory-failed',
        }
      }
      return { ok: false, message: 'binding-changed' }
    }
  }

  function handleIframeLoaded(environmentId: string, entryUrl: string): void {
    const before = viewsRef.current[environmentId]
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
    const next: Record<string, IdeViewState> = {}
    for (const [environmentId, view] of Object.entries(viewsRef.current)) {
      if (available.has(environmentId)) {
        next[environmentId] = view
      }
    }
    if (Object.keys(next).length !== Object.keys(viewsRef.current).length) {
      commitViews(next)
      writeSession()
    }
    const environmentMap = getEnvironmentMap()
    const previousReady = lastEnvironmentReadyRef.current
    for (const environmentId of available) {
      const ready = environmentMap.get(environmentId)?.ready === true
      if (ready && sessionReady() && identityRef.current != null) {
        const view = viewsRef.current[environmentId]
        if (view?.status === 'waiting-ready') {
          // Daemon 未就绪只能等新事件或显式重试；环境从离线转就绪才自动再对账。
          const wasReady = previousReady.get(environmentId)
          if (view.waitingReason !== 'daemon' || wasReady === false) {
            reconcile(environmentId, 'connection')
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
    for (const environmentId of getPresentation().openedEnvironmentIds) {
      const view = viewsRef.current[environmentId]
      if (view != null && view.status !== 'detached' && view.status !== 'gone') {
        void reconcile(environmentId, 'restore')
      }
    }
  }

  function resetForClone(): void {
    viewsRef.current = {}
    setViews({})
    revisionRef.current = 0n
    lastIdentityRef.current = null
    snapshotCacheRef.current.clear()
    operationQueueRef.current = []
    operationGenerationRef.current += 1
    bindingRef.current = false
    operationConnectionRef.current = null
    pageSuspendedRef.current = false
  }

  function withdrawUsage(): void {
    if (identityRef.current == null || !sessionReady()) {
      return
    }
    pageSuspendedRef.current = true
    const browserSessionId = sessionRef.current.browserSessionId
    void events.registerUsage({
      browserSessionId,
      revision: nextRevision(),
      views: [],
    }).catch(() => {
      // pagehide/断线后发送失败是预期路径；Daemon 按最后有效证明回收。
    })
  }

  function restoreUsage(): void {
    pageSuspendedRef.current = false
    if (identityRef.current != null && sessionReady()) {
      registerUsageQuiet()
    }
  }

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
    operationGenerationRef.current += 1
    bindingRef.current = false
    operationConnectionRef.current = null
    operationQueueRef.current = []
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
    const view = viewsRef.current[activeEnvironmentId]
    if (view == null || view.status === 'detached' || view.status === 'gone') {
      return
    }
    if (
      view.status === 'ready'
      && isBoundToConnection(view, currentIdentity.eventNodeId, currentIdentity.connectionId)
    ) {
      registerUsageQuiet()
      maybeRunPendingShell(activeEnvironmentId)
      return
    }
    reconcile(activeEnvironmentId, 'focus')
    // activeEnvironmentId 切换只做聚焦/对账，不在没有 view 时自动创建。
  }, [activeEnvironmentId, session.browserSessionId, session.status])

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
    resetForClone,
    withdrawUsage,
    restoreUsage,
    reconcileAll,
  }
}
