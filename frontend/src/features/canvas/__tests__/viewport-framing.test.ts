import { describe, expect, it } from 'vitest'
import {
  fitPaddingClearingToolRail,
  preserveCanvasWorldCenter,
} from '@/features/canvas/viewport-framing'

describe('canvas viewport framing', () => {
  it('preserves the same world center when the Agent panel changes canvas width', () => {
    // React Flow translation is measured in screen pixels, so zoom must not scale the width delta.
    expect(preserveCanvasWorldCenter(
      { x: 40, y: 12, zoom: 0.5 },
      1200,
      840,
    )).toEqual({
      x: -140,
      y: 12,
      zoom: 0.5,
    })
    expect(preserveCanvasWorldCenter(
      { x: -140, y: 12, zoom: 0.5 },
      840,
      1200,
    )).toEqual({
      x: 40,
      y: 12,
      zoom: 0.5,
    })
  })

  // 浮动 `+` 右缘在舞台左缘右侧 50px：窄舞台取景要让出它（含 12px 间距），宽舞台保持比例留白。
  it('widens the fit padding on both sides only when the tool rail would cover the framed nodes', () => {
    // 390px 时比例留白每侧约 30px，小于 62px：左右对称改为 62px，上下仍按比例。
    expect(fitPaddingClearingToolRail(0.18, 390, 50)).toEqual({ y: 0.18, x: '62px' })
    // 1440px 时比例留白每侧约 110px，已足够让出工具栏。
    expect(fitPaddingClearingToolRail(0.18, 1440, 50)).toBe(0.18)
    // 工具栏不占舞台宽度时无需让出。
    expect(fitPaddingClearingToolRail(0.18, 390, 0)).toBe(0.18)
  })
})
