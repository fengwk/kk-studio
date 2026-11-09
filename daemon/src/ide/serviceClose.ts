import { rm } from 'node:fs/promises'
import type { DaemonConfig, IdeCommand, IdeReply } from '../contracts.js'
import type { BridgeDocument, BridgeResult, IdeBridge } from './bridge.js'
import type { CodeServerManager, ManagedViewCloseMode } from './codeServer.js'
import { IdeError, failureReply } from './errors.js'
import type { IdeScheduler, IdeServiceEvent } from './service.js'
import type { IdeSnapshot, ViewRecord } from './serviceTypes.js'
import { RecoveryQuotaError, validateViewId, writeRecoveryBackup, type RecoveryBackup } from './workspace.js'

interface ClosePathDependencies {
  config: DaemonConfig
  views: Map<string, ViewRecord>
  scheduler: IdeScheduler
  bridge: IdeBridge
  manager: CodeServerManager
  lifetime: AbortController
  recoveryQuotaBytes: number
  closeBudgetMillis: number
  expiryMillis: number
  emit: (event: IdeServiceEvent) => void
  withViewLock: <T>(viewId: string, task: () => Promise<T>) => Promise<T>
  clearTimer: (view: ViewRecord) => void
  scheduleExpiry: (view: ViewRecord, notBefore?: number) => void
  commitRevision: () => string
  snapshot: () => IdeSnapshot
  scheduleIdleStop: () => void
  waitForViewStreams: (view: ViewRecord) => Promise<void>
  waitWithSignal: <T>(promise: Promise<T>, signal: AbortSignal) => Promise<T>
  required: <T>(value: T | undefined, field: string, code?: string) => T
  requireBoundView: (command: IdeCommand, options: { requireOpen: boolean }) => ViewRecord
}

export function createClosePath({
  config, views, scheduler, bridge, manager, lifetime, recoveryQuotaBytes, closeBudgetMillis,
  expiryMillis, emit, withViewLock, clearTimer, scheduleExpiry, commitRevision, snapshot,
  scheduleIdleStop, waitForViewStreams, waitWithSignal, required, requireBoundView,
}: ClosePathDependencies) {
  const rollbackOpen = (view: ViewRecord): void => {
    if (views.get(view.viewId) !== view || view.cleanupFailed) {
      return
    }
    if (view.state !== 'OPEN') {
      commitRevision()
    }
    view.state = 'OPEN'
    // A cancellation or lost control connection must not immediately trigger expired cleanup.
    scheduleExpiry(view, scheduler.now() + expiryMillis)
  }

  const performClose = (view: ViewRecord, mode: ManagedViewCloseMode): Promise<IdeReply> => {
    if (view.closePromise !== undefined) {
      return view.closePromise
    }
    const operation = (async (): Promise<IdeReply> => {
      const viewId = view.viewId
      const epoch = view.bindingEpoch
      try {
        // Normal close reaches here only after the native confirmation succeeded.
        await waitForViewStreams(view)
        if (views.get(viewId) !== view || view.bindingEpoch !== epoch) {
          return { ok: false, code: 'VIEW_GONE', message: 'view binding changed' }
        }
        if (mode !== 'normal' && bridge.isConnected(viewId, epoch)) {
          const bridgeResult = await bridge.request(
            viewId, { op: 'closeView', discard: true }, lifetime.signal, { expectedEpoch: epoch },
          )
          if (!bridgeResult.ok) {
            throw new IdeError(bridgeResult.message ?? 'ide window did not confirm close', 'CLEANUP_FAILED')
          }
        }
        if (views.get(viewId) !== view || view.bindingEpoch !== epoch) {
          return { ok: false, code: 'VIEW_GONE', message: 'view binding changed' }
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
        if (view.workspaceOwned) {
          await rm(view.workspacePath, { force: true })
        }
        commitRevision()
        views.delete(viewId)
        scheduleIdleStop()
        return { ok: true, ...snapshot() }
      } catch (error) {
        if (views.get(viewId) === view && view.bindingEpoch === epoch) {
          view.cleanupFailed = true
          if (view.state !== 'CLOSING') {
            commitRevision()
          }
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
          snapshot(),
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
      const signal = AbortSignal.any([lifetime.signal, AbortSignal.timeout(closeBudgetMillis)])
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
      const browserSessionId = view.browserSessionId
      if (mode === 'discard' && bridge.isConnected(viewId, epoch) && !view.forceAllowed) {
        throw new IdeError('force close is only available when the window state is unknown', 'BUSY')
      }
      // Keep the data plane available while the native dialog awaits the user.
      view.nativeClosePending = mode === 'normal'
      if (mode !== 'normal') {
        commitRevision()
        view.state = 'CLOSING'
      }
      clearTimer(view)
      if (mode === 'normal') {
        if (!bridge.isConnected(view.viewId, epoch)) {
          view.forceAllowed = true
          view.nativeClosePending = false
          rollbackOpen(view)
          throw new IdeError('IDE window is not connected; close outcome is unknown', 'UNAVAILABLE')
        }
        let result: BridgeResult
        try {
          const closeSignal = AbortSignal.any([signal, lifetime.signal])
          result = await waitWithSignal(
            bridge.request(view.viewId, { op: 'closeView' }, closeSignal, { expectedEpoch: epoch }),
            closeSignal,
          )
        } catch (error) {
          if (views.get(view.viewId) === view && view.bindingEpoch === epoch) {
            view.forceAllowed = true
            view.nativeClosePending = false
            rollbackOpen(view)
          }
          throw error
        }
        if (views.get(view.viewId) !== view || view.bindingEpoch !== epoch || view.browserSessionId !== browserSessionId) {
          throw new IdeError('view binding changed', 'VIEW_GONE')
        }
        if (signal.aborted || lifetime.signal.aborted || !bridge.isConnected(viewId, epoch)) {
          view.forceAllowed = true
          view.nativeClosePending = false
          rollbackOpen(view)
          throw new IdeError('IDE close outcome is unknown', 'UNAVAILABLE')
        }
        if (!result.ok) {
          view.forceAllowed = result.code !== 'CANCELLED'
          view.nativeClosePending = false
          rollbackOpen(view)
          throw new IdeError(
            result.message ?? 'IDE close outcome is unknown',
            result.code === 'CANCELLED' ? 'CANCELLED' : 'UNAVAILABLE',
          )
        }
        view.forceAllowed = false
        view.nativeClosePending = false
        commitRevision()
        view.state = 'CLOSING'
      } else {
        await snapshotForClose(view)
      }
      if (views.get(view.viewId) !== view || view.bindingEpoch !== epoch || view.browserSessionId !== browserSessionId) {
        throw new IdeError('view binding changed', 'VIEW_GONE')
      }
      return performClose(view, mode)
    })
  }

  const expireView = (viewId: string, epoch: string): Promise<void> => {
    return withViewLock(viewId, async () => {
      const view = views.get(viewId)
      if (view === undefined || view.bindingEpoch !== epoch || view.state !== 'OPEN') {
        return
      }
      commitRevision()
      view.state = 'CLOSING'
      clearTimer(view)
      // An expired lease is not permission to discard a dirty or unobservable editor.
      // Keep the view for an explicit user decision when the window cannot prove it is clean.
      let confirmed = false
      try {
        if (bridge.isConnected(viewId, epoch)) {
          const timeout = AbortSignal.any([lifetime.signal, AbortSignal.timeout(closeBudgetMillis)])
          const inspection = await bridge.request(viewId, { op: 'inspect' }, timeout, { expectedEpoch: epoch })
          if (inspection.ok && inspection.clean === true && bridge.isConnected(viewId, epoch)) {
            const result = await bridge.request(viewId, { op: 'closeView' }, timeout, { expectedEpoch: epoch })
            confirmed = result.ok && bridge.isConnected(viewId, epoch) && !timeout.aborted
          }
        }
      } catch {
        // No confirmation: retain the view rather than guessing that its content is disposable.
      }
      if (views.get(viewId) !== view || view.bindingEpoch !== epoch) {
        return
      }
      if (!confirmed) {
        commitRevision()
        view.state = 'OPEN'
        view.forceAllowed = !bridge.isConnected(viewId, epoch)
        emit({ kind: 'expiry-deferred', viewId, message: 'view expiry requires an explicit close' })
        return
      }
      await performClose(view, 'normal')
    }).then(
      () => undefined,
      () => undefined,
    )
  }

  return { closeView, expireView }
}
