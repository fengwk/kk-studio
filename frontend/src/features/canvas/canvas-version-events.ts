import { useEffect, useRef } from 'react'
import { useResourceSync } from '@/shared/app-events/useResourceSync'
import type { CanvasRevision } from '@/shared/api/contracts/base'
import type { CanvasRevisionEventDTO, UUIDString } from '@/shared/api/contracts/studio'
import { compareCanvasRevisions } from '@/shared/lib/canvas-version'

export interface CanvasVersionEventsOptions {
  canvasId: UUIDString | null
  /** 仅当编辑器持有权威 snapshot 后才订阅。 */
  enabled: boolean
  /** 客户端当前已知的 graph 版本修订号（canonical 十进制字符串）。 */
  revision: CanvasRevision
  /** 更高 revision、订阅建立/重连或 resync/error 后读取权威 Snapshot。 */
  onSnapshot: () => void
}

/**
 * Canvas 修订号事件订阅（应用级 WebSocket，经 ApplicationEventManager）：
 * - 订阅建立（首次与每次重连）、resync、error 触发 Snapshot 同步，关闭
 *   快照 GET 与 wire 建立之间以及断线窗口内的版本缺口；
 * - 'revision' 事件（data {"revision":"N"}）仅在高于本地 revision 时触发 Snapshot 拉取。
 */
export function useCanvasVersionEvents(options: CanvasVersionEventsOptions) {
  const { canvasId, enabled, onSnapshot, revision } = options
  const revisionRef = useRef(revision)

  useEffect(() => {
    if (!enabled || !canvasId) {
      return
    }
    revisionRef.current = revision
  }, [canvasId, enabled, revision])

  useResourceSync(enabled && canvasId ? { kind: 'canvas', id: canvasId } : null, {
    sync: onSnapshot,
    syncOn: (name, data) => {
      if (name !== 'revision') {
        return false
      }
      const payload = data as CanvasRevisionEventDTO
      return Boolean(payload.revision)
        && compareCanvasRevisions(payload.revision, revisionRef.current) > 0
    },
  })
}
