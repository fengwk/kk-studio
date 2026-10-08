/**
 * 只读终端镜像与逐槽几何推导。
 *
 * 权威数据是 numeric UTF-16 unit + 槽 kind（unit/empty/dwc）+ 逐槽样式 + wrapped。
 * 这里不做 Unicode 宽度推理、字形合并或字符规范化：字形片段完全由该行实际槽拓扑决定，
 * 因此同一份镜像在任意环境下产生相同的列数与定位。
 */

import { DEFAULT_SLOT_STYLE, indexedColor, rgbFromPacked, type SlotStyle } from './terminal-style'

/** JediTerm 用 U+E000 表示双宽字符的续格（CharUtils.DWC）。 */
export const DWC_SLOT_CODE = 0xe000

export type SlotKind = 'unit' | 'empty' | 'dwc'

/** 单个单元格槽。`code` 始终是该槽的 UTF-16 code unit；empty 槽为 0。 */
export interface GridSlot {
  readonly kind: SlotKind
  readonly code: number
  readonly style: SlotStyle
}

/** 一行（可能是历史行）。`slots` 恰好 `cols` 长，尾部空槽为 `empty`。 */
export interface GridLine {
  readonly wrapped: boolean
  readonly slots: readonly GridSlot[]
}

/** 只读终端镜像。`lines` 为 历史行 + 屏幕行，顺序与 Daemon 捕获一致。 */
export interface TerminalGrid {
  readonly cols: number
  readonly rows: number
  readonly cursorX: number
  readonly cursorY: number
  readonly alternate: boolean
  readonly history: number
  readonly lines: readonly GridLine[]
}

/** S0 捕获的逐槽样式；除 `bold`/`fg` 外的字段为兼容扩展样式保留。 */
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
  /** 显式索引色；与 `fg`/`bg` 互斥，优先于打包真彩。 */
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

function buildSlot(code: number): GridSlot {
  if (code === DWC_SLOT_CODE) {
    return { kind: 'dwc', code, style: DEFAULT_SLOT_STYLE }
  }
  return { kind: 'unit', code, style: DEFAULT_SLOT_STYLE }
}

function emptySlot(): GridSlot {
  return { kind: 'empty', code: 0, style: DEFAULT_SLOT_STYLE }
}

/**
 * 把捕获画面转换为权威镜像。
 *
 * 关键点：
 * - 逐 `charCodeAt` 提取 UTF-16 unit，绝不迭代码点，孤立代理不丢失；
 * - 每个 unit 占一槽，DWC（0xE000）标记为续格；
 * - 超出 `text` 长度的尾部槽为 `empty`，显式表达“空格语义”的填充。
 */
export function gridFromCapturedView(view: CapturedView): TerminalGrid {
  const { cols } = view
  const lines: GridLine[] = view.lines.map((line) => {
    const slots: GridSlot[] = []
    for (let x = 0; x < cols; x += 1) {
      if (x < line.text.length) {
        const code = line.text.charCodeAt(x)
        const base = buildSlot(code)
        const style = line.styles ? slotStyleFromCaptured(line.styles[x]) : DEFAULT_SLOT_STYLE
        slots.push({ kind: base.kind, code, style })
      } else {
        slots.push(emptySlot())
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

/** 高代理（0xD800-0xDBFF）。 */
export function isHighSurrogate(code: number): boolean {
  return code >= 0xd800 && code <= 0xdbff
}

/** 低代理（0xDC00-0xDFFF）。 */
export function isLowSurrogate(code: number): boolean {
  return code >= 0xdc00 && code <= 0xdfff
}

/**
 * 一个绘制字形片段。位置/宽度只来自槽拓扑：
 * - 普通 unit：1 格；
 * - 同行成对 high+low 代理：2 格（同一逻辑字符），但两侧样式各自保留在 cell 层；
 * - 后随 DWC 的 unit：1 + 连续 DWC 数（正常为 2 格）。
 */
export interface GlyphFragment {
  readonly x: number
  readonly span: number
  readonly text: string
  readonly style: SlotStyle
}

function unitText(code: number): string {
  // NUL 保持明确的空格语义；其余 unit（含 FEFF、孤立代理）原样保留。
  return code === 0 ? ' ' : String.fromCharCode(code)
}

/**
 * 由一行实际槽拓扑推导字形片段。
 *
 * 不依赖任何字符宽度表：宽字符必须由后续 DWC 续格证明其为 2 格，否则一律按 1 格绘制并裁剪，
 * 从而保证“覆写 DWC 后 中 与 X 各占 1 格”且中不覆盖相邻 X。
 */
export function glyphFragments(line: GridLine): GlyphFragment[] {
  const slots = line.slots
  const fragments: GlyphFragment[] = []
  let x = 0
  while (x < slots.length) {
    const slot = slots[x]
    if (slot.kind !== 'unit') {
      // dwc 续格由前导消费；孤立续格不产生字形。empty 无字形。
      x += 1
      continue
    }
    const next = slots[x + 1]
    if (next && next.kind === 'unit' && isHighSurrogate(slot.code) && isLowSurrogate(next.code)) {
      fragments.push({
        x,
        span: 2,
        text: String.fromCharCode(slot.code, next.code),
        style: slot.style,
      })
      x += 2
      continue
    }
    if (next && next.kind === 'dwc') {
      let span = 2
      let cursor = x + 2
      while (cursor < slots.length && slots[cursor].kind === 'dwc') {
        span += 1
        cursor += 1
      }
      fragments.push({ x, span, text: unitText(slot.code), style: slot.style })
      x = cursor
      continue
    }
    fragments.push({ x, span: 1, text: unitText(slot.code), style: slot.style })
    x += 1
  }
  return fragments
}

/** 光标所在行的绝对行号（历史行 + 屏幕行）。 */
export function cursorLineIndex(grid: TerminalGrid): number {
  return grid.history + grid.cursorY
}
