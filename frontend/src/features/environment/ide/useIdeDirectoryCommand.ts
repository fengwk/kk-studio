import type { RefObject } from 'react'
import type { EnvironmentIdeChangeDirectoryDTO } from '@/shared/api/generated/contracts'
import type { ApplicationEventConnectionIdentity } from '@/shared/app-events/protocol'
import type { IdeBrowserSessionState } from '@/features/environment/ide/ide-browser-session'
import { compareDecimal } from '@/features/environment/ide/ide-decimal'
import { changeIdeViewDirectory } from '@/features/environment/ide/environment-ide-service'
import {
  IdeProtocolError,
  isIdeProtocolError,
  validateCommandView,
} from '@/features/environment/ide/ide-contract'
import {
  ideFailureReason,
  isUnknownIdeFailure,
  safeIdeError,
} from '@/features/environment/ide/ide-failure'
import { isSameBinding, type IdeViewState } from '@/features/environment/ide/ide-view-state'

interface IdeDirectoryCommandOptions {
  viewsRef: RefObject<Record<string, IdeViewState>>
  sessionRef: RefObject<IdeBrowserSessionState>
  identityRef: RefObject<ApplicationEventConnectionIdentity | null>
  updateView: (
    viewId: string,
    attempt: number,
    patch: Partial<IdeViewState>,
  ) => IdeViewState | null
  writeSession: () => boolean
  registerUsageNow: (override: {
    environmentId: string
    viewId: string
    claimId: string
    bindingEpoch?: string
  }) => Promise<unknown>
  reconcile: (
    viewId: string,
    reason: 'connection' | 'directory',
    options?: { allowRebind: boolean },
  ) => void
}

/** 切换单个 view 的目录；REST 响应仍由 Environment + viewId 定位。 */
export function useIdeDirectoryCommand({
  viewsRef,
  sessionRef,
  identityRef,
  updateView,
  writeSession,
  registerUsageNow,
  reconcile,
}: IdeDirectoryCommandOptions) {
  async function changeDirectory(
    viewId: string,
    directory: string,
  ): Promise<{ ok: boolean; message?: string }> {
    const view = viewsRef.current[viewId]
    if (
      view == null
      || view.status !== 'ready'
      || view.bindingEpoch == null
      || view.daemonInstanceId == null
      || view.closeInFlight
    ) {
      return { ok: false, message: 'view-not-ready' }
    }
    const environmentId = view.environmentId
    const epoch = view.bindingEpoch
    const requestIdentity = identityRef.current
    try {
      await registerUsageNow({ environmentId, viewId: view.viewId, claimId: view.claimId, bindingEpoch: epoch })
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
      const current = viewsRef.current[viewId]
      const appliedIdentity = identityRef.current
      if (
        requestIdentity == null
        || appliedIdentity == null
        || appliedIdentity.eventNodeId !== requestIdentity.eventNodeId
        || appliedIdentity.connectionId !== requestIdentity.connectionId
      ) {
        if (appliedIdentity != null) {
          reconcile(viewId, 'connection')
        }
        return { ok: false, message: 'binding-changed' }
      }
      if (isSameBinding(current, view)) {
        updateView(viewId, view.attempt, {
          daemonInstanceId: validated.daemonInstanceId,
          bindingEpoch: validated.bindingEpoch,
          eventNodeId: validated.eventNodeId,
          connectionId: validated.connectionId,
          directory: validated.directory,
          workspacePath: validated.workspacePath,
        })
        writeSession()
        if (validated.bindingEpoch !== epoch) {
          reconcile(viewId, 'directory', { allowRebind: true })
        }
      }
      return { ok: true }
    } catch (error) {
      const current = viewsRef.current[viewId]
      if (isSameBinding(current, view)) {
        if (isIdeProtocolError(error)) {
          updateView(viewId, view.attempt, { status: 'error', errorMessage: error.message })
          return { ok: false, message: error.message }
        }
        const unknown = isUnknownIdeFailure(error)
          || ideFailureReason(error) === 'IDE_OUTCOME_UNKNOWN'
          || ideFailureReason(error) === 'STALE_BINDING'
        if (unknown) {
          reconcile(viewId, 'directory', { allowRebind: false })
          return { ok: false, message: 'directory-result-unknown' }
        }
        return { ok: false, message: safeIdeError(error) || 'directory-failed' }
      }
      return { ok: false, message: 'binding-changed' }
    }
  }

  return { changeDirectory }
}
