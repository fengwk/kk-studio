import { realpath, rm } from 'node:fs/promises'
import { join } from 'node:path'
import type { IdeCommand, IdeReply } from '../contracts.js'
import { ensurePrivateDirectory, writePrivateFile } from '../native/index.js'
import type { IdeBridge } from './bridge.js'
import type { CodeServerManager, IdePaths } from './codeServer.js'
import { IdeError } from './errors.js'
import type { IdeScheduler, IdeServiceEvent } from './service.js'
import type { ClaimRecord, ViewRecord } from './serviceTypes.js'
import {
  canonicalDirectory, hostHome, validateBindingEpoch, validateUuid, validateViewId, workspaceFileContent,
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
  proofExclusivityMillis: number
  closeBudgetMillis: number
  getCrashEpoch: () => number
  emit: (event: IdeServiceEvent) => void
  withViewLock: <T>(viewId: string, task: () => Promise<T>) => Promise<T>
  withClaimLock: <T>(claimId: string, task: () => Promise<T>) => Promise<T>
  scheduleExpiry: (view: ViewRecord, notBefore?: number) => void
  scheduleIdleStop: () => void
  cancelIdle: () => void
  allocateEpoch: () => string
  awaitCrashRecovery: (signal: AbortSignal) => Promise<void>
  awaitManagerStopped: (signal: AbortSignal) => Promise<void>
  convergeAfterCrash: () => Promise<void>
  abortViewStreams: (view: ViewRecord) => void
  required: <T>(value: T | undefined, field: string, code?: string) => T
  boundedText: (value: string | undefined, field: string) => string | undefined
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

export function createViewRegistry({
  paths, views, reservations, claims, scheduler, bridge, manager, maxViews, proofExclusivityMillis,
  closeBudgetMillis, getCrashEpoch, emit, withViewLock, withClaimLock, scheduleExpiry, scheduleIdleStop,
  cancelIdle, allocateEpoch, awaitCrashRecovery, awaitManagerStopped, convergeAfterCrash, abortViewStreams,
  required, boundedText,
}: RegistryDependencies) {
  const openCommandShape = (command: IdeCommand): string => {
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
    const crashAtStart = getCrashEpoch()
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
      bridge.assign(viewId, workspacePath, epoch, directory)
      const session = await manager.ensureStarted(signal)
      sessionGeneration = session.generation
      if (getCrashEpoch() !== crashAtStart) {
        throw new IdeError('code-server process changed during view creation', 'BUSY')
      }
      const registered = await manager.registerView(viewId, epoch, workspacePath, signal)
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
        if (getCrashEpoch() === crashAtStart && sessionGeneration !== undefined) {
          if (manager.session()?.generation === sessionGeneration) {
            try {
              await manager.closeView(viewId, epoch, 'discard', AbortSignal.timeout(closeBudgetMillis))
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
      const occupiedUntil = (view.lastProofAt ?? view.createdAt) + proofExclusivityMillis
      if (scheduler.now() < occupiedUntil) {
        throw new IdeError('view is in use by another document', 'VIEW_IN_USE')
      }
    }
    await awaitCrashRecovery(signal)
    await awaitManagerStopped(signal)
    cancelIdle()
    const crashAtStart = getCrashEpoch()
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
    if (getCrashEpoch() !== crashAtStart || manager.session()?.generation !== sessionGeneration) {
      throw new IdeError('code-server process changed during view resume', 'BUSY')
    }
    view.bindingEpoch = epoch
    view.documentId = documentId
    view.eventNodeId = eventNodeId
    view.connectionId = connectionId
    view.claimId = claimId
    view.state = 'OPEN'
    view.cleanupFailed = false
    view.forceAllowed = false
    view.lastProofAt = null
    view.createdAt = scheduler.now()
    view.sessionGeneration = sessionGeneration
    dropClaimsForView(view.viewId)
    bridge.assign(view.viewId, view.workspacePath, epoch, view.directory)
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

  return { openView, requireBoundView, dropClaimsForView }
}
