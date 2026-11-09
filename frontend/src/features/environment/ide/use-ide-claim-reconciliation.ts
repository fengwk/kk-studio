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
  EnvironmentIdeOpenViewDTO,
  EnvironmentIdeReadViewDTO,
  EnvironmentIdeViewBindingDTO,
  EnvironmentIdeViewDTO,
  EnvironmentIdeViewSnapshotDTO,
} from '@/shared/api/generated/contracts'
import { createUuid, isCanonicalUuid } from '@/shared/lib/uuid'
import type { IdeActionMode, IdeEnvironmentInfo } from '@/features/environment/ide/ide-panel-context'
import { validateIdeEntryUrl } from '@/features/environment/ide/ide-url'
import {
  isIdeDirectory,
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
  buildUsageViews,
  initialViews,
  isBoundToConnection,
  POSITIVE_DECIMAL,
  recoveryFromViews,
  type IdeViewState,
  type IdeViewStatus,
} from '@/features/environment/ide/ide-view-state'
import {
  failureStatus,
  ideFailureReason,
  isDaemonNotReady,
  isOutcomeUnknown,
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
  daemonInstanceId: string
  revision: string
  fingerprint: string
}

interface ReconcileOptions {
  allowRebind: boolean
}

function compareDecimal(left: string, right: string): number {
  if (left.length !== right.length) {
    return left.length < right.length ? -1 : 1
  }
  return left < right ? -1 : left > right ? 1 : 0
}

function viewFingerprint(view: EnvironmentIdeViewDTO | null): string {
  if (view == null) {
    return 'absent'
  }
  return [
    view.viewId,
    view.environmentId,
    view.browserSessionId,
    view.eventNodeId,
    view.connectionId,
    view.claimId,
    view.daemonInstanceId,
    view.bindingEpoch,
    view.directory,
    view.workspacePath,
    view.state,
  ].join('|')
}

function isViewDto(value: unknown): value is EnvironmentIdeViewDTO {
  return value != null && typeof value === 'object'
}

function validateSnapshot(
  environmentId: string,
  viewId: string,
  browserSessionId: string,
  snapshot: EnvironmentIdeViewSnapshotDTO,
): { daemonInstanceId: string; revision: string; view: EnvironmentIdeViewDTO | null } {
  if (
    snapshot == null
    || typeof snapshot !== 'object'
    || !isCanonicalUuid(snapshot.daemonInstanceId)
    || typeof snapshot.revision !== 'string'
    || !/^(0|[1-9][0-9]*)$/.test(snapshot.revision)
    || (snapshot.view !== null && !isViewDto(snapshot.view))
  ) {
    throw new Error('Invalid IDE view snapshot')
  }
  if (snapshot.view == null) {
    return {
      daemonInstanceId: snapshot.daemonInstanceId,
      revision: snapshot.revision,
      view: null,
    }
  }
  const view = snapshot.view
  if (
    view.viewId !== viewId
    || view.environmentId !== environmentId
    || view.browserSessionId !== browserSessionId
    || view.daemonInstanceId !== snapshot.daemonInstanceId
    || !isCanonicalUuid(view.daemonInstanceId)
    || typeof view.bindingEpoch !== 'string'
    || !POSITIVE_DECIMAL.test(view.bindingEpoch)
    || !isIdeDirectory(view.directory)
    || (view.state !== 'OPEN' && view.state !== 'CLOSING')
  ) {
    throw new Error('Invalid IDE view snapshot')
  }
  return {
    daemonInstanceId: snapshot.daemonInstanceId,
    revision: snapshot.revision,
    view,
  }
}

function validateOpenResponse(
  environmentId: string,
  browserSessionId: string,
  pending: IdePendingClaim,
  response: EnvironmentIdeViewDTO,
): {
  viewId: string
  daemonInstanceId: string
  bindingEpoch: string
  directory: string
  workspacePath: string
  entryUrl: string
} {
  if (
    response == null
    || typeof response !== 'object'
    || response.viewId !== pending.viewId
    || response.environmentId !== environmentId
    || response.browserSessionId !== browserSessionId
    || response.claimId !== pending.claimId
    || response.daemonInstanceId !== pending.expectedDaemonInstanceId
    || !isCanonicalUuid(response.daemonInstanceId)
    || typeof response.bindingEpoch !== 'string'
    || !POSITIVE_DECIMAL.test(response.bindingEpoch)
    || (pending.mode === 'resume'
      && compareDecimal(response.bindingEpoch, pending.expectedBindingEpoch) < 0)
    || !isIdeDirectory(response.directory)
    || typeof response.workspacePath !== 'string'
    || response.workspacePath.length === 0
    || response.state !== 'OPEN'
  ) {
    throw new Error('Invalid IDE binding response')
  }
  return {
    viewId: response.viewId,
    daemonInstanceId: response.daemonInstanceId,
    bindingEpoch: response.bindingEpoch,
    directory: response.directory,
    workspacePath: response.workspacePath,
    entryUrl: validateIdeEntryUrl(response.entryUrl, environmentId, pending.viewId),
  }
}

function validateCommandView(
  environmentId: string,
  browserSessionId: string,
  viewId: string,
  response: EnvironmentIdeViewDTO,
): {
  bindingEpoch: string
  daemonInstanceId: string
  directory: string
  workspacePath: string
  eventNodeId?: string
  connectionId?: string
} {
  if (
    response == null
    || typeof response !== 'object'
    || response.viewId !== viewId
    || response.environmentId !== environmentId
    || response.browserSessionId !== browserSessionId
    || !isCanonicalUuid(response.daemonInstanceId)
    || typeof response.bindingEpoch !== 'string'
    || !POSITIVE_DECIMAL.test(response.bindingEpoch)
    || !isIdeDirectory(response.directory)
    || response.state !== 'OPEN'
  ) {
    throw new Error('Invalid IDE command response')
  }
  return {
    bindingEpoch: response.bindingEpoch,
    daemonInstanceId: response.daemonInstanceId,
    directory: response.directory,
    workspacePath: typeof response.workspacePath === 'string' ? response.workspacePath : response.directory,
    eventNodeId: response.eventNodeId,
    connectionId: response.connectionId,
  }
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
  const operationQueueRef = useRef<Array<() => Promise<void>>>([])
  const sessionRef = useRef(session)
  const lastIdentityRef = useRef<string | null>(null)
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

  /** 连接内只允许一个在途绑定操作；后续对账串行执行，不并发抢占。 */
  function runExclusive(task: () => Promise<void>): void {
    if (bindingRef.current) {
      operationQueueRef.current.push(task)
      return
    }
    bindingRef.current = true
    void (async () => {
      try {
        await task()
      } finally {
        bindingRef.current = false
        const next = operationQueueRef.current.shift()
        if (next != null && mountedRef.current) {
          runExclusive(next)
        }
      }
    })()
  }

  function setWaitingReady(environmentId: string, attempt: number): void {
    updateView(environmentId, attempt, {
      status: 'waiting-ready',
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

  function applyFailure(
    environmentId: string,
    attempt: number,
    error: unknown,
    retainPending: boolean,
  ): { status: IdeViewStatus; message?: string } {
    const mapped = failureStatus(error)
    if (!retainPending && mapped.status !== 'reconciling') {
      updateView(environmentId, attempt, { pending: undefined })
    }
    updateView(environmentId, attempt, {
      status: mapped.status,
      errorMessage: mapped.message,
      closeInFlight: false,
    })
    writeSession()
    return mapped
  }

  function handleIdentityFailure(): void {
    if (identityRef.current != null) {
      actionsRef.current?.reconcileAll()
    }
  }

  async function failOpen(
    environmentId: string,
    attempt: number,
    error: unknown,
    options: ReconcileOptions,
  ): Promise<void> {
    if (!mountedRef.current) {
      return
    }
    const reason = ideFailureReason(error)
    if (reason === 'DAEMON_NOT_READY') {
      setWaitingReady(environmentId, attempt)
      registerUsageQuiet()
      return
    }
    if (
      (reason === 'STALE_BINDING' || reason === 'IDE_OUTCOME_UNKNOWN' || isOutcomeUnknown(error))
      && options.allowRebind
    ) {
      updateView(environmentId, attempt, {
        status: 'reconciling',
        errorMessage: undefined,
      })
      await reconcileNow(environmentId, 'outcome-unknown', { allowRebind: false })
      return
    }
    const mapped = applyFailure(environmentId, attempt, error, false)
    if (mapped.status === 'detached' || mapped.status === 'gone' || mapped.status === 'error') {
      registerUsageQuiet()
    }
    if (isUnknownIdeFailure(error)) {
      const current = viewsRef.current[environmentId]
      if (current != null && current.attempt === attempt) {
        updateView(environmentId, attempt, {
          status: 'error',
          errorMessage: mapped.message || safeIdeError(error),
        })
      }
    }
    if (identityRef.current == null) {
      handleIdentityFailure()
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
    if (currentIdentity == null || !sessionReady() || pageSuspendedRef.current) {
      setWaitingReady(environmentId, attempt)
      return
    }
    const request: EnvironmentIdeOpenViewDTO = {
      viewId: pending.viewId,
      browserSessionId: sessionRef.current.browserSessionId,
      eventNodeId: currentIdentity.eventNodeId,
      connectionId: currentIdentity.connectionId,
      claimId: pending.claimId,
      mode: pending.mode,
      expectedBindingEpoch: pending.expectedBindingEpoch,
      expectedDaemonInstanceId: pending.expectedDaemonInstanceId,
      ...(pending.mode === 'create' && pending.directory != null
        ? { directory: pending.directory }
        : {}),
    }
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
      if (error instanceof Error && error.message.startsWith('Invalid IDE')) {
        updateView(environmentId, attempt, {
          status: 'error',
          errorMessage: error.message,
        })
        return
      }
      await failOpen(environmentId, attempt, error, options)
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
      if (identityRef.current?.connectionId !== currentIdentity.connectionId) {
        setWaitingReady(environmentId, attempt)
        handleIdentityFailure()
        return
      }
      applyFailure(environmentId, attempt, error, false)
      return
    }
    if (!mountedRef.current) {
      return
    }
    if (identityRef.current?.connectionId !== currentIdentity.connectionId) {
      setWaitingReady(environmentId, attempt)
      handleIdentityFailure()
      return
    }
    const query: EnvironmentIdeReadViewDTO = {
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
      const accepted = acceptSnapshot(environmentId, viewId, snapshot)
      if (!accepted.accepted) {
        setWaitingReady(environmentId, attempt)
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
      if (!mountedRef.current) {
        return
      }
      if (error instanceof Error && error.message.startsWith('Invalid IDE')) {
        updateView(environmentId, attempt, {
          status: 'error',
          errorMessage: error.message,
        })
        return
      }
      if (isViewGone(error)) {
        updateView(environmentId, attempt, {
          status: 'gone',
          errorMessage: undefined,
          entryUrl: undefined,
          pending: undefined,
        })
        writeSession()
        return
      }
      if (isDaemonNotReady(error)) {
        setWaitingReady(environmentId, attempt)
        return
      }
      const reason = ideFailureReason(error)
      if (reason === 'VIEW_OWNED_BY_OTHER_SESSION') {
        updateView(environmentId, attempt, {
          status: 'detached',
          errorMessage: undefined,
          pending: undefined,
        })
        writeSession()
        return
      }
      updateView(environmentId, attempt, {
        status: 'error',
        errorMessage: safeIdeError(error),
      })
      writeSession()
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
    const validated = validateSnapshot(
      environmentId,
      viewId,
      sessionRef.current.browserSessionId,
      snapshot,
    )
    const cached = snapshotCacheRef.current.get(environmentId)
    if (cached != null && cached.daemonInstanceId === validated.daemonInstanceId) {
      const compare = compareDecimal(validated.revision, cached.revision)
      if (compare < 0) {
        return {
          accepted: false,
          daemonInstanceId: cached.daemonInstanceId,
          view: null,
        }
      }
      if (compare === 0 && cached.fingerprint !== viewFingerprint(validated.view)) {
        throw new Error('Invalid IDE view snapshot: conflicting revision')
      }
    }
    snapshotCacheRef.current.set(environmentId, {
      daemonInstanceId: validated.daemonInstanceId,
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
      if (identityRef.current?.connectionId !== currentIdentity.connectionId) {
        setWaitingReady(environmentId, attempt)
        handleIdentityFailure()
        return
      }
      applyFailure(environmentId, attempt, error, true)
      return
    }
    if (!mountedRef.current) {
      return
    }
    if (identityRef.current?.connectionId !== currentIdentity.connectionId) {
      setWaitingReady(environmentId, attempt)
      handleIdentityFailure()
      return
    }
    const query: EnvironmentIdeReadViewDTO = {
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
      const accepted = acceptSnapshot(environmentId, viewId, snapshot)
      if (!accepted.accepted) {
        const latest = viewsRef.current[environmentId]
        if (latest?.status === 'ready') {
          return
        }
        throw new Error('Invalid IDE view snapshot: stale revision')
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
      if (!mountedRef.current) {
        return
      }
      if (error instanceof Error && error.message.startsWith('Invalid IDE')) {
        updateView(environmentId, attempt, {
          status: 'error',
          errorMessage: error.message,
        })
        return
      }
      if (isDaemonNotReady(error)) {
        setWaitingReady(environmentId, attempt)
        return
      }
      if (isViewGone(error)) {
        updateView(environmentId, attempt, {
          status: 'gone',
          errorMessage: undefined,
          entryUrl: undefined,
          pending: undefined,
        })
        writeSession()
        return
      }
      const reason = ideFailureReason(error)
      if (reason === 'VIEW_OWNED_BY_OTHER_SESSION') {
        updateView(environmentId, attempt, {
          status: 'detached',
          errorMessage: undefined,
          pending: undefined,
        })
        writeSession()
        return
      }
      if ((reason === 'STALE_BINDING' || isOutcomeUnknown(error)) && options.allowRebind) {
        await reconcileNow(environmentId, 'read-stale', { allowRebind: false })
        return
      }
      updateView(environmentId, attempt, {
        status: 'error',
        errorMessage: isUnknownIdeFailure(error)
          ? undefined
          : safeIdeError(error),
      })
      writeSession()
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
      if (current?.bindingEpoch !== view.bindingEpoch) {
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
      if (current?.viewId !== view.viewId || current.bindingEpoch !== view.bindingEpoch) {
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
      if (current?.viewId !== view.viewId || current.bindingEpoch !== view.bindingEpoch) {
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
      const current = viewsRef.current[environmentId]
      if (
        current != null
        && current.bindingEpoch === epoch
        && current.viewId === view.viewId
      ) {
        updateView(environmentId, view.attempt, {
          daemonInstanceId: validated.daemonInstanceId,
          bindingEpoch: validated.bindingEpoch,
          directory: validated.directory,
          workspacePath: validated.workspacePath,
        })
        writeSession()
        if (validated.bindingEpoch !== epoch) {
          void reconcile(environmentId, 'directory', { allowRebind: true })
        }
      }
      return { ok: true }
    } catch (error) {
      const current = viewsRef.current[environmentId]
      if (current != null && current.bindingEpoch === epoch) {
        if (isOutcomeUnknown(error) || ideFailureReason(error) === 'STALE_BINDING') {
          void reconcile(environmentId, 'directory', { allowRebind: false })
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
    if (!sessionReady() || identityRef.current == null) {
      return
    }
    for (const environmentId of getPresentation().openedEnvironmentIds) {
      const view = viewsRef.current[environmentId]
      if (view == null) {
        continue
      }
      if (
        (view.status === 'waiting-ready' || (view.status === 'reconciling' && view.attempt === 0))
        && getEnvironmentMap().get(environmentId)?.ready === true
      ) {
        void reconcile(environmentId, 'connection')
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
    bindingRef.current = false
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
    if (activeEnvironmentId == null || !sessionReady() || identity == null) {
      return
    }
    const view = viewsRef.current[activeEnvironmentId]
    if (view == null || view.status === 'detached' || view.status === 'gone') {
      return
    }
    if (
      view.status === 'ready'
      && isBoundToConnection(view, identity.eventNodeId, identity.connectionId)
    ) {
      registerUsageQuiet()
      maybeRunPendingShell(activeEnvironmentId)
      return
    }
    void reconcile(activeEnvironmentId, 'focus')
    // activeEnvironmentId 切换只做聚焦/对账，不在没有 view 时自动创建。
  }, [activeEnvironmentId, identity, session.browserSessionId, session.status])

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
