import { realpath, rm } from 'node:fs/promises'
import { join } from 'node:path'
import type { IdeCommand, IdeReply } from '../contracts.js'
import { ensurePrivateDirectory, writePrivateFile } from '../native/index.js'
import type { IdeBridge } from './bridge.js'
import type { CodeServerManager, IdePaths, ManagedViewInspection } from './codeServer.js'
import { IdeError } from './errors.js'
import type { IdeScheduler, IdeServiceEvent } from './service.js'
import type { ClaimRecord, IdeSnapshot, ViewRecord } from './serviceTypes.js'
import {
  canonicalDirectory,
  hostHome,
  requireDaemonInstance,
  validateBindingEpoch,
  validateExpectedBindingEpoch,
  validateUuid,
  validateViewId,
  workspaceFileContent,
} from './workspace.js'

const MAX_CACHED_CLAIMS = 256

interface RegistryDependencies {
  paths: IdePaths
  views: Map<string, ViewRecord>
  reservations: Set<string>
  claims: Map<string, ClaimRecord>
  scheduler: IdeScheduler
  bridge: IdeBridge
  manager: CodeServerManager
  maxViews: number
  daemonInstanceId: string
  closeBudgetMillis: number
  getCrashEpoch: () => number
  emit: (event: IdeServiceEvent) => void
  withViewLock: <T>(viewId: string, task: () => Promise<T>) => Promise<T>
  withClaimLock: <T>(claimId: string, task: () => Promise<T>) => Promise<T>
  scheduleExpiry: (view: ViewRecord, notBefore?: number) => void
  scheduleIdleStop: () => void
  cancelIdle: () => void
  commitRevision: () => string
  snapshot: () => IdeSnapshot
  awaitCrashRecovery: (signal: AbortSignal) => Promise<void>
  awaitManagerStopped: (signal: AbortSignal) => Promise<void>
  convergeAfterCrash: () => Promise<void>
  abortViewStreams: (view: ViewRecord) => void
  required: <T>(value: T | undefined, field: string, code?: string) => T
  boundedText: (value: string | undefined, field: string) => string | undefined
}

interface ParsedOpenCommand {
  viewId: string
  browserSessionId: string
  eventNodeId: string
  connectionId: string
  claimId: string
  mode: 'create' | 'resume'
  directory?: string
  expectedBindingEpoch: string
}

function openReply(view: ViewRecord, snapshot: IdeSnapshot): IdeReply {
  return {
    ok: true,
    viewId: view.viewId,
    browserSessionId: view.browserSessionId,
    eventNodeId: view.eventNodeId,
    connectionId: view.connectionId,
    claimId: view.claimId,
    bindingEpoch: view.bindingEpoch,
    directory: view.directory,
    workspacePath: view.workspacePath,
    revision: snapshot.revision,
    views: snapshot.views,
  }
}

export function createViewRegistry({
  paths, views, reservations, claims, scheduler, bridge, manager, maxViews, daemonInstanceId,
  closeBudgetMillis, getCrashEpoch, emit, withViewLock, withClaimLock, scheduleExpiry, scheduleIdleStop,
  cancelIdle, commitRevision, snapshot, awaitCrashRecovery, awaitManagerStopped, convergeAfterCrash,
  abortViewStreams, required, boundedText,
}: RegistryDependencies) {
  const parseOpenCommand = (command: IdeCommand): ParsedOpenCommand => {
    const mode = required(command.mode, 'mode')
    if (mode !== 'create' && mode !== 'resume') {
      throw new IdeError('mode is invalid')
    }
    const expectedBindingEpoch = validateExpectedBindingEpoch(
      required(command.expectedBindingEpoch, 'expectedBindingEpoch'),
    )
    if (mode === 'create' && expectedBindingEpoch !== '0') {
      throw new IdeError('create requires expectedBindingEpoch 0')
    }
    if (mode === 'resume' && expectedBindingEpoch === '0') {
      throw new IdeError('resume requires a positive expectedBindingEpoch')
    }
    return {
      viewId: validateViewId(required(command.viewId, 'viewId')),
      browserSessionId: validateUuid(required(command.browserSessionId, 'browserSessionId'), 'browserSessionId'),
      eventNodeId: validateUuid(required(command.eventNodeId, 'eventNodeId'), 'eventNodeId'),
      connectionId: validateUuid(required(command.connectionId, 'connectionId'), 'connectionId'),
      claimId: validateUuid(required(command.claimId, 'claimId'), 'claimId'),
      mode,
      expectedBindingEpoch,
      ...(command.directory === undefined
        ? {}
        : { directory: boundedText(command.directory, 'directory') }),
    }
  }

  const openCommandShape = (command: ParsedOpenCommand): string =>
    JSON.stringify({
      action: 'OPEN_VIEW',
      viewId: command.viewId,
      browserSessionId: command.browserSessionId,
      eventNodeId: command.eventNodeId,
      connectionId: command.connectionId,
      claimId: command.claimId,
      mode: command.mode,
      directory: command.directory ?? null,
      expectedBindingEpoch: command.expectedBindingEpoch,
    })

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

  /** A committed claim returns current state only; it can never publish an older snapshot. */
  const replayClaim = (claimId: string, shape: string): IdeReply | null => {
    const cached = claims.get(claimId)
    if (cached === undefined) {
      return null
    }
    if (cached.shape !== shape) {
      throw new IdeError('claimId was reused with different request fields', 'CLAIM_CONFLICT')
    }
    const current = views.get(cached.viewId)
    if (
      current !== undefined &&
      current.state === 'OPEN' &&
      current.bindingEpoch === cached.bindingEpoch &&
      current.claimId === claimId
    ) {
      return openReply(current, snapshot())
    }
    throw new IdeError('claim was superseded by a newer binding', 'STALE_BINDING')
  }

  const requireBoundView = (
    command: IdeCommand,
    options: { requireOpen: boolean },
  ): ViewRecord => {
    requireDaemonInstance(command, daemonInstanceId)
    const viewId = validateViewId(required(command.viewId, 'viewId'))
    const view = views.get(viewId)
    if (view === undefined) {
      throw new IdeError('view is not open', 'VIEW_GONE')
    }
    const browserSessionId = validateUuid(
      required(command.browserSessionId, 'browserSessionId'),
      'browserSessionId',
    )
    if (view.browserSessionId !== browserSessionId) {
      throw new IdeError('view is owned by another browser session', 'VIEW_OWNED_BY_OTHER_SESSION')
    }
    const bindingEpoch = validateBindingEpoch(required(command.bindingEpoch, 'bindingEpoch'))
    if (view.bindingEpoch !== bindingEpoch) {
      throw new IdeError('view binding changed', 'STALE_BINDING')
    }
    if (options.requireOpen && view.state !== 'OPEN') {
      throw new IdeError('view is closing', 'BUSY')
    }
    return view
  }

  const createView = async (
    command: ParsedOpenCommand,
    signal: AbortSignal,
  ): Promise<IdeReply> => {
    await awaitCrashRecovery(signal)
    await awaitManagerStopped(signal)
    await convergeAfterCrash()
    if (views.size + reservations.size >= maxViews) {
      throw new IdeError('IDE view limit reached', 'IDE_CAPACITY_EXCEEDED')
    }
    reservations.add(command.viewId)
    cancelIdle()
    const crashAtStart = getCrashEpoch()
    let workspacePath: string | undefined
    let epoch: string | undefined
    let sessionGeneration: number | undefined
    try {
      const directory = await canonicalDirectory(command.directory ?? hostHome())
      await ensurePrivateDirectory(paths.viewsDir)
      const workspaceFile = join(paths.viewsDir, `${command.viewId}.code-workspace`)
      workspacePath = workspaceFile
      await writePrivateFile(workspaceFile, workspaceFileContent(directory))
      // code-server canonicalises the workspace path at registration; bind the bridge to the
      // same canonical file so a symlinked data directory cannot produce two identities.
      workspacePath = await realpath(workspaceFile)
      // The epoch may advance the revision sequence before the native registration lands.
      // The visible view insertion below commits a fresh revision, so bindingEpoch <= revision.
      epoch = commitRevision()
      bridge.assign(command.viewId, workspacePath, epoch, directory)
      const session = await manager.ensureStarted(signal)
      sessionGeneration = session.generation
      if (getCrashEpoch() !== crashAtStart) {
        throw new IdeError('code-server process changed during view creation', 'BUSY')
      }
      const registered = await manager.registerView(command.viewId, epoch, workspacePath, signal)
      if (registered.state !== 'OPEN') {
        throw new IdeError('managed view did not become open', 'CLEANUP_FAILED')
      }
      if (registered.bindingEpoch !== epoch || registered.workspacePath !== workspacePath) {
        throw new IdeError('managed view registration drifted', 'CLEANUP_FAILED')
      }
      if (getCrashEpoch() !== crashAtStart || manager.session()?.generation !== session.generation) {
        throw new IdeError('code-server process changed during view creation', 'BUSY')
      }
      const view: ViewRecord = {
        viewId: command.viewId,
        browserSessionId: command.browserSessionId,
        eventNodeId: command.eventNodeId,
        connectionId: command.connectionId,
        claimId: command.claimId,
        bindingEpoch: epoch,
        directory,
        workspacePath,
        workspaceOwned: true,
        state: 'OPEN',
        cleanupFailed: false,
        createdAt: scheduler.now(),
        lastProofAt: null,
        expiresAt: 0,
        streams: new Set(),
        sessionGeneration: session.generation,
      }
      commitRevision()
      views.set(command.viewId, view)
      scheduleExpiry(view)
      rememberClaim(command.claimId, {
        shape: openCommandShape(command),
        viewId: command.viewId,
        bindingEpoch: epoch,
      })
      return openReply(view, snapshot())
    } catch (error) {
      if (workspacePath !== undefined && epoch !== undefined) {
        bridge.revoke(command.viewId, epoch)
        // Registration may have become visible in code-server even though the command failed.
        // Converge it; if cleanup cannot be proven, retain a counted CLOSING record. A process
        // crash already invalidated its whole scope, so there is nothing left to converge.
        let cleanupFailed = false
        if (getCrashEpoch() === crashAtStart && sessionGeneration !== undefined) {
          if (manager.session()?.generation === sessionGeneration) {
            try {
              const closed = await manager.closeView(
                command.viewId,
                epoch,
                'discard',
                AbortSignal.timeout(closeBudgetMillis),
              )
              if (closed.state !== 'CLOSED' || closed.cleanup.complete !== true) {
                cleanupFailed = true
              }
            } catch {
              cleanupFailed = true
            }
          } else {
            cleanupFailed = true
          }
        }
        if (cleanupFailed) {
          commitRevision()
          views.set(command.viewId, {
            viewId: command.viewId,
            browserSessionId: command.browserSessionId,
            eventNodeId: command.eventNodeId,
            connectionId: command.connectionId,
            claimId: command.claimId,
            bindingEpoch: epoch,
            directory: command.directory ?? 'unavailable',
            workspacePath,
            workspaceOwned: true,
            state: 'CLOSING',
            cleanupFailed: true,
            createdAt: scheduler.now(),
            lastProofAt: null,
            expiresAt: 0,
            streams: new Set(),
            sessionGeneration: sessionGeneration ?? 0,
          })
          emit({
            kind: 'cleanup-failed',
            viewId: command.viewId,
            message: 'failed view registration could not be cleaned up',
          })
        } else {
          await rm(workspacePath, { force: true })
        }
      } else if (workspacePath !== undefined) {
        await rm(workspacePath, { force: true })
      }
      throw error
    } finally {
      reservations.delete(command.viewId)
      scheduleIdleStop()
    }
  }

  const markBindingUnverified = (view: ViewRecord): void => {
    if (views.get(view.viewId) !== view) {
      return
    }
    view.bindingPending = false
    if (view.state === 'CLOSING' && view.cleanupFailed) {
      return
    }
    commitRevision()
    view.state = 'CLOSING'
    view.cleanupFailed = true
    view.forceAllowed = true
    view.nativeClosePending = false
    // CLOSING makes the lease scheduler clear the expiry timer without deleting or touching files.
    scheduleExpiry(view)
    emit({
      kind: 'cleanup-failed',
      viewId: view.viewId,
      message: 'resume binding outcome could not be verified',
    })
  }

  const adoptRegisteredBinding = (
    view: ViewRecord,
    command: ParsedOpenCommand,
    epoch: string,
    sessionGeneration: number,
  ): IdeReply => {
    commitRevision()
    view.bindingEpoch = epoch
    view.eventNodeId = command.eventNodeId
    view.connectionId = command.connectionId
    view.claimId = command.claimId
    view.state = 'OPEN'
    view.cleanupFailed = false
    view.forceAllowed = false
    view.lastProofAt = null
    view.createdAt = scheduler.now()
    view.sessionGeneration = sessionGeneration
    view.bindingPending = false
    bridge.assign(view.viewId, view.workspacePath, epoch, view.directory)
    scheduleExpiry(view)
    rememberClaim(command.claimId, {
      shape: openCommandShape(command),
      viewId: view.viewId,
      bindingEpoch: epoch,
    })
    return openReply(view, snapshot())
  }

  /**
   * Reconciles a failed/uncertain native resume without guessing.
   *
   * <p>The native registry is the only proof of which epoch is live. If it still reports the old
   * OPEN binding, that binding stays authoritative. If it reports the new OPEN binding, the
   * response was merely lost and the binding can be adopted. Anything else keeps the workspace
   * counted as CLOSING/cleanupFailed so a later explicit close can snapshot dirty text.
   */
  const reconcileResumeFailure = async (
    view: ViewRecord,
    command: ParsedOpenCommand,
    epoch: string,
    crashAtStart: number,
    sessionGeneration: number,
  ): Promise<IdeReply | null> => {
    const sameGeneration = (): boolean =>
      getCrashEpoch() === crashAtStart &&
      manager.session()?.generation === sessionGeneration &&
      views.get(view.viewId) === view
    if (!sameGeneration()) {
      markBindingUnverified(view)
      return null
    }
    let nativeViews: ManagedViewInspection[]
    try {
      nativeViews = await manager.listViews(AbortSignal.timeout(closeBudgetMillis))
    } catch {
      markBindingUnverified(view)
      return null
    }
    if (!sameGeneration()) {
      markBindingUnverified(view)
      return null
    }
    const native = nativeViews.find((candidate) => candidate.viewId === view.viewId)
    if (native !== undefined && native.state === 'OPEN' && native.workspacePath === view.workspacePath) {
      if (native.bindingEpoch === epoch) {
        return adoptRegisteredBinding(view, command, epoch, sessionGeneration)
      }
      if (native.bindingEpoch === view.bindingEpoch) {
        view.bindingPending = false
        return null
      }
    }
    markBindingUnverified(view)
    return null
  }

  const resumeView = async (
    command: ParsedOpenCommand,
    view: ViewRecord,
    signal: AbortSignal,
  ): Promise<IdeReply> => {
    if (view.state !== 'OPEN') {
      throw new IdeError('view is closing', 'BUSY')
    }
    if (view.cleanupFailed) {
      throw new IdeError('view cleanup is incomplete', 'CLEANUP_FAILED')
    }
    if (view.browserSessionId !== command.browserSessionId) {
      throw new IdeError('view is owned by another browser session', 'VIEW_OWNED_BY_OTHER_SESSION')
    }
    if (view.bindingEpoch !== command.expectedBindingEpoch) {
      throw new IdeError('view binding changed', 'STALE_BINDING')
    }
    const requestedDirectory = command.directory === undefined
      ? undefined
      : await canonicalDirectory(command.directory)
    if (requestedDirectory !== undefined && requestedDirectory !== view.directory) {
      throw new IdeError('resume must not change the workspace directory', 'VIEW_IN_USE')
    }
    await awaitCrashRecovery(signal)
    await awaitManagerStopped(signal)
    cancelIdle()
    const crashAtStart = getCrashEpoch()
    const sessionGeneration = manager.session()?.generation
    if (sessionGeneration === undefined) {
      throw new IdeError('code-server is not running', 'UNAVAILABLE')
    }
    view.bindingPending = true
    const epoch = commitRevision()
    try {
      abortViewStreams(view)
      const registered = await manager.registerView(view.viewId, epoch, view.workspacePath, signal)
      if (registered.state !== 'OPEN' || registered.bindingEpoch !== epoch) {
        throw new IdeError('managed view did not accept the new binding epoch', 'CLEANUP_FAILED')
      }
      if (
        getCrashEpoch() !== crashAtStart ||
        manager.session()?.generation !== sessionGeneration ||
        views.get(view.viewId) !== view ||
        view.state !== 'OPEN'
      ) {
        throw new IdeError('code-server process changed during view resume', 'BUSY')
      }
      return adoptRegisteredBinding(view, command, epoch, sessionGeneration)
    } catch (error) {
      const adopted = await reconcileResumeFailure(view, command, epoch, crashAtStart, sessionGeneration)
      if (adopted !== null) {
        return adopted
      }
      throw error
    } finally {
      view.bindingPending = false
    }
  }

  const openView = async (command: IdeCommand, signal: AbortSignal): Promise<IdeReply> => {
    requireDaemonInstance(command, daemonInstanceId)
    const parsed = parseOpenCommand(command)
    const shape = openCommandShape(parsed)
    const replayed = replayClaim(parsed.claimId, shape)
    if (replayed !== null) {
      return replayed
    }
    return withClaimLock(parsed.claimId, () =>
      withViewLock(parsed.viewId, async () => {
        const replay = replayClaim(parsed.claimId, shape)
        if (replay !== null) {
          return replay
        }
        const existing = views.get(parsed.viewId)
        if (parsed.mode === 'resume') {
          if (existing === undefined) {
            throw new IdeError('view is gone', 'VIEW_GONE')
          }
          return resumeView(parsed, existing, signal)
        }
        if (existing !== undefined) {
          // A create is idempotent only when its claim replay was already accepted above.
          // The durable resource stays with its owner; an old expected epoch is stale.
          if (existing.browserSessionId !== parsed.browserSessionId) {
            throw new IdeError('view is owned by another browser session', 'VIEW_OWNED_BY_OTHER_SESSION')
          }
          throw new IdeError('view already exists for this session', 'STALE_BINDING')
        }
        return createView(parsed, signal)
      }),
    )
  }

  return { openView, requireBoundView }
}
