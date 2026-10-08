/**
 * S0 捕获画面适配器（测试/探针专用，不属于生产渲染路径）。
 *
 * 把 S0Probe 捕获的 `{wrapped,text,styles}` 结构化画面转换为 production `TerminalGrid`。
 * 关键点：逐 `charCodeAt` 提取 UTF-16 unit，绝不迭代码点；每个 unit 占一槽，DWC（0xE000）
 * 标记为续格；超出 `text` 长度的尾部槽为 `empty`。
 */

import {
  DEFAULT_SLOT_STYLE,
  indexedColor,
  rgbFromPacked,
  type SlotStyle,
} from '../terminal-style'
import { DWC_SLOT_CODE, type GridLine, type GridSlot, type TerminalGrid } from '../terminal-grid'

/** S0 捕获的逐槽样式；除 `bold`/`fg` 外的字段供扩展样式用例。 */
export interface CapturedSlotStyle {
  readonly bold?: boolean
  readonly dim?: boolean
  readonly italic?: boolean
  readonly underline?: boolean
  readonly blink?: boolean
  readonly inverse?: boolean
  readonly hidden?: boolean
  readonly strikethrough?: boolean
  /** 打包的真彩 0xRRGGBB；null/缺省表示默认色。 */
  readonly fg?: number | null
  readonly bg?: number | null
  /** 显式索引色；优先于打包真彩。 */
  readonly fgIndex?: number | null
  readonly bgIndex?: number | null
}

/** S0 捕获的一行：`text` 为按 UTF-16 unit 逐字编码的字符串。 */
export interface CapturedLine {
  readonly wrapped: boolean
  readonly text: string
  readonly styles?: readonly CapturedSlotStyle[]
}

/** S0Probe 捕获的结构化画面。 */
export interface CapturedView {
  readonly cols: number
  readonly rows: number
  readonly cursorX: number
  readonly cursorY: number
  readonly alternate: boolean
  readonly history: number
  readonly lines: readonly CapturedLine[]
}

function capturedColor(packed: number | null | undefined, index: number | null | undefined) {
  if (index !== null && index !== undefined) {
    return indexedColor(index)
  }
  if (packed !== null && packed !== undefined) {
    return rgbFromPacked(packed)
  }
  return null
}

/** 把捕获样式补齐为完整 `SlotStyle`。 */
export function slotStyleFromCaptured(style: CapturedSlotStyle | undefined): SlotStyle {
  if (!style) {
    return DEFAULT_SLOT_STYLE
  }
  return {
    fg: capturedColor(style.fg, style.fgIndex),
    bg: capturedColor(style.bg, style.bgIndex),
    bold: style.bold ?? false,
    dim: style.dim ?? false,
    italic: style.italic ?? false,
    underline: style.underline ?? false,
    blink: style.blink ?? false,
    inverse: style.inverse ?? false,
    hidden: style.hidden ?? false,
    strikethrough: style.strikethrough ?? false,
  }
}

/** 把捕获画面转换为权威镜像。 */
export function gridFromCapturedView(view: CapturedView): TerminalGrid {
  const { cols } = view
  const lines: GridLine[] = view.lines.map((line) => {
    const slots: GridSlot[] = []
    for (let x = 0; x < cols; x += 1) {
      if (x < line.text.length) {
        const code = line.text.charCodeAt(x)
        slots.push({
          kind: code === DWC_SLOT_CODE ? 'dwc' : 'unit',
          code,
          style: line.styles ? slotStyleFromCaptured(line.styles[x]) : DEFAULT_SLOT_STYLE,
        })
      } else {
        slots.push({ kind: 'empty', code: 0, style: DEFAULT_SLOT_STYLE })
      }
    }
    return { wrapped: line.wrapped, slots }
  })
  return {
    cols: view.cols,
    rows: view.rows,
    cursorX: view.cursorX,
    cursorY: view.cursorY,
    alternate: view.alternate,
    history: view.history,
    lines,
  }
}
