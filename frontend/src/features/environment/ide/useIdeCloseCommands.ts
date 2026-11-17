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
    environmentId: string,
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

/**
 * 单个视图的命令：关闭与其错误提示。
 *
 * <p>关闭是有副作用的命令，因此先用浏览器会话与 server 冻结坐标发起，再以绑定未变作为
 * 落地的唯一条件；`discard` 只在用户显式确认后为真，未确认时的“未知结果”只能报告 dirty。
 */
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
  async function closeEnvironment(environmentId: string, discard: boolean): Promise<IdeCloseResult> {
    const view = viewsRef.current[environmentId]
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
    updateView(environmentId, view.attempt, {
      closeInFlight: true,
      closeError: undefined,
    })
    try {
      await registerUsageNow({
        environmentId,
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
      await closeIdeView(environmentId, view.viewId, body)
      const current = viewsRef.current[environmentId]
      if (!isSameBinding(current, view)) {
        return { ok: false, requiresForceConfirmation: false, message: 'binding-changed' }
      }
      const next = { ...viewsRef.current }
      delete next[environmentId]
      forgetView(view.daemonInstanceId, view.viewId)
      commitViews(next)
      writeSession()
      registerUsageQuiet()
      return { ok: true, requiresForceConfirmation: false }
    } catch (error) {
      const current = viewsRef.current[environmentId]
      if (!isSameBinding(current, view)) {
        return { ok: false, requiresForceConfirmation: false, message: 'binding-changed' }
      }
      const reason = ideFailureReason(error)
      const outcomeUnknown = isUnknownIdeFailure(error) || reason === 'IDE_OUTCOME_UNKNOWN'
      const controlUnavailable = reason === 'IDE_CONTROL_UNAVAILABLE'
      const requiresForceConfirmation = !discard && (outcomeUnknown || controlUnavailable)
      const closeError = requiresForceConfirmation
        ? undefined
        : outcomeUnknown
          ? 'close-result-unknown'
          : safeIdeError(error) || 'close-failed'
      updateView(environmentId, view.attempt, {
        closeInFlight: false,
        closeError,
      })
      return {
        ok: false,
        requiresForceConfirmation,
        message: requiresForceConfirmation
          ? (controlUnavailable ? 'close-control-unavailable' : 'close-result-unknown')
          : safeIdeError(error),
      }
    }
  }

  function dismissCloseError(environmentId: string): void {
    const view = viewsRef.current[environmentId]
    if (view == null || view.closeError == null) {
      return
    }
    updateView(environmentId, view.attempt, { closeError: undefined })
  }

  return { closeEnvironment, dismissCloseError }
}
