import type { CanvasRevision } from '@/shared/api/contracts/base'

/** 资源与运行状态的取值集合：wire DTO 声明为字符串，领域投影按这里收窄。 */
export const CANVAS_RESOURCE_KINDS = ['IMAGE', 'VIDEO', 'AUDIO', 'TEXT'] as const
export type CanvasResourceKind = (typeof CANVAS_RESOURCE_KINDS)[number]

export const CANVAS_FUNCTION_RUN_STATUSES = [
  'READY',
  'RUNNING',
  'SUCCEEDED',
  'FAILED',
  'CANCELLED',
  'UNKNOWN',
] as const
export type CanvasFunctionRunStatus = (typeof CANVAS_FUNCTION_RUN_STATUSES)[number]

/**
 * 应用事件 WebSocket `revision` payload：revision 前进提示，客户端随后读取
 * 权威 Snapshot。'resync' 事件无 payload，同样要求全量快照。
 */
export interface CanvasRevisionEventDTO {
  revision: CanvasRevision
}

