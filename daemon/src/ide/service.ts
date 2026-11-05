import { rm } from 'node:fs/promises'
import type {
  DaemonConfig,
  IdeCommand,
  IdeReply,
  IdeService,
  IdeStreamRequest,
  IdeStreamUpstream,
  IdeViewSummary,
  ProcessSupervisor,
} from '../contracts.js'
import { writePrivateFile } from '../native/index.js'
import { LONG_MAX } from '../validation-constants.js'
import { createIdeBridge, type IdeBridge } from './bridge.js'
import {
  createCodeServerManager,
  idePaths,
  type CodeServerManager,
  type CodeServerSession,
} from './codeServer.js'
import { abortError, IdeError, failureReply } from './errors.js'
import { createClosePath } from './serviceClose.js'
import { createCommandHandler } from './serviceCommands.js'
import { createViewLease } from './serviceLease.js'
import { createViewRegistry } from './serviceRegistry.js'
import type { ActiveStream, ClaimRecord, ViewRecord } from './serviceTypes.js'
import {
  RecoveryQuotaError,
  canonicalDirectory,
  readRecoveryBackup,
  validateBindingEpoch,
  validateViewId,
  workspaceFileContent,
} from './workspace.js'

/**
 * Managed code-server IDE lifecycle for one Environment.
 *
 * <p>All view claims, document exclusivity and binding epochs are decided in this process under
 * a per-view lock. A claim reservation counts toward the fixed view quota before any process or
 * filesystem work starts, so concurrent opens cannot oversubscribe one Environment. Usage proof
 * never creates or extends a view beyond its own epoch: Runtime supplies the local monotonic time
 * at which the lease round was sent; old binding epochs cannot be renewed.
 */

const IDE_MAX_VIEWS = 64
export const IDE_PROOF_EXCLUSIVITY_MILLIS = 45_000
export const IDE_VIEW_EXPIRY_MILLIS = 600_000
export const IDE_IDLE_STOP_MILLIS = 60_000
const IDE_CLOSE_BUDGET_MILLIS = 10_000
const DEFAULT_RECOVERY_QUOTA_BYTES = 16 * 1024 * 1024
const MAX_COMMAND_TEXT_CHARS = 4096
const MAX_STREAM_PATH_BYTES = 8192
const MAX_STREAM_HEADERS = 100
const MAX_STREAM_HEADER_BYTES = 16 * 1024
const HEADER_NAME_PATTERN = /^[!#$%&'*+.^_`|~0-9A-Za-z-]+$/
const STREAM_METHOD_PATTERN = /^[A-Za-z!#$%&'*+.^_`|~-]{1,32}$/
const PROTOCOL_TOKEN = /^[A-Za-z0-9!#$%&'*+.^_`|~-]+$/
const DROP_UPSTREAM_HEADERS = new Set([
  'host',
  'connection',
  'keep-alive',
  'proxy-authenticate',
  'proxy-authorization',
  'te',
  'trailer',
  'transfer-encoding',
  'upgrade',
  'cookie',
  'authorization',
  'origin',
  'referer',
  'sec-websocket-key',
  'sec-websocket-version',
  'sec-websocket-extensions',
])

export interface IdeScheduler {
  now(): number
  setTimeout(callback: () => void, delayMillis: number): unknown
  clearTimeout(handle: unknown): void
}

export interface IdeServiceEvent {
  kind: 'backup-failed' | 'backup-incomplete' | 'cleanup-failed' | 'idle-stop-failed' | 'process-exit' | 'expiry-deferred'
  viewId?: string
  message: string
}

export interface IdeServiceOptions {
  bridge?: IdeBridge
  manager?: CodeServerManager
  scheduler?: IdeScheduler
  onEvent?: (event: IdeServiceEvent) => void
  recoveryQuotaBytes?: number
  maxViews?: number
}

const systemScheduler: IdeScheduler = {
  now: () => performance.now(),
  setTimeout: (callback, delayMillis) => setTimeout(callback, delayMillis),
  clearTimeout: (handle) => clearTimeout(handle as NodeJS.Timeout),
}

function waitWithSignal<T>(promise: Promise<T>, signal: AbortSignal): Promise<T> {
  if (signal.aborted) {
    return Promise.reject(abortError())
  }
  return new Promise<T>((resolve, reject) => {
    const onAbort = (): void => reject(abortError())
    signal.addEventListener('abort', onAbort, { once: true })
    promise.then(
      (value) => {
        signal.removeEventListener('abort', onAbort)
        resolve(value)
      },
      (error: unknown) => {
        signal.removeEventListener('abort', onAbort)
        reject(error)
      },
    )
  })
}

function required<T>(value: T | undefined, field: string, code = 'UNAVAILABLE'): T {
  if (value === undefined) {
    throw new IdeError(`${field} is required`, code)
  }
  return value
}

function boundedText(value: string | undefined, field: string): string | undefined {
  if (value === undefined) {
    return undefined
  }
  if (value.trim() === '' || value.length > MAX_COMMAND_TEXT_CHARS || /[\u0000-\u001f\u007f]/.test(value)) {
    throw new IdeError(`${field} is invalid`)
  }
  return value
}

function summary(view: ViewRecord): IdeViewSummary {
  return {
    viewId: view.viewId,
    documentId: view.documentId,
    eventNodeId: view.eventNodeId,
    connectionId: view.connectionId,
    claimId: view.claimId,
    bindingEpoch: view.bindingEpoch,
    directory: view.directory,
    workspacePath: view.workspacePath,
    state: view.state,
  }
}

function upstreamPath(request: IdeStreamRequest, viewId: string, bindingEpoch: string): string {
  if (request.mode !== 'HTTP' && request.mode !== 'WS') {
    throw new IdeError('stream mode is invalid')
  }
  if (!STREAM_METHOD_PATTERN.test(request.method)) {
    throw new IdeError('stream method is invalid')
  }
  if (
    typeof request.path !== 'string' ||
    !request.path.startsWith('/') ||
    request.path.startsWith('//') ||
    request.path.includes('\\') ||
    request.path.length > MAX_STREAM_PATH_BYTES ||
    Buffer.byteLength(request.path, 'utf8') > MAX_STREAM_PATH_BYTES ||
    /[\u0000-\u001f\u007f]/.test(request.path)
  ) {
    throw new IdeError('stream path is invalid')
  }
  for (const segment of request.path.split('/')) {
    const normalized = segment.toLowerCase()
    if (
      segment === '.' ||
      segment === '..' ||
      normalized === '%2e' ||
      normalized === '%2e%2e' ||
      normalized === '.%2e' ||
      normalized === '%2e.'
    ) {
      throw new IdeError('stream path is invalid')
    }
  }
  const base = `/__kk/ide/view/${viewId}/${bindingEpoch}`
  return request.path === '/' ? `${base}/` : `${base}${request.path}`
}

function upstreamHeaders(headers: [string, string][]): { headers: [string, string][]; protocols: string[] } {
  if (!Array.isArray(headers) || headers.length > MAX_STREAM_HEADERS) {
    throw new IdeError('stream headers are invalid')
  }
  const filtered: [string, string][] = []
  let protocols: string[] = []
  let bytes = 0
  for (const entry of headers) {
    if (!Array.isArray(entry) || entry.length !== 2 || typeof entry[0] !== 'string' || typeof entry[1] !== 'string') {
      throw new IdeError('stream headers are invalid')
    }
    const [name, value] = entry
    if (!HEADER_NAME_PATTERN.test(name) || name.length > 256 || /[\u0000-\u0008\u000a-\u001f\u007f]/.test(value)) {
      throw new IdeError('stream headers are invalid')
    }
    bytes += Buffer.byteLength(name, 'utf8') + Buffer.byteLength(value, 'utf8')
    if (bytes > MAX_STREAM_HEADER_BYTES) {
      throw new IdeError('stream headers are invalid')
    }
    const key = name.toLowerCase()
    if (key === 'sec-websocket-protocol') {
      protocols = value
        .split(',')
        .map((token) => token.trim())
        .filter((token) => token.length > 0 && PROTOCOL_TOKEN.test(token))
      continue
    }
    if (DROP_UPSTREAM_HEADERS.has(key)) {
      continue
    }
    filtered.push([name, value])
  }
  return { headers: filtered, protocols }
}

export async function createIdeService(
  config: DaemonConfig,
  processes: ProcessSupervisor,
  options: IdeServiceOptions = {},
): Promise<IdeService> {
  const paths = idePaths(config)
  const scheduler = options.scheduler ?? systemScheduler
  const maxViews = options.maxViews ?? IDE_MAX_VIEWS
  const recoveryQuotaBytes = options.recoveryQuotaBytes ?? DEFAULT_RECOVERY_QUOTA_BYTES
  const bridge = options.bridge ?? (await createIdeBridge())
  const manager =
    options.manager ??
    createCodeServerManager(config, processes, {
      url: bridge.url,
      token: bridge.secret,
    })
  const views = new Map<string, ViewRecord>()
  const reservations = new Set<string>()
  const claims = new Map<string, ClaimRecord>()
  const activeStreams = new Set<ActiveStream>()
  const viewLocks = new Map<string, Promise<void>>()
  const claimLocks = new Map<string, Promise<void>>()
  const lifetime = new AbortController()
  let closed = false
  let nextEpoch = 1n
  let idleTimer: unknown
  let managerStop: Promise<void> | undefined
  let crashEpoch = 0
  let crashRecovery: Promise<void> | undefined
  let crashUnconverged = false

  const emit = (event: IdeServiceEvent): void => {
    try {
      options.onEvent?.(event)
    } catch {
      // Diagnostics must not alter lifecycle facts.
    }
  }

  const allocateEpoch = (): string => {
    if (nextEpoch > LONG_MAX) {
      throw new IdeError('bindingEpoch space is exhausted', 'BUSY')
    }
    const value = nextEpoch.toString()
    nextEpoch++
    return value
  }

  const withLock = <T>(locks: Map<string, Promise<void>>, key: string, task: () => Promise<T>): Promise<T> => {
    const previous = locks.get(key) ?? Promise.resolve()
    const next = previous.then(task, task)
    const settled = next.then(
      () => undefined,
      () => undefined,
    )
    locks.set(key, settled)
    void settled.then(() => {
      if (locks.get(key) === settled) {
        locks.delete(key)
      }
    })
    return next
  }

  const withViewLock = <T>(viewId: string, task: () => Promise<T>): Promise<T> => withLock(viewLocks, viewId, task)

  /** claims are global: the same claimId must never create two bindings under different view locks. */
  const withClaimLock = <T>(claimId: string, task: () => Promise<T>): Promise<T> => withLock(claimLocks, claimId, task)

  const lease = createViewLease(views, scheduler, IDE_VIEW_EXPIRY_MILLIS, () => closed, (id, epoch) => expireView(id, epoch))
  const { clearTimer, scheduleExpiry } = lease

  const cancelIdle = (): void => {
    if (idleTimer !== undefined) {
      scheduler.clearTimeout(idleTimer)
      idleTimer = undefined
    }
  }

  const stopManager = (): Promise<void> => {
    if (managerStop === undefined) {
      const operation = manager.stop()
      managerStop = operation
      void operation
        .finally(() => {
          if (managerStop === operation) {
            managerStop = undefined
          }
        })
        .catch(() => undefined)
    }
    return managerStop
  }

  const scheduleIdleStop = (): void => {
    if (
      closed ||
      idleTimer !== undefined ||
      views.size > 0 ||
      reservations.size > 0 ||
      activeStreams.size > 0 ||
      managerStop !== undefined
    ) {
      return
    }
    idleTimer = scheduler.setTimeout(() => {
      idleTimer = undefined
      void stopIdle()
    }, IDE_IDLE_STOP_MILLIS)
  }

  const stopIdle = async (): Promise<void> => {
    if (closed || views.size > 0 || reservations.size > 0 || activeStreams.size > 0) {
      scheduleIdleStop()
      return
    }
    try {
      await stopManager()
    } catch {
      emit({ kind: 'idle-stop-failed', message: 'idle code-server stop failed' })
      // A failed stop leaves the runtime alive; retry after another idle interval rather than
      // pretending it was released.
      scheduleIdleStop()
    }
  }

  const awaitManagerStopped = async (signal: AbortSignal): Promise<void> => {
    if (managerStop !== undefined) {
      await waitWithSignal(managerStop, signal)
    }
  }

  const awaitCrashRecovery = async (signal: AbortSignal): Promise<void> => {
    if (crashRecovery !== undefined) {
      await waitWithSignal(crashRecovery, signal)
    }
  }

  /** Removes records whose process generation is gone; user files are never touched. */
  const releaseInvalidatedViews = async (records: Iterable<ViewRecord>): Promise<void> => {
    for (const view of records) {
      if (views.get(view.viewId) !== view) {
        continue
      }
      clearTimer(view)
      dropClaimsForView(view.viewId)
      bridge.revoke(view.viewId, view.bindingEpoch)
      if (view.workspaceOwned) {
        try {
          await rm(view.workspacePath, { force: true })
        } catch {
          emit({ kind: 'cleanup-failed', viewId: view.viewId, message: 'managed workspace could not be removed' })
        }
      }
      if (views.get(view.viewId) === view) {
        views.delete(view.viewId)
      }
    }
    scheduleIdleStop()
  }

  /** Retries a crash cleanup that previously failed before any replacement instance is started. */
  const convergeAfterCrash = async (): Promise<void> => {
    if (!crashUnconverged) {
      return
    }
    try {
      await stopManager()
    } catch {
      emit({ kind: 'cleanup-failed', message: 'code-server process scope did not converge after exit' })
      throw new IdeError('code-server cleanup did not converge after exit', 'CLEANUP_FAILED')
    }
    crashUnconverged = false
    await releaseInvalidatedViews([...views.values()])
  }

  const releaseStream = (stream: ActiveStream): void => {
    if (stream.released) {
      return
    }
    stream.released = true
    activeStreams.delete(stream)
    const view = views.get(stream.viewId)
    if (view !== undefined && view.bindingEpoch === stream.bindingEpoch) {
      view.streams.delete(stream)
    }
    stream.resolveClosed()
    if (activeStreams.size === 0) {
      scheduleIdleStop()
    }
  }

  const abortViewStreams = (view: ViewRecord): void => {
    for (const stream of [...view.streams]) {
      stream.controller.abort()
    }
  }

  const waitForViewStreams = async (view: ViewRecord): Promise<void> => {
    abortViewStreams(view)
    if (view.streams.size === 0) {
      return
    }
    const closed = Promise.all([...view.streams].map((stream) => stream.closed))
    let timer: unknown
    try {
      await Promise.race([
        closed,
        new Promise<void>((_resolve, reject) => {
          timer = scheduler.setTimeout(
            () => reject(new IdeError('ide data streams did not close before the cleanup budget', 'CLEANUP_FAILED')),
            IDE_CLOSE_BUDGET_MILLIS,
          )
        }),
      ])
    } finally {
      if (timer !== undefined) {
        scheduler.clearTimeout(timer)
      }
    }
  }


  const { openView, requireBoundView, dropClaimsForView } = createViewRegistry({
    paths, views, reservations, claims, scheduler, bridge, manager, maxViews,
    proofExclusivityMillis: IDE_PROOF_EXCLUSIVITY_MILLIS, closeBudgetMillis: IDE_CLOSE_BUDGET_MILLIS,
    getCrashEpoch: () => crashEpoch, emit, withViewLock, withClaimLock, scheduleExpiry, scheduleIdleStop,
    cancelIdle, allocateEpoch, awaitCrashRecovery, awaitManagerStopped, convergeAfterCrash, abortViewStreams,
    required, boundedText,
  })

  const { closeView, expireView } = createClosePath({
    config, views, scheduler, bridge, manager, lifetime, recoveryQuotaBytes,
    closeBudgetMillis: IDE_CLOSE_BUDGET_MILLIS, expiryMillis: IDE_VIEW_EXPIRY_MILLIS,
    emit, withViewLock, clearTimer, scheduleExpiry, dropClaimsForView, scheduleIdleStop,
    waitForViewStreams, waitWithSignal, required, requireBoundView,
  })

  const changeDirectory = (command: IdeCommand, signal: AbortSignal): Promise<IdeReply> => {
    const viewId = validateViewId(required(command.viewId, 'viewId'))
    return withViewLock(viewId, async () => {
      const view = requireBoundView(command, { requireOpen: true })
      const directory = await canonicalDirectory(required(command.directory, 'directory'))
      const result = await bridge.request(
        view.viewId,
        { op: 'changeDirectory', directory },
        signal,
        { expectedDirectory: directory, expectedEpoch: view.bindingEpoch },
      )
      if (!result.ok) {
        throw new IdeError(result.message ?? 'directory change was rejected', result.code ?? 'UNAVAILABLE')
      }
      const current = views.get(view.viewId)
      if (
        current !== view ||
        current.state !== 'OPEN' ||
        current.bindingEpoch !== view.bindingEpoch ||
        current.documentId !== view.documentId
      ) {
        throw new IdeError('view binding changed', 'VIEW_GONE')
      }
      await writePrivateFile(view.workspacePath, workspaceFileContent(directory))
      view.directory = directory
      return { ok: true, viewId: view.viewId, directory: view.directory }
    })
  }

  const focusTerminal = (command: IdeCommand, signal: AbortSignal): Promise<IdeReply> => {
    if (!processes.trackDescendant) {
      return Promise.resolve(failureReply(new IdeError('terminal supervision is unavailable')))
    }
    const viewId = validateViewId(required(command.viewId, 'viewId'))
    return withViewLock(viewId, async () => {
      const view = requireBoundView(command, { requireOpen: true })
      if (!bridge.isConnected(view.viewId, view.bindingEpoch)) {
        await bridge.waitForConnection(view.viewId, signal)
      }
      const current = views.get(view.viewId)
      if (
        current !== view ||
        current.state !== 'OPEN' ||
        current.bindingEpoch !== view.bindingEpoch ||
        current.documentId !== view.documentId
      ) {
        throw new IdeError('view binding changed', 'VIEW_GONE')
      }
      const result = await bridge.request(
        view.viewId,
        { op: 'focusTerminal' },
        signal,
        { expectedEpoch: view.bindingEpoch },
      )
      if (!result.ok) {
        throw new IdeError(result.message ?? 'terminal could not be focused', result.code ?? 'UNAVAILABLE')
      }
      const after = views.get(view.viewId)
      if (after !== view || after.bindingEpoch !== view.bindingEpoch || after.documentId !== view.documentId) {
        throw new IdeError('view binding changed', 'VIEW_GONE')
      }
      return { ok: true, viewId: view.viewId }
    })
  }

  const collapseMobile = (command: IdeCommand, signal: AbortSignal): Promise<IdeReply> => {
    const viewId = validateViewId(required(command.viewId, 'viewId'))
    return withViewLock(viewId, async () => {
      const view = requireBoundView(command, { requireOpen: true })
      const epoch = view.bindingEpoch
      if (!bridge.isConnected(viewId, epoch)) {
        await bridge.waitForConnection(viewId, signal)
      }
      if (views.get(viewId) !== view || view.state !== 'OPEN' || view.bindingEpoch !== epoch) {
        throw new IdeError('view binding changed', 'VIEW_GONE')
      }
      const result = await bridge.request(viewId, { op: 'collapseMobile' }, signal, { expectedEpoch: epoch })
      if (!result.ok) {
        throw new IdeError(result.message ?? 'mobile layout could not be applied', result.code ?? 'UNAVAILABLE')
      }
      return { ok: true, viewId }
    })
  }

  const listViews = (): IdeReply => {
    const values = [...views.values()].map(summary)
    return { ok: true, views: values }
  }

  const handle = createCommandHandler({
    isClosed: () => closed, awaitCrashRecovery, openView, changeDirectory, focusTerminal, closeView,
    collapseMobile, listViews, boundedText,
  })

  const getViewState = (viewId: string): IdeViewSummary | null => {
    let normalized: string
    try {
      normalized = validateViewId(viewId)
    } catch {
      return null
    }
    const view = views.get(normalized)
    return view === undefined ? null : summary(view)
  }

  const resolveView = async (
    viewId: string,
    bindingEpoch: string,
    request: IdeStreamRequest,
  ): Promise<IdeStreamUpstream> => {
    if (closed) {
      throw new IdeError('IDE service is closed')
    }
    const normalizedView = validateViewId(viewId)
    const normalizedEpoch = validateBindingEpoch(bindingEpoch)
    const view = views.get(normalizedView)
    if (view === undefined || view.state !== 'OPEN' || view.bindingEpoch !== normalizedEpoch) {
      throw new IdeError('view is not open for this binding epoch', 'VIEW_GONE')
    }
    const session = manager.session()
    if (session === undefined) {
      throw new IdeError('code-server is not running', 'UNAVAILABLE')
    }
    const path = upstreamPath(request, normalizedView, normalizedEpoch)
    const filtered = upstreamHeaders(request.headers)
    const controller = new AbortController()
    let resolveClosed: () => void = () => {}
    const closedPromise = new Promise<void>((resolve) => {
      resolveClosed = resolve
    })
    const stream: ActiveStream = {
      viewId: normalizedView,
      bindingEpoch: normalizedEpoch,
      controller,
      closed: closedPromise,
      resolveClosed,
      released: false,
    }
    view.streams.add(stream)
    activeStreams.add(stream)
    cancelIdle()
    return {
      host: session.host,
      port: session.port,
      path,
      headers: filtered.headers,
      cookie: session.cookie,
      protocols: filtered.protocols,
      signal: controller.signal,
      release: () => releaseStream(stream),
    }
  }

  function canStartUpdate(): boolean {
    return (
      !closed &&
      views.size === 0 &&
      reservations.size === 0 &&
      activeStreams.size === 0 &&
      crashRecovery === undefined &&
      !crashUnconverged
    )
  }

  const stopForUpdate = async (signal: AbortSignal): Promise<void> => {
    if (!canStartUpdate()) {
      throw new IdeError('IDE views, cleanup or data streams are active', 'BUSY')
    }
    cancelIdle()
    await waitWithSignal(stopManager(), signal)
  }

  const restoreBackup = async (viewId: string, signal: AbortSignal): Promise<{ restored: number; skipped: number }> => {
    const normalized = validateViewId(viewId)
    let backup
    try {
      backup = await readRecoveryBackup(config, normalized, recoveryQuotaBytes)
    } catch (error) {
      throw new IdeError(
        error instanceof RecoveryQuotaError ? error.message : 'recovery backup could not be read',
        'UNAVAILABLE',
      )
    }
    if (backup === null) {
      return { restored: 0, skipped: 0 }
    }
    const view = views.get(normalized)
    if (view === undefined || view.state !== 'OPEN') {
      throw new IdeError('view is not open', 'VIEW_GONE')
    }
    if (!bridge.isConnected(view.viewId, view.bindingEpoch)) {
      throw new IdeError('IDE window is not connected', 'UNAVAILABLE')
    }
    const result = await bridge.request(
      view.viewId,
      { op: 'restore', documents: backup.documents },
      signal,
      { expectedEpoch: view.bindingEpoch },
    )
    if (!result.ok) {
      throw new IdeError(result.message ?? 'recovery restore failed', result.code ?? 'UNAVAILABLE')
    }
    return { restored: result.restored ?? 0, skipped: result.skipped ?? 0 }
  }

  manager.onUnexpectedExit((_generation) => {
    if (closed) {
      return
    }
    crashEpoch++
    const affected = [...views.values()]
    for (const view of affected) {
      view.state = 'CLOSING'
      view.cleanupFailed = true
      clearTimer(view)
      dropClaimsForView(view.viewId)
      abortViewStreams(view)
      bridge.revoke(view.viewId, view.bindingEpoch)
    }
    emit({ kind: 'process-exit', message: 'code-server exited; IDE views were invalidated' })
    const recovery = (async () => {
      try {
        await stopManager()
      } catch {
        // The old scope is not proven dead; keep every affected view CLOSING and refuse a
        // replacement instance until a later convergence attempt succeeds.
        crashUnconverged = true
        emit({ kind: 'cleanup-failed', message: 'code-server process scope did not converge after exit' })
        throw new IdeError('code-server cleanup did not converge after exit', 'CLEANUP_FAILED')
      }
      crashUnconverged = false
      await releaseInvalidatedViews(affected)
    })()
    crashRecovery = recovery
    void recovery
      .finally(() => {
        if (crashRecovery === recovery) {
          crashRecovery = undefined
        }
      })
      .catch(() => undefined)
  })

  bridge.onPty(async (_viewId, pid) => {
    const session: CodeServerSession | undefined = manager.session()
    if (session === undefined) {
      throw new IdeError('terminal supervision is unavailable')
    }
    // The IDE PTY host intentionally creates its own session; register the shell so the
    // native supervisor can still reap it with the code-server tree.
    await processes.trackDescendant?.(session.pid, pid)
  })

  return {
    handle,
    canStartUpdate,
    stopForUpdate,
    hasActiveViews() {
      return views.size > 0 || reservations.size > 0
    },
    apply(leaseValue, roundStartedAt) {
      lease.apply(leaseValue, roundStartedAt)
    },
    getViewState,
    activeStreamCount() {
      return activeStreams.size
    },
    resolveView,
    restoreBackup,
    async close() {
      if (closed) {
        return
      }
      closed = true
      cancelIdle()
      lifetime.abort()
      for (const view of views.values()) {
        clearTimer(view)
        abortViewStreams(view)
      }
      const errors: unknown[] = []
      try {
        await bridge.close()
      } catch (error) {
        errors.push(error)
      }
      try {
        await manager.close()
      } catch (error) {
        errors.push(error)
      }
      views.clear()
      reservations.clear()
      claims.clear()
      viewLocks.clear()
      if (errors.length > 0) {
        throw new AggregateError(errors, 'ide service shutdown failed')
      }
    },
  }
}
