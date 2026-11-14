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
    environmentId: string,
    attempt: number,
    patch: Partial<IdeViewState>,
  ) => IdeViewState | null
  registerUsageNow: (override: {
    environmentId: string
    viewId: string
    claimId: string
    bindingEpoch?: string
  }) => Promise<unknown>
  /** 视图未建立时记下终端意图，等视图创建时由创建方取走。 */
  rememberPendingShellIntent: (environmentId: string) => void
}

/**
 * 视图内终端：只有当视图已 READY 且绑定完整时才发命令，且必须在 iframe 已加载后
 * （终端的 DOM 在 iframe 里）。失败只记录可读原因，不回滚已建立的绑定。
 */
export function useIdeTerminalCommands({
  viewsRef,
  sessionRef,
  identityRef,
  updateView,
  registerUsageNow,
  rememberPendingShellIntent,
}: IdeTerminalOptions) {
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
    const requestIdentity = identityRef.current
    try {
      await registerUsageNow({
        environmentId,
        viewId: view.viewId,
        claimId: view.claimId,
        bindingEpoch: view.bindingEpoch,
      })
      const body: EnvironmentIdeViewBindingDTO = {
        browserSessionId: sessionRef.current.browserSessionId,
        bindingEpoch: view.bindingEpoch,
        expectedDaemonInstanceId: view.daemonInstanceId,
      }
      await focusIdeTerminal(environmentId, view.viewId, body)
    } catch (error) {
      const current = viewsRef.current[environmentId]
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
      updateView(environmentId, view.attempt, {
        terminalError: isUnknownIdeFailure(error)
          ? 'terminal-result-unknown'
          : safeIdeError(error) || 'terminal-failed',
      })
    }
  }

  /** 用户显式请求终端：视图未建立时把意图挂到 environment 上，等视图创建时取走。 */
  function setPendingShell(environmentId: string): void {
    const view = viewsRef.current[environmentId]
    if (view == null) {
      rememberPendingShellIntent(environmentId)
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

  /** iframe 加载完成、视图就绪且仍有挂起意图时，才真正请求终端。 */
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

  return { setPendingShell, maybeRunPendingShell }
}
