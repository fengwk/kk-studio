import { useEffect, useRef } from 'react'
import type { CanvasVersion } from '@/shared/api/contracts/base'
import type { CanvasVersionEventDTO, UUIDString } from '@/shared/api/contracts/studio'
import { createCanvasRealtimeStream } from '@/shared/api/studio-service'
import { compareCanvasVersions, isCanvasVersion } from '@/shared/lib/canvas-version'

export interface CanvasVersionEventsOptions {
  canvasId: UUIDString | null
  /** 仅当编辑器持有权威 snapshot 后才订阅。 */
  enabled: boolean
  /** 客户端当前已知的 graph 版本（每次渲染的最新值，canonical 十进制字符串）。 */
  version: CanvasVersion
  /** 'version' 事件或重连后：按最后已知版本拉取 changes 并应用。 */
  onVersion: () => void
  /** 'resync' 事件：需要整体替换为全量快照。 */
  onResync: () => void
}

const RECONNECT_DELAY_MS = 250

/**
 * Canvas 版本事件 SSE 订阅：
 * - 'version' 事件（{version:"N"}）触发 changes 拉取（不携带载荷应用逻辑），
 *   只接受 canonical 非负十进制字符串且严格大于当前版本的事件；
 * - 'resync' 事件触发全量快照；
 * - 断线重连使用客户端最后已知版本，并在重连后先同步一次 changes，
 *   关闭断线窗口内的版本缺口；全量快照只发生在初始加载、gap 或 resync。
 */
export function useCanvasVersionEvents(options: CanvasVersionEventsOptions) {
  const { canvasId, enabled, version, onVersion, onResync } = options
  const versionRef = useRef(version)
  const onVersionRef = useRef(onVersion)
  const onResyncRef = useRef(onResync)

  useEffect(() => {
    onVersionRef.current = onVersion
    onResyncRef.current = onResync
  }, [onResync, onVersion])

  useEffect(() => {
    if (!enabled || !canvasId) {
      return
    }
    versionRef.current = version
  }, [canvasId, enabled, version])

  useEffect(() => {
    if (!enabled || !canvasId) {
      return
    }
    const streamCanvasId = canvasId
    let cancelled = false
    let reconnectTimer: number | null = null
    let source: EventSource | null = null

    const connect = () => {
      if (cancelled) {
        return
      }
      // 连接建立后立即同步一次：关闭「快照 GET 与 SSE 建立之间」以及
      // 「上次断线窗口」内的版本缺口。changes 无进展时是空操作。
      onVersionRef.current()
      source = createCanvasRealtimeStream(streamCanvasId, versionRef.current)
      source.addEventListener('version', (event) => {
        const payload = parseVersionEvent((event as MessageEvent<string>).data)
        if (payload == null || compareCanvasVersions(payload.version, versionRef.current) <= 0) {
          return
        }
        onVersionRef.current()
      })
      source.addEventListener('resync', () => {
        onResyncRef.current()
      })
      source.onerror = () => {
        // 手动重连：EventSource 自动重连会沿用旧的 afterVersion，这里显式
        // 关闭并以最后已知版本重建，保证重连后不重放也不丢事件。
        if (source !== null) {
          source.close()
          source = null
        }
        if (reconnectTimer !== null) {
          window.clearTimeout(reconnectTimer)
        }
        reconnectTimer = window.setTimeout(connect, RECONNECT_DELAY_MS)
      }
    }

    connect()
    return () => {
      cancelled = true
      if (reconnectTimer !== null) {
        window.clearTimeout(reconnectTimer)
      }
      source?.close()
    }
  }, [canvasId, enabled])
}

/**
 * 严格解析 SSE 'version' 事件：只接受 canonical 非负十进制字符串。
 * 数字、前导零、负数、缺失字段或非 JSON 载荷一律返回 null（忽略）。
 */
function parseVersionEvent(raw: string): CanvasVersionEventDTO | null {
  try {
    const value = JSON.parse(raw) as Partial<CanvasVersionEventDTO>
    return isCanvasVersion(value.version) ? { version: value.version } : null
  } catch {
    return null
  }
}
