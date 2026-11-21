import { useRef, type RefObject } from 'react'
import {
  IdeProtocolError,
  validateViewSnapshot,
  viewFingerprint,
  type IdeNormalizedView,
  type IdeWireSnapshotView,
} from '@/features/environment/ide/ide-contract'
import { compareDecimal } from '@/features/environment/ide/ide-decimal'
import type { IdeBrowserSessionState } from '@/features/environment/ide/ide-browser-session'

interface SnapshotCacheEntry {
  revision: string
  fingerprint: string
}

export interface AcceptedSnapshot {
  accepted: boolean
  daemonInstanceId: string
  view: IdeNormalizedView | null
}

/**
 * 纯读快照的接受规则：同一 daemonInstance 内 revision 必须单调，
 * 同一 revision 的内容必须完全一致。拒绝是结构化结果，内容冲突是契约错误。
 */
export function useIdeSnapshotCache(sessionRef: RefObject<IdeBrowserSessionState>) {
  const snapshotCacheRef = useRef(new Map<string, SnapshotCacheEntry>())

  function acceptSnapshot(
    environmentId: string,
    viewId: string,
    snapshot: IdeWireSnapshotView,
  ): AcceptedSnapshot {
    const validated = validateViewSnapshot(
      environmentId,
      viewId,
      sessionRef.current.browserSessionId,
      snapshot,
    )
    const cacheKey = `${validated.daemonInstanceId}:${viewId}`
    const cached = snapshotCacheRef.current.get(cacheKey)
    if (cached != null) {
      const compare = compareDecimal(validated.revision, cached.revision)
      if (compare < 0) {
        return {
          accepted: false,
          daemonInstanceId: validated.daemonInstanceId,
          view: null,
        }
      }
      if (compare === 0 && cached.fingerprint !== viewFingerprint(validated.view)) {
        throw new IdeProtocolError('Invalid IDE view snapshot: conflicting revision')
      }
    }
    snapshotCacheRef.current.set(cacheKey, {
      revision: validated.revision,
      fingerprint: viewFingerprint(validated.view),
    })
    return { accepted: true, daemonInstanceId: validated.daemonInstanceId, view: validated.view }
  }

  /** 视图被关闭时丢弃它的快照记录：同一 viewId 下次是一个新资源。 */
  function forgetView(daemonInstanceId: string, viewId: string): void {
    snapshotCacheRef.current.delete(`${daemonInstanceId}:${viewId}`)
  }

  function clearSnapshots(): void {
    snapshotCacheRef.current.clear()
  }

  return { acceptSnapshot, forgetView, clearSnapshots }
}
