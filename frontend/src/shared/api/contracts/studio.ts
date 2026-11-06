import type { CanvasRevision } from '@/shared/api/contracts/base'

/**
 * 应用事件 WebSocket `revision` payload：revision 前进提示，客户端随后读取
 * 权威 Snapshot。'resync' 事件无 payload，同样要求全量快照。
 */
export interface CanvasRevisionEventDTO {
  revision: CanvasRevision
}

