import type { FitViewOptions } from '@xyflow/react'
import type { StoredCanvasViewport } from '@/features/canvas/viewport-storage'

/** 左侧浮动 `+` 与取景内容之间的最小间距（像素）。 */
const TOOL_RAIL_GAP = 12

/**
 * React Flow viewport 的 x 是屏幕像素平移量。
 * 容器宽度变化一半的同向平移，可保持画布中心对应的 world 坐标不变。
 */
export function preserveCanvasWorldCenter(
  viewport: StoredCanvasViewport,
  previousWidth: number,
  nextWidth: number,
): StoredCanvasViewport {
  return {
    ...viewport,
    x: viewport.x + (nextWidth - previousWidth) / 2,
  }
}

/**
 * 取景留白：左侧浮动 `+` 叠在舞台上，左右留白至少让出它（含间距），窄屏取景的节点不被它压住。
 * 左右对称以保持内容居中；舞台足够宽时比例留白本就更大，原样沿用。
 *
 * React Flow 的数字留白 p 让取景内容占视口宽度的 1/(1+p)，即每侧 (w - w/(1+p))/2 像素。
 * `toolRailRight` 是 `+` 右缘相对舞台左缘的距离；它不占舞台宽度（≤ 0）时无需让出。
 */
export function fitPaddingClearingToolRail(
  padding: number,
  stageWidth: number,
  toolRailRight: number,
): NonNullable<FitViewOptions['padding']> {
  const clearance = toolRailRight + TOOL_RAIL_GAP
  const proportional = (stageWidth - stageWidth / (1 + padding)) / 2
  return toolRailRight > 0 && clearance > proportional
    ? { y: padding, x: `${Math.ceil(clearance)}px` }
    : padding
}
