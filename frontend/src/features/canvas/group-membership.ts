import type { CanvasTransformDTO } from '@/shared/api/contracts/studio'
import {
  CANVAS_NODE_HEADER_GAP,
  CANVAS_NODE_HEADER_HEIGHT,
} from '@/features/canvas/resource-node-size'

/**
 * Group 的可见范围是标题下方的着色 Body。节点与 Body 仍有正面积相交时保留成员关系；
 * 完全无交集（包括仅贴边）时视为拖出 Group。
 */
export function isNodeCompletelyOutsideGroup(
  node: CanvasTransformDTO,
  group: CanvasTransformDTO,
): boolean {
  const groupBodyTop = group.y + CANVAS_NODE_HEADER_HEIGHT + CANVAS_NODE_HEADER_GAP
  const groupBodyHeight = Math.max(
    0,
    group.height - CANVAS_NODE_HEADER_HEIGHT - CANVAS_NODE_HEADER_GAP,
  )
  const overlapWidth = Math.min(node.x + node.width, group.x + group.width)
    - Math.max(node.x, group.x)
  const overlapHeight = Math.min(node.y + node.height, groupBodyTop + groupBodyHeight)
    - Math.max(node.y, groupBodyTop)
  return overlapWidth <= 0 || overlapHeight <= 0
}
