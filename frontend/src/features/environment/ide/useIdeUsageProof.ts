import { useRef, type RefObject } from 'react'
import type { ApplicationEventConnectionIdentity } from '@/shared/app-events/protocol'
import type { IdeBrowserSessionState } from '@/features/environment/ide/ide-browser-session'
import {
  saveIdeSession,
  type IdePresentation,
  type IdeTabStorage,
} from '@/features/environment/ide/ide-tab-state'
import { buildUsageViews, recoveryFromViews, type IdeViewState } from '@/features/environment/ide/ide-view-state'

/** 使用证明里对单个 view 的临时覆盖：本次证明以这个 claim 声明该 view。 */
export interface IdeUsageOverride {
  environmentId: string
  viewId: string
  claimId: string
  bindingEpoch?: string
}

interface IdePresentationView {
  openedEnvironmentIds: string[]
}

export interface IdeSessionWriterOptions {
  storage?: IdeTabStorage
  sessionRef: RefObject<IdeBrowserSessionState>
  viewsRef: RefObject<Record<string, IdeViewState>>
  mountedRef: RefObject<boolean>
  getPresentation: () => IdePresentation
  onStorageStatusChange: (unavailable: boolean) => void
}

/** 把本 tab 的可恢复身份写回 sessionStorage，并把写失败如实报告给宿主。 */
export function useIdeSessionWriter({
  storage,
  sessionRef,
  viewsRef,
  mountedRef,
  getPresentation,
  onStorageStatusChange,
}: IdeSessionWriterOptions) {
  function writeSession(): boolean {
    const presentation = getPresentation()
    const saved = saveIdeSession(
      sessionRef.current.browserSessionId,
      presentation,
      recoveryFromViews(viewsRef.current, presentation.openedEnvironmentIds),
      storage,
    )
    if (mountedRef.current) {
      onStorageStatusChange(!saved)
    }
    return saved
  }

  return { writeSession }
}

export interface IdeUsageProofOptions {
  identityRef: RefObject<ApplicationEventConnectionIdentity | null>
  sessionRef: RefObject<IdeBrowserSessionState>
  viewsRef: RefObject<Record<string, IdeViewState>>
  getPresentation: () => IdePresentationView
  sessionReady: () => boolean
  isPageSuspended: () => boolean
  setPageSuspended: (suspended: boolean) => void
  registerUsage: (usage: {
    browserSessionId: string
    revision: string
    views: ReturnType<typeof buildUsageViews>
  }) => Promise<unknown>
}

/**
 * 浏览器使用证明：声明本 tab 仍持有哪些 view 意图。
 *
 * <p>每次证明都带新的单调 revision；发送失败不重试、不改变会话身份，后续只由新的 connected
 * 身份重新对账，因此静默失败是预期路径。
 */
export function useIdeUsageProof({
  identityRef,
  sessionRef,
  viewsRef,
  getPresentation,
  sessionReady,
  isPageSuspended,
  setPageSuspended,
  registerUsage,
}: IdeUsageProofOptions) {
  const revisionRef = useRef(0n)

  function nextRevision(): string {
    revisionRef.current += 1n
    return revisionRef.current.toString()
  }

  function usageOverride(
    environmentId: string,
    viewId: string,
    claimId: string,
    bindingEpoch?: string,
  ): IdeUsageOverride {
    return { environmentId, viewId, claimId, ...(bindingEpoch == null ? {} : { bindingEpoch }) }
  }

  function registerUsageNow(override?: IdeUsageOverride): Promise<unknown> {
    const currentIdentity = identityRef.current
    const browserSessionId = sessionRef.current.browserSessionId
    if (currentIdentity == null || !sessionReady() || isPageSuspended()) {
      return Promise.reject(new Error('application event connection is not ready'))
    }
    const opened = getPresentation().openedEnvironmentIds
    const environmentIds = override != null && !opened.includes(override.environmentId)
      ? [...opened, override.environmentId]
      : opened
    return registerUsage({
      browserSessionId,
      revision: nextRevision(),
      views: buildUsageViews(viewsRef.current, environmentIds, override),
    })
  }

  function registerUsageQuiet(override?: IdeUsageOverride): void {
    void registerUsageNow(override).catch(() => {
      // 断线由 reject 明示；后续只由新的 connected 身份重新对账，不在这里重试。
    })
  }

  /** 全量重置（复制 tab）后 revision 回到起点，因为此后是另一个会话身份。 */
  function resetRevision(): void {
    revisionRef.current = 0n
  }

  /**
   * 撤回使用证明：先置挂起标记再发送空意图，因此后续 registerUsageNow 不会再发出证明。
   * activation/pagehide 之后发送失败是预期路径，Daemon 按最后有效证明回收。
   */
  function withdrawUsage(isConnected: () => boolean): void {
    if (!isConnected()) {
      return
    }
    setPageSuspended(true)
    void registerUsage({
      browserSessionId: sessionRef.current.browserSessionId,
      revision: nextRevision(),
      views: [],
    }).catch(() => {
      // pagehide/断线后发送失败是预期路径；Daemon 按最后有效证明回收。
    })
  }

  return { registerUsageNow, registerUsageQuiet, usageOverride, resetRevision, withdrawUsage }
}
