import { useEffect, useRef } from 'react'
import { useApplicationEvents } from '@/shared/app-events'
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
 * - 'subscribed'（首次订阅与每次重连重订阅后）触发 Snapshot 同步，关闭
 *   快照 GET 与 wire 建立之间以及断线窗口内的版本缺口；
 * - 'revision' 事件（data {"revision":"N"}）触发 Snapshot 拉取；
 * - 'resync' 事件触发全量快照。
 */
export function useCanvasVersionEvents(options: CanvasVersionEventsOptions) {
  const { canvasId, enabled, onSnapshot, revision } = options
  const revisionRef = useRef(revision)
  const onSnapshotRef = useRef(onSnapshot)
  const applicationEvents = useApplicationEvents()

  useEffect(() => {
    onSnapshotRef.current = onSnapshot
  }, [onSnapshot])

  useEffect(() => {
    if (!enabled || !canvasId) {
      return
    }
    revisionRef.current = revision
  }, [canvasId, enabled, revision])

  useEffect(() => {
    if (!enabled || !canvasId) {
      return undefined
    }
    return applicationEvents.subscribe(
      { kind: 'canvas', id: canvasId },
      {
        onSubscribed: () => {
          onSnapshotRef.current()
        },
        onEvent: (name, data) => {
          if (name !== 'revision') {
            return
          }
          const payload = data as CanvasRevisionEventDTO
          if (!payload.revision || compareCanvasRevisions(payload.revision, revisionRef.current) <= 0) {
            return
          }
          onSnapshotRef.current()
        },
        onResync: () => {
          onSnapshotRef.current()
        },
        onError: () => {
          // 订阅/事件处理失败：本地状态不可信，重新读取权威快照。
          onSnapshotRef.current()
        },
      },
    )
  }, [applicationEvents, canvasId, enabled])
}
