import type { StoredCanvasViewport } from '@/features/canvas/viewport-storage'

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
