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
    environmentId: string,
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
  /** 以串行队列重新对账；`directory` 与 `connection` 是这里唯一会用的原因。 */
  reconcile: (
    environmentId: string,
    reason: 'connection' | 'directory',
    options?: { allowRebind: boolean },
  ) => void
}

/**
 * 切换视图的目录。
 *
 * <p>server 返回的绑定坐标可能推进 epoch（daemon 重建了绑定），因此响应必须先通过
 * 协议校验：实例不变、epoch 不回退、同 epoch 下工作区不得变化；校验失败按协议错误处理，
 * 而不是静默接受一个更旧或更松的绑定。
 */
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
    environmentId: string,
    directory: string,
  ): Promise<{ ok: boolean; message?: string }> {
    const view = viewsRef.current[environmentId]
    if (
      view == null
      || view.status !== 'ready'
      || view.bindingEpoch == null
      || view.daemonInstanceId == null
      || view.closeInFlight
    ) {
      return { ok: false, message: 'view-not-ready' }
    }
    const epoch = view.bindingEpoch
    const requestIdentity = identityRef.current
    try {
      await registerUsageNow({
        environmentId,
        viewId: view.viewId,
        claimId: view.claimId,
        bindingEpoch: epoch,
      })
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
      const current = viewsRef.current[environmentId]
      const appliedIdentity = identityRef.current
      if (
        requestIdentity == null
        || appliedIdentity == null
        || appliedIdentity.eventNodeId !== requestIdentity.eventNodeId
        || appliedIdentity.connectionId !== requestIdentity.connectionId
      ) {
        if (appliedIdentity != null) {
          reconcile(environmentId, 'connection')
        }
        return { ok: false, message: 'binding-changed' }
      }
      if (isSameBinding(current, view)) {
        updateView(environmentId, view.attempt, {
          daemonInstanceId: validated.daemonInstanceId,
          bindingEpoch: validated.bindingEpoch,
          eventNodeId: validated.eventNodeId,
          connectionId: validated.connectionId,
          directory: validated.directory,
          workspacePath: validated.workspacePath,
        })
        writeSession()
        if (validated.bindingEpoch !== epoch) {
          reconcile(environmentId, 'directory', { allowRebind: true })
        }
      }
      return { ok: true }
    } catch (error) {
      const current = viewsRef.current[environmentId]
      if (isSameBinding(current, view)) {
        if (isIdeProtocolError(error)) {
          updateView(environmentId, view.attempt, {
            status: 'error',
            errorMessage: error.message,
          })
          return { ok: false, message: error.message }
        }
        const unknown = isUnknownIdeFailure(error)
          || ideFailureReason(error) === 'IDE_OUTCOME_UNKNOWN'
          || ideFailureReason(error) === 'STALE_BINDING'
        if (unknown) {
          reconcile(environmentId, 'directory', { allowRebind: false })
          return { ok: false, message: 'directory-result-unknown' }
        }
        return {
          ok: false,
          message: safeIdeError(error) || 'directory-failed',
        }
      }
      return { ok: false, message: 'binding-changed' }
    }
  }

  return { changeDirectory }
}
