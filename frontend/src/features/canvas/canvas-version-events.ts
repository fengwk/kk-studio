import { useEffect, useRef } from 'react'
import { useApplicationEvents } from '@/shared/app-events'
import type { CanvasVersion } from '@/shared/api/contracts/base'
import type { CanvasVersionEventDTO, UUIDString } from '@/shared/api/contracts/studio'
import { compareCanvasVersions } from '@/shared/lib/canvas-version'

export interface CanvasVersionEventsOptions {
  canvasId: UUIDString | null
  /** 仅当编辑器持有权威 snapshot 后才订阅。 */
  enabled: boolean
  /** 客户端当前已知的 graph 版本（每次渲染的最新值，canonical 十进制字符串）。 */
  version: CanvasVersion
  /** 更高 version、订阅建立/重连或 resync/error 后读取权威 Snapshot。 */
  onSnapshot: () => void
}

/**
 * Canvas 版本事件订阅（应用级 WebSocket，经 ApplicationEventManager）：
 * - 'subscribed'（首次订阅与每次重连重订阅后）触发 Snapshot 同步，关闭
 *   快照 GET 与 wire 建立之间以及断线窗口内的版本缺口；
 * - 'version' 事件（data {"version":"N"}）触发 Snapshot 拉取（不携带载荷
 *   应用逻辑），codec 保证 canonical 非负十进制字符串，只接受严格大于
 *   当前版本的事件；
 * - 'resync' 事件触发全量快照。
 */
export function useCanvasVersionEvents(options: CanvasVersionEventsOptions) {
  const { canvasId, enabled, version, onSnapshot } = options
  const versionRef = useRef(version)
  const onSnapshotRef = useRef(onSnapshot)
  const applicationEvents = useApplicationEvents()

  useEffect(() => {
    onSnapshotRef.current = onSnapshot
  }, [onSnapshot])

  useEffect(() => {
    if (!enabled || !canvasId) {
      return
    }
    versionRef.current = version
  }, [canvasId, enabled, version])

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
          if (name !== 'version') {
            return
          }
          // codec 已严格校验 data 形状为 {"version":"<canonical>"}。
          const payload = data as CanvasVersionEventDTO
          if (compareCanvasVersions(payload.version, versionRef.current) <= 0) {
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
