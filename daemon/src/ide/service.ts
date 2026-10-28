import { realpath, rm } from 'node:fs/promises'
import { join } from 'node:path'
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
import type { IdeLease } from '../protocol.js'
import { ensurePrivateDirectory, writePrivateFile } from '../native/index.js'
import { createIdeBridge, type BridgeDocument, type BridgeResult, type IdeBridge } from './bridge.js'
import {
  createCodeServerManager,
  idePaths,
  type CodeServerManager,
  type CodeServerSession,
  type ManagedViewCloseMode,
} from './codeServer.js'
import { IdeError, failureReply } from './errors.js'
import {
  LONG_MAX,
  RecoveryQuotaError,
  canonicalDirectory,
  hostHome,
  readRecoveryBackup,
  validateBindingEpoch,
  validateUuid,
  validateViewId,
  workspaceFileContent,
  writeRecoveryBackup,
  type RecoveryBackup,
} from './workspace.js'

/**
 * Managed code-server IDE lifecycle for one Environment.
 *
 * <p>All view claims, document exclusivity and binding epochs are decided in this process under
 * a per-view lock. A claim reservation counts toward the fixed view quota before any process or
 * filesystem work starts, so concurrent opens cannot oversubscribe one Environment. Usage proof
 * never creates or extends a view beyond its own epoch: Runtime supplies the local monotonic time
 * at which the lease round was sent, and DETACH freezes that epoch against late proofs.
 */

export const IDE_MAX_VIEWS = 64
export const IDE_PROOF_EXCLUSIVITY_MILLIS = 45_000
export const IDE_VIEW_EXPIRY_MILLIS = 600_000
export const IDE_IDLE_STOP_MILLIS = 60_000
export const IDE_CLOSE_BUDGET_MILLIS = 10_000
export const DEFAULT_RECOVERY_QUOTA_BYTES = 16 * 1024 * 1024
const MAX_CACHED_CLAIMS = 256
const MAX_COMMAND_TEXT_CHARS = 4096
const MAX_STREAM_PATH_BYTES = 8192
const MAX_STREAM_HEADERS = 100
const MAX_STREAM_HEADER_BYTES = 16 * 1024
const EPOCH_PATTERN = /^[1-9][0-9]*$/
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
  kind: 'backup-failed' | 'backup-incomplete' | 'cleanup-failed' | 'idle-stop-failed' | 'process-exit'
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

interface ActiveStream {
  viewId: string
  bindingEpoch: string
  controller: AbortController
  closed: Promise<void>
  resolveClosed: () => void
  released: boolean
}

interface ViewRecord {
  viewId: string
  documentId: string
  eventNodeId: string
  connectionId: string
  claimId: string
  bindingEpoch: string
  directory: string
  workspacePath: string
  workspaceOwned: boolean
  state: 'OPEN' | 'CLOSING'
  cleanupFailed: boolean
  createdAt: number
  lastProofAt: number | null
  detachedAt: number | null
  expiresAt: number
  expiryTimer?: unknown
  streams: Set<ActiveStream>
  closePromise?: Promise<IdeReply>
  sessionGeneration: number
}

interface ClaimRecord {
  shape: string
  reply: IdeReply
  viewId: string
  bindingEpoch: string
}

const systemScheduler: IdeScheduler = {
  now: () => performance.now(),
  setTimeout: (callback, delayMillis) => setTimeout(callback, delayMillis),
  clearTimeout: (handle) => clearTimeout(handle as NodeJS.Timeout),
}

function abortError(): Error {
  const error = new Error('ide request was cancelled')
  error.name = 'AbortError'
  return error
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

function openReply(view: ViewRecord): IdeReply {
  return {
    ok: true,
    viewId: view.viewId,
    documentId: view.documentId,
    eventNodeId: view.eventNodeId,
    connectionId: view.connectionId,
    claimId: view.claimId,
    bindingEpoch: view.bindingEpoch,
    directory: view.directory,
    workspacePath: view.workspacePath,
  }
}

function openCommandShape(command: IdeCommand): string {
  const mode = required(command.mode, 'mode')
  if (mode !== 'create' && mode !== 'resume') {
    throw new IdeError('mode is invalid')
  }
  return JSON.stringify({
    action: 'OPEN_VIEW',
    viewId: validateViewId(required(command.viewId, 'viewId')),
    documentId: validateUuid(required(command.documentId, 'documentId'), 'documentId'),
    eventNodeId: validateUuid(required(command.eventNodeId, 'eventNodeId'), 'eventNodeId'),
    connectionId: validateUuid(required(command.connectionId, 'connectionId'), 'connectionId'),
    claimId: validateUuid(required(command.claimId, 'claimId'), 'claimId'),
    mode,
    directory: boundedText(command.directory, 'directory') ?? null,
  })
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

  const clearTimer = (view: ViewRecord): void => {
    if (view.expiryTimer !== undefined) {
      scheduler.clearTimeout(view.expiryTimer)
      view.expiryTimer = undefined
    }
  }

  const scheduleExpiry = (view: ViewRecord): void => {
    clearTimer(view)
    if (view.state !== 'OPEN' || closed) {
      return
    }
    const base = view.detachedAt ?? view.lastProofAt ?? view.createdAt
    view.expiresAt = base + IDE_VIEW_EXPIRY_MILLIS
    const delay = Math.max(0, view.expiresAt - scheduler.now())
    view.expiryTimer = scheduler.setTimeout(() => {
      view.expiryTimer = undefined
      void expireView(view.viewId, view.bindingEpoch)
    }, delay)
  }

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

  const rememberClaim = (claimId: string, record: ClaimRecord): void => {
    claims.delete(claimId)
    claims.set(claimId, record)
    while (claims.size > MAX_CACHED_CLAIMS) {
      const oldest = claims.keys().next()
      if (oldest.done === true) {
        break
      }
      claims.delete(oldest.value)
    }
  }

  const dropClaimsForView = (viewId: string): void => {
    for (const [claimId, record] of claims) {
      if (record.viewId === viewId) {
        claims.delete(claimId)
      }
    }
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

  const rollbackOpen = (view: ViewRecord): void => {
    if (views.get(view.viewId) !== view || view.cleanupFailed) {
      return
    }
    view.state = 'OPEN'
    scheduleExpiry(view)
  }

  const performClose = (view: ViewRecord, mode: ManagedViewCloseMode): Promise<IdeReply> => {
    if (view.closePromise !== undefined) {
      return view.closePromise
    }
    const operation = (async (): Promise<IdeReply> => {
      const viewId = view.viewId
      const epoch = view.bindingEpoch
      // A normal close that can still be rolled back must not abort streams until the bridge has
      // accepted the final close. Discard, expiry and retry-after-failure are irreversible modes.
      const rollbackableNormalClose = mode === 'normal' && !view.cleanupFailed
      try {
        if (!rollbackableNormalClose) {
          await waitForViewStreams(view)
        }
        if (views.get(viewId) !== view || view.bindingEpoch !== epoch) {
          return { ok: false, code: 'VIEW_GONE', message: 'view binding changed' }
        }
        if (bridge.isConnected(viewId, epoch)) {
          let bridgeResult: BridgeResult
          try {
            bridgeResult = await bridge.request(
              viewId,
              { op: 'closeView', ...(mode === 'normal' ? {} : { discard: true }) },
              lifetime.signal,
              { expectedEpoch: epoch },
            )
          } catch (error) {
            if (mode === 'normal' && !view.cleanupFailed) {
              rollbackOpen(view)
              return failureReply(error)
            }
            throw error
          }
          if (!bridgeResult.ok) {
            if (mode === 'normal') {
              if (view.cleanupFailed) {
                throw new IdeError(bridgeResult.message ?? 'ide window did not confirm close', 'CLEANUP_FAILED')
              }
              rollbackOpen(view)
              return {
                ok: false,
                code: bridgeResult.code === 'DIRTY' ? 'DIRTY' : 'UNAVAILABLE',
                message:
                  bridgeResult.message ??
                  'unsaved state is unknown; save or discard unsaved changes before closing the view',
              }
            }
            throw new IdeError(bridgeResult.message ?? 'ide window did not confirm close', 'CLEANUP_FAILED')
          }
        } else if (mode === 'normal' && !view.cleanupFailed) {
          // A clean observation is needed for a normal close. Losing the window before the
          // bridge confirms means the dirty state is unknown, so the view returns to OPEN.
          rollbackOpen(view)
          return {
            ok: false,
            code: 'UNAVAILABLE',
            message: 'save or discard unsaved changes before closing the view',
          }
        }
        if (views.get(viewId) !== view || view.bindingEpoch !== epoch) {
          return { ok: false, code: 'VIEW_GONE', message: 'view binding changed' }
        }
        if (rollbackableNormalClose) {
          // The bridge has now accepted the final close; only from this point is aborting the
          // target view's data streams an accepted, irreversible cleanup step.
          await waitForViewStreams(view)
          if (views.get(viewId) !== view || view.bindingEpoch !== epoch) {
            return { ok: false, code: 'VIEW_GONE', message: 'view binding changed' }
          }
        }
        const serverMode: ManagedViewCloseMode = mode === 'normal' ? 'discard' : mode
        const result = await manager.closeView(viewId, epoch, serverMode, lifetime.signal)
        if (result.cleanup.complete !== true || result.state !== 'CLOSED') {
          throw new IdeError('managed view resources did not report exit', 'CLEANUP_FAILED')
        }
        if (views.get(viewId) !== view || view.bindingEpoch !== epoch) {
          return { ok: false, code: 'VIEW_GONE', message: 'view binding changed' }
        }
        clearTimer(view)
        bridge.revoke(viewId, epoch)
        dropClaimsForView(viewId)
        if (view.workspaceOwned) {
          await rm(view.workspacePath, { force: true })
        }
        views.delete(viewId)
        scheduleIdleStop()
        return { ok: true, viewId }
      } catch (error) {
        if (views.get(viewId) === view && view.bindingEpoch === epoch) {
          view.cleanupFailed = true
          view.state = 'CLOSING'
          clearTimer(view)
          emit({
            kind: 'cleanup-failed',
            viewId,
            message: error instanceof IdeError ? error.message : 'view cleanup failed',
          })
        }
        return failureReply(
          error instanceof IdeError
            ? error
            : new IdeError('view cleanup failed', 'CLEANUP_FAILED'),
        )
      }
    })()
    let tracked: Promise<IdeReply>
    tracked = operation.finally(() => {
      if (view.closePromise === tracked) {
        view.closePromise = undefined
      }
    })
    view.closePromise = tracked
    return tracked
  }

  const snapshotForClose = async (view: ViewRecord): Promise<void> => {
    if (!bridge.isConnected(view.viewId, view.bindingEpoch)) {
      emit({ kind: 'backup-failed', viewId: view.viewId, message: 'recovery bridge is not connected' })
      return
    }
    try {
      const signal = AbortSignal.any([lifetime.signal, AbortSignal.timeout(IDE_CLOSE_BUDGET_MILLIS)])
      const result = await bridge.request(
        view.viewId,
        { op: 'snapshot', maxBytes: Math.min(recoveryQuotaBytes, 4 * 1024 * 1024) },
        signal,
        { expectedEpoch: view.bindingEpoch },
      )
      if (!result.ok) {
        emit({
          kind: 'backup-failed',
          viewId: view.viewId,
          message: result.message ?? 'dirty text snapshot failed',
        })
        return
      }
      const documents: BridgeDocument[] = result.documents ?? []
      const complete = result.complete !== false && (result.omitted ?? 0) === 0
      const backup: RecoveryBackup = {
        version: 1,
        viewId: view.viewId,
        bindingEpoch: view.bindingEpoch,
        directory: view.directory,
        createdAt: new Date().toISOString(),
        complete,
        documents,
      }
      await writeRecoveryBackup(config, backup, recoveryQuotaBytes)
      if (!complete) {
        emit({ kind: 'backup-incomplete', viewId: view.viewId, message: 'dirty text snapshot was incomplete' })
      }
    } catch (error) {
      emit({
        kind: 'backup-failed',
        viewId: view.viewId,
        message:
          error instanceof IdeError || error instanceof RecoveryQuotaError
            ? error.message
            : 'dirty text snapshot failed',
      })
    }
  }

  const mergeSummary = (view: ViewRecord): IdeViewSummary => summary(view)

  const requireBoundView = (
    command: IdeCommand,
    options: { requireOpen: boolean },
  ): ViewRecord => {
    const viewId = validateViewId(required(command.viewId, 'viewId'))
    const view = views.get(viewId)
    if (view === undefined) {
      throw new IdeError('view is not open', 'VIEW_GONE')
    }
    const documentId = validateUuid(required(command.documentId, 'documentId'), 'documentId')
    const bindingEpoch = validateBindingEpoch(required(command.bindingEpoch, 'bindingEpoch'))
    if (view.viewId !== viewId || view.documentId !== documentId || view.bindingEpoch !== bindingEpoch) {
      throw new IdeError('view binding changed', 'VIEW_GONE')
    }
    if (options.requireOpen && view.state !== 'OPEN') {
      throw new IdeError('view is closing', 'BUSY')
    }
    return view
  }

  const createView = async (command: IdeCommand, signal: AbortSignal): Promise<IdeReply> => {
    const viewId = validateViewId(required(command.viewId, 'viewId'))
    const documentId = validateUuid(required(command.documentId, 'documentId'), 'documentId')
    const eventNodeId = validateUuid(required(command.eventNodeId, 'eventNodeId'), 'eventNodeId')
    const connectionId = validateUuid(required(command.connectionId, 'connectionId'), 'connectionId')
    const claimId = validateUuid(required(command.claimId, 'claimId'), 'claimId')
    const mode = required(command.mode, 'mode')
    if (mode !== 'create') {
      throw new IdeError('view is gone', 'VIEW_GONE')
    }
    await awaitCrashRecovery(signal)
    await awaitManagerStopped(signal)
    await convergeAfterCrash()
    if (views.size + reservations.size >= maxViews) {
      throw new IdeError('IDE view limit reached', 'BUSY')
    }
    reservations.add(viewId)
    cancelIdle()
    const crashAtStart = crashEpoch
    let workspacePath: string | undefined
    let epoch: string | undefined
    let sessionGeneration: number | undefined
    try {
      const directory = await canonicalDirectory(command.directory ?? hostHome())
      await ensurePrivateDirectory(paths.viewsDir)
      const workspaceFile = join(paths.viewsDir, `${viewId}.code-workspace`)
      workspacePath = workspaceFile
      await writePrivateFile(workspaceFile, workspaceFileContent(directory))
      // code-server canonicalises the workspace path at registration; bind the bridge to the
      // same canonical file so a symlinked data directory cannot produce two identities.
      workspacePath = await realpath(workspaceFile)
      epoch = allocateEpoch()
      bridge.assign(viewId, workspacePath, epoch)
      const session = await manager.ensureStarted(signal)
      sessionGeneration = session.generation
      if (crashEpoch !== crashAtStart) {
        throw new IdeError('code-server process changed during view creation', 'BUSY')
      }
      const registered = await manager.registerView(viewId, epoch, workspacePath, signal)
      if (registered.state !== 'OPEN') {
        throw new IdeError('managed view did not become open', 'CLEANUP_FAILED')
      }
      if (registered.bindingEpoch !== epoch || registered.workspacePath !== workspacePath) {
        throw new IdeError('managed view registration drifted', 'CLEANUP_FAILED')
      }
      if (crashEpoch !== crashAtStart || manager.session()?.generation !== session.generation) {
        throw new IdeError('code-server process changed during view creation', 'BUSY')
      }
      const view: ViewRecord = {
        viewId,
        documentId,
        eventNodeId,
        connectionId,
        claimId,
        bindingEpoch: epoch,
        directory,
        workspacePath,
        workspaceOwned: true,
        state: 'OPEN',
        cleanupFailed: false,
        createdAt: scheduler.now(),
        lastProofAt: null,
        detachedAt: null,
        expiresAt: 0,
        streams: new Set(),
        sessionGeneration: session.generation,
      }
      views.set(viewId, view)
      scheduleExpiry(view)
      const reply = openReply(view)
      rememberClaim(claimId, { shape: openCommandShape(command), reply, viewId, bindingEpoch: epoch })
      return reply
    } catch (error) {
      if (workspacePath !== undefined && epoch !== undefined) {
        bridge.revoke(viewId, epoch)
        // Registration may have become visible in code-server even though the command failed.
        // Converge it; if cleanup cannot be proven, retain a counted CLOSING record. A process
        // crash already invalidated its whole scope, so there is nothing left to converge.
        let cleanupFailed = false
        if (crashEpoch === crashAtStart && sessionGeneration !== undefined) {
          if (manager.session()?.generation === sessionGeneration) {
            try {
              await manager.closeView(viewId, epoch, 'discard', AbortSignal.timeout(IDE_CLOSE_BUDGET_MILLIS))
            } catch {
              cleanupFailed = true
            }
          } else {
            cleanupFailed = true
          }
        }
        if (cleanupFailed) {
          views.set(viewId, {
            viewId,
            documentId,
            eventNodeId,
            connectionId,
            claimId,
            bindingEpoch: epoch,
            directory: command.directory ?? 'unavailable',
            workspacePath,
            workspaceOwned: true,
            state: 'CLOSING',
            cleanupFailed: true,
            createdAt: scheduler.now(),
            lastProofAt: null,
            detachedAt: null,
            expiresAt: 0,
            streams: new Set(),
            sessionGeneration: sessionGeneration ?? 0,
          })
          emit({ kind: 'cleanup-failed', viewId, message: 'failed view registration could not be cleaned up' })
        } else {
          await rm(workspacePath, { force: true })
        }
      }
      throw error
    } finally {
      reservations.delete(viewId)
      scheduleIdleStop()
    }
  }

  const resumeView = async (command: IdeCommand, view: ViewRecord, signal: AbortSignal): Promise<IdeReply> => {
    if (view.state !== 'OPEN') {
      throw new IdeError('view is closing', 'BUSY')
    }
    if (view.cleanupFailed) {
      throw new IdeError('view cleanup is incomplete', 'CLEANUP_FAILED')
    }
    const mode = required(command.mode, 'mode')
    if (mode !== 'resume') {
      throw new IdeError('mode is invalid')
    }
    const documentId = validateUuid(required(command.documentId, 'documentId'), 'documentId')
    const eventNodeId = validateUuid(required(command.eventNodeId, 'eventNodeId'), 'eventNodeId')
    const connectionId = validateUuid(required(command.connectionId, 'connectionId'), 'connectionId')
    const claimId = validateUuid(required(command.claimId, 'claimId'), 'claimId')
    const requestedDirectory = command.directory === undefined ? undefined : await canonicalDirectory(command.directory)
    if (requestedDirectory !== undefined && requestedDirectory !== view.directory) {
      throw new IdeError('resume must not change the workspace directory', 'VIEW_IN_USE')
    }
    if (view.documentId !== documentId) {
      const occupiedUntil = (view.detachedAt ?? view.lastProofAt ?? view.createdAt) + IDE_PROOF_EXCLUSIVITY_MILLIS
      if (view.detachedAt === null && scheduler.now() < occupiedUntil) {
        throw new IdeError('view is in use by another document', 'VIEW_IN_USE')
      }
    }
    await awaitCrashRecovery(signal)
    await awaitManagerStopped(signal)
    cancelIdle()
    const crashAtStart = crashEpoch
    const sessionGeneration = manager.session()?.generation
    if (sessionGeneration === undefined) {
      throw new IdeError('code-server is not running', 'UNAVAILABLE')
    }
    const epoch = allocateEpoch()
    abortViewStreams(view)
    const registered = await manager.registerView(view.viewId, epoch, view.workspacePath, signal)
    if (registered.state !== 'OPEN' || registered.bindingEpoch !== epoch) {
      throw new IdeError('managed view did not accept the new binding epoch', 'CLEANUP_FAILED')
    }
    if (crashEpoch !== crashAtStart || manager.session()?.generation !== sessionGeneration) {
      throw new IdeError('code-server process changed during view resume', 'BUSY')
    }
    view.bindingEpoch = epoch
    view.documentId = documentId
    view.eventNodeId = eventNodeId
    view.connectionId = connectionId
    view.claimId = claimId
    view.state = 'OPEN'
    view.cleanupFailed = false
    view.lastProofAt = null
    view.detachedAt = null
    view.createdAt = scheduler.now()
    view.sessionGeneration = sessionGeneration
    dropClaimsForView(view.viewId)
    bridge.assign(view.viewId, view.workspacePath, epoch)
    scheduleExpiry(view)
    const reply = openReply(view)
    rememberClaim(claimId, {
      shape: openCommandShape(command),
      reply,
      viewId: view.viewId,
      bindingEpoch: epoch,
    })
    return reply
  }

  const openView = (command: IdeCommand, signal: AbortSignal): Promise<IdeReply> => {
    const viewId = validateViewId(required(command.viewId, 'viewId'))
    const claimId = validateUuid(required(command.claimId, 'claimId'), 'claimId')
    const shape = openCommandShape(command)
    const cached = claims.get(claimId)
    if (cached !== undefined) {
      if (cached.shape !== shape) {
        throw new IdeError('claimId was reused with different request fields', 'CLAIM_CONFLICT')
      }
      const current = views.get(viewId)
      if (current !== undefined && current.bindingEpoch === cached.bindingEpoch) {
        return Promise.resolve(cached.reply)
      }
      claims.delete(claimId)
    }
    return withClaimLock(claimId, () =>
      withViewLock(viewId, async () => {
        const replay = claims.get(claimId)
        if (replay !== undefined) {
          if (replay.shape !== shape) {
            throw new IdeError('claimId was reused with different request fields', 'CLAIM_CONFLICT')
          }
          const current = views.get(viewId)
          if (current !== undefined && current.bindingEpoch === replay.bindingEpoch) {
            return replay.reply
          }
          claims.delete(claimId)
        }
        const existing = views.get(viewId)
        if (existing !== undefined) {
          if (command.mode === 'resume') {
            return resumeView(command, existing, signal)
          }
          throw new IdeError('view is already open', 'VIEW_IN_USE')
        }
        return createView(command, signal)
      }),
    )
  }

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
      return { ok: true, viewId: view.viewId }
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

  const closeView = (command: IdeCommand, signal: AbortSignal): Promise<IdeReply> => {
    const viewId = validateViewId(required(command.viewId, 'viewId'))
    if (typeof command.discard !== 'boolean') {
      return Promise.resolve(failureReply(new IdeError('discard is required')))
    }
    return withViewLock(viewId, async () => {
      const view = requireBoundView(command, { requireOpen: false })
      if (view.state === 'CLOSING') {
        if (view.closePromise !== undefined) {
          return view.closePromise
        }
        if (!view.cleanupFailed) {
          throw new IdeError('view is closing', 'BUSY')
        }
        return performClose(view, command.discard === true ? 'discard' : 'normal')
      }
      const mode: ManagedViewCloseMode = command.discard === true ? 'discard' : 'normal'
      const epoch = view.bindingEpoch
      const documentId = view.documentId
      view.state = 'CLOSING'
      clearTimer(view)
      if (mode === 'normal') {
        if (!bridge.isConnected(view.viewId, epoch)) {
          rollbackOpen(view)
          throw new IdeError('unsaved state is unknown because the IDE window is not connected', 'UNAVAILABLE')
        }
        let inspection: BridgeResult
        try {
          inspection = await bridge.request(view.viewId, { op: 'inspect' }, signal, { expectedEpoch: epoch })
        } catch (error) {
          if (views.get(view.viewId) === view && view.bindingEpoch === epoch) {
            rollbackOpen(view)
          }
          throw error
        }
        if (views.get(view.viewId) !== view || view.bindingEpoch !== epoch || view.documentId !== documentId) {
          throw new IdeError('view binding changed', 'VIEW_GONE')
        }
        if (!inspection.ok) {
          rollbackOpen(view)
          throw new IdeError(inspection.message ?? 'unsaved state is unknown', inspection.code ?? 'UNAVAILABLE')
        }
        if (inspection.clean !== true) {
          rollbackOpen(view)
          throw new IdeError('save or discard unsaved changes before closing the view', 'DIRTY')
        }
      } else {
        await snapshotForClose(view)
      }
      if (views.get(view.viewId) !== view || view.bindingEpoch !== epoch || view.documentId !== documentId) {
        throw new IdeError('view binding changed', 'VIEW_GONE')
      }
      return performClose(view, mode)
    })
  }

  const detachView = (command: IdeCommand, signal: AbortSignal): Promise<IdeReply> => {
    const viewId = validateViewId(required(command.viewId, 'viewId'))
    return withViewLock(viewId, async () => {
      const view = requireBoundView(command, { requireOpen: true })
      if (view.detachedAt !== null && view.bindingEpoch === command.bindingEpoch) {
        return { ok: true, viewId: view.viewId }
      }
      view.detachedAt = scheduler.now()
      scheduleExpiry(view)
      return { ok: true, viewId: view.viewId }
    })
  }

  const listViews = (): IdeReply => {
    const values = [...views.values()].map(mergeSummary)
    return { ok: true, views: values }
  }

  const expireView = (viewId: string, epoch: string): Promise<void> => {
    return withViewLock(viewId, async () => {
      const view = views.get(viewId)
      if (view === undefined || view.bindingEpoch !== epoch || view.state !== 'OPEN') {
        return
      }
      view.state = 'CLOSING'
      clearTimer(view)
      await snapshotForClose(view)
      await performClose(view, 'expired')
    }).then(
      () => undefined,
      () => undefined,
    )
  }

  const handle = async (command: IdeCommand, signal: AbortSignal): Promise<IdeReply> => {
    if (closed) {
      return failureReply(new IdeError('IDE service is closed'))
    }
    try {
      await awaitCrashRecovery(signal)
      switch (command.action) {
        case 'OPEN_VIEW':
          return await openView(command, signal)
        case 'CHANGE_DIRECTORY':
          return await changeDirectory(command, signal)
        case 'FOCUS_TERMINAL':
          return await focusTerminal(command, signal)
        case 'CLOSE_VIEW':
          return await closeView(command, signal)
        case 'DETACH_VIEW':
          return await detachView(command, signal)
        case 'LIST_VIEWS':
          return listViews()
        default:
          throw new IdeError('unknown ide action')
      }
    } catch (error) {
      return failureReply(error)
    }
  }

  const applyLease = (lease: IdeLease, roundStartedAt: number): void => {
    if (closed || !Number.isFinite(roundStartedAt)) {
      return
    }
    for (const binding of lease.bindings) {
      const view = views.get(binding.viewId)
      if (
        view === undefined ||
        view.state !== 'OPEN' ||
        view.detachedAt !== null ||
        view.documentId !== binding.documentId ||
        view.bindingEpoch !== binding.bindingEpoch
      ) {
        continue
      }
      if (roundStartedAt < view.createdAt) {
        continue
      }
      if (view.lastProofAt !== null && roundStartedAt <= view.lastProofAt) {
        continue
      }
      view.lastProofAt = roundStartedAt
      scheduleExpiry(view)
    }
  }

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
    apply(lease, roundStartedAt) {
      applyLease(lease, roundStartedAt)
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

/** 只接受固定的根相对 view 路径；数据适配器不得借此访问任意 host/port。 */
export function assertIdeEpoch(value: string): string {
  if (!EPOCH_PATTERN.test(value) || BigInt(value) > LONG_MAX) {
    throw new IdeError('bindingEpoch is invalid')
  }
  return value
}
