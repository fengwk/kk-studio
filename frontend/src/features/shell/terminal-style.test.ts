import { describe, expect, it } from 'vitest'
import {
  DEFAULT_SLOT_STYLE,
  colorToCss,
  effectiveColors,
  indexedColor,
  indexedPaletteRgb,
  rgbFromPacked,
  slotDecoration,
  type SlotStyle,
} from './terminal-style'

function style(patch: Partial<SlotStyle>): SlotStyle {
  return { ...DEFAULT_SLOT_STYLE, ...patch }
}

describe('terminal style projection', () => {
  // 真彩打包整数必须按 0xRRGGBB 拆分，越界输入显式失败而不是静默截断。
  it('splits packed rgb and rejects out-of-range values', () => {
    expect(rgbFromPacked(0x7b2d43)).toEqual({ kind: 'rgb', r: 123, g: 45, b: 67 })
    expect(() => rgbFromPacked(-1)).toThrow(RangeError)
    expect(() => rgbFromPacked(0x1000000)).toThrow(RangeError)
    expect(() => rgbFromPacked(1.5)).toThrow(RangeError)
  })

  // 索引颜色覆盖 ansi16、6x6x6 cube 与灰阶三段，越界同样显式失败。
  it('projects the fixed 256-color palette', () => {
    expect(indexedPaletteRgb(0)).toEqual([0, 0, 0])
    expect(indexedPaletteRgb(12)).toEqual([92, 92, 255])
    expect(indexedPaletteRgb(16)).toEqual([0, 0, 0])
    expect(indexedPaletteRgb(21)).toEqual([0, 0, 255])
    expect(indexedPaletteRgb(231)).toEqual([255, 255, 255])
    expect(indexedPaletteRgb(232)).toEqual([8, 8, 8])
    expect(indexedPaletteRgb(255)).toEqual([238, 238, 238])
    expect(() => indexedColor(256)).toThrow(RangeError)
    expect(() => indexedPaletteRgb(-1)).toThrow(RangeError)
  })

  it('renders explicit and indexed colors to css', () => {
    expect(colorToCss({ kind: 'rgb', r: 1, g: 2, b: 3 })).toBe('rgb(1 2 3)')
    expect(colorToCss(indexedColor(9))).toBe('rgb(255 0 0)')
  })

  // inverse 只交换有效颜色，未显式指定的那一侧回落到容器默认变量。
  it('swaps fg/bg under inverse including defaults', () => {
    expect(effectiveColors(style({}))).toEqual({ fg: null, bg: null })
    const rgb = style({ fg: { kind: 'rgb', r: 1, g: 2, b: 3 }, bg: indexedColor(4) })
    expect(effectiveColors(rgb)).toEqual({ fg: 'rgb(1 2 3)', bg: 'rgb(0 0 238)' })
    expect(effectiveColors({ ...rgb, inverse: true })).toEqual({
      fg: 'rgb(0 0 238)',
      bg: 'rgb(1 2 3)',
    })
    expect(effectiveColors(style({ inverse: true }))).toEqual({
      fg: 'var(--terminal-bg)',
      bg: 'var(--terminal-fg)',
    })
  })

  it('maps bold/dim/italic/underline/blink/strikethrough to css decoration', () => {
    expect(slotDecoration(style({}))).toEqual({
      fontWeight: null,
      fontStyle: null,
      textDecoration: null,
      opacity: null,
      animation: null,
    })
    expect(slotDecoration(style({ bold: true, italic: true, dim: true, blink: true }))).toEqual({
      fontWeight: '700',
      fontStyle: 'italic',
      textDecoration: null,
      opacity: '0.55',
      animation: 'terminal-grid-blink 1s steps(1, end) infinite',
    })
    expect(
      slotDecoration(style({ underline: true, strikethrough: true })).textDecoration,
    ).toBe('underline line-through')
  })
})
