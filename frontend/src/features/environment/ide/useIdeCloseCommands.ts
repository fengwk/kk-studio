import type { RefObject } from 'react'
import type { EnvironmentIdeCloseViewDTO } from '@/shared/api/generated/contracts'
import type { IdeBrowserSessionState } from '@/features/environment/ide/ide-browser-session'
import { ideFailureReason, isUnknownIdeFailure, safeIdeError } from '@/features/environment/ide/ide-failure'
import { closeIdeView } from '@/features/environment/ide/environment-ide-service'
import { isSameBinding, type IdeViewState } from '@/features/environment/ide/ide-view-state'
import type { IdeCloseResult } from '@/features/environment/ide/IdeOverlayPanel'

interface IdeViewCommandsOptions {
  viewsRef: RefObject<Record<string, IdeViewState>>
  sessionRef: RefObject<IdeBrowserSessionState>
  updateView: (
    viewId: string,
    attempt: number,
    patch: Partial<IdeViewState>,
  ) => IdeViewState | null
  commitViews: (next: Record<string, IdeViewState>) => void
  /** 关闭成功后丢弃该视图的快照记录。 */
  forgetView: (daemonInstanceId: string, viewId: string) => void
  writeSession: () => boolean
  registerUsageNow: (override: {
    environmentId: string
    viewId: string
    claimId: string
    bindingEpoch?: string
  }) => Promise<unknown>
  registerUsageQuiet: () => void
}

/** 单 view 的关闭与错误提示；服务端坐标始终由该 view 的归属和绑定提供。 */
export function useIdeCloseCommands({
  viewsRef,
  sessionRef,
  updateView,
  commitViews,
  forgetView,
  writeSession,
  registerUsageNow,
  registerUsageQuiet,
}: IdeViewCommandsOptions) {
  async function closeView(viewId: string, discard: boolean): Promise<IdeCloseResult> {
    const view = viewsRef.current[viewId]
    if (view == null) {
      return { ok: true, requiresForceConfirmation: false }
    }
    if (
      view.bindingEpoch == null
      || view.daemonInstanceId == null
      || view.closeInFlight
      || view.status === 'binding'
      || view.status === 'reconciling'
    ) {
      return { ok: false, requiresForceConfirmation: false, message: 'view-not-ready' }
    }
    updateView(viewId, view.attempt, { closeInFlight: true, closeError: undefined })
    try {
      await registerUsageNow({
        environmentId: view.environmentId,
        viewId: view.viewId,
        claimId: view.claimId,
        bindingEpoch: view.bindingEpoch,
      })
      const body: EnvironmentIdeCloseViewDTO = {
        browserSessionId: sessionRef.current.browserSessionId,
        bindingEpoch: view.bindingEpoch,
        expectedDaemonInstanceId: view.daemonInstanceId,
        discard,
      }
      await closeIdeView(view.environmentId, view.viewId, body)
      const current = viewsRef.current[viewId]
      if (!isSameBinding(current, view)) {
        return { ok: false, requiresForceConfirmation: false, message: 'binding-changed' }
      }
      const next = { ...viewsRef.current }
      delete next[viewId]
      forgetView(view.daemonInstanceId, view.viewId)
      commitViews(next)
      writeSession()
      registerUsageQuiet()
      return { ok: true, requiresForceConfirmation: false }
    } catch (error) {
      const current = viewsRef.current[viewId]
      if (!isSameBinding(current, view)) {
        return { ok: false, requiresForceConfirmation: false, message: 'binding-changed' }
      }
      const reason = ideFailureReason(error)
      const outcomeUnknown = isUnknownIdeFailure(error) || reason === 'IDE_OUTCOME_UNKNOWN'
      const controlUnavailable = reason === 'IDE_CONTROL_UNAVAILABLE'
      const requiresForceConfirmation = !discard && (outcomeUnknown || controlUnavailable)
      const closeError = controlUnavailable
        ? 'close-control-unavailable'
        : outcomeUnknown
          ? 'close-result-unknown'
          : safeIdeError(error) || 'close-failed'
      updateView(viewId, view.attempt, { closeInFlight: false, closeError })
      return {
        ok: false,
        requiresForceConfirmation,
        message: closeError,
      }
    }
  }

  function dismissCloseError(viewId: string): void {
    const view = viewsRef.current[viewId]
    if (view == null || view.closeError == null) {
      return
    }
    updateView(viewId, view.attempt, { closeError: undefined })
  }

  return { closeView, closeEnvironment: closeView, dismissCloseError }
}
