import type { RefObject } from 'react'
import type { EnvironmentIdeViewBindingDTO } from '@/shared/api/generated/contracts'
import type { ApplicationEventConnectionIdentity } from '@/shared/app-events/protocol'
import type { IdeBrowserSessionState } from '@/features/environment/ide/ide-browser-session'
import { isUnknownIdeFailure, safeIdeError } from '@/features/environment/ide/ide-failure'
import { focusIdeTerminal } from '@/features/environment/ide/environment-ide-service'
import { isSameBinding, type IdeViewState } from '@/features/environment/ide/ide-view-state'

interface IdeTerminalOptions {
  viewsRef: RefObject<Record<string, IdeViewState>>
  sessionRef: RefObject<IdeBrowserSessionState>
  identityRef: RefObject<ApplicationEventConnectionIdentity | null>
  updateView: (
    viewId: string,
    attempt: number,
    patch: Partial<IdeViewState>,
  ) => IdeViewState | null
  registerUsageNow: (override: {
    environmentId: string
    viewId: string
    claimId: string
    bindingEpoch?: string
  }) => Promise<unknown>
  rememberPendingShellIntent: (environmentId: string) => void
}

/** 终端命令与 iframe、usage proof 均绑定到指定 view。 */
export function useIdeTerminalCommands({
  viewsRef,
  sessionRef,
  identityRef,
  updateView,
  registerUsageNow,
  rememberPendingShellIntent,
}: IdeTerminalOptions) {
  async function runTerminal(viewId: string): Promise<void> {
    const view = viewsRef.current[viewId]
    if (
      view == null
      || view.status !== 'ready'
      || view.bindingEpoch == null
      || view.daemonInstanceId == null
      || view.closeInFlight
    ) {
      return
    }
    updateView(viewId, view.attempt, { pendingShell: false, terminalError: undefined })
    const requestIdentity = identityRef.current
    try {
      await registerUsageNow({
        environmentId: view.environmentId,
        viewId: view.viewId,
        claimId: view.claimId,
        bindingEpoch: view.bindingEpoch,
      })
      const body: EnvironmentIdeViewBindingDTO = {
        browserSessionId: sessionRef.current.browserSessionId,
        bindingEpoch: view.bindingEpoch,
        expectedDaemonInstanceId: view.daemonInstanceId,
      }
      await focusIdeTerminal(view.environmentId, view.viewId, body)
    } catch (error) {
      const current = viewsRef.current[viewId]
      const appliedIdentity = identityRef.current
      if (
        !isSameBinding(current, view)
        || requestIdentity == null
        || appliedIdentity == null
        || appliedIdentity.eventNodeId !== requestIdentity.eventNodeId
        || appliedIdentity.connectionId !== requestIdentity.connectionId
      ) {
        return
      }
      updateView(viewId, view.attempt, {
        terminalError: isUnknownIdeFailure(error)
          ? 'terminal-result-unknown'
          : safeIdeError(error) || 'terminal-failed',
      })
    }
  }

  /** 没有可用 view 时把待处理意图按 Environment 记下，由新建 view 消费。 */
  function setPendingShell(environmentId: string, viewId?: string): void {
    const view = viewId == null ? undefined : viewsRef.current[viewId]
    if (view == null) {
      rememberPendingShellIntent(environmentId)
      return
    }
    if (view.pendingShell && view.terminalError === undefined) {
      return
    }
    updateView(view.viewId, view.attempt, { pendingShell: true, terminalError: undefined })
  }

  function maybeRunPendingShell(viewId: string): void {
    const view = viewsRef.current[viewId]
    if (
      view == null
      || !view.pendingShell
      || !view.iframeLoaded
      || view.status !== 'ready'
      || view.closeInFlight
    ) {
      return
    }
    void runTerminal(viewId)
  }

  return { setPendingShell, maybeRunPendingShell }
}
